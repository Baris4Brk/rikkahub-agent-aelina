package me.rerere.locallm.litert

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AttestedBackgroundTextGenerationProvider
import me.rerere.ai.provider.BackgroundProviderDispatchCallback
import me.rerere.ai.provider.BackgroundRuntimeAttestation
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.PreparedBackgroundTextGeneration
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.FencedTextGenerationProvider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import android.content.Context
import me.rerere.ai.util.audioBytes
import me.rerere.ai.util.toBitmap
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.LocalRuntimePreferences
import com.google.ai.edge.litertlm.tool as litertTool
import com.google.ai.edge.litertlm.ToolProvider

private const val TAG = "LiteRtProvider"
private const val LITERT_CANCELLATION_FENCE_ABI = "litert-inference-epoch-v1"
private val liteRtProviderInferenceMutex = Mutex()
private val liteRtCacheIdentityJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

internal fun computeLiteRtToolAuthorizationFingerprint(tools: List<Tool>): String {
    val fields = buildList<ByteArray> {
        tools.forEach { tool ->
            add(tool.name.toByteArray(Charsets.UTF_8))
            add(tool.description.toByteArray(Charsets.UTF_8))
            val schema = runCatching {
                tool.parameters()?.let {
                    liteRtCacheIdentityJson.encodeToString(InputSchema.serializer(), it)
                }.orEmpty()
            }.getOrElse { "<schema-unavailable>" }
            add(schema.toByteArray(Charsets.UTF_8))
        }
    }
    return strongFingerprint("rikkahub-litert-tool-authorization-v1", fields)
}

/** Pure canonical constructor kept separate so the complete background identity is JVM-testable. */
internal fun liteRtBackgroundAttestation(
    artifactSha256: String,
    forceCpu: Boolean,
    accelerator: String,
    contextWindowTokens: Int,
    topK: Int,
    topP: Double,
    temperature: Double,
): BackgroundRuntimeAttestation = BackgroundRuntimeAttestation(
    providerRuntimeAbi = LITERT_BACKGROUND_RUNTIME_ABI,
    sdkAbi = LocalRuntimePreferences.LITERTLM_SDK_VERSION,
    cancellationFenceAbi = LITERT_CANCELLATION_FENCE_ABI,
    artifactSha256 = artifactSha256,
    forceCpu = forceCpu,
    accelerator = accelerator,
    contextWindowTokens = contextWindowTokens,
    topK = topK,
    topP = topP,
    temperature = temperature,
    promptRendererAbi = LITERT_PROMPT_RENDERER_ABI,
    nativeToolAbi = LITERT_NATIVE_TOOL_ABI,
    textOnly = true,
    toolsEmpty = true,
    constrainedDecoding = false,
    speculativeDecoding = false,
    providerCacheDisabled = true,
)

/** Collection-time gate: creating multiple cold Flows still permits exactly one execution. */
internal class LiteRtSingleUseBackgroundGate {
    private val consumed = AtomicBoolean(false)

    fun claim(): Boolean = consumed.compareAndSet(false, true)
}

/**
 * Outcome of [decideImageForwarding]: whether to hand image bytes to the runtime this turn,
 * and whether the user's attached images were dropped in a way that warrants a chat note.
 */
internal data class ImageForwardingDecision(
    val forwardImages: Boolean,
    val noteImagesDropped: Boolean,
)

/**
 * Decide whether the current turn's images may be forwarded to [LiteRtRuntime.streamTurns].
 *
 * The forward gate keys off [visionEnabledPostLoad] — the ACTUAL vision state of the loaded
 * engine ([LiteRtRuntime.LoadOutcome.visionEnabled]) — NOT the pre-load estimate. When the GPU
 * vision executor fails to initialise (Adreno 7xx + OEM ROMs; upstream LiteRT-LM #2292), the
 * runtime falls back to a text-only engine; forwarding image bytes to that engine null-derefs
 * the native runtime (SIGSEGV in `liblitertlm_jni.so`). Reading the pre-load estimate here was
 * the root cause of that crash.
 *
 * [ImageForwardingDecision.noteImagesDropped] is true only when the user actually attached
 * images to an image-capable model whose vision is not live — i.e. a vision model that could
 * not bring up its encoder on this device. A plain text-only model with a stray image
 * attachment keeps the prior silent drop (the device's vision capability was never the issue).
 */
internal fun decideImageForwarding(
    modelImageCapable: Boolean,
    visionEnabledPostLoad: Boolean,
    userSentImages: Boolean,
): ImageForwardingDecision = ImageForwardingDecision(
    forwardImages = visionEnabledPostLoad,
    noteImagesDropped = userSentImages && modelImageCapable && !visionEnabledPostLoad,
)

/**
 * Implements the existing Provider interface so any assistant can pick a LiteRT
 * model the same way it picks an OpenAI / Claude model. Routes inference through
 * [LiteRtRuntime] and tool calls through [LiteRtToolPrefix].
 *
 * When [LiteRtRuntime.ensureLoaded] throws [LiteRtModelCorruptException], this provider
 * self-heals: the broken file is deleted from disk, removed from [LocalRuntimePreferences],
 * and removed from [ProviderSetting.LiteRtLocal.models] via [settingsUpdater]. The caller
 * receives a user-readable [RuntimeException] explaining what happened.
 *
 * @param settingsUpdater Suspend lambda that lets the provider mutate the persisted
 *   [ProviderSetting] list without coupling the `local-llm` module to `SettingsStore`
 *   directly. Supply `{ fn -> settingsStore.update { old -> old.copy(providers = fn(old.providers)) } }`
 *   from the DI layer.
 */
class LiteRtProvider(
    private val context: Context,
    private val runtime: LiteRtRuntime,
    private val prefs: LocalRuntimePreferences,
    private val settingsUpdater: suspend (transform: (List<ProviderSetting>) -> List<ProviderSetting>) -> Unit,
) : Provider<ProviderSetting.LiteRtLocal>,
    FencedTextGenerationProvider,
    AttestedBackgroundTextGenerationProvider<ProviderSetting.LiteRtLocal> {

    /**
     * [LiteRtRuntime]'s monotonic inference epoch is revoked before `cancelProcess()`. Every
     * callback is guarded by that epoch, so a late native callback cannot publish after cancel.
     */
    override val cancellationFenceAbi: String = LITERT_CANCELLATION_FENCE_ABI
    override val backgroundRuntimeAttestationAbi: String = LITERT_BACKGROUND_RUNTIME_ABI

    /** Singleton bridge — one ToolSet for the lifetime of this provider. Its @Tool
     *  method reads the per-request tool list from [LiteRtToolBridgeRegistry]. */
    private val toolBridge = LiteRtToolBridge()
    private val toolProvider: ToolProvider = litertTool(toolBridge)

    private data class BackgroundSnapshot(
        val seal: LiteRtBackgroundRuntimeSeal,
        val forceCpu: Boolean,
        val contextWindowTokens: Int,
        val config: LiteRtModelConfig,
        val attestation: BackgroundRuntimeAttestation,
    )

    override suspend fun attestBackgroundRuntime(
        providerSetting: ProviderSetting.LiteRtLocal,
        model: Model,
    ): BackgroundRuntimeAttestation {
        liteRtProviderInferenceMutex.lock()
        return try {
            resolveBackgroundSnapshotLocked(providerSetting, model).attestation
        } finally {
            liteRtProviderInferenceMutex.unlock()
        }
    }

    override fun prepareBackgroundTextGeneration(
        providerSetting: ProviderSetting.LiteRtLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        expectedAttestation: BackgroundRuntimeAttestation,
    ): PreparedBackgroundTextGeneration {
        require(expectedAttestation.providerRuntimeAbi == backgroundRuntimeAttestationAbi) {
            "Unexpected LiteRT background runtime ABI"
        }
        val singleUse = LiteRtSingleUseBackgroundGate()
        return object : PreparedBackgroundTextGeneration {
            override val expectedAttestation: BackgroundRuntimeAttestation = expectedAttestation

            override fun streamText(
                onDispatchStarted: BackgroundProviderDispatchCallback,
            ): Flow<MessageChunk> = flow {
                // Guard collection, not merely Flow creation: a cold Flow can otherwise be
                // retained and collected twice after one successful preparation.
                check(singleUse.claim()) {
                    "A prepared LiteRT background execution is single-use"
                }
                streamTextInternal(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = params,
                    expectedBackgroundAttestation = expectedAttestation,
                    backgroundDispatchCallback = onDispatchStarted,
                ).collect { chunk -> emit(chunk) }
            }
        }
    }

    /** Caller holds [liteRtProviderInferenceMutex]. */
    private suspend fun resolveBackgroundSnapshotLocked(
        providerSetting: ProviderSetting.LiteRtLocal,
        model: Model,
    ): BackgroundSnapshot {
        check(providerSetting.enabled) { "LiteRT provider is disabled" }
        check(providerSetting.models.any { it.id == model.id && it.modelId == model.modelId }) {
            "LiteRT background model is not present in the frozen provider"
        }
        val modelPath = prefs.installedModels(LocalRuntime.LiteRT)[model.modelId]
            ?: throw IllegalStateException("Model ${model.modelId} not installed")
        check(java.io.File(modelPath).isFile) {
            "LiteRT background artifact is unavailable"
        }
        val preferredAccel = prefs.acceleratorFlow(LocalRuntime.LiteRT).first()
        val forceCpu = prefs.forceCpu(LocalRuntime.LiteRT)
        val config = LiteRtModelDefaults.forModelFile(model.modelId)
        val contextWindowTokens = (
            prefs.maxNumTokensOverride(LocalRuntime.LiteRT)
                ?: config.maxContextLength
                ?: config.maxTokens
            ).coerceAtLeast(1)
        val seal = runtime.attestBackgroundRuntime(
            modelPath = modelPath,
            preferredAccel = preferredAccel,
            forceCpu = forceCpu,
        )
        val attestation = liteRtBackgroundAttestation(
            artifactSha256 = seal.artifactSha256,
            forceCpu = forceCpu,
            accelerator = seal.accelerator,
            contextWindowTokens = contextWindowTokens,
            topK = config.topK,
            topP = config.topP,
            temperature = config.temperature,
        )
        return BackgroundSnapshot(
            seal = seal,
            forceCpu = forceCpu,
            contextWindowTokens = contextWindowTokens,
            config = config,
            attestation = attestation,
        )
    }

    override suspend fun resolveTrustedContextWindowTokens(
        providerSetting: ProviderSetting.LiteRtLocal,
        model: Model,
    ): Int? {
        val runtimeOverride = prefs.maxNumTokensOverride(LocalRuntime.LiteRT)?.takeIf { it > 0 }
        if (runtimeOverride != null) return runtimeOverride
        model.trustedContextWindowTokens?.takeIf { it > 0 }?.let { return it }
        val defaults = LiteRtModelDefaults.forModelFile(model.modelId)
        return (defaults.maxContextLength ?: defaults.maxTokens).takeIf { it > 0 }
    }

    /**
     * Self-heal a permanently corrupt model: delete the file from disk, remove it from
     * [LocalRuntimePreferences], and remove it from the provider's models list in settings.
     * After cleanup, throws a user-readable [RuntimeException] so the chat surface can
     * display an appropriate error message.
     */
    private suspend fun handleCorruptModel(corrupt: LiteRtModelCorruptException): Nothing {
        val file = java.io.File(corrupt.modelPath)
        val fileName = file.name
        Log.w(TAG, "Model file is corrupt — auto-removing: $fileName (${corrupt.modelPath})")

        // 1. Delete the file from disk (best-effort; log if it fails).
        runCatching { file.delete() }.onFailure { e ->
            Log.e(TAG, "Failed to delete corrupt model file: ${corrupt.modelPath}", e)
        }

        // 2. Remove from the installed-models DataStore index.
        runCatching { prefs.removeInstalledModel(LocalRuntime.LiteRT, fileName) }.onFailure { e ->
            Log.e(TAG, "Failed to remove model from prefs: $fileName", e)
        }

        // 3. Remove the matching Model entry from ProviderSetting.LiteRtLocal.models so the
        //    entry disappears from the chat picker without requiring a manual delete.
        runCatching {
            settingsUpdater { providers ->
                providers.map { p ->
                    if (p is ProviderSetting.LiteRtLocal) {
                        val target = p.models.firstOrNull { it.modelId == fileName }
                        if (target != null) p.delModel(target) else p
                    } else {
                        p
                    }
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to remove model from provider settings: $fileName", e)
        }

        throw RuntimeException(
            "The model file \"$fileName\" could not be loaded because it is corrupt or " +
            "incompatible with this runtime version. The file has been removed automatically. " +
            "Pick a different model in Settings → Providers → Local · LiteRT, or tap " +
            "\"Install default\" to download the recommended model.",
            corrupt,
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.LiteRtLocal): List<Model> {
        val installed = prefs.installedModels(LocalRuntime.LiteRT)
        val engineContextOverride = prefs.maxNumTokensOverride(LocalRuntime.LiteRT)
        return installed.map { (fileName, _) ->
            val defaults = LiteRtModelDefaults.forModelFile(fileName)
            Model(
                modelId = fileName,
                displayName = fileName,
                trustedContextWindowTokens = engineContextOverride
                    ?: defaults.maxContextLength
                    ?: defaults.maxTokens,
            )
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.LiteRtLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        val collected = StringBuilder()
        var finishReason: String? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            chunk.choices.firstOrNull()?.let { c ->
                c.delta?.parts?.forEach { p ->
                    if (p is UIMessagePart.Text) collected.append(p.text)
                }
                if (c.finishReason != null) finishReason = c.finishReason
            }
        }
        return MessageChunk(
            id = "litert-${System.currentTimeMillis()}",
            model = params.model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(collected.toString())),
                    ),
                    finishReason = finishReason ?: "stop",
                )
            ),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.LiteRtLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = streamTextInternal(
        providerSetting = providerSetting,
        messages = messages,
        params = params,
        expectedBackgroundAttestation = null,
        backgroundDispatchCallback = BackgroundProviderDispatchCallback.NO_OP,
    )

    private fun streamTextInternal(
        providerSetting: ProviderSetting.LiteRtLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        expectedBackgroundAttestation: BackgroundRuntimeAttestation?,
        backgroundDispatchCallback: BackgroundProviderDispatchCallback,
    ): Flow<MessageChunk> = flow {
        liteRtProviderInferenceMutex.lock()
        try {
        if (expectedBackgroundAttestation != null) {
            check(params.tools.isEmpty()) { "LiteRT background execution cannot expose tools" }
            check(params.providerCacheIdentity == null) {
                "LiteRT background execution cannot reuse provider cache state"
            }
            check(messages.none { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Image || part is UIMessagePart.Audio
                }
            }) { "LiteRT background execution is text-only" }
        }
        val backgroundSnapshot = expectedBackgroundAttestation?.let { expected ->
            resolveBackgroundSnapshotLocked(providerSetting, params.model).also { current ->
                if (current.attestation != expected) {
                    throw LiteRtBackgroundRuntimeMismatchException(
                        "LiteRT background runtime changed after it was frozen",
                    )
                }
            }
        }
        val installed = if (backgroundSnapshot == null) {
            prefs.installedModels(LocalRuntime.LiteRT)
        } else {
            emptyMap()
        }
        val modelPath = backgroundSnapshot?.seal?.canonicalModelPath
            ?: installed[params.model.modelId]
            ?: throw IllegalStateException("Model ${params.model.modelId} not installed")

        // Guard against a stale DataStore entry (file registered but later deleted by the user,
        // ADB, or a failed partial download).  Surface a clean message rather than letting the
        // engine throw an opaque native error.
        if (!java.io.File(modelPath).exists()) {
            throw IllegalStateException(
                "Model file for \"${params.model.modelId}\" is no longer present on disk " +
                "($modelPath). Delete the model entry in Settings → Providers → Local · LiteRT " +
                "and re-download it."
            )
        }

        // Pass the cached accelerator so ensureLoaded does not re-probe on every turn.
        // Probe runs once at install/re-detect time and is persisted; reading it here
        // avoids the System.loadLibrary("qnn_delegate_jni") call on every generation.
        val cachedAccel = backgroundSnapshot?.seal?.accelerator
            ?: prefs.acceleratorFlow(LocalRuntime.LiteRT).first()
        // Honor the per-runtime force-CPU override. Default true after the LiteRT-LM
        // 0.11.0 GPU/NNAPI native crashes; users opt back in via the settings toggle.
        val forceCpu = backgroundSnapshot?.forceCpu
            ?: prefs.forceCpu(LocalRuntime.LiteRT)

        // Per-model defaults curated to mirror Gallery's `model_allowlists/1_0_13.json`
        // (topK / topP / temperature / maxTokens / multimodal flags / speculative decoding).
        // For HF-pasted models that aren't in the table, this returns the FALLBACK config.
        val config = backgroundSnapshot?.config
            ?: LiteRtModelDefaults.forModelFile(params.model.modelId)
        // Note: the old pre-flight refusal here (multimodal + forceCpu = throw) has been
        // removed. With the runtime's vision-encoder fallback path (see
        // [LiteRtRuntime.LoadOutcome.visionFellBackToTextOnly]) and the per-model
        // [LocalRuntimePreferences.isVisionUnavailable] flag, a multimodal model on a
        // force-CPU device just loads text-only — the user can chat with it, they only
        // lose the ability to pass images. Refusing was wrong: it punished users on
        // GPU-broken devices (Adreno 7xx + restrictive OEM ROMs, Google Tensor) by
        // making the bigger Gemma-4 models completely unreachable when a text-only
        // load would have worked.
        // User-set max-context override. Lets capable models (Gemma 4 E2B = 32k) use more
        // than Gallery's curated default; the underlying KV cache size still caps it
        // (Qwen `ekv4096` cannot exceed 4096 regardless of this setting).
        val maxNumTokensOverride = if (backgroundSnapshot == null) {
            prefs.maxNumTokensOverride(LocalRuntime.LiteRT)
        } else {
            null
        }
        // EngineConfig.maxNumTokens is input + output (the KV-cache size), not an output cap.
        val effectiveEngineContextTokens = backgroundSnapshot?.contextWindowTokens
            ?: (maxNumTokensOverride
                ?: config.maxContextLength
                ?: config.maxTokens).coerceAtLeast(1)
        // LiteRT-LM 0.11.0 exposes EngineConfig.maxNumTokens (total KV/context capacity),
        // but no independent Conversation output-token parameter. Never substitute
        // params.maxTokens here: the app gate reserves that requested output separately, while
        // this value must remain the trusted engine window.

        // ---- Exact protected system instruction ----
        val systemTextsRaw = messages
            .filter { it.role == MessageRole.SYSTEM }
            .map { msg ->
                msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        // The app-level hard gate has already bounded the request against the trusted local
        // window. Preserve protected instructions exactly; a second character truncation here
        // could cut a negation, Unicode surrogate, or prompt boundary.
        val exactSystemTexts = systemTextsRaw

        // Compact tool prefix only — full-schema dump would re-blow the context budget
        // (every tool's schema is ~200-500 chars, ×50 tools = ~15k chars on its own).
        // Budget is adaptive: large-context models (Gemma 4 = 32k) see every enabled
        // tool, small-context models (Qwen 1.5B = 4k) stay capped at 25 / 2000 chars.
        // We pass the model's MAX CONTEXT LENGTH (not output cap): config.maxContextLength
        // is the input + output budget, which is what determines how much tool prefix we
        // can afford. For models without a curated maxContextLength (URL-pasted entries),
        // fall back to maxTokens (output cap) as a conservative lower bound. See
        // [LiteRtToolPrefix.budgetForContext] for tier thresholds.
        // Native tool calling via the LiteRT-LM SDK's ToolSet mechanism — mirrors Google
        // AI Edge Gallery's approach exactly. The bridge's @Tool method is enumerated
        // by the SDK at engine creation time; the model invokes it as a native function
        // call (no <tool_call> prompt-engineering, no regex extraction). When the model
        // calls runTool(name, argsJson) the bridge looks up the named tool from the
        // per-request snapshot and dispatches it through the regular execute path.
        //
        // Populate the snapshot before handing the engine the call, clear it in the
        // finally below so concurrent requests cannot see each other's tools.
        LiteRtToolBridgeRegistry.setForRequest(params.tools)
        val toolAuthorizationFingerprint = computeLiteRtToolAuthorizationFingerprint(params.tools)
        val nativeTools = if (params.tools.isNotEmpty()) listOf(toolProvider) else emptyList()
        Log.i(
            TAG,
            "tool bridge: ${params.tools.size} tool(s) registered for this request " +
                "(${params.tools.joinToString(",") { it.name }.take(200)}…)",
        )
        // For models that still need a per-tool catalogue in the system prompt (small
        // local models that benefit from explicit name + description listing alongside
        // SDK tool registration), build a compact reference block. This is descriptive
        // text only; the model invokes tools through the bridge, NOT by emitting
        // <tool_call> blocks.
        val toolReference = if (params.tools.isNotEmpty()) {
            buildString {
                append("You have ${params.tools.size} tools available. ")
                append("Call runTool(name, argsJson) with one of these names to use them:\n")
                params.tools.take(60).forEach { tool ->
                    val firstLineDesc = tool.description.lineSequence().firstOrNull()
                        ?.trim().orEmpty().take(80)
                    append("- ${tool.name}: $firstLineDesc\n")
                }
                if (params.tools.size > 60) {
                    append("(…and ${params.tools.size - 60} more tools available — ")
                    append("call runTool with their names directly when needed.)\n")
                }
            }
        } else {
            ""
        }
        val toolPrefix = toolReference

        val combinedSystem = buildString {
            if (toolPrefix.isNotEmpty()) {
                append(toolPrefix.trimEnd())
                append("\n\n")
            }
            if (exactSystemTexts.isNotEmpty()) append(exactSystemTexts)
        }.trim()

        // ---- Conversation history → turn list + cold-path blob ----
        //
        // We hand the runtime BOTH:
        //   - `turns`: the full app-gated turn list, each carrying a content signature. The
        //     runtime uses these to decide whether its warm Conversation's KV cache already
        //     holds a prefix of this history — if so it sends ONLY the new turn and reuses
        //     the cache (Gallery's behaviour), instead of re-prefilling the whole history.
        //   - `coldBlob`: the full render used only when the cache can't be reused (first
        //     turn, edited/regenerated history, tool round-trip, config change). Always
        //     correct, just not warm.
        //
        // TOOL-role messages are dropped here: their content is already represented inline
        // in the immediately-preceding ASSISTANT message's Tool part output.
        //
        // The app-level gate already drops old history as complete user-led turns. Keep that
        // atomic projection instead of applying a second, character-based truncation.
        val preparedTurns = messages.filter {
            it.role != MessageRole.SYSTEM && it.role != MessageRole.TOOL
        }
        val coldBlob = renderColdBlob(preparedTurns)
        val turns = preparedTurns.map { it.toTurn() }

        // Check the persisted vision-unavailable flag. If a prior load fell back to
        // text-only on this device, skip the GPU vision attempt entirely — saves ~1 s of
        // doomed engine init per cold load. The user can clear the flag via Settings →
        // Local · LiteRT if they update GPU drivers or move to a capable device.
        val visionPersistentlyUnavailable = if (backgroundSnapshot != null) {
            true
        } else {
            runCatching {
                prefs.isVisionUnavailable(LocalRuntime.LiteRT, params.model.modelId)
            }.getOrDefault(false)
        }
        // Also honor the per-model config field [LiteRtModelConfig.visionAccelerator] —
        // when null we never try the GPU vision backend even if the model file contains
        // vision tensors. Today this is set to null for Gemma-3n entries and to "gpu"
        // for Gemma-4 entries; treat null as "skip vision regardless".
        val effectiveSupportImage = backgroundSnapshot == null &&
            config.supportsImage &&
            config.visionAccelerator != null &&
            !visionPersistentlyUnavailable

        // ---- Engine load with full per-model + per-call config ----
        val outcome = try {
            runtime.ensureLoaded(
                modelPath = modelPath,
                preferredAccel = cachedAccel,
                forceCpu = forceCpu,
                maxNumTokens = effectiveEngineContextTokens,
                supportImage = effectiveSupportImage,
                supportAudio = backgroundSnapshot == null && config.supportsAudio,
                // Match Google AI Edge Gallery: speculative decoding is an explicit user
                // opt-in (their `ConfigKeys.ENABLE_SPECULATIVE_DECODING` defaults to false).
                // Forcing it on whenever the model file supports it has been a candidate
                // contributor to GPU init regressions on Adreno-class devices; matching
                // Gallery removes that variable. Re-enable if/when we expose a settings
                // toggle and the user explicitly opts in.
                speculativeDecoding = false,
                visionAccelerator = config.visionAccelerator ?: "gpu",
                // A background request gets its own Conversation below. Keep the engine's
                // ordinary shared Conversation neutral so request bytes/KV cannot survive.
                systemInstructionText = if (backgroundSnapshot == null) {
                    combinedSystem.ifBlank { null }
                } else {
                    null
                },
                tools = if (backgroundSnapshot == null) nativeTools else emptyList(),
                // Constrained decoding ON when this turn carries tools — matches Gallery's
                // tool-task behaviour. The SDK projects the model's output into the @Tool
                // call grammar so Gemma actually invokes runTool(...) via the bridge
                // instead of producing free-form "I can't do that" text. OFF for plain
                // chat turns to avoid biasing normal language.
                constrainedDecoding = backgroundSnapshot == null && params.tools.isNotEmpty(),
                topK = config.topK,
                topP = config.topP,
                temperature = config.temperature,
                providerCacheIdentity = if (backgroundSnapshot == null) {
                    params.providerCacheIdentity
                } else {
                    null
                },
                toolAuthorizationFingerprint = if (backgroundSnapshot == null) {
                    toolAuthorizationFingerprint
                } else {
                    ""
                },
            )
        } catch (corrupt: LiteRtModelCorruptException) {
            handleCorruptModel(corrupt)
        } catch (t: Throwable) {
            throw translateSdkError(t, effectiveEngineContextTokens)
        }
        val resolvedAccel = outcome.accelerator
        if (
            backgroundSnapshot != null &&
            resolvedAccel != backgroundSnapshot.attestation.accelerator
        ) {
            throw LiteRtBackgroundRuntimeMismatchException(
                "LiteRT accelerator changed while preparing background execution",
            )
        }
        // Persist whatever the runtime actually used. Without this, the in-runtime
        // GPU -> CPU fallback only sticks for the current process: every cold start
        // re-probes the (known-broken-on-this-device) GPU and wastes ~1 s before
        // landing on CPU again. By persisting we also surface the truth to the
        // Doctor and Settings UI ("running on CPU because GPU failed at init").
        if (resolvedAccel != cachedAccel) {
            runCatching { prefs.setAccelerator(LocalRuntime.LiteRT, resolvedAccel) }
                .onFailure { Log.w(TAG, "setAccelerator($resolvedAccel) failed", it) }
        }
        // If the runtime had to drop vision to text-only on this load, persist that so the
        // next load skips the doomed GPU vision attempt up front. Only stamp on a true
        // fallback — not when we already skipped vision because of the persisted flag.
        if (outcome.visionFellBackToTextOnly && !visionPersistentlyUnavailable) {
            Log.w(TAG, "Vision encoder unavailable on this device for ${params.model.modelId}; " +
                "persisting decision so future loads skip the GPU vision attempt")
            runCatching {
                prefs.setVisionUnavailable(LocalRuntime.LiteRT, params.model.modelId)
            }.onFailure { Log.w(TAG, "setVisionUnavailable failed", it) }
        }

        // ---- Stream + cumulative→delta conversion ----
        //
        // The SDK's MessageCallback.onMessage emits the CUMULATIVE response so far
        // (Gallery's `partialResult` is consumed via a REPLACE-the-content path). But
        // RikkaHub's downstream chunk-handling APPENDS each Text part (see
        // UIMessage.appendChunk in ai/ui/Message.kt: incoming Text parts are concatenated
        // onto the previous Text part). Emitting cumulative chunks straight through would
        // duplicate the response geometrically. So we hold the previous cumulative and
        // emit only the new suffix as the delta.
        //
        // We also collect the full cumulative response so we can extract tool-call blocks
        // at end-of-stream (see the comment block right after the collect{} below for why
        // we do this once at the end rather than incrementally like AICoreProvider does).
        val streamId = "litert-${System.currentTimeMillis()}"
        var previousCumulative = ""
        val fullResponseBuilder = StringBuilder()

        // Extract images + audio from the LAST USER message only (the new turn). Earlier
        // turns' media is already represented in the conversation prefix via the cold-blob
        // text render; the SDK's vision/audio executors only consume current-turn input.
        // Decode here so the runtime gets ready-to-use Bitmaps / encoded audio bytes and
        // doesn't have to know about content URIs.
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER }
        val userImageParts = lastUser?.parts
            ?.filterIsInstance<UIMessagePart.Image>()
            .orEmpty()
        // Gate image forwarding on the ACTUAL post-load vision state (outcome.visionEnabled),
        // NOT the pre-load effectiveSupportImage. ensureLoaded may have fallen back to a
        // text-only engine when the GPU vision executor failed to init; forwarding image bytes
        // to an engine with no vision executor crashes the native runtime (SIGSEGV in
        // liblitertlm_jni.so). See [decideImageForwarding].
        val imageDecision = decideImageForwarding(
            modelImageCapable = config.supportsImage && config.visionAccelerator != null,
            visionEnabledPostLoad = outcome.visionEnabled,
            userSentImages = userImageParts.isNotEmpty(),
        )
        val turnImages: List<android.graphics.Bitmap> = if (imageDecision.forwardImages) {
            userImageParts.mapNotNull { it.toBitmap(context) }
        } else {
            // Vision unavailable / not supported by this model — drop image parts. When the
            // user actually attached images to a vision-capable model, the note below tells
            // them why; otherwise it's a silent no-op (text-only model, no attachment).
            emptyList()
        }
        val turnAudio: List<ByteArray> = if (config.supportsAudio) {
            lastUser?.parts
                ?.filterIsInstance<UIMessagePart.Audio>()
                ?.mapNotNull { it.audioBytes(context) }
                .orEmpty()
        } else {
            emptyList()
        }
        if (turnImages.isNotEmpty() || turnAudio.isNotEmpty()) {
            Log.i(
                TAG,
                "streamText: forwarding ${turnImages.size} image(s) + ${turnAudio.size} " +
                    "audio clip(s) to runtime for modelId=${params.model.modelId}",
            )
        }

        // The user attached image(s) to a vision-capable model whose encoder couldn't
        // initialise on this device, so they were dropped above. Tell the user once, in-line,
        // so a text-only reply to an image prompt isn't silently confusing.
        if (imageDecision.noteImagesDropped) {
            Log.w(
                TAG,
                "vision not live post-load for ${params.model.modelId}; dropped " +
                    "${userImageParts.size} image(s), noting it to the user",
            )
            emit(
                MessageChunk(
                    id = streamId,
                    model = params.model.modelId,
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(UIMessagePart.Text(VISION_DROPPED_NOTE)),
                            ),
                            message = null,
                            finishReason = null,
                        )
                    ),
                )
            )
        }

        try {
            try {
                val runtimeStream = if (backgroundSnapshot == null) {
                    runtime.streamTurns(
                        history = turns,
                        coldBlob = coldBlob,
                        images = turnImages,
                        audioClips = turnAudio,
                    )
                } else {
                    runtime.streamIsolatedBackground(
                        prepared = LiteRtPreparedBackgroundRuntime(
                            seal = backgroundSnapshot.seal,
                            maxNumTokens = backgroundSnapshot.contextWindowTokens,
                            systemInstructionText = combinedSystem.ifBlank { null },
                            topK = backgroundSnapshot.config.topK,
                            topP = backgroundSnapshot.config.topP,
                            temperature = backgroundSnapshot.config.temperature,
                        ),
                        coldBlob = coldBlob,
                        onDispatchStarted = {
                            backgroundDispatchCallback.onDispatchStarted(
                                backgroundSnapshot.attestation,
                            )
                        },
                    )
                }
                runtimeStream.collect { cumulative ->
                    // Defensive: if the SDK ever emits a non-monotonic cumulative (e.g. after
                    // an internal retry or template re-tokenisation), treat the new payload as a
                    // fresh start — emit it whole as the delta rather than computing a negative-
                    // length suffix that would silently drop characters.
                    val delta = if (cumulative.startsWith(previousCumulative)) {
                        cumulative.substring(previousCumulative.length)
                    } else {
                        cumulative
                    }
                    previousCumulative = cumulative
                    fullResponseBuilder.setLength(0)
                    fullResponseBuilder.append(cumulative)

                    if (delta.isNotEmpty()) {
                        emit(
                            MessageChunk(
                                id = streamId,
                                model = params.model.modelId,
                                choices = listOf(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = UIMessage(
                                            role = MessageRole.ASSISTANT,
                                            parts = listOf(UIMessagePart.Text(delta)),
                                        ),
                                        message = null,
                                        finishReason = null,
                                    )
                                ),
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                // Translate the LiteRT-LM SDK's raw native-error text into something the user
                // and the chat error surface can act on. The most common one — "Input token
                // ids are too long. Exceeding the maximum number of tokens allowed: N >= M"
                // — looks scary but means "your conversation is bigger than this model can
                // hold". Surface that with a recovery hint.
                throw translateSdkError(t, effectiveEngineContextTokens)
            }
        } finally {
            LiteRtToolBridgeRegistry.clear()
        }

        // ---- Persist tok/s telemetry --------------------------------------------------
        // After a clean stream the runtime stamps lastTelemetry with prefill / decode
        // timings and character counts. Persist it per-model so the Settings page can
        // render "Last gen: 12.4 tok/s prefill, 2.7 tok/s decode" without re-running
        // inference. Best-effort: a write failure must never break the user's reply.
        if (backgroundSnapshot == null) runtime.lastTelemetry?.let { tele ->
            // Only persist samples with non-trivial timings — a zero-decode case would
            // be the SDK emitting no tokens, which is already a bug we want to see
            // elsewhere; persisting tps=0 muddies the rolling average.
            if (tele.decodeMs > 100 && tele.outputCharCount > 0) {
                runCatching {
                    prefs.setPerfTelemetry(
                        LocalRuntime.LiteRT,
                        LocalRuntimePreferences.PerfSample(
                            modelId = params.model.modelId,
                            prefillTps = tele.prefillTps,
                            decodeTps = tele.decodeTps,
                            specDecodingEngaged = tele.specDecodingEngaged,
                            sampledAtMs = System.currentTimeMillis(),
                        ),
                    )
                }.onFailure { Log.w(TAG, "setPerfTelemetry failed", it) }
                Log.i(
                    TAG,
                    "telemetry: prefill=${"%.2f".format(tele.prefillTps)} tok/s decode=${"%.2f".format(tele.decodeTps)} tok/s " +
                        "(prefillMs=${tele.prefillMs} decodeMs=${tele.decodeMs} inChars=${tele.inputCharCount} outChars=${tele.outputCharCount} " +
                        "specDecoding=${tele.specDecodingEngaged})",
                )
            }
        }

        // With native SDK tool calling the @Tool method body already executed the tool
        // and returned its output as the function's return value — there are NO leftover
        // <tool_call> blocks to extract from the streamed text. The SDK incorporated the
        // tool's output back into the model's reasoning automatically. Always emit
        // finishReason = "stop" at end of stream.
        //
        // (The legacy LiteRtToolPrefix.extractToolCalls + unclosed-tag recovery path
        // is kept around for the in-app browser tool which still uses prompt-engineered
        // tool calling, but it is not called from the LiteRT chat path anymore.)
        emit(
            MessageChunk(
                id = streamId,
                model = params.model.modelId,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                        ),
                        message = null,
                        finishReason = "stop",
                    )
                ),
            )
        )
        } finally {
            // Clear while the provider-wide lock is still held. An older request can therefore
            // never clear a newer request's tool authorization snapshot.
            LiteRtToolBridgeRegistry.clear()
            liteRtProviderInferenceMutex.unlock()
        }
    }

    /**
     * Render ONE message's content WITHOUT a role marker — the raw turn text.
     *
     * Used both for the warm path (the runtime sends this as-is and the SDK's chat template
     * applies role wrapping, exactly like Gallery) and as the body of each line in
     * [renderHistoryAsChatML]. Tool calls/outputs are kept in the
     * `<tool_call>…</tool_call>` / `<tool_result>…</tool_result>` shape the prompt prefix
     * teaches the model, preserving prompt-engineered tool-calling continuity across turns.
     */
    private fun renderTurnRawText(msg: UIMessage): String = buildString {
        when (msg.role) {
            MessageRole.USER -> {
                append(
                    msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
                )
            }
            MessageRole.ASSISTANT -> {
                // Walk the parts in order so an assistant turn that interleaves text +
                // tool calls is reconstructed faithfully.
                for (part in msg.parts) {
                    when (part) {
                        is UIMessagePart.Text -> append(part.text)
                        is UIMessagePart.Tool -> {
                            val callJson = """{"name":"${part.toolName}","arguments":${part.input.ifBlank { "{}" }}}"""
                            append("<tool_call>").append(callJson).append("</tool_call>")
                            if (part.isExecuted) {
                                val outText = part.output
                                    .filterIsInstance<UIMessagePart.Text>()
                                    .joinToString("") { it.text }
                                if (outText.isNotEmpty()) {
                                    append("\n<tool_result>").append(outText).append("</tool_result>")
                                }
                            }
                        }
                        else -> { /* image/audio/document parts not yet wired into LiteRT input */ }
                    }
                }
            }
            else -> { /* SYSTEM / TOOL are filtered out by the caller */ }
        }
    }

    /** Convert a message into a [Turn] for the runtime's warm/cold KV-cache decision. */
    private fun UIMessage.toTurn(): Turn {
        val turnRole = when (this.role) {
            MessageRole.USER -> ROLE_USER
            MessageRole.ASSISTANT -> ROLE_ASSISTANT
            else -> "other"
        }
        return Turn(
            role = turnRole,
            rawText = renderTurnRawText(this),
            containsMedia = parts.any { part ->
                part is UIMessagePart.Image || part is UIMessagePart.Video ||
                    part is UIMessagePart.Audio || part is UIMessagePart.Document
            },
        )
    }

    /**
     * The cold-path render of the (already SYSTEM/TOOL-filtered) history — what the runtime
     * sends when it cannot reuse a warm KV cache.
     *
     * A single message is sent raw, so the SDK chat template wraps it cleanly (identical to
     * the warm path). Multiple messages are crammed into one marker-formatted ChatML blob:
     * Qwen/Gemma recognise the inner "User:/Assistant:" markers from their instruction-tuning
     * data even when the template only wraps the OUTER content.
     */
    private fun renderColdBlob(messages: List<UIMessage>): String =
        if (messages.size == 1) renderTurnRawText(messages[0])
        else renderHistoryAsChatML(messages)

    private fun renderHistoryAsChatML(messages: List<UIMessage>): String = buildString {
        var first = true
        for (msg in messages) {
            if (msg.role == MessageRole.SYSTEM || msg.role == MessageRole.TOOL) continue
            if (!first) append("\n")
            first = false
            when (msg.role) {
                MessageRole.USER -> append("User: ").append(renderTurnRawText(msg))
                MessageRole.ASSISTANT -> append("Assistant: ").append(renderTurnRawText(msg))
                else -> { /* unreachable — filtered above */ }
            }
        }
        // Trailing assistant cue gives the model a clear "your turn" signal even after the
        // chat template wraps the input. Cheap belt-and-braces vs. the model going silent.
        if (isNotEmpty()) append("\nAssistant: ")
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("LiteRT does not support image generation in 22A")

    /**
     * Translate the LiteRT-LM SDK's raw native error message into a user-actionable
     * exception. The SDK throws bare `RuntimeException` with messages like
     * "Status Code: 3. Message: Input token ids are too long. Exceeding the maximum
     * number of tokens allowed: 4610 >= 4096" — technically correct but unhelpful for
     * users on a chat surface.
     */
    private fun translateSdkError(t: Throwable, effectiveMaxNumTokens: Int): Throwable {
        // Typed exception from the runtime — the structural diagnosis already happened.
        // Vision-unavailable here means BOTH "GPU vision tried and failed" AND "text-only
        // retry also failed", i.e. the model truly cannot load on this device's chip class
        // (typically Adreno 7xx + One UI / OriginOS + multimodal Gemma — upstream LiteRT-LM
        // issue #2292, OpenGL fallback's CreateSharedMemoryManager is UNIMPLEMENTED).
        if (t is LiteRtVisionUnavailableException) {
            return RuntimeException(
                "This multimodal model could not be loaded on your device. The GPU vision " +
                    "encoder failed (a known upstream LiteRT-LM issue on Adreno 7xx GPUs + " +
                    "OEM ROMs that restrict vendor library access), and the text-only " +
                    "fallback ALSO failed. Pick a text-only model from Settings → Local · " +
                    "LiteRT (Qwen2.5-1.5B-Instruct is the most reliable) or try a newer " +
                    "device. If a future LiteRT-LM SDK adds an OpenGL fallback path, an " +
                    "app update should pick it up automatically.",
                t,
            )
        }
        // Walk the cause chain too — LiteRtRuntime wraps the SDK error inside a
        // RuntimeException of its own ("LiteRT engine could not load this model on
        // this device's GPU OR CPU. … Underlying: …"), so the SDK's own text only
        // appears in the inner cause. Concatenate so the regex catches either layer.
        val joined = generateSequence<Throwable>(t) { it.cause }
            .map { it.message.orEmpty() }
            .joinToString("\n")

        // Context-length overflow. The numbers in the message tell us the actual
        // input vs. the limit; surface both plus what to do next.
        val overflow = Regex("""(\d+)\s*>=\s*(\d+)""").find(joined)
        if (joined.contains("Input token ids are too long", ignoreCase = true) ||
            (joined.contains("Status Code: 3", ignoreCase = true) && overflow != null)) {
            val (have, limit) = overflow?.let { it.groupValues[1] to it.groupValues[2] }
                ?: ("?" to effectiveMaxNumTokens.toString())
            return RuntimeException(
                "This model can only hold $limit tokens of context, but the conversation " +
                    "needs $have. Tap /new to start a fresh chat, switch to a model with a " +
                    "larger context (e.g. Gemma 4 E2B = 32k), OR raise \"Max context\" in " +
                    "Settings → Local · LiteRT if your model file supports it.",
                t,
            )
        }

        // Vision-executor failure. The SDK's stack trace points at
        // `vision_litert_compiled_model_executor.cc` when the model has vision
        // tensors but the runtime couldn't allocate the GPU vision encoder. The
        // pre-flight check covers force-CPU, but this catches the case where the
        // user has GPU enabled and it STILL fails (older device, GPU compute path
        // not supported for the model's vision config). Recovery: pick text-only.
        if (joined.contains("vision_litert_compiled_model_executor", ignoreCase = true) ||
            joined.contains("vision_litert", ignoreCase = true)) {
            return RuntimeException(
                "This model is multimodal (vision) and the GPU vision executor failed " +
                    "to initialise on this device. The most reliable workaround on devices " +
                    "without compatible GPU compute is to pick a text-only model: " +
                    "Gemma3-1B-IT or Qwen2.5-1.5B-Instruct " +
                    "from the catalog in Settings → Local · LiteRT.",
                t,
            )
        }

        // Anything else — keep the original; GenerationHandler's wrapper already
        // strips the stack trace from the LLM-facing envelope.
        return t
    }

    companion object {
        /** In-chat note shown once when the user attached image(s) to a vision-capable model
         *  whose vision encoder could not initialise on this device (text-only fallback). */
        private const val VISION_DROPPED_NOTE =
            "[Note: this model's vision encoder is unavailable on this device, so attached " +
                "images were not analysed. Replying from text only.]\n\n"

        // MAX_TOOLS_IN_PREFIX / TOOL_PREFIX_CHAR_BUDGET were static caps that starved
        // large-context models (Gemma 4 = 32k) of 30+ enabled tools. Replaced by
        // [LiteRtToolPrefix.budgetForContext], which scales with the model's context.
    }
}

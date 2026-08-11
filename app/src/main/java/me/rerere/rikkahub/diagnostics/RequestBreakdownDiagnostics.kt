package me.rerere.rikkahub.diagnostics

import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.context.ApproximateContextTokenEstimator
import me.rerere.ai.context.ProviderRequestTokenEstimator
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.DEFAULT_USER_CONTEXT_WINDOW_TOKENS
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val REQUEST_BREAKDOWN_DIRECTORY = "diagnostics"
private const val REQUEST_BREAKDOWN_FILE = "last_request_breakdown.json"
private const val REQUEST_BREAKDOWN_HISTORY_FILE = "request_breakdown_history.json"
private const val REQUEST_BREAKDOWN_HISTORY_LIMIT = 128
private const val MESSAGE_FRAMING_TOKENS = 8
private val requestBreakdownJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

data class RequestBreakdownSection(
    val name: String,
    val characters: Long,
    val utf8Bytes: Long,
    val estimatedTokens: Int,
)

data class RequestBreakdownDiagnostic(
    val recordedAtEpochMs: Long,
    val generationHash: String,
    val providerCallIndex: Int,
    val modelId: String,
    val providerType: String,
    val requestMode: String,
    val messageCount: Int,
    val toolCount: Int,
    /** Full eligible catalogue count before the second-user progressive selection. */
    val toolCatalogCandidateCount: Int? = null,
    /** Number of real selected schemas, excluding the fixed catalogue helpers. */
    val toolCatalogSelectedSchemaCount: Int? = null,
    /** Privacy-safe phase of the progressive second-user directory. */
    val toolCatalogStage: String? = null,
    /** Number of durable, model-confirmed shortcut metadata rows considered for this turn. */
    val toolFastLaneShortcutLibraryCount: Int? = null,
    /** Number of real schemas automatically injected by a Fast Lane path. */
    val toolFastLaneInjectedSchemaCount: Int? = null,
    /** Host-maintained quick-package id; never a Skill body or user prompt. */
    val toolFastLaneBundleId: String? = null,
    val continuationHistoryEpoch: Int = 0,
    val continuationHistoryEpochReason: String? = null,
    val contextProjectionMode: String = "ordinary",
    val frozenPrefixMessageCount: Int = 0,
    val contextWindowTokens: Int? = null,
    val contextInputBudgetTokens: Int? = null,
    val contextHighWatermarkTokens: Int? = null,
    val contextHighWatermarkReached: Boolean? = null,
    val memoryCount: Int,
    /** Random retrieval-trace handle; unrelated to conversation, scope, query, or memory ids. */
    val memoryRetrievalTraceId: String? = null,
    val enabledSkillNames: List<String>,
    val toolNames: List<String>,
    val wireSections: List<RequestBreakdownSection>,
    val systemSourceHints: List<RequestBreakdownSection>,
    val estimatedMessageTokens: Int,
    val estimatedToolSchemaTokens: Int,
    val estimatedRequestTokens: Int,
    /** Transient generation-keyed fingerprints. They are deliberately never persisted. */
    val semanticSegmentFingerprints: List<String> = emptyList(),
    /** Token weight for each transient semantic segment, in the same order as its fingerprint. */
    val semanticSegmentEstimatedTokens: List<Int> = emptyList(),
    val semanticFingerprintStatus: String = "ok",
    val toolManifestFingerprint: String? = null,
    val commonPrefixSegmentCount: Int? = null,
    val commonPrefixEstimatedTokens: Int? = null,
    val commonPrefixRequestBasisPoints: Int? = null,
    val previousToolManifestMatched: Boolean? = null,
    val providerPromptTokens: Int? = null,
    val providerCachedTokens: Int? = null,
    val providerFreshPromptTokens: Int? = null,
    val providerCachedPromptBasisPoints: Int? = null,
    val providerCompletionTokens: Int? = null,
) {
    fun withProviderUsage(
        promptTokens: Int,
        cachedTokens: Int,
        completionTokens: Int,
    ): RequestBreakdownDiagnostic {
        val prompt = promptTokens.coerceAtLeast(0)
        val cached = cachedTokens.coerceIn(0, prompt)
        return copy(
            recordedAtEpochMs = System.currentTimeMillis(),
            providerPromptTokens = prompt,
            providerCachedTokens = cached,
            providerFreshPromptTokens = prompt - cached,
            providerCachedPromptBasisPoints = if (prompt > 0) {
                (cached.toLong() * 10_000L / prompt).toInt()
            } else {
                null
            },
            providerCompletionTokens = completionTokens.coerceAtLeast(0),
        )
    }

    fun withPreviousRequest(previous: RequestBreakdownDiagnostic?): RequestBreakdownDiagnostic {
        if (previous == null || previous.generationHash != generationHash) return this
        val commonPrefix = previous.semanticSegmentFingerprints
            .zip(semanticSegmentFingerprints)
            .takeWhile { (left, right) -> left == right }
            .size
        val commonPrefixTokens = semanticSegmentEstimatedTokens
            .take(commonPrefix)
            .fold(0L) { total, tokens -> total + tokens.coerceAtLeast(0) }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return copy(
            commonPrefixSegmentCount = commonPrefix,
            commonPrefixEstimatedTokens = commonPrefixTokens,
            commonPrefixRequestBasisPoints = if (estimatedRequestTokens > 0) {
                (commonPrefixTokens.toLong() * 10_000L / estimatedRequestTokens)
                    .coerceAtMost(10_000L)
                    .toInt()
            } else {
                null
            },
            previousToolManifestMatched = previous.toolManifestFingerprint != null &&
                previous.toolManifestFingerprint == toolManifestFingerprint,
        )
    }

    /** Shadow-only budget signal. It never authorizes truncation or summarization. */
    fun withContextBudget(
        effectiveContextWindowTokens: Int,
        requestedOutputTokens: Int?,
    ): RequestBreakdownDiagnostic {
        val window = effectiveContextWindowTokens
            .takeIf { it > 0 }
            ?: DEFAULT_USER_CONTEXT_WINDOW_TOKENS
        val defaultOutputReserve = minOf(8_192, maxOf(2_048, window / 8))
        val outputReserve = (requestedOutputTokens?.takeIf { it > 0 } ?: defaultOutputReserve)
            .coerceAtMost((window / 2).coerceAtLeast(1))
        val framingReserve = maxOf(
            2_048,
            ((window.toLong() * 2L + 99L) / 100L).toInt(),
        ).coerceAtMost((window / 4).coerceAtLeast(1))
        val inputBudget = (window - outputReserve - framingReserve).coerceAtLeast(1)
        val highWatermark = (inputBudget.toLong() * 85L / 100L).toInt().coerceAtLeast(1)
        return copy(
            contextWindowTokens = window,
            contextInputBudgetTokens = inputBudget,
            contextHighWatermarkTokens = highWatermark,
            contextHighWatermarkReached = estimatedRequestTokens >= highWatermark,
        )
    }

    fun redactedSummary(): String = buildString {
        append("requestBreakdown(call=").append(providerCallIndex)
        append(", estimated=").append(estimatedRequestTokens)
        append(", messages=").append(estimatedMessageTokens)
        append(", schemas=").append(estimatedToolSchemaTokens)
        append(", tools=").append(toolCount)
        toolCatalogCandidateCount?.let { append(", catalogCandidates=").append(it) }
        toolCatalogSelectedSchemaCount?.let { append(", catalogSelected=").append(it) }
        toolCatalogStage?.let { append(", catalogStage=").append(it) }
        toolFastLaneShortcutLibraryCount?.let { append(", fastLaneLibrary=").append(it) }
        toolFastLaneInjectedSchemaCount?.let { append(", fastLaneInjected=").append(it) }
        toolFastLaneBundleId?.let { append(", fastLaneBundle=").append(it) }
        append(", historyEpoch=").append(continuationHistoryEpoch)
        append(", projection=").append(contextProjectionMode)
        contextHighWatermarkReached?.let { append(", highWater=").append(it) }
        append(", memories=").append(memoryCount)
        providerPromptTokens?.let { append(", providerPrompt=").append(it) }
        providerCachedTokens?.let { append(", cached=").append(it) }
        providerCachedPromptBasisPoints?.let { append(", providerCacheBp=").append(it) }
        commonPrefixEstimatedTokens?.let { append(", commonPrefixTokens=").append(it) }
        append(')')
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("schema_version", 3)
        put("recorded_at_epoch_ms", recordedAtEpochMs)
        put("generation_hash", generationHash)
        put("provider_call_index", providerCallIndex)
        put("model_id", modelId.take(120))
        put("provider_type", providerType.take(80))
        put("request_mode", requestMode.take(80))
        put("message_count", messageCount)
        put("tool_count", toolCount)
        toolCatalogCandidateCount?.let { put("tool_catalog_candidate_count", it) }
        toolCatalogSelectedSchemaCount?.let { put("tool_catalog_selected_schema_count", it) }
        toolCatalogStage?.let { put("tool_catalog_stage", it.take(32)) }
        toolFastLaneShortcutLibraryCount?.let { put("tool_fast_lane_shortcut_library_count", it) }
        toolFastLaneInjectedSchemaCount?.let { put("tool_fast_lane_injected_schema_count", it) }
        toolFastLaneBundleId?.let { put("tool_fast_lane_bundle_id", it.take(64)) }
        put("continuation_history_epoch", continuationHistoryEpoch)
        continuationHistoryEpochReason?.let {
            put("continuation_history_epoch_reason", it.take(64))
        }
        put("context_projection_mode", contextProjectionMode.take(64))
        put("frozen_prefix_message_count", frozenPrefixMessageCount)
        contextWindowTokens?.let { put("context_window_tokens", it) }
        contextInputBudgetTokens?.let { put("context_input_budget_tokens", it) }
        contextHighWatermarkTokens?.let { put("context_high_watermark_tokens", it) }
        contextHighWatermarkReached?.let { put("context_high_watermark_reached", it) }
        put("memory_count", memoryCount)
        memoryRetrievalTraceId?.let { put("memory_retrieval_trace_id", it.take(64)) }
        put("enabled_skill_names", JsonArray(enabledSkillNames.map(::JsonPrimitive)))
        put("tool_names", JsonArray(toolNames.map(::JsonPrimitive)))
        put("estimated_message_tokens", estimatedMessageTokens)
        put("estimated_tool_schema_tokens", estimatedToolSchemaTokens)
        put("estimated_request_tokens", estimatedRequestTokens)
        put("semantic_fingerprint_status", semanticFingerprintStatus.take(32))
        commonPrefixSegmentCount?.let { put("common_prefix_segment_count", it) }
        commonPrefixEstimatedTokens?.let { put("common_prefix_estimated_tokens", it) }
        commonPrefixRequestBasisPoints?.let { put("common_prefix_request_basis_points", it) }
        previousToolManifestMatched?.let { put("previous_tool_manifest_matched", it) }
        providerPromptTokens?.let { put("provider_prompt_tokens", it) }
        providerCachedTokens?.let { put("provider_cached_tokens", it) }
        providerFreshPromptTokens?.let { put("provider_fresh_prompt_tokens", it) }
        providerCachedPromptBasisPoints?.let { put("provider_cached_prompt_basis_points", it) }
        providerCompletionTokens?.let { put("provider_completion_tokens", it) }
        put("wire_sections", wireSections.toJson())
        put("system_source_hints", systemSourceHints.toJson())
        put(
            "privacy_note",
            "Counts, names, and aggregate prefix metrics only; transient HMACs and payload text are not persisted.",
        )
    }

    companion object {
        fun create(
            generationId: String,
            providerCallIndex: Int,
            modelId: String,
            providerType: String,
            requestMode: String,
            finalMessages: List<UIMessage>,
            tools: List<Tool>,
            builtInTools: Set<BuiltInTools> = emptySet(),
            assistantPrompt: String,
            userIdentityPrompt: String,
            toolSystemPrompts: List<String>,
            memoryPrompt: String,
            recentChatsPrompt: String,
            dynamicSystemAddendum: String?,
            memoryCount: Int,
            memoryRetrievalTraceId: String? = null,
            enabledSkillNames: Collection<String>,
            toolCatalogCandidateCount: Int? = null,
            toolCatalogSelectedSchemaCount: Int? = null,
            toolCatalogStage: String? = null,
            toolFastLaneShortcutLibraryCount: Int? = null,
            toolFastLaneInjectedSchemaCount: Int? = null,
            toolFastLaneBundleId: String? = null,
            continuationHistoryEpoch: Int = 0,
            continuationHistoryEpochReason: String? = null,
            contextProjectionMode: String = "ordinary",
            frozenPrefixMessageCount: Int = 0,
            fingerprintKey: ByteArray = newRequestBreakdownFingerprintKey(),
        ): RequestBreakdownDiagnostic {
            val systemMessages = finalMessages.filter { it.role == MessageRole.SYSTEM }
            val nonSystemMessages = finalMessages.filterNot { it.role == MessageRole.SYSTEM }
            val toolSchemaPayloads = tools.toToolSchemaPayloads()
            val toolSchemaSection = toolSchemaPayloads.toToolSchemaSection()
            val builtInToolNames = builtInTools.map { it.diagnosticName() }.sorted()
            val builtInToolTokens = ProviderRequestTokenEstimator()
                .estimateBuiltInToolTokens(builtInTools)
            val builtInToolSection = RequestBreakdownSection(
                name = "built_in_tools",
                characters = builtInToolNames.sumOf { it.length }.toLong(),
                utf8Bytes = builtInToolNames.sumOf { it.toByteArray(Charsets.UTF_8).size }.toLong(),
                estimatedTokens = builtInToolTokens,
            )
            val systemSection = systemMessages.toMessageSection("system_messages")
            val nonSystemSection = nonSystemMessages.toMessageSection("non_system_messages")
            val wireSections = listOf(
                systemSection,
                nonSystemSection,
                toolSchemaSection,
                builtInToolSection,
            )
            val estimatedMessageTokens = systemSection.estimatedTokens + nonSystemSection.estimatedTokens
            val estimatedToolSchemaTokens = (
                toolSchemaSection.estimatedTokens.toLong() + builtInToolTokens
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val toolManifestFingerprint = runCatching {
                RequestBreakdownFingerprinter.toolManifest(
                    fingerprintKey,
                    toolSchemaPayloads,
                    builtInToolNames,
                )
            }.getOrNull()
            val semanticSegments = runCatching {
                buildList {
                    requireNotNull(toolManifestFingerprint)
                    add(SemanticSegment(
                        fingerprint = toolManifestFingerprint,
                        estimatedTokens = estimatedToolSchemaTokens,
                    ))
                    addAll(finalMessages.semanticSegments(fingerprintKey))
                }
            }
            val semanticFingerprintStatus = if (semanticSegments.isSuccess) "ok" else "unavailable"
            val resolvedSemanticSegments = semanticSegments.getOrDefault(emptyList())

            return RequestBreakdownDiagnostic(
                recordedAtEpochMs = System.currentTimeMillis(),
                generationHash = RequestBreakdownFingerprinter.values(
                    fingerprintKey,
                    "generation",
                    listOf(generationId),
                ),
                providerCallIndex = providerCallIndex.coerceAtLeast(1),
                modelId = modelId,
                providerType = providerType,
                requestMode = requestMode,
                messageCount = finalMessages.size,
                toolCount = tools.size + builtInTools.size,
                toolCatalogCandidateCount = toolCatalogCandidateCount,
                toolCatalogSelectedSchemaCount = toolCatalogSelectedSchemaCount,
                toolCatalogStage = toolCatalogStage,
                toolFastLaneShortcutLibraryCount = toolFastLaneShortcutLibraryCount,
                toolFastLaneInjectedSchemaCount = toolFastLaneInjectedSchemaCount,
                toolFastLaneBundleId = toolFastLaneBundleId,
                continuationHistoryEpoch = continuationHistoryEpoch.coerceAtLeast(0),
                continuationHistoryEpochReason = continuationHistoryEpochReason
                    ?.takeIf(String::isNotBlank),
                contextProjectionMode = contextProjectionMode,
                frozenPrefixMessageCount = frozenPrefixMessageCount.coerceAtLeast(0),
                memoryCount = memoryCount.coerceAtLeast(0),
                memoryRetrievalTraceId = memoryRetrievalTraceId?.take(64),
                enabledSkillNames = enabledSkillNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted(),
                toolNames = (
                    tools.map(Tool::name) + builtInToolNames.map { "built_in:$it" }
                    ).distinct().sorted(),
                wireSections = wireSections,
                systemSourceHints = listOf(
                    assistantPrompt.toTextSection("assistant_system_prompt"),
                    userIdentityPrompt.toTextSection("user_identity_prompt"),
                    toolSystemPrompts.toTextSection("tool_system_prompts"),
                    memoryPrompt.toTextSection("memory_prompt"),
                    recentChatsPrompt.toTextSection("recent_chats_prompt"),
                    dynamicSystemAddendum.orEmpty().toTextSection("dynamic_system_addendum"),
                ),
                estimatedMessageTokens = estimatedMessageTokens,
                estimatedToolSchemaTokens = estimatedToolSchemaTokens,
                estimatedRequestTokens = estimatedMessageTokens + estimatedToolSchemaTokens,
                semanticSegmentFingerprints = resolvedSemanticSegments.map(SemanticSegment::fingerprint),
                semanticSegmentEstimatedTokens = resolvedSemanticSegments.map(
                    SemanticSegment::estimatedTokens,
                ),
                semanticFingerprintStatus = semanticFingerprintStatus,
                toolManifestFingerprint = toolManifestFingerprint,
            )
        }
    }
}

object RequestBreakdownDiagnosticsStore {
    @Synchronized
    fun write(
        filesDir: File,
        diagnostic: RequestBreakdownDiagnostic,
        includeHistory: Boolean = true,
    ) {
        runCatching {
            val directory = File(filesDir, REQUEST_BREAKDOWN_DIRECTORY).apply { mkdirs() }
            val destination = File(directory, REQUEST_BREAKDOWN_FILE)
            val entry = diagnostic.toJson()
            val payload = requestBreakdownJson.encodeToString(
                JsonObject.serializer(),
                entry,
            )
            writeAtomically(destination, payload)
            if (!includeHistory) return@runCatching

            val historyDestination = File(directory, REQUEST_BREAKDOWN_HISTORY_FILE)
            // The bounded history is for numeric cache trends. User-defined external tool/skill
            // names remain available only in the last-call file and are not multiplied 128 times.
            val historyEntry = JsonObject(entry.filterKeys { key ->
                key !in HISTORY_REDACTED_NAME_FIELDS
            })
            val existing = readHistoryEntries(historyDestination)
            val retained = existing.filterNot { candidate ->
                candidate["generation_hash"]?.jsonPrimitive?.content == diagnostic.generationHash &&
                    candidate["provider_call_index"]?.jsonPrimitive?.content?.toIntOrNull() ==
                    diagnostic.providerCallIndex
            }
            val historyEntries = (retained + historyEntry).takeLast(REQUEST_BREAKDOWN_HISTORY_LIMIT)
            val historyPayload = requestBreakdownJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("schema_version", 1)
                    put("max_entries", REQUEST_BREAKDOWN_HISTORY_LIMIT)
                    put("entries", JsonArray(historyEntries))
                    put(
                        "privacy_note",
                        "Bounded aggregate metrics only; no HMAC, payload text, tool name, or skill name is persisted.",
                    )
                },
            )
            writeAtomically(historyDestination, historyPayload)
        }
    }

    fun outputFile(filesDir: File): File =
        File(File(filesDir, REQUEST_BREAKDOWN_DIRECTORY), REQUEST_BREAKDOWN_FILE)

    fun historyOutputFile(filesDir: File): File =
        File(File(filesDir, REQUEST_BREAKDOWN_DIRECTORY), REQUEST_BREAKDOWN_HISTORY_FILE)

    private fun readHistoryEntries(file: File): List<JsonObject> {
        if (!file.isFile) return emptyList()
        return runCatching {
            requestBreakdownJson.parseToJsonElement(file.readText())
                .jsonObject["entries"]
                ?.jsonArray
                ?.map { it.jsonObject }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun writeAtomically(destination: File, payload: String) {
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        temporary.writeText(payload)
        if (!temporary.renameTo(destination)) {
            destination.writeText(payload)
            temporary.delete()
        }
    }
}

private val HISTORY_REDACTED_NAME_FIELDS = setOf(
    "enabled_skill_names",
    "tool_names",
    "tool_fast_lane_bundle_id",
)

private data class PayloadSize(val characters: Long = 0, val utf8Bytes: Long = 0) {
    operator fun plus(other: PayloadSize): PayloadSize = PayloadSize(
        characters = characters + other.characters,
        utf8Bytes = utf8Bytes + other.utf8Bytes,
    )
}

private fun List<UIMessage>.toMessageSection(name: String): RequestBreakdownSection {
    val size = asSequence()
        .flatMap { it.parts.asSequence() }
        .fold(PayloadSize()) { total, part -> total + part.payloadSize() }
    return RequestBreakdownSection(
        name = name,
        characters = size.characters,
        utf8Bytes = size.utf8Bytes,
        estimatedTokens = sumOf(ApproximateContextTokenEstimator::estimate),
    )
}

private data class ToolSchemaPayload(
    val name: String,
    val description: String,
    val schema: String,
)

private fun BuiltInTools.diagnosticName(): String = when (this) {
    BuiltInTools.Search -> "search"
    BuiltInTools.UrlContext -> "url_context"
    BuiltInTools.ImageGeneration -> "image_generation"
}

private fun List<Tool>.toToolSchemaPayloads(): List<ToolSchemaPayload> = map { tool ->
    ToolSchemaPayload(
        name = tool.name,
        description = tool.description,
        schema = runCatching {
            tool.parameters()?.let { schema ->
                requestBreakdownJson.encodeToString(InputSchema.serializer(), schema)
            }.orEmpty()
        }.getOrDefault(""),
    )
}

private fun List<ToolSchemaPayload>.toToolSchemaSection(): RequestBreakdownSection {
    var size = PayloadSize()
    var tokens = 0
    forEach { tool ->
        val fragments = listOf(tool.name, tool.description, tool.schema)
        size += fragments.fold(PayloadSize()) { total, text -> total + text.payloadSize() }
        tokens += fragments.sumOf(::estimateTextTokens)
        tokens += 12
    }
    return RequestBreakdownSection("tool_schemas", size.characters, size.utf8Bytes, tokens)
}

private data class SemanticSegment(
    val fingerprint: String,
    val estimatedTokens: Int,
)

/**
 * Provider-semantic projection used only to compare adjacent requests in memory.
 *
 * UI-only identity, timestamps, usage, translation, state, approval, and execution bookkeeping
 * are intentionally excluded because provider adapters do not serialize them. Metadata remains on
 * native reasoning/media/tool parts because it may contain a provider replay signature.
 */
private fun List<UIMessage>.semanticSegments(key: ByteArray): List<SemanticSegment> = buildList {
    this@semanticSegments.forEach { message ->
        add(SemanticSegment(
            fingerprint = RequestBreakdownFingerprinter.values(
                key,
                "message",
                listOf(message.role.name),
            ),
            estimatedTokens = MESSAGE_FRAMING_TOKENS,
        ))
        message.parts.forEach { part ->
            addAll(part.semanticSegments(key, message.role))
        }
    }
}

private fun UIMessagePart.semanticSegments(
    key: ByteArray,
    role: MessageRole,
): List<SemanticSegment> {
    val totalPartTokens = estimatedTokensInRole(role)
    if (this !is UIMessagePart.Tool) {
        return listOf(SemanticSegment(
            fingerprint = RequestBreakdownFingerprinter.values(
                key,
                "part",
                providerSemanticValues(),
            ),
            estimatedTokens = totalPartTokens,
        ))
    }

    val outputTokens = output.map { it.estimatedTokensInRole(role) }
    val toolCallTokens = (totalPartTokens.toLong() - outputTokens.sumOf { it.toLong() })
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
    return buildList {
        add(SemanticSegment(
            fingerprint = RequestBreakdownFingerprinter.values(
                key,
                "tool_call",
                providerSemanticValues(),
            ),
            estimatedTokens = toolCallTokens,
        ))
        output.forEachIndexed { index, outputPart ->
            add(SemanticSegment(
                fingerprint = RequestBreakdownFingerprinter.values(
                    key,
                    "tool_output",
                    listOf(toolCallId, index.toString()) + outputPart.providerSemanticValues(),
                ),
                estimatedTokens = outputTokens[index],
            ))
        }
    }
}

private fun UIMessagePart.estimatedTokensInRole(role: MessageRole): Int = (
    ApproximateContextTokenEstimator.estimate(
        UIMessage(role = role, parts = listOf(this)),
    ) - MESSAGE_FRAMING_TOKENS
    ).coerceAtLeast(0)

@Suppress("DEPRECATION")
private fun UIMessagePart.providerSemanticValues(): List<String> = buildList {
    when (val part = this@providerSemanticValues) {
        is UIMessagePart.Text -> addAll(listOf("text", part.text))
        is UIMessagePart.Image -> addAll(listOf(
            "image",
            part.url,
        ) + part.providerReplayMetadataValues("thoughtSignature"))
        is UIMessagePart.Video -> addAll(listOf("video", part.url))
        is UIMessagePart.Audio -> addAll(listOf("audio", part.url))
        is UIMessagePart.Document -> addAll(listOf(
            "document",
            part.url,
            part.fileName,
            part.mime,
        ))
        is UIMessagePart.Reasoning -> addAll(listOf(
            "reasoning",
            part.reasoning,
        ) + part.providerReplayMetadataValues(
            "signature",
            "reasoning_id",
            "encrypted_content",
            "thoughtSignature",
        ))
        is UIMessagePart.Tool -> {
            addAll(listOf(
                "tool",
                part.toolCallId,
                part.toolName,
                part.input,
            ) + part.providerReplayMetadataValues("thoughtSignature"))
        }
        is UIMessagePart.ToolCall -> addAll(listOf(
            "tool_call",
            part.toolCallId,
            part.toolName,
            part.arguments,
        ))
        is UIMessagePart.ToolResult -> addAll(listOf(
            "tool_result",
            part.toolCallId,
            part.toolName,
            part.content.toString(),
            part.arguments.toString(),
        ))
        UIMessagePart.Search -> add("search")
    }
}

private fun UIMessagePart.providerReplayMetadataValues(vararg keys: String): List<String> =
    keys.map { key -> metadata?.get(key)?.toString().orEmpty() }

/**
 * The random key exists only for one generation handle. Fingerprints can be compared across calls
 * in that run, are never persisted, and cannot be used as an offline prompt dictionary.
 */
private object RequestBreakdownFingerprinter {
    fun toolManifest(
        key: ByteArray,
        tools: List<ToolSchemaPayload>,
        builtInToolNames: List<String>,
    ): String = values(
        key,
        "tools",
        tools.flatMap { tool -> listOf(tool.name, tool.description, tool.schema) } +
            listOf("built_in_tools") + builtInToolNames,
    )

    fun values(key: ByteArray, namespace: String, values: List<String>): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        updateLengthPrefixed(mac, namespace.toByteArray(Charsets.UTF_8))
        values.forEach { value ->
            updateLengthPrefixed(mac, value.toByteArray(Charsets.UTF_8))
        }
        return mac.doFinal().take(16).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun updateLengthPrefixed(mac: Mac, bytes: ByteArray) {
        val size = bytes.size
        mac.update(byteArrayOf(
            (size ushr 24).toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ))
        mac.update(bytes)
    }
}

internal fun newRequestBreakdownFingerprintKey(): ByteArray =
    ByteArray(32).also(SecureRandom()::nextBytes)

private fun String.toTextSection(name: String): RequestBreakdownSection = listOf(this).toTextSection(name)

private fun List<String>.toTextSection(name: String): RequestBreakdownSection {
    val size = fold(PayloadSize()) { total, text -> total + text.payloadSize() }
    return RequestBreakdownSection(
        name = name,
        characters = size.characters,
        utf8Bytes = size.utf8Bytes,
        estimatedTokens = sumOf(::estimateTextTokens),
    )
}

private fun List<RequestBreakdownSection>.toJson(): JsonArray = buildJsonArray {
    this@toJson.forEach { section ->
        add(buildJsonObject {
            put("name", section.name)
            put("characters", section.characters)
            put("utf8_bytes", section.utf8Bytes)
            put("estimated_tokens", section.estimatedTokens)
        })
    }
}

private fun UIMessagePart.payloadSize(): PayloadSize = when (this) {
    is UIMessagePart.Text -> text.payloadSize()
    is UIMessagePart.Reasoning -> reasoning.payloadSize()
    is UIMessagePart.Image -> url.payloadSize()
    is UIMessagePart.Video -> url.payloadSize()
    is UIMessagePart.Audio -> url.payloadSize()
    is UIMessagePart.Document -> url.payloadSize() + fileName.payloadSize() + mime.payloadSize()
    is UIMessagePart.Tool -> toolName.payloadSize() + input.toString().payloadSize() +
        output.fold(PayloadSize()) { total, part -> total + part.payloadSize() }
    is UIMessagePart.ToolCall -> toolName.payloadSize() + arguments.payloadSize()
    is UIMessagePart.ToolResult -> toolName.payloadSize() + content.toString().payloadSize() +
        arguments.toString().payloadSize()
    UIMessagePart.Search -> PayloadSize()
}

private fun String.payloadSize(): PayloadSize = PayloadSize(length.toLong(), utf8Length())

private fun String.utf8Length(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.code <= 0x7f -> bytes += 1
            char.code <= 0x7ff -> bytes += 2
            char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                bytes += 4
                index += 1
            }
            else -> bytes += 3
        }
        index += 1
    }
    return bytes
}

private fun estimateTextTokens(text: String): Int {
    if (text.isEmpty()) return 0
    val framed = ApproximateContextTokenEstimator.estimate(
        UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(text))),
    )
    return (framed - MESSAGE_FRAMING_TOKENS).coerceAtLeast(0)
}

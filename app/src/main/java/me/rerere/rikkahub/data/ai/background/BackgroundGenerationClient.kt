package me.rerere.rikkahub.data.ai.background

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.isActive
import me.rerere.ai.provider.BackgroundProviderDispatchCallback
import me.rerere.ai.context.ProviderContextGateResult
import me.rerere.ai.context.ProviderContextGateTrace
import me.rerere.ai.context.ProviderContextWindowResolver
import me.rerere.ai.context.ProviderRequestContextGate
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.RemoteBackgroundDispatchContext
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.GenerationTerminalTracker
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.learning.model.LearningProviderKind
import me.rerere.rikkahub.learning.model.ResolvedLearningModel
import me.rerere.rikkahub.learning.privacy.LearningOutboundFieldCategory
import me.rerere.rikkahub.learning.resources.LearningCancellationCapability
import me.rerere.rikkahub.learning.resources.LearningExecutionClass
import me.rerere.rikkahub.learning.resources.LearningForegroundPreemption
import me.rerere.rikkahub.learning.resources.LearningPermitResult
import me.rerere.rikkahub.learning.resources.LearningResourceGovernor
import me.rerere.rikkahub.learning.resources.LearningResourceKind
import me.rerere.rikkahub.learning.resources.LearningResourcePermit
import me.rerere.rikkahub.learning.resources.LearningYieldReason

const val BACKGROUND_GENERATION_REQUEST_SCHEMA_VERSION = 1
const val MAX_BACKGROUND_SYSTEM_UTF8_BYTES = 32 * 1_024
const val MAX_BACKGROUND_PAYLOAD_UTF8_BYTES = 128 * 1_024
const val MAX_BACKGROUND_TOTAL_INPUT_UTF8_BYTES =
    MAX_BACKGROUND_SYSTEM_UTF8_BYTES + MAX_BACKGROUND_PAYLOAD_UTF8_BYTES
const val MAX_BACKGROUND_OUTPUT_UTF8_BYTES = 128 * 1_024
const val MAX_BACKGROUND_OUTPUT_TOKENS = 8_192
const val MAX_BACKGROUND_GENERATION_TIMEOUT_MS = 2L * 60_000L

/**
 * A prompt that has already passed the Learning redaction/allowlist boundary.
 *
 * The private constructor prevents the generation client from accepting arbitrary UI messages or
 * a raw Conversation. This type is ephemeral and its string representation never exposes content.
 */
class BoundedRedactedBackgroundPromptV1 private constructor(
    systemText: String,
    payloadText: String,
    val redactionPolicyVersion: String,
    val fieldCategories: Set<LearningOutboundFieldCategory>,
    val totalUtf8Bytes: Int,
) : AutoCloseable {
    private val systemTextRef = AtomicReference<String?>(systemText)
    private val payloadTextRef = AtomicReference<String?>(payloadText)

    /** The provider boundary takes one short-lived copy; a closed prompt cannot be reused. */
    internal fun providerTexts(): Pair<String, String> {
        val system = systemTextRef.get() ?: error("Background prompt is closed")
        val payload = payloadTextRef.get() ?: error("Background prompt is closed")
        return system to payload
    }

    /** Drop the last request-owned references on every success, failure, timeout, or cancellation. */
    override fun close() {
        systemTextRef.getAndSet(null)
        payloadTextRef.getAndSet(null)
    }

    internal fun isClosed(): Boolean = systemTextRef.get() == null && payloadTextRef.get() == null

    override fun toString(): String =
        "BoundedRedactedBackgroundPromptV1(policy=$redactionPolicyVersion, " +
            "fields=$fieldCategories, utf8Bytes=$totalUtf8Bytes, content=<redacted>)"

    companion object {
        private val SAFE_VERSION = Regex("^[A-Za-z0-9._-]{1,64}$")

        fun fromRedacted(
            systemText: String,
            payloadText: String,
            redactionPolicyVersion: String,
            fieldCategories: Set<LearningOutboundFieldCategory>,
        ): BoundedRedactedBackgroundPromptV1 {
            require(systemText.isNotBlank()) { "Redacted system contract must not be blank" }
            require(payloadText.isNotBlank()) { "Redacted payload must not be blank" }
            require(SAFE_VERSION.matches(redactionPolicyVersion)) {
                "Invalid redaction policy version"
            }
            require(fieldCategories.isNotEmpty()) { "At least one outbound field category is required" }
            val systemBytes = systemText.utf8Size()
            val payloadBytes = payloadText.utf8Size()
            require(systemBytes <= MAX_BACKGROUND_SYSTEM_UTF8_BYTES) {
                "Redacted system contract exceeds its byte limit"
            }
            require(payloadBytes <= MAX_BACKGROUND_PAYLOAD_UTF8_BYTES) {
                "Redacted payload exceeds its byte limit"
            }
            val totalBytes = Math.addExact(systemBytes, payloadBytes)
            require(totalBytes <= MAX_BACKGROUND_TOTAL_INPUT_UTF8_BYTES) {
                "Redacted prompt exceeds its total byte limit"
            }
            return BoundedRedactedBackgroundPromptV1(
                systemText = systemText,
                payloadText = payloadText,
                redactionPolicyVersion = redactionPolicyVersion,
                fieldCategories = fieldCategories.toSet(),
                totalUtf8Bytes = totalBytes,
            )
        }
    }
}

data class BackgroundGenerationRequestV1(
    val schemaVersion: Int = BACKGROUND_GENERATION_REQUEST_SCHEMA_VERSION,
    val prompt: BoundedRedactedBackgroundPromptV1,
    /** Exact content-free identity frozen when the durable job is claimed. */
    val frozenModel: ResolvedLearningModel,
    val templateVersion: String,
    /** Exact bounded/redacted input identity; required only for durable remote dispatch. */
    val inputIdentitySha256: String? = null,
    val maxOutputTokens: Int,
    val maxOutputUtf8Bytes: Int = MAX_BACKGROUND_OUTPUT_UTF8_BYTES,
    val timeoutMs: Long,
    /** Present only for a durable Learning provider job whose budget was already reserved. */
    val providerAttemptAuthority: BackgroundProviderAttemptAuthority? = null,
) {
    init {
        require(schemaVersion == BACKGROUND_GENERATION_REQUEST_SCHEMA_VERSION) {
            "Unsupported background generation request schema"
        }
        require(templateVersion.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            "Invalid template version"
        }
        require(inputIdentitySha256 == null || inputIdentitySha256.isLowerSha256()) {
            "Invalid background input identity"
        }
        require(frozenModel.providerIdentityDigest.isLowerSha256()) { "Invalid provider digest" }
        require(frozenModel.modelIdentityDigest.isLowerSha256()) { "Invalid model digest" }
        require(frozenModel.configurationDigest.isLowerSha256()) { "Invalid configuration digest" }
        require(frozenModel.providerKind != LearningProviderKind.AICORE) {
            "AICore cannot execute background Learning requests"
        }
        require(
            when (frozenModel.providerKind) {
                LearningProviderKind.REMOTE ->
                    frozenModel.route.executionClass == LearningExecutionClass.REMOTE_NETWORK &&
                        frozenModel.route.requiresNetwork &&
                        frozenModel.runtimeAttestationDigest == null
                LearningProviderKind.LOCAL_LITERT ->
                    frozenModel.route.executionClass == LearningExecutionClass.LOCAL_COMPUTE &&
                        !frozenModel.route.requiresNetwork &&
                        frozenModel.runtimeAttestationDigest?.isLowerSha256() == true
                LearningProviderKind.AICORE -> false
            },
        ) { "Frozen provider kind and route do not match" }
        require(
            frozenModel.route.cancellation == LearningCancellationCapability.PROVEN_RELIABLE,
        ) { "Background generation requires proven cancellation" }
        require(maxOutputTokens in 1..MAX_BACKGROUND_OUTPUT_TOKENS) {
            "Background output token limit is outside policy"
        }
        require(maxOutputUtf8Bytes in 1..MAX_BACKGROUND_OUTPUT_UTF8_BYTES) {
            "Background output byte limit is outside policy"
        }
        require(timeoutMs in 1L..MAX_BACKGROUND_GENERATION_TIMEOUT_MS) {
            "Background generation timeout is outside policy"
        }
        providerAttemptAuthority?.let { authority ->
            require(
                authority.stableProviderIdempotencyKey.matches(
                    Regex("^learning-provider-v[0-9]+:[0-9a-f]{64}$"),
                ),
            ) { "Invalid stable provider request key" }
            require(authority.expectedDispatchAttestationSha256.isLowerSha256()) {
                "Invalid expected dispatch attestation"
            }
            require(
                frozenModel.providerKind != LearningProviderKind.LOCAL_LITERT ||
                    authority.expectedDispatchAttestationSha256 ==
                    frozenModel.runtimeAttestationDigest
            ) { "Attempt and frozen runtime attestation disagree" }
            require(
                frozenModel.providerKind != LearningProviderKind.REMOTE ||
                    inputIdentitySha256 != null
            ) { "Remote attempt requires a frozen input identity" }
        }
    }

    override fun toString(): String =
        "BackgroundGenerationRequestV1(schema=$schemaVersion, prompt=$prompt, " +
        "template=$templateVersion, maxOutputTokens=$maxOutputTokens, " +
            "maxOutputUtf8Bytes=$maxOutputUtf8Bytes, timeoutMs=$timeoutMs, " +
            "durableAttempt=${providerAttemptAuthority != null}, identity=<redacted>)"
}

enum class BackgroundBindingUnavailableReason {
    NO_CONFIGURATION,
    CONFIGURATION_CHANGED,
    BACKGROUND_NOT_AUTHORIZED,
    PROVIDER_DISABLED,
    CREDENTIALS_UNAVAILABLE,
    UNSUPPORTED_PROVIDER,
    CANCELLATION_UNSAFE,
}

sealed interface BackgroundGenerationBindingResult {
    data class Bound(val execution: BackgroundGenerationExecution) : BackgroundGenerationBindingResult {
        override fun toString(): String =
            "BackgroundGenerationBindingResult.Bound(execution=<redacted>)"
    }

    data class Unavailable(
        val reason: BackgroundBindingUnavailableReason,
    ) : BackgroundGenerationBindingResult
}

/** Settings/ProviderManager credentials stay behind this execution-time seam. */
fun interface BackgroundGenerationBinder {
    suspend fun bind(frozenModel: ResolvedLearningModel): BackgroundGenerationBindingResult
}

/**
 * Live, execution-time authorization for the exact frozen identity.
 *
 * The production adapter must re-read background authorization, the remote-reflection flag, and
 * the exact provider/model/keyed-configuration digest. The default denies every route so a
 * missing Settings/ProviderManager integration can never send provider bytes.
 */
fun interface BackgroundGenerationAuthorizationGate {
    fun isAuthorized(frozenModel: ResolvedLearningModel): Boolean

    companion object {
        val DENY_ALL = BackgroundGenerationAuthorizationGate { false }
    }
}

interface BackgroundGenerationExecution {
    /** Must exactly match the identity requested from [BackgroundGenerationBinder]. */
    val frozenModel: ResolvedLearningModel
    val model: Model

    suspend fun resolveTrustedContextWindowTokens(): Int?

    /**
     * Last content-free host revalidation before provider dispatch. Production bindings use this
     * to re-read Settings and reject authorization or configuration drift after [bind].
     */
    fun validateBeforeDispatch(): BackgroundBindingUnavailableReason? = null

    /** Implementations must honor coroutine cancellation at the underlying transport/runtime. */
    suspend fun streamText(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>
}

sealed interface BackgroundGenerationDeferral {
    data class Resource(val reason: LearningYieldReason) : BackgroundGenerationDeferral
    data class Binding(val reason: BackgroundBindingUnavailableReason) : BackgroundGenerationDeferral
    data class Foreground(val reason: LearningForegroundPreemption) : BackgroundGenerationDeferral
}

/** Whether a retry could duplicate a provider-side request or cost. */
enum class BackgroundProviderAttemptState {
    NOT_DISPATCHED,
    DISPATCH_STARTED,
    TERMINAL_OBSERVED;

    /** False means a retry needs provider idempotency support or an explicit durable decision. */
    val safeForBlindRetry: Boolean
        get() = this == NOT_DISPATCHED
}

enum class BackgroundGenerationFailureReason {
    BINDER_FAILURE,
    UNSUPPORTED_MODEL,
    INVALID_TRUSTED_CONTEXT_CAPABILITY,
    CONTEXT_HARD_CAP,
    CONTEXT_REQUIRES_EXPLICIT_ADJUSTMENT,
    OUTPUT_BYTE_LIMIT,
    TOOLS_NOT_ALLOWED,
    UNSUPPORTED_OUTPUT,
    EMPTY_OUTPUT,
    OUTPUT_TOKEN_LIMIT,
    SAFETY_REJECTION,
    PROVIDER_CANCELLED,
    PROVIDER_TIMEOUT,
    PROVIDER_INCOMPLETE,
    PROVIDER_FAILURE,
}

sealed interface BackgroundGenerationResult {
    data class Success(
        val text: String,
        val usage: TokenUsage?,
        val effectiveMaxOutputTokens: Int,
        val contextTrace: ProviderContextGateTrace,
    ) : BackgroundGenerationResult {
        override fun toString(): String =
            "BackgroundGenerationResult.Success(text=<redacted>, usage=<redacted>, " +
                "effectiveMaxOutputTokens=$effectiveMaxOutputTokens, contextTrace=$contextTrace)"
    }

    data class Deferred(
        val reason: BackgroundGenerationDeferral,
        val providerAttemptState: BackgroundProviderAttemptState =
            BackgroundProviderAttemptState.NOT_DISPATCHED,
    ) : BackgroundGenerationResult

    data class Failure(
        val reason: BackgroundGenerationFailureReason,
        val providerAttemptState: BackgroundProviderAttemptState =
            BackgroundProviderAttemptState.NOT_DISPATCHED,
    ) : BackgroundGenerationResult
}

/** One bounded, tool-free provider call under the shared Learning resource governor. */
class BackgroundGenerationClient(
    private val governor: LearningResourceGovernor,
    private val binder: BackgroundGenerationBinder,
    private val authorizationGate: BackgroundGenerationAuthorizationGate =
        BackgroundGenerationAuthorizationGate.DENY_ALL,
    private val contextGate: ProviderRequestContextGate = ProviderRequestContextGate(),
) {
    suspend fun generate(request: BackgroundGenerationRequestV1): BackgroundGenerationResult = try {
        generateOpen(request)
    } finally {
        request.prompt.close()
    }

    private suspend fun generateOpen(
        request: BackgroundGenerationRequestV1,
    ): BackgroundGenerationResult {
        if (!isCurrentlyAuthorized(request.frozenModel)) {
            return settleDurableAttempt(
                request,
                BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(
                    BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
                ),
                ),
            )
        }
        val permit = when (
            val admission = governor.acquire(
                kind = LearningResourceKind.LANGUAGE_MODEL,
                route = request.frozenModel.route,
            )
        ) {
            is LearningPermitResult.Deferred -> return settleDurableAttempt(
                request,
                BackgroundGenerationResult.Deferred(
                    BackgroundGenerationDeferral.Resource(admission.reason),
                ),
            )

            is LearningPermitResult.Granted -> admission.permit
        }

        val attempt = BackgroundProviderAttemptTracker()
        permit.use {
            val result = try {
                withTimeout(request.timeoutMs) {
                    generateWithPermit(request, permit, attempt)
                }
            } catch (timedOut: TimeoutCancellationException) {
                // An enclosing caller timeout/cancellation is not this request's provider timeout.
                if (!currentCoroutineContext().isActive) throw timedOut
                BackgroundGenerationResult.Failure(
                    BackgroundGenerationFailureReason.PROVIDER_TIMEOUT,
                    attempt.current(),
                )
            } catch (cancelled: CancellationException) {
                settleCancellationBestEffort(request, attempt.current())
                throw cancelled
            } catch (_: BackgroundOutputByteLimitException) {
                BackgroundGenerationResult.Failure(
                    BackgroundGenerationFailureReason.OUTPUT_BYTE_LIMIT,
                    attempt.current(),
                )
            } catch (_: BackgroundToolsNotAllowedException) {
                BackgroundGenerationResult.Failure(
                    BackgroundGenerationFailureReason.TOOLS_NOT_ALLOWED,
                    attempt.current(),
                )
            } catch (_: BackgroundUnsupportedOutputException) {
                BackgroundGenerationResult.Failure(
                    BackgroundGenerationFailureReason.UNSUPPORTED_OUTPUT,
                    attempt.current(),
                )
            } catch (_: Exception) {
                BackgroundGenerationResult.Failure(
                    BackgroundGenerationFailureReason.PROVIDER_FAILURE,
                    attempt.current(),
                )
            }
            return settleDurableAttempt(request, result)
        }
    }

    private suspend fun settleDurableAttempt(
        request: BackgroundGenerationRequestV1,
        result: BackgroundGenerationResult,
    ): BackgroundGenerationResult {
        val authority = request.providerAttemptAuthority ?: return result
        return when (result.attemptState()) {
            BackgroundProviderAttemptState.NOT_DISPATCHED -> {
                val released = runCatching { authority.releaseUndispatched() }.getOrDefault(false)
                if (released) result else BackgroundGenerationResult.Failure(
                    BackgroundGenerationFailureReason.PROVIDER_FAILURE,
                    BackgroundProviderAttemptState.NOT_DISPATCHED,
                )
            }

            BackgroundProviderAttemptState.DISPATCH_STARTED,
            BackgroundProviderAttemptState.TERMINAL_OBSERVED,
            -> {
                val terminal = runCatching {
                    authority.markTerminal(
                        outcome = result.toDurableTerminalOutcome(),
                        usage = (result as? BackgroundGenerationResult.Success)
                            ?.usage
                            .let(BackgroundProviderUsage::from),
                    )
                }.getOrDefault(false)
                if (!terminal) {
                    BackgroundGenerationResult.Failure(
                        BackgroundGenerationFailureReason.PROVIDER_FAILURE,
                        BackgroundProviderAttemptState.DISPATCH_STARTED,
                    )
                } else {
                    result.withAttemptState(BackgroundProviderAttemptState.TERMINAL_OBSERVED)
                }
            }
        }
    }

    private suspend fun settleCancellationBestEffort(
        request: BackgroundGenerationRequestV1,
        state: BackgroundProviderAttemptState,
    ) {
        val authority = request.providerAttemptAuthority ?: return
        withContext(NonCancellable) {
            runCatching {
                if (state == BackgroundProviderAttemptState.NOT_DISPATCHED) {
                    authority.releaseUndispatched()
                } else {
                    authority.markTerminal(
                        BackgroundProviderTerminalOutcome.CANCELLED,
                        BackgroundProviderUsage.UNKNOWN,
                    )
                }
            }
        }
    }

    private suspend fun generateWithPermit(
        request: BackgroundGenerationRequestV1,
        permit: LearningResourcePermit,
        attempt: BackgroundProviderAttemptTracker,
    ): BackgroundGenerationResult {
        val binding = try {
            binder.bind(request.frozenModel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.BINDER_FAILURE,
            )
        }
        val execution = when (binding) {
            is BackgroundGenerationBindingResult.Unavailable ->
                return BackgroundGenerationResult.Deferred(
                    BackgroundGenerationDeferral.Binding(binding.reason),
                )

            is BackgroundGenerationBindingResult.Bound -> binding.execution
        }
        if (execution.frozenModel != request.frozenModel) {
            return BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(
                    BackgroundBindingUnavailableReason.CONFIGURATION_CHANGED,
                ),
            )
        }
        if (!isCurrentlyAuthorized(request.frozenModel)) {
            return BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(
                    BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
                ),
            )
        }
        val boundModel = execution.model
        if (
            boundModel.type != ModelType.CHAT ||
            boundModel.modelId.isBlank() ||
            Modality.TEXT !in boundModel.inputModalities ||
            Modality.TEXT !in boundModel.outputModalities
        ) {
            return BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.UNSUPPORTED_MODEL,
            )
        }

        val trustedWindow = try {
            execution.resolveTrustedContextWindowTokens()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.BINDER_FAILURE,
            )
        }
        if (trustedWindow != null && trustedWindow <= 0) {
            return BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.INVALID_TRUSTED_CONTEXT_CAPABILITY,
            )
        }
        val resolvedWindow = ProviderContextWindowResolver.resolve(
            configuredPolicyTokens = boundModel.userContextWindowTokens,
            trustedCapabilityTokens = trustedWindow,
            advertisedTokens = boundModel.contextLength,
        )
        val (systemText, payloadText) = request.prompt.providerTexts()
        val providerMessages = listOf(
            UIMessage.system(systemText),
            UIMessage.user(payloadText),
        )
        val gated = when (
            val result = contextGate.enforce(
                messages = providerMessages,
                contextWindowTokens = resolvedWindow.effectiveTokens,
                requestedOutputTokens = request.maxOutputTokens,
                tools = emptyList(),
                builtInTools = emptySet(),
            )
        ) {
            is ProviderContextGateResult.Overflow -> return BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.CONTEXT_HARD_CAP,
            )

            is ProviderContextGateResult.Success -> result
        }
        if (!gated.trace.isLossless()) {
            return BackgroundGenerationResult.Failure(
                BackgroundGenerationFailureReason.CONTEXT_REQUIRES_EXPLICIT_ADJUSTMENT,
            )
        }

        val requestModel = boundModel.copy(
            tools = emptySet(),
            customHeaders = emptyList(),
            customBodies = emptyList(),
            providerOverwrite = null,
        )
        val params = TextGenerationParams(
            model = requestModel,
            maxTokens = gated.effectiveMaxOutputTokens,
            tools = emptyList(),
            reasoningLevel = ReasoningLevel.OFF,
            omitReasoningConfigurationWhenOff = true,
            freshConnection = false,
            providerCacheIdentity = null,
            stableProviderIdempotencyKey = request.providerAttemptAuthority
                ?.stableProviderIdempotencyKey,
            remoteBackgroundDispatchContext = if (
                request.frozenModel.providerKind == LearningProviderKind.REMOTE &&
                request.inputIdentitySha256 != null &&
                request.providerAttemptAuthority != null
            ) {
                RemoteBackgroundDispatchContext(
                    providerIdentitySha256 = request.frozenModel.providerIdentityDigest,
                    modelIdentitySha256 = request.frozenModel.modelIdentityDigest,
                    configurationIdentitySha256 = request.frozenModel.configurationDigest,
                    templateVersion = request.templateVersion,
                    inputIdentitySha256 = request.inputIdentitySha256,
                    providerRequestKey =
                        request.providerAttemptAuthority.stableProviderIdempotencyKey,
                    maxOutputTokens = gated.effectiveMaxOutputTokens,
                )
            } else {
                null
            },
            customHeaders = emptyList(),
            customBody = emptyList(),
        )
        permit.validateBeforeDispatch()?.let { reason ->
            return BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Resource(reason),
            )
        }
        if (!isCurrentlyAuthorized(request.frozenModel)) {
            return BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(
                    BackgroundBindingUnavailableReason.BACKGROUND_NOT_AUTHORIZED,
                ),
            )
        }
        execution.validateBeforeDispatch()?.let { reason ->
            return BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Binding(reason),
            )
        }

        return when (
            val race = raceProviderAgainstForeground(permit) {
                collectProviderStream(
                    execution = execution,
                    messages = gated.messages,
                    params = params,
                    maxOutputUtf8Bytes = request.maxOutputUtf8Bytes,
                    attempt = attempt,
                    durableAttempt = request.providerAttemptAuthority,
                )
            }
        ) {
            is BackgroundProviderRace.Preempted -> BackgroundGenerationResult.Deferred(
                BackgroundGenerationDeferral.Foreground(race.reason),
                attempt.current(),
            )

            // Once the provider has completed, its request and cost are facts. Re-checking the
            // foreground snapshot here could mislabel a completed call as "deferred" and cause a
            // duplicate retry merely because a foreground run started after the response won the
            // race. Admission is checked immediately before dispatch and preemption is observed by
            // the race itself; the completed result must therefore be preserved.
            is BackgroundProviderRace.Completed -> race.value.toPublicResult(
                effectiveMaxOutputTokens = gated.effectiveMaxOutputTokens,
                contextTrace = gated.trace,
                providerAttemptState = attempt.current(),
            )
        }
    }

    private fun isCurrentlyAuthorized(frozenModel: ResolvedLearningModel): Boolean = try {
        authorizationGate.isAuthorized(frozenModel)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private data class BackgroundProviderCompletion(
    val text: String,
    val usage: TokenUsage?,
    val terminal: GenerationTerminal,
)

private suspend fun collectProviderStream(
    execution: BackgroundGenerationExecution,
    messages: List<UIMessage>,
    params: TextGenerationParams,
    maxOutputUtf8Bytes: Int,
    attempt: BackgroundProviderAttemptTracker,
    durableAttempt: BackgroundProviderAttemptAuthority?,
): BackgroundProviderCompletion {
    var accumulated = listOf(UIMessage.assistant(""))
    var usage: TokenUsage? = null
    val terminalTracker = GenerationTerminalTracker()
    val stream = if (execution is AttestedBackgroundGenerationExecution) {
        execution.streamText(
            messages = messages,
            params = params,
            onDispatchStarted = BackgroundProviderDispatchCallback { observed ->
                val observedDigest = observed.opaqueDigestSha256()
                if (
                    durableAttempt != null &&
                    (
                        observedDigest != durableAttempt.expectedDispatchAttestationSha256 ||
                            !durableAttempt.markDispatchStarted(observedDigest)
                    )
                ) {
                    throw BackgroundDispatchAuthorityRejectedException()
                }
                attempt.markDispatchStarted()
            },
        )
    } else {
        // A durable attempt requires a provider-owned final-attestation callback. Stamping the
        // ledger before a generic Flow is collected would misclassify prepare failures as sends.
        if (durableAttempt != null) throw BackgroundDispatchAuthorityRejectedException()
        attempt.markDispatchStarted()
        execution.streamText(messages, params)
    }
    stream.collect { chunk ->
        terminalTracker.observe(chunk)
        if (chunk.usage != null) usage = chunk.usage
        accumulated = accumulated.handleMessageChunk(chunk, params.model)
        val output = accumulated.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: throw BackgroundUnsupportedOutputException()
        if (output.getTools().isNotEmpty()) throw BackgroundToolsNotAllowedException()
        if (output.boundedOutputUtf8Size() > maxOutputUtf8Bytes) {
            throw BackgroundOutputByteLimitException()
        }
    }
    val output = accumulated.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?: throw BackgroundUnsupportedOutputException()
    if (output.getTools().isNotEmpty()) throw BackgroundToolsNotAllowedException()
    val terminal = terminalTracker.finish()
    if (terminal.terminalSeen) attempt.markTerminalObserved()
    return BackgroundProviderCompletion(
        text = output.toText().trim(),
        usage = usage,
        terminal = terminal,
    )
}

private fun BackgroundProviderCompletion.toPublicResult(
    effectiveMaxOutputTokens: Int,
    contextTrace: ProviderContextGateTrace,
    providerAttemptState: BackgroundProviderAttemptState,
): BackgroundGenerationResult = when (terminal.category) {
    FinishCategory.STOP -> if (text.isEmpty()) {
        BackgroundGenerationResult.Failure(
            BackgroundGenerationFailureReason.EMPTY_OUTPUT,
            providerAttemptState,
        )
    } else {
        BackgroundGenerationResult.Success(
            text = text,
            usage = usage,
            effectiveMaxOutputTokens = effectiveMaxOutputTokens,
            contextTrace = contextTrace,
        )
    }

    FinishCategory.TOOL_CALLS -> BackgroundGenerationResult.Failure(
        BackgroundGenerationFailureReason.TOOLS_NOT_ALLOWED,
        providerAttemptState,
    )

    FinishCategory.LENGTH -> BackgroundGenerationResult.Failure(
        BackgroundGenerationFailureReason.OUTPUT_TOKEN_LIMIT,
        providerAttemptState,
    )

    FinishCategory.SAFETY -> BackgroundGenerationResult.Failure(
        BackgroundGenerationFailureReason.SAFETY_REJECTION,
        providerAttemptState,
    )

    FinishCategory.CANCELLED -> BackgroundGenerationResult.Failure(
        BackgroundGenerationFailureReason.PROVIDER_CANCELLED,
        providerAttemptState,
    )

    FinishCategory.INCOMPLETE,
    FinishCategory.EOF,
    FinishCategory.UNKNOWN,
    -> BackgroundGenerationResult.Failure(
        BackgroundGenerationFailureReason.PROVIDER_INCOMPLETE,
        providerAttemptState,
    )

    FinishCategory.FAILED -> BackgroundGenerationResult.Failure(
        BackgroundGenerationFailureReason.PROVIDER_FAILURE,
        providerAttemptState,
    )
}

private fun BackgroundGenerationResult.attemptState(): BackgroundProviderAttemptState = when (this) {
    is BackgroundGenerationResult.Success -> BackgroundProviderAttemptState.TERMINAL_OBSERVED
    is BackgroundGenerationResult.Deferred -> providerAttemptState
    is BackgroundGenerationResult.Failure -> providerAttemptState
}

private fun BackgroundGenerationResult.withAttemptState(
    state: BackgroundProviderAttemptState,
): BackgroundGenerationResult = when (this) {
    is BackgroundGenerationResult.Success -> this
    is BackgroundGenerationResult.Deferred -> copy(providerAttemptState = state)
    is BackgroundGenerationResult.Failure -> copy(providerAttemptState = state)
}

private fun BackgroundGenerationResult.toDurableTerminalOutcome(): BackgroundProviderTerminalOutcome =
    when (this) {
        is BackgroundGenerationResult.Success -> BackgroundProviderTerminalOutcome.SUCCESS
        is BackgroundGenerationResult.Deferred -> when (reason) {
            is BackgroundGenerationDeferral.Foreground -> BackgroundProviderTerminalOutcome.CANCELLED
            else -> BackgroundProviderTerminalOutcome.DEFERRED
        }
        is BackgroundGenerationResult.Failure -> when (reason) {
            BackgroundGenerationFailureReason.PROVIDER_CANCELLED ->
                BackgroundProviderTerminalOutcome.CANCELLED
            BackgroundGenerationFailureReason.PROVIDER_TIMEOUT ->
                BackgroundProviderTerminalOutcome.TIMED_OUT
            else -> BackgroundProviderTerminalOutcome.FAILED
        }
    }

private sealed interface BackgroundProviderRace<out T> {
    data class Completed<T>(val value: T) : BackgroundProviderRace<T>
    data class Preempted(val reason: LearningForegroundPreemption) : BackgroundProviderRace<Nothing>
}

private class BackgroundProviderAttemptTracker {
    private val state = AtomicReference(BackgroundProviderAttemptState.NOT_DISPATCHED)

    fun markDispatchStarted() {
        state.compareAndSet(
            BackgroundProviderAttemptState.NOT_DISPATCHED,
            BackgroundProviderAttemptState.DISPATCH_STARTED,
        )
    }

    fun markTerminalObserved() {
        state.set(BackgroundProviderAttemptState.TERMINAL_OBSERVED)
    }

    fun current(): BackgroundProviderAttemptState = state.get()
}

private suspend fun <T> raceProviderAgainstForeground(
    permit: LearningResourcePermit,
    providerCall: suspend () -> T,
): BackgroundProviderRace<T> = supervisorScope {
    val generation = async(start = CoroutineStart.LAZY) { providerCall() }
    val foreground = async(start = CoroutineStart.LAZY) { permit.awaitForegroundArrival() }
    generation.start()
    foreground.start()
    try {
        select {
            generation.onAwait { value ->
                foreground.cancel()
                BackgroundProviderRace.Completed(value)
            }
            foreground.onAwait { reason ->
                generation.cancel(CancellationException("background_generation_preempted"))
                generation.cancelAndJoin()
                BackgroundProviderRace.Preempted(reason)
            }
        }
    } finally {
        foreground.cancel()
        generation.cancel()
    }
}

private fun ProviderContextGateTrace.isLossless(): Boolean =
    strippedHistoricalReasoningParts == 0 &&
        droppedCompletedTurns == 0 &&
        droppedMessages == 0 &&
        !outputClamped

private fun UIMessage.boundedOutputUtf8Size(): Int {
    var total = 0L
    parts.forEach { part ->
        val bytes = when (part) {
            is UIMessagePart.Text -> part.text.utf8Size()
            is UIMessagePart.Reasoning -> part.reasoning.utf8Size()
            is UIMessagePart.Tool -> throw BackgroundToolsNotAllowedException()
            else -> throw BackgroundUnsupportedOutputException()
        }
        total += bytes.toLong()
        if (total > Int.MAX_VALUE) throw BackgroundOutputByteLimitException()
    }
    return total.toInt()
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private class BackgroundOutputByteLimitException : IllegalStateException()
private class BackgroundToolsNotAllowedException : IllegalStateException()
private class BackgroundUnsupportedOutputException : IllegalStateException()
private class BackgroundDispatchAuthorityRejectedException : IllegalStateException()

package me.rerere.rikkahub.data.ai.execution

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LegacyToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import kotlin.time.Duration.Companion.milliseconds

interface ToolRuntime {
    suspend fun assess(request: ToolAssessmentRequest): ToolAssessment
    suspend fun execute(request: ToolExecutionPlanRequest): ToolExecutionPlanResult
}

data class ToolAssessmentRequest(
    val toolName: String,
    val args: JsonObject,
    val context: ToolExecutionContext,
)

data class ToolAssessment(
    val policy: ToolExecutionPolicy,
    val securityDescriptor: ToolSecurityDescriptor?,
    val accepted: Boolean,
    val errorCode: String? = null,
)

data class ToolExecutionPlanRequest(
    val toolCallId: String,
    val toolName: String,
    val args: JsonElement,
    val executionContext: ToolExecutionContext?,
    val startableTool: StartableTool?,
    val legacyExecute: suspend (JsonElement) -> ToolResult,
    val runControl: GenerationRunControl?,
    val wallClockBudgetMs: Long,
    val preExecutionGate: suspend () -> ToolPreExecutionDecision = {
        ToolPreExecutionDecision.Allow
    },
)

sealed interface ToolPreExecutionDecision {
    data object Allow : ToolPreExecutionDecision
    data class Deny(
        val errorCode: String,
        val reason: String,
    ) : ToolPreExecutionDecision
}

sealed interface ToolExecutionPlanResult {
    val output: ToolResult

    data class Completed(
        override val output: ToolResult,
        val policy: ToolExecutionPolicy,
        val executionId: String,
    ) : ToolExecutionPlanResult

    data class Rejected(
        val errorCode: String,
        val detail: String,
        override val output: ToolResult = errorResult(errorCode, detail),
    ) : ToolExecutionPlanResult

    data class TimedOut(
        val policy: ToolExecutionPolicy,
        val executionId: String?,
        override val output: ToolResult = errorResult(
            "tool_cancelled_wall_clock",
            "Tool execution exceeded the shared turn budget.",
        ),
    ) : ToolExecutionPlanResult

    companion object {
        private fun errorResult(code: String, detail: String): ToolResult = listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("error", JsonPrimitive(code))
                    put("detail", JsonPrimitive(detail.take(500)))
                }.toString()
            )
        )
    }
}

data class RedactedToolCallContext(
    val toolName: String,
    val effects: Set<ToolEffect>,
    val resourceNamespaces: Set<String>,
    val origin: ToolCallOrigin,
    val hasConversationOwner: Boolean,
    /** Opaque ownership keys used by trusted host routing; never serialized to plugin code. */
    val assistantId: String,
    val conversationId: String,
    val runId: String,
)

sealed interface ToolHookDecision {
    data object Proceed : ToolHookDecision
    data class Block(val reason: String) : ToolHookDecision
}

interface ToolCallInterceptor {
    suspend fun intercept(context: RedactedToolCallContext): ToolHookDecision
}

fun interface ToolLifecycleObserver {
    suspend fun onEvent(event: RedactedToolLifecycleEvent)
}

data class RedactedToolLifecycleEvent(
    val phase: Phase,
    val context: RedactedToolCallContext,
    val executionId: String? = null,
    val terminationState: ToolTerminationState? = null,
) {
    enum class Phase { STARTING, COMPLETED, TIMED_OUT, CANCELLED, FAILED }
}

class DefaultToolRuntime(
    private val policyResolver: ToolExecutionPolicyResolver,
    private val securityDescriptorResolver: ToolSecurityDescriptorResolver =
        DefaultToolSecurityDescriptorResolver(),
    private val interceptors: List<ToolCallInterceptor> = emptyList(),
    private val observers: List<ToolLifecycleObserver> = emptyList(),
    private val interceptorTimeoutMs: Long = 2_000L,
    private val observerTimeoutMs: Long = 500L,
) : ToolRuntime {
    private val globalMutex = Mutex()
    private val resourceMutexes = ConcurrentHashMap<ToolResourceKey, Mutex>()

    override suspend fun assess(request: ToolAssessmentRequest): ToolAssessment {
        val policy = policyResolver.resolve(request.toolName, request.args, request.context)
        val descriptor = securityDescriptorResolver.resolve(request.toolName, request.context)
        val explicitlyUntrustedPlugin = descriptor?.source == ToolDescriptorSource.PLUGIN &&
            policy.effects == setOf(ToolEffect.UNKNOWN) &&
            policy.concurrency == ToolConcurrency.GLOBAL_SERIAL
        return if (descriptor == null ||
            (ToolEffect.UNKNOWN in policy.effects && !explicitlyUntrustedPlugin)
        ) {
            ToolAssessment(
                policy = policy,
                securityDescriptor = descriptor,
                accepted = false,
                errorCode = "tool_security_descriptor_missing",
            )
        } else {
            ToolAssessment(
                policy = policy,
                securityDescriptor = descriptor,
                accepted = true,
            )
        }
    }

    override suspend fun execute(request: ToolExecutionPlanRequest): ToolExecutionPlanResult {
        require(request.wallClockBudgetMs >= 0L) { "wallClockBudgetMs cannot be negative" }
        val context = request.executionContext ?: return ToolExecutionPlanResult.Rejected(
            errorCode = "tool_execution_context_missing",
            detail = "Tool execution requires assistant, conversation, run, and origin identity.",
        )
        when (val gate = request.preExecutionGate()) {
            ToolPreExecutionDecision.Allow -> Unit
            is ToolPreExecutionDecision.Deny -> return ToolExecutionPlanResult.Rejected(
                errorCode = gate.errorCode,
                detail = gate.reason,
            )
        }
        val argsObject = request.args as? JsonObject ?: JsonObject(emptyMap())
        val assessment = assess(ToolAssessmentRequest(request.toolName, argsObject, context))
        if (!assessment.accepted) {
            return ToolExecutionPlanResult.Rejected(
                errorCode = checkNotNull(assessment.errorCode),
                detail = "No runtime security descriptor exists for this tool.",
            )
        }
        if (request.wallClockBudgetMs == 0L) {
            return ToolExecutionPlanResult.TimedOut(assessment.policy, executionId = null)
        }

        val redacted = RedactedToolCallContext(
            toolName = request.toolName,
            effects = assessment.policy.effects,
            resourceNamespaces = assessment.policy.resourceKeys.mapTo(linkedSetOf()) { it.namespace },
            origin = context.callOrigin,
            hasConversationOwner = true,
            assistantId = context.assistantId,
            conversationId = context.conversationId.toString(),
            runId = context.runId.toString(),
        )
        val hookRejection = runInterceptors(redacted)
        if (hookRejection != null) return hookRejection

        val effectivePolicy = if (request.startableTool == null) {
            assessment.policy.copy(
                cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
            )
        } else {
            assessment.policy
        }

        var executionId: String? = null
        val completed = withTimeoutOrNull(request.wallClockBudgetMs) {
            withPolicyLocks(effectivePolicy) {
                notifyObservers(
                    RedactedToolLifecycleEvent(
                        phase = RedactedToolLifecycleEvent.Phase.STARTING,
                        context = redacted,
                    )
                )
                coroutineScope {
                    val handle = request.startableTool?.start(request.args, context) ?: run {
                        val deferred = async(Dispatchers.IO) { request.legacyExecute(request.args) }
                        LegacyToolExecutionHandle(result = deferred)
                    }
                    executionId = handle.executionId
                    request.runControl?.registerTool(request.toolCallId, handle)
                    try {
                        val output = handle.awaitResult()
                        notifyObservers(
                            RedactedToolLifecycleEvent(
                                phase = RedactedToolLifecycleEvent.Phase.COMPLETED,
                                context = redacted,
                                executionId = handle.executionId,
                            )
                        )
                        ToolExecutionPlanResult.Completed(
                            output = output,
                            policy = effectivePolicy,
                            executionId = handle.executionId,
                        )
                    } catch (cancelled: CancellationException) {
                        val cancelReason = when {
                            cancelled is TimeoutCancellationException -> ToolCancelReason.TIMEOUT
                            request.runControl?.stoppedBy != null -> ToolCancelReason.USER_STOPPED
                            else -> ToolCancelReason.USER_INTERRUPTED
                        }
                        val state = cancelHandle(handle, cancelReason)
                        notifyObservers(
                            RedactedToolLifecycleEvent(
                                phase = RedactedToolLifecycleEvent.Phase.CANCELLED,
                                context = redacted,
                                executionId = handle.executionId,
                                terminationState = state,
                            )
                        )
                        throw cancelled
                    } catch (failure: Throwable) {
                        notifyObservers(
                            RedactedToolLifecycleEvent(
                                phase = RedactedToolLifecycleEvent.Phase.FAILED,
                                context = redacted,
                                executionId = handle.executionId,
                            )
                        )
                        throw failure
                    } finally {
                        request.runControl?.unregisterTool(request.toolCallId, handle)
                    }
                }
            }
        }
        if (completed != null) return completed

        notifyObservers(
            RedactedToolLifecycleEvent(
                phase = RedactedToolLifecycleEvent.Phase.TIMED_OUT,
                context = redacted,
                executionId = executionId,
            )
        )
        return ToolExecutionPlanResult.TimedOut(effectivePolicy, executionId)
    }

    private suspend fun runInterceptors(
        context: RedactedToolCallContext,
    ): ToolExecutionPlanResult.Rejected? {
        for (interceptor in interceptors) {
            val attempt = runCatching {
                withTimeoutOrNull(interceptorTimeoutMs) { interceptor.intercept(context) }
            }
            if (attempt.isFailure) {
                return ToolExecutionPlanResult.Rejected(
                    "tool_hook_failed",
                    "A tool interceptor failed; the call was blocked.",
                )
            }
            when (val decision = attempt.getOrNull()) {
                null -> return ToolExecutionPlanResult.Rejected(
                    "tool_hook_timeout",
                    "A tool interceptor timed out; the call was blocked.",
                )
                is ToolHookDecision.Block -> return ToolExecutionPlanResult.Rejected(
                    "tool_hook_blocked",
                    decision.reason,
                )
                ToolHookDecision.Proceed -> Unit
            }
        }
        return null
    }

    private suspend fun cancelHandle(
        handle: ToolExecutionHandle,
        reason: ToolCancelReason,
    ): ToolTerminationState = withContext(NonCancellable) {
        runCatching { handle.requestCancel(reason) }
        runCatching { handle.awaitTermination(2_000.milliseconds) }
            .getOrDefault(ToolTerminationState.Unknown)
    }

    private suspend fun notifyObservers(event: RedactedToolLifecycleEvent) {
        observers.forEach { observer ->
            runCatching {
                withTimeoutOrNull(observerTimeoutMs) { observer.onEvent(event) }
            }
        }
    }

    private suspend fun <T> withPolicyLocks(
        policy: ToolExecutionPolicy,
        block: suspend () -> T,
    ): T = when (policy.concurrency) {
        ToolConcurrency.GLOBAL_SERIAL -> globalMutex.withLock { block() }
        ToolConcurrency.RESOURCE_SERIAL,
        ToolConcurrency.PARALLEL_SAFE -> {
            val mutexes = policy.resourceKeys
                .sortedBy(ToolResourceKey::toString)
                .map { key -> resourceMutexes.computeIfAbsent(key) { Mutex() } }
            withLocks(mutexes, 0, block)
        }
    }

    private suspend fun <T> withLocks(
        locks: List<Mutex>,
        index: Int,
        block: suspend () -> T,
    ): T = if (index >= locks.size) {
        block()
    } else {
        locks[index].withLock { withLocks(locks, index + 1, block) }
    }
}

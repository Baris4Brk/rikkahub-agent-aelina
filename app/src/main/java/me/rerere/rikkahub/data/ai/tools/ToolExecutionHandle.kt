package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.CapabilityKey
import kotlin.time.Duration
import kotlin.uuid.Uuid

typealias ToolResult = List<UIMessagePart>

data class ToolCancelReason(val message: String) {
    companion object {
        val USER_INTERRUPTED = ToolCancelReason("User interrupted the task")
        val USER_STOPPED = ToolCancelReason("User stopped the task")
        val STEERING_OVERRIDE = ToolCancelReason("User redirected the task")
        val TIMEOUT = ToolCancelReason("Tool execution timed out")
        val SHUTDOWN = ToolCancelReason("Session is shutting down")
    }
}

interface ToolExecutionHandle {
    val executionId: String
    suspend fun awaitResult(): ToolResult
    fun requestCancel(reason: ToolCancelReason): CancelRequestResult
    suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState
}

interface StartableTool {
    suspend fun start(
        args: kotlinx.serialization.json.JsonElement,
        context: ToolExecutionContext,
    ): ToolExecutionHandle
}

data class ToolExecutionContext(
    val runId: Uuid,
    val conversationId: Uuid,
    val assistantId: String,
    val callOrigin: ToolCallOrigin,
    /** Optional policy principal. Absent means the pre-existing local assistant policy. */
    val capabilitySubject: CapabilitySubject? = null,
    /** True only when the invocation is bound to the selected second-user conversation. */
    val selectedPrivilegedConversation: Boolean = false,
    /** Capability snapshot for a headless workflow; empty for ordinary tool calls. */
    val frozenCapabilities: Set<CapabilityKey> = emptySet(),
)

sealed interface CancelRequestResult {
    data object Requested : CancelRequestResult
    data object AlreadyRequested : CancelRequestResult
    data object Unsupported : CancelRequestResult
    data object LocalWaitCancelledOnly : CancelRequestResult
    data object NotFound : CancelRequestResult
    data class Failed(val reason: String) : CancelRequestResult
}

enum class ToolTerminationState {
    CancelRequested,
    StoppedConfirmed,
    StillRunning,
    Unsupported,
    Unknown,
}

class LegacyToolExecutionHandle(
    override val executionId: String = Uuid.random().toString(),
    private val result: Deferred<ToolResult>,
) : ToolExecutionHandle {
    private val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun awaitResult(): ToolResult = result.await()

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        result.cancel(CancellationException(reason.message))
        return CancelRequestResult.LocalWaitCancelledOnly
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState =
        if (cancelRequested.get()) ToolTerminationState.Unknown
        else if (result.isCompleted) ToolTerminationState.StoppedConfirmed
        else ToolTerminationState.Unknown
}

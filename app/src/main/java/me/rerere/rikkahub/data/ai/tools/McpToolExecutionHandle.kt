package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Cancellation hooks for MCP. Local transport cancellation and server acknowledgement are
 * intentionally separate: closing HTTP/SSE does not prove the remote task stopped.
 */
data class McpCancellationHooks(
    val cancelLocalWait: () -> Unit,
    val cancelTransport: () -> Unit,
    val sendProtocolCancel: (() -> Unit)?,
    val awaitServerConfirmation: (suspend () -> Boolean)?,
)

class McpToolExecutionHandle(
    override val executionId: String,
    private val result: Deferred<ToolResult>,
    private val hooks: McpCancellationHooks,
) : ToolExecutionHandle {
    private val cancelRequested = AtomicBoolean(false)
    private val serverConfirmed = CompletableDeferred<Boolean>()

    override suspend fun awaitResult(): ToolResult = result.await()

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        return try {
            hooks.cancelLocalWait()
            hooks.cancelTransport()
            hooks.sendProtocolCancel?.invoke()
            CancelRequestResult.Requested
        } catch (t: Throwable) {
            CancelRequestResult.Failed(t.message ?: reason.message)
        }
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        if (!cancelRequested.get()) {
            return if (result.isCompleted) ToolTerminationState.StoppedConfirmed
            else ToolTerminationState.Unknown
        }
        val confirmation = withTimeoutOrNull(gracePeriod) {
            hooks.awaitServerConfirmation?.invoke() ?: false
        } ?: false
        serverConfirmed.complete(confirmation)
        return if (confirmation) {
            ToolTerminationState.StoppedConfirmed
        } else if (result.isCompleted) {
            ToolTerminationState.Unknown
        } else {
            ToolTerminationState.StillRunning
        }
    }
}

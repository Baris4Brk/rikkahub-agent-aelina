package me.rerere.rikkahub.data.ai.tools

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** SSH cancellation deliberately separates channel close from remote process-group termination. */
data class SshCancellationHooks(
    val closeChannel: () -> Unit,
    val terminateRemoteProcessGroup: (force: Boolean) -> Boolean,
    val awaitRemoteExit: suspend () -> Boolean,
)

class SshToolExecutionHandle(
    override val executionId: String,
    private val result: Deferred<ToolResult>,
    private val hooks: SshCancellationHooks,
) : ToolExecutionHandle {
    private val cancelRequested = AtomicBoolean(false)

    override suspend fun awaitResult(): ToolResult = result.await()

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        return try {
            hooks.closeChannel()
            hooks.terminateRemoteProcessGroup(false)
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
        if (withTimeoutOrNull(gracePeriod) { hooks.awaitRemoteExit() } == true) {
            return ToolTerminationState.StoppedConfirmed
        }
        return try {
            if (hooks.terminateRemoteProcessGroup(true)) {
                if (withTimeoutOrNull(gracePeriod) { hooks.awaitRemoteExit() } == true) {
                    ToolTerminationState.StoppedConfirmed
                } else {
                    ToolTerminationState.Unknown
                }
            } else {
                ToolTerminationState.Unknown
            }
        } catch (_: Throwable) {
            ToolTerminationState.Unknown
        }
    }
}

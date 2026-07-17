package me.rerere.rikkahub.privilege

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/** Real handle for one synchronous UserService Binder command. */
internal class ShizukuCommandExecutionHandle(
    override val executionId: String,
    private val result: Deferred<PrivilegedCommandResult>,
    private val cancellationScope: CoroutineScope,
    private val cancelRemote: suspend (String) -> PrivilegedCommandResult,
) : ToolExecutionHandle {
    private val cancelRequested = AtomicBoolean(false)
    private val cancellationResult = CompletableDeferred<PrivilegedCommandResult>()

    override suspend fun awaitResult(): ToolResult = try {
        listOf(UIMessagePart.Text(PrivilegedCommandJson.encodeResult(result.await())))
    } catch (cancelled: CancellationException) {
        requestCancel(ToolCancelReason.SHUTDOWN)
        throw cancelled
    }

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelRequested.compareAndSet(false, true)) {
            return CancelRequestResult.AlreadyRequested
        }
        cancellationScope.launch {
            val response = runCatching { cancelRemote(executionId) }.getOrElse { error ->
                PrivilegedCommandResult(
                    ok = false,
                    code = "BINDER_DIED",
                    message = error.message ?: "The privileged Binder became unavailable.",
                )
            }
            cancellationResult.complete(response)
        }
        return CancelRequestResult.Requested
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        val waitMs = gracePeriod.inWholeMilliseconds.coerceAtLeast(1)
        val commandResult = withTimeoutOrNull(waitMs) { result.await() }
        if (commandResult != null) return commandResult.toTerminationState()
        val cancelResult = withTimeoutOrNull(1) { cancellationResult.await() }
        return when {
            cancelResult == null && cancelRequested.get() -> ToolTerminationState.CancelRequested
            cancelResult == null -> ToolTerminationState.StillRunning
            cancelResult.code == "COMMAND_CANCELLED" -> ToolTerminationState.StoppedConfirmed
            cancelResult.code == "CANCEL_REQUESTED" -> ToolTerminationState.CancelRequested
            else -> ToolTerminationState.Unknown
        }
    }

    private fun PrivilegedCommandResult.toTerminationState(): ToolTerminationState = when (code) {
        "BINDER_DIED", "TERMINATION_UNKNOWN" -> ToolTerminationState.Unknown
        else -> ToolTerminationState.StoppedConfirmed
    }
}

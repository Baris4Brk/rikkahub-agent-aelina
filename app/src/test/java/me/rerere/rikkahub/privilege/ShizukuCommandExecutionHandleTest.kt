package me.rerere.rikkahub.privilege

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ShizukuCommandExecutionHandleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `remote cancellation is requested once and confirmed result is observable`() = runBlocking {
        val result = CompletableDeferred<PrivilegedCommandResult>()
        var cancellationCalls = 0
        val handle = ShizukuCommandExecutionHandle(
            executionId = COMMAND_ID,
            result = result,
            cancellationScope = scope,
            cancelRemote = {
                cancellationCalls++
                val cancelled = commandResult("COMMAND_CANCELLED", cancelled = true)
                result.complete(cancelled)
                cancelled
            },
        )

        assertEquals(CancelRequestResult.Requested, handle.requestCancel(ToolCancelReason.USER_STOPPED))
        assertEquals(CancelRequestResult.AlreadyRequested, handle.requestCancel(ToolCancelReason.SHUTDOWN))
        assertEquals(ToolTerminationState.StoppedConfirmed, handle.awaitTermination(1.seconds))
        assertEquals(1, cancellationCalls)
    }

    @Test
    fun `binder death is never presented as confirmed termination`() = runBlocking {
        val result = CompletableDeferred(commandResult("BINDER_DIED"))
        val handle = ShizukuCommandExecutionHandle(
            executionId = COMMAND_ID,
            result = result,
            cancellationScope = scope,
            cancelRemote = { commandResult("BINDER_DIED") },
        )

        assertEquals(ToolTerminationState.Unknown, handle.awaitTermination(1.seconds))
        val part = handle.awaitResult().single() as UIMessagePart.Text
        assertTrue(part.text.contains("BINDER_DIED"))
    }

    private fun commandResult(
        code: String,
        cancelled: Boolean = false,
    ) = PrivilegedCommandResult(
        ok = code == "OK" || code == "COMMAND_CANCELLED",
        code = code,
        message = code,
        data = PrivilegedCommandResultData(
            commandId = COMMAND_ID,
            cancelled = cancelled,
        ),
    )

    private companion object {
        const val COMMAND_ID = "9e03bab6-323e-42b3-a17a-53e8d04f56c9"
    }
}

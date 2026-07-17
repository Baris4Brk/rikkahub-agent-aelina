package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class ToolExecutionHandleTest {
    @Test
    fun `mcp separates local cancellation from server confirmation`() = runBlocking {
        var localCancelled = false
        var transportCancelled = false
        var protocolCancelled = false
        val hooks = McpCancellationHooks(
            cancelLocalWait = { localCancelled = true },
            cancelTransport = { transportCancelled = true },
            sendProtocolCancel = { protocolCancelled = true },
            awaitServerConfirmation = { true },
        )
        val handle = McpToolExecutionHandle(
            executionId = "mcp-1",
            result = CompletableDeferred<List<UIMessagePart>>(),
            hooks = hooks,
        )

        assertEquals(CancelRequestResult.Requested, handle.requestCancel(ToolCancelReason.USER_INTERRUPTED))
        assertEquals(ToolTerminationState.StoppedConfirmed, handle.awaitTermination(100.milliseconds))
        assertTrue(localCancelled)
        assertTrue(transportCancelled)
        assertTrue(protocolCancelled)
    }

    @Test
    fun `mcp reports still running when server does not confirm`() = runBlocking {
        val handle = McpToolExecutionHandle(
            executionId = "mcp-unknown",
            result = CompletableDeferred<List<UIMessagePart>>(),
            hooks = McpCancellationHooks(
                cancelLocalWait = {},
                cancelTransport = {},
                sendProtocolCancel = {},
                awaitServerConfirmation = { false },
            ),
        )

        handle.requestCancel(ToolCancelReason.USER_INTERRUPTED)
        assertEquals(ToolTerminationState.StillRunning, handle.awaitTermination(100.milliseconds))
    }

    @Test
    fun `ssh requires remote process exit confirmation`() = runBlocking {
        var force = false
        val hooks = SshCancellationHooks(
            closeChannel = {},
            terminateRemoteProcessGroup = { requestedForce -> force = requestedForce; requestedForce },
            awaitRemoteExit = { force },
        )
        val handle = SshToolExecutionHandle(
            executionId = "ssh-1",
            result = CompletableDeferred<List<UIMessagePart>>(),
            hooks = hooks,
        )

        assertEquals(CancelRequestResult.Requested, handle.requestCancel(ToolCancelReason.USER_INTERRUPTED))
        assertEquals(ToolTerminationState.StoppedConfirmed, handle.awaitTermination(100.milliseconds))
        assertTrue(force)
    }

    @Test
    fun `termux rejects pid reuse and reports unknown`() = runBlocking {
        val handle = TermuxToolExecutionHandle(
            executionId = "termux-1",
            result = CompletableDeferred<List<UIMessagePart>>(),
            bridge = object : TermuxBridgeClient {
                override suspend fun cancel(runId: String, force: Boolean): Boolean = true
                override suspend fun status(runId: String): TermuxProcessStatus =
                    TermuxProcessStatus(
                        runId = runId,
                        pid = 99,
                        pgid = 100,
                        startTimeMillis = 1234,
                        running = false,
                        cancellationConfirmed = true,
                    )
            },
            runId = "run-1",
            expectedPid = 1,
            expectedPgid = 2,
            expectedStartTimeMillis = 3,
        )

        handle.requestCancel(ToolCancelReason.USER_INTERRUPTED)
        assertEquals(ToolTerminationState.Unknown, handle.awaitTermination(100.milliseconds))
    }

    @Test
    fun `local process returns a bounded structured result`() = runBlocking {
        val javaBinary = java.io.File(System.getProperty("java.home"), "bin${java.io.File.separator}java")
        val tool = LocalProcessTool(
            command = listOf(javaBinary.absolutePath, "-version"),
            outputLimitBytes = 4096,
            executionTimeout = 10_000.milliseconds,
        )
        val handle = tool.start(
            args = kotlinx.serialization.json.JsonObject(emptyMap()),
            context = ToolExecutionContext(
                runId = kotlin.uuid.Uuid.random(),
                conversationId = kotlin.uuid.Uuid.random(),
                assistantId = "test",
                callOrigin = me.rerere.rikkahub.data.ai.ToolCallOrigin.LocalChat,
            ),
        )
        val output = withTimeout(5_000) { handle.awaitResult() }
        assertTrue(output.single() is UIMessagePart.Text)
        assertTrue((output.single() as UIMessagePart.Text).text.contains("executionId"))
    }
}

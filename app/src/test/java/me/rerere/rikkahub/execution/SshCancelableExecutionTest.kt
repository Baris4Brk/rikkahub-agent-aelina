package me.rerere.rikkahub.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.SshCancellationHooks
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.ai.tools.local.SshAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class SshCancelableExecutionTest {
    @Test
    fun `finite ssh call uses real cancellation hooks`() = runBlocking {
        val forces = mutableListOf<Boolean>()
        var closeCalls = 0
        val result = CompletableDeferred(
            listOf(UIMessagePart.Text("{\"success\":true}"))
        )
        val backend = SshCancelableExecutionBackend { _, _ ->
            Result.success(
                StartedSshExecution(
                    identity = RemoteSshProcessIdentity(12, 12, 900),
                    result = result,
                    hooks = SshCancellationHooks(
                        closeChannel = { closeCalls++ },
                        terminateRemoteProcessGroup = { force -> forces += force; true },
                        awaitRemoteExit = { true },
                    ),
                )
            )
        }
        val startable = SshCancelableStartableTool(
            legacyTool = legacyTool(),
            specResolver = SshExecutionSpecResolver { Result.success(spec()) },
            backend = backend,
            scope = scope,
        )
        val handle = startable.start(buildJsonObject {}, executionContext)

        handle.requestCancel(ToolCancelReason.USER_STOPPED)
        val terminated = handle.awaitTermination(1.seconds)

        assertEquals(ToolTerminationState.StoppedConfirmed, terminated)
        assertEquals(1, closeCalls)
        assertEquals(listOf(false), forces)
    }

    @Test
    fun `temporary background call stays legacy and is never written to managed ledger`() = runBlocking {
        var backendStarts = 0
        val startable = SshCancelableStartableTool(
            legacyTool = legacyTool(),
            specResolver = SshExecutionSpecResolver {
                Result.success(spec().copy(background = true))
            },
            backend = SshCancelableExecutionBackend { _, _ ->
                backendStarts++
                error("must not start managed backend")
            },
            scope = scope,
        )

        val result = startable.start(buildJsonObject {}, executionContext).awaitResult()

        assertEquals(0, backendStarts)
        assertTrue(result.single().toString().contains("legacy"))
    }

    @Test
    fun `remote wrapper publishes identity before output and validates pid reuse fields`() {
        val identity = RemoteSshProcessIdentity(44, 44, 1234)
        val wrapper = AndroidSshCancelableExecutionBackend.wrapCancelableCommand(
            "run-id",
            "printf '%s' hello",
        )
        val verification = AndroidSshCancelableExecutionBackend.identityCheckScript(identity)

        assertTrue(wrapper.indexOf("__RIKKAHUB_ID__") < wrapper.indexOf("wait \"\$pid\""))
        assertTrue(wrapper.indexOf("cat >\"\$d/in\"") < wrapper.indexOf("setsid"))
        assertTrue(verification.contains("/proc/44/stat"))
        assertTrue(verification.contains("1234"))
        assertTrue(verification.contains("pgid"))
        assertFalse(verification.contains("password"))
        assertFalse(verification.contains("private"))
    }

    private fun legacyTool() = Tool(
        name = "ssh_exec",
        description = "legacy",
        parameters = { InputSchema.Obj(buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("legacy")) },
    )

    private fun spec() = SshExecutionSpec(
        host = "example.test",
        port = 22,
        user = "agent",
        auth = SshAuth(password = "not-persisted"),
        command = "sleep 30",
        stdin = null,
        background = false,
        timeoutMs = 30_000,
    )

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val executionContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            assistantId = "assistant",
            callOrigin = ToolCallOrigin.LocalChat,
        )
    }
}

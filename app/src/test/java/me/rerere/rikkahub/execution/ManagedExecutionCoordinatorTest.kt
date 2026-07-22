package me.rerere.rikkahub.execution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.workspace.WorkspaceDesiredState
import me.rerere.workspace.WorkspaceProcessLogs
import me.rerere.workspace.WorkspaceProcessResult
import me.rerere.workspace.WorkspaceProcessSnapshot
import me.rerere.workspace.WorkspaceProcessStatus
import me.rerere.workspace.WorkspaceRestartPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExecutionCoordinatorTest {
    @Test
    fun `runtime prefix cannot bypass caller allowed adapters`() = runBlocking {
        val workspace = RecordingAdapter(ManagedExecutionRuntime.WORKSPACE)
        val ssh = RecordingAdapter(ManagedExecutionRuntime.SSH)
        val coordinator = DefaultManagedExecutionCoordinator(listOf(workspace, ssh))

        val result = coordinator.dispatch(
            ManagedExecutionRequest.Status(caller, "ssh:native")
        )

        assertEquals("execution_runtime_not_allowed", (result as ManagedExecutionResult.Error).code)
        assertEquals(0, ssh.calls)
    }

    @Test
    fun `workspace adapter restricts status logs and stop to caller workspace`() = runBlocking {
        val port = FakeWorkspacePort()
        val adapter = WorkspaceManagedExecutionAdapter(port)
        val wrongCaller = caller.copy(workspaceId = "workspace-b")

        assertTrue(adapter.status(wrongCaller, "workspace:wp_12345678") is ManagedExecutionResult.Error)
        assertTrue(adapter.logs(wrongCaller, "workspace:wp_12345678", 100) is ManagedExecutionResult.Error)
        assertTrue(adapter.stop(wrongCaller, "workspace:wp_12345678", false) is ManagedExecutionResult.Error)
        assertEquals(0, port.logCalls)
        assertEquals(0, port.stopCalls)
    }

    @Test
    fun `workspace stop keeps an unconfirmed live process visible`() = runBlocking {
        val port = FakeWorkspacePort(stopConfirmed = false)
        val adapter = WorkspaceManagedExecutionAdapter(port)

        val result = adapter.stop(caller, "workspace:wp_12345678", force = true)

        val snapshot = (result as ManagedExecutionResult.Snapshot).execution
        assertTrue(snapshot.terminationUncertain)
        assertTrue(snapshot.alive)
    }

    private class RecordingAdapter(
        override val runtime: ManagedExecutionRuntime,
    ) : ManagedExecutionAdapter {
        var calls = 0
        override suspend fun list(caller: ManagedExecutionCaller, includeStopped: Boolean) = emptyList<ManagedExecutionSnapshot>()
        override suspend fun status(caller: ManagedExecutionCaller, executionId: String): ManagedExecutionResult {
            calls++
            return ManagedExecutionResult.Error("unused")
        }
        override suspend fun logs(caller: ManagedExecutionCaller, executionId: String, tailBytes: Int) = status(caller, executionId)
        override suspend fun stop(caller: ManagedExecutionCaller, executionId: String, force: Boolean) = status(caller, executionId)
        override suspend fun emergencyStop() = emptyList<ManagedExecutionSnapshot>()
    }

    private class FakeWorkspacePort(
        private val stopConfirmed: Boolean = true,
    ) : WorkspaceProcessPort {
        var logCalls = 0
        var stopCalls = 0
        private fun snapshot(
            workspaceId: String = "workspace-a",
            status: WorkspaceProcessStatus = WorkspaceProcessStatus.RUNNING,
            alive: Boolean = true,
        ) = WorkspaceProcessSnapshot(
            processId = "wp_12345678",
            workspaceId = workspaceId,
            name = "server",
            status = status,
            alive = alive,
            restartPolicy = WorkspaceRestartPolicy.NEVER,
            desiredState = WorkspaceDesiredState.RUNNING,
            keepAwake = false,
        )

        override suspend fun list(workspaceId: String?, includeStopped: Boolean) =
            listOf(snapshot()).filter { it.workspaceId == workspaceId }

        override suspend fun status(processId: String) = WorkspaceProcessResult(
            ok = true,
            code = "OK",
            message = "ok",
            process = snapshot(),
        )

        override suspend fun logs(processId: String, tailBytes: Int): WorkspaceProcessResult {
            logCalls++
            return WorkspaceProcessResult(
                ok = true,
                code = "OK",
                message = "ok",
                process = snapshot(),
                logs = WorkspaceProcessLogs(stdout = "ready"),
            )
        }

        override suspend fun stop(processId: String, force: Boolean): WorkspaceProcessResult {
            stopCalls++
            return WorkspaceProcessResult(
                ok = stopConfirmed,
                code = if (stopConfirmed) "PROCESS_STOPPED" else "PROCESS_STOP_FAILED",
                message = "stop",
                process = snapshot(
                    status = if (stopConfirmed) WorkspaceProcessStatus.STOPPED else WorkspaceProcessStatus.FAILED,
                    alive = !stopConfirmed,
                ),
            )
        }

        override suspend fun emergencyStop() = emptyList<WorkspaceProcessSnapshot>()
    }

    private companion object {
        val caller = ManagedExecutionCaller(
            assistantId = "assistant",
            conversationId = "conversation",
            runId = "run",
            origin = ToolCallOrigin.LocalChat,
            allowedRuntimes = setOf(ManagedExecutionRuntime.WORKSPACE),
            workspaceId = "workspace-a",
        )
    }
}

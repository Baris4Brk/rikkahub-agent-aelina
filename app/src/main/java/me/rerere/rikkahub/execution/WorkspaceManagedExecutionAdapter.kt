package me.rerere.rikkahub.execution

import me.rerere.workspace.WorkspaceProcessLogStream
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessResult
import me.rerere.workspace.WorkspaceProcessSnapshot
import me.rerere.workspace.WorkspaceProcessStatus
import me.rerere.workspace.WorkspaceProcessStopReason

interface WorkspaceProcessPort {
    suspend fun list(workspaceId: String?, includeStopped: Boolean): List<WorkspaceProcessSnapshot>
    suspend fun status(processId: String): WorkspaceProcessResult
    suspend fun logs(processId: String, tailBytes: Int): WorkspaceProcessResult
    suspend fun stop(processId: String, force: Boolean): WorkspaceProcessResult
    suspend fun emergencyStop(): List<WorkspaceProcessSnapshot>
}

class WorkspaceProcessManagerPort(
    private val manager: WorkspaceProcessManager,
) : WorkspaceProcessPort {
    override suspend fun list(workspaceId: String?, includeStopped: Boolean) =
        manager.list(workspaceId, includeStopped)

    override suspend fun status(processId: String) = manager.status(processId)

    override suspend fun logs(processId: String, tailBytes: Int) = manager.logs(
        processId,
        WorkspaceProcessLogStream.BOTH,
        tailBytes,
    )

    override suspend fun stop(processId: String, force: Boolean) = manager.stop(
        processId,
        force,
        WorkspaceProcessStopReason.USER,
    )

    override suspend fun emergencyStop(): List<WorkspaceProcessSnapshot> {
        manager.stopAll(force = true, reason = WorkspaceProcessStopReason.EMERGENCY_STOP)
        return manager.list(workspaceId = null, includeStopped = true)
    }
}

class WorkspaceManagedExecutionAdapter(
    private val port: WorkspaceProcessPort,
) : ManagedExecutionAdapter {
    override val runtime = ManagedExecutionRuntime.WORKSPACE

    override suspend fun list(
        caller: ManagedExecutionCaller,
        includeStopped: Boolean,
    ): List<ManagedExecutionSnapshot> {
        val workspaceId = caller.workspaceId ?: return emptyList()
        return port.list(workspaceId, includeStopped).map(WorkspaceProcessSnapshot::toManaged)
    }

    override suspend fun status(
        caller: ManagedExecutionCaller,
        executionId: String,
    ): ManagedExecutionResult {
        val nativeId = nativeManagedExecutionId(executionId)
            ?: return ManagedExecutionResult.Error("execution_id_invalid")
        val result = port.status(nativeId)
        val snapshot = result.process?.takeIf { it.workspaceId == caller.workspaceId }
            ?: return ManagedExecutionResult.Error("execution_not_found")
        return if (result.ok) ManagedExecutionResult.Snapshot(snapshot.toManaged())
        else ManagedExecutionResult.Error(result.code.lowercase())
    }

    override suspend fun logs(
        caller: ManagedExecutionCaller,
        executionId: String,
        tailBytes: Int,
    ): ManagedExecutionResult {
        val ownership = status(caller, executionId)
        val snapshot = (ownership as? ManagedExecutionResult.Snapshot)?.execution
            ?: return ownership
        val nativeId = nativeManagedExecutionId(executionId)!!
        val result = port.logs(nativeId, tailBytes)
        if (!result.ok) return ManagedExecutionResult.Error(result.code.lowercase())
        val logs = result.logs ?: return ManagedExecutionResult.Error("execution_logs_unavailable")
        return ManagedExecutionResult.Logs(
            execution = snapshot,
            logs = ManagedExecutionLogs(logs.stdout, logs.stderr, logs.truncated),
        )
    }

    override suspend fun stop(
        caller: ManagedExecutionCaller,
        executionId: String,
        force: Boolean,
    ): ManagedExecutionResult {
        val ownership = status(caller, executionId)
        if (ownership !is ManagedExecutionResult.Snapshot) return ownership
        val result = port.stop(nativeManagedExecutionId(executionId)!!, force)
        val snapshot = result.process?.toManaged()
            ?: return ManagedExecutionResult.Error(result.code.lowercase())
        return if (result.ok) ManagedExecutionResult.Stopped(snapshot)
        else ManagedExecutionResult.Snapshot(snapshot.copy(terminationUncertain = true))
    }

    override suspend fun emergencyStop(): List<ManagedExecutionSnapshot> =
        port.emergencyStop().map(WorkspaceProcessSnapshot::toManaged)
}

private fun WorkspaceProcessSnapshot.toManaged() = ManagedExecutionSnapshot(
    executionId = managedExecutionId(ManagedExecutionRuntime.WORKSPACE, processId),
    runtime = ManagedExecutionRuntime.WORKSPACE,
    name = name,
    status = when (status) {
        WorkspaceProcessStatus.STARTING -> ManagedExecutionStatus.STARTING
        WorkspaceProcessStatus.RUNNING -> ManagedExecutionStatus.RUNNING
        WorkspaceProcessStatus.EXITED -> ManagedExecutionStatus.EXITED
        WorkspaceProcessStatus.STOPPING -> ManagedExecutionStatus.STOP_REQUESTED
        WorkspaceProcessStatus.STOPPED -> ManagedExecutionStatus.STOPPED
        WorkspaceProcessStatus.FAILED -> ManagedExecutionStatus.FAILED
        WorkspaceProcessStatus.RECOVERING -> ManagedExecutionStatus.RECOVERING
        WorkspaceProcessStatus.LOST -> ManagedExecutionStatus.LOST
    },
    alive = alive,
    startedAtMs = startedAt,
    runtimeInstanceMarker = runtimeInstanceMarker,
    lastExitCode = lastExitCode,
    terminationUncertain = status == WorkspaceProcessStatus.FAILED && alive,
)

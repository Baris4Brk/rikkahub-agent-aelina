package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.execution.ManagedExecutionCaller
import me.rerere.rikkahub.execution.ManagedExecutionCoordinator
import me.rerere.rikkahub.execution.ManagedExecutionLedger
import me.rerere.rikkahub.execution.ManagedExecutionRequest
import me.rerere.rikkahub.execution.ManagedExecutionResult
import me.rerere.rikkahub.execution.ManagedExecutionRuntime
import me.rerere.rikkahub.execution.ManagedExecutionSnapshot
import me.rerere.rikkahub.execution.ManagedExecutionStatus
import me.rerere.rikkahub.execution.nativeManagedExecutionId
import me.rerere.rikkahub.execution.isUnmanagedSshExecution
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessManagerState
import me.rerere.workspace.WorkspaceProcessStatus

sealed interface RuntimeProbeResult {
    data class Alive(val runtimeInstanceMarker: String?) : RuntimeProbeResult
    data class Exited(val exitCode: Int?) : RuntimeProbeResult
    data class Missing(val authoritative: Boolean = true) : RuntimeProbeResult
    data class Recovering(val reasonCode: String) : RuntimeProbeResult
    data class Unreachable(
        val reasonCode: String,
        val retryable: Boolean = true,
    ) : RuntimeProbeResult
    data class Unsupported(val reasonCode: String) : RuntimeProbeResult
}

fun interface ExecutionRuntimeProbe {
    suspend fun probe(record: ExecutionRecord): RuntimeProbeResult
}

class ManagedExecutionCallerResolver(
    private val ledger: ManagedExecutionLedger,
    private val workspaceManager: WorkspaceProcessManager,
) {
    suspend fun resolve(record: ExecutionRecord): ManagedExecutionCaller? {
        val runtime = ExecutionRuntime.fromWire(record.runtime)
        val handle = record.runtimeHandleSummary ?: record.id
        if (runtime == ExecutionRuntime.SSH && handle.isUnmanagedSshExecution()) {
            val origin = runCatching {
                me.rerere.rikkahub.data.ai.ToolCallOrigin.valueOf(record.origin)
            }.getOrNull() ?: return null
            return ManagedExecutionCaller(
                assistantId = record.subjectId,
                conversationId = record.conversationId.orEmpty(),
                runId = record.traceId,
                origin = origin,
                allowedRuntimes = setOf(ManagedExecutionRuntime.SSH),
            )
        }
        if (runtime == ExecutionRuntime.WORKSPACE) {
            val nativeId = nativeManagedExecutionId(handle) ?: return null
            val process = workspaceManager.status(nativeId).process ?: return null
            return ManagedExecutionCaller(
                assistantId = record.subjectId,
                conversationId = record.conversationId.orEmpty(),
                runId = record.traceId,
                origin = me.rerere.rikkahub.data.ai.ToolCallOrigin.valueOf(record.origin),
                allowedRuntimes = setOf(ManagedExecutionRuntime.WORKSPACE),
                workspaceId = process.workspaceId,
            )
        }
        val managed = ledger.list().firstOrNull { it.executionId == handle } ?: return null
        val managedRuntime = when (runtime) {
            ExecutionRuntime.TERMUX -> ManagedExecutionRuntime.TERMUX
            ExecutionRuntime.SSH -> ManagedExecutionRuntime.SSH
            else -> return null
        }
        val origin = runCatching {
            me.rerere.rikkahub.data.ai.ToolCallOrigin.valueOf(managed.ownerOrigin)
        }.getOrNull() ?: return null
        return ManagedExecutionCaller(
            assistantId = managed.ownerAssistantId,
            conversationId = managed.ownerConversationId,
            runId = record.traceId,
            origin = origin,
            allowedRuntimes = setOf(managedRuntime),
        )
    }
}

class DefaultExecutionRuntimeProbe(
    private val workspaceManager: WorkspaceProcessManager,
    private val coordinator: ManagedExecutionCoordinator,
    private val callerResolver: ManagedExecutionCallerResolver,
) : ExecutionRuntimeProbe {
    override suspend fun probe(record: ExecutionRecord): RuntimeProbeResult = when (
        ExecutionRuntime.fromWire(record.runtime)
    ) {
        ExecutionRuntime.WORKSPACE -> probeWorkspace(record)
        ExecutionRuntime.TERMUX, ExecutionRuntime.SSH -> probeManaged(record)
        else -> RuntimeProbeResult.Unsupported("runtime_probe_unsupported")
    }

    private suspend fun probeWorkspace(record: ExecutionRecord): RuntimeProbeResult {
        val handle = record.runtimeHandleSummary ?: record.id
        val nativeId = nativeManagedExecutionId(handle)
            ?: return RuntimeProbeResult.Unsupported("workspace_handle_invalid")
        val result = workspaceManager.status(nativeId)
        val snapshot = result.process
        if (snapshot == null) {
            return when (workspaceManager.initializationState.value) {
                WorkspaceProcessManagerState.NOT_STARTED,
                WorkspaceProcessManagerState.LOADING,
                -> RuntimeProbeResult.Recovering("workspace_manager_loading")
                WorkspaceProcessManagerState.FAILED ->
                    RuntimeProbeResult.Unreachable("workspace_manager_failed")
                WorkspaceProcessManagerState.READY -> RuntimeProbeResult.Missing()
            }
        }
        return when (snapshot.status) {
            WorkspaceProcessStatus.RUNNING -> if (snapshot.alive) {
                RuntimeProbeResult.Alive(snapshot.runtimeInstanceMarker)
            } else {
                RuntimeProbeResult.Recovering("workspace_running_not_yet_confirmed")
            }
            WorkspaceProcessStatus.STARTING,
            WorkspaceProcessStatus.RECOVERING,
            -> RuntimeProbeResult.Recovering("workspace_process_recovering")
            WorkspaceProcessStatus.STOPPING -> if (snapshot.alive) {
                RuntimeProbeResult.Alive(snapshot.runtimeInstanceMarker)
            } else {
                RuntimeProbeResult.Exited(snapshot.lastExitCode)
            }
            WorkspaceProcessStatus.LOST -> RuntimeProbeResult.Missing()
            WorkspaceProcessStatus.EXITED,
            WorkspaceProcessStatus.STOPPED,
            WorkspaceProcessStatus.FAILED,
            -> RuntimeProbeResult.Exited(snapshot.lastExitCode)
        }
    }

    private suspend fun probeManaged(record: ExecutionRecord): RuntimeProbeResult {
        val handle = record.runtimeHandleSummary ?: record.id
        val caller = callerResolver.resolve(record)
            ?: return RuntimeProbeResult.Unreachable("managed_owner_or_handle_unavailable")
        return when (val result = coordinator.dispatch(ManagedExecutionRequest.Status(caller, handle))) {
            is ManagedExecutionResult.Snapshot -> result.execution.toProbe()
            is ManagedExecutionResult.Stopped -> result.execution.toProbe()
            is ManagedExecutionResult.Logs -> result.execution.toProbe()
            is ManagedExecutionResult.Error -> when (result.code) {
                "execution_not_found" -> RuntimeProbeResult.Missing()
                "execution_unsupported" ->
                    RuntimeProbeResult.Unsupported("ssh_temporary_background_unmanaged")
                "execution_runtime_unavailable", "execution_status_failed" ->
                    RuntimeProbeResult.Unreachable(result.code)
                else -> RuntimeProbeResult.Unreachable(result.code)
            }
            is ManagedExecutionResult.Executions ->
                RuntimeProbeResult.Unreachable("managed_probe_protocol_error")
        }
    }

    private fun ManagedExecutionSnapshot.toProbe(): RuntimeProbeResult = when {
        alive && !terminationUncertain -> RuntimeProbeResult.Alive(runtimeInstanceMarker)
        terminationUncertain -> RuntimeProbeResult.Unreachable("runtime_identity_unconfirmed")
        status == ManagedExecutionStatus.RECOVERING || status == ManagedExecutionStatus.STARTING ->
            RuntimeProbeResult.Recovering("managed_runtime_recovering")
        status in setOf(
            ManagedExecutionStatus.EXITED,
            ManagedExecutionStatus.STOPPED,
            ManagedExecutionStatus.FAILED,
            ManagedExecutionStatus.LOST,
        ) -> RuntimeProbeResult.Exited(lastExitCode)
        else -> RuntimeProbeResult.Unreachable("managed_runtime_state_unknown")
    }
}

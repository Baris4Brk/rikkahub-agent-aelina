package me.rerere.rikkahub.execution

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultManagedExecutionCoordinator(
    adapters: List<ManagedExecutionAdapter>,
) : ManagedExecutionCoordinator {
    private val adaptersByRuntime = adapters.associateBy(ManagedExecutionAdapter::runtime)
    private val stateMutex = Mutex()
    private val mutableState = MutableStateFlow(ManagedExecutionState())
    override val state: StateFlow<ManagedExecutionState> = mutableState.asStateFlow()

    init {
        require(adaptersByRuntime.size == adapters.size) { "Duplicate managed execution adapter" }
    }

    override suspend fun dispatch(request: ManagedExecutionRequest): ManagedExecutionResult = when (request) {
        is ManagedExecutionRequest.List -> list(request)
        is ManagedExecutionRequest.Status -> route(request.caller, request.executionId) { adapter ->
            adapter.status(request.caller, request.executionId)
        }
        is ManagedExecutionRequest.Logs -> route(request.caller, request.executionId) { adapter ->
            adapter.logs(request.caller, request.executionId, request.tailBytes.coerceIn(1, 256 * 1024))
        }
        is ManagedExecutionRequest.Stop -> route(request.caller, request.executionId) { adapter ->
            adapter.stop(request.caller, request.executionId, request.force)
        }
        ManagedExecutionRequest.EmergencyStop -> emergencyStop(adaptersByRuntime.keys)
        is ManagedExecutionRequest.EmergencyStopRuntimes -> emergencyStop(request.runtimes)
    }.also { result -> updateState(result) }

    private suspend fun list(request: ManagedExecutionRequest.List): ManagedExecutionResult = coroutineScope {
        val executions = request.caller.allowedRuntimes.mapNotNull(adaptersByRuntime::get)
            .map { adapter -> async { adapter.list(request.caller, request.includeStopped) } }
            .awaitAll()
            .flatten()
            .sortedWith(compareBy(ManagedExecutionSnapshot::runtime, ManagedExecutionSnapshot::executionId))
        ManagedExecutionResult.Executions(executions)
    }

    private suspend fun route(
        caller: ManagedExecutionCaller,
        executionId: String,
        block: suspend (ManagedExecutionAdapter) -> ManagedExecutionResult,
    ): ManagedExecutionResult {
        val runtime = parseManagedExecutionRuntime(executionId)
            ?: return ManagedExecutionResult.Error("execution_id_invalid")
        if (runtime !in caller.allowedRuntimes) {
            return ManagedExecutionResult.Error("execution_runtime_not_allowed")
        }
        val adapter = adaptersByRuntime[runtime]
            ?: return ManagedExecutionResult.Error("execution_runtime_unavailable")
        return block(adapter)
    }

    private suspend fun emergencyStop(
        runtimes: Set<ManagedExecutionRuntime>,
    ): ManagedExecutionResult = coroutineScope {
        val snapshots = runtimes.mapNotNull(adaptersByRuntime::get)
            .map { adapter -> async { adapter.emergencyStop() } }
            .awaitAll()
            .flatten()
        ManagedExecutionResult.Executions(snapshots)
    }

    private suspend fun updateState(result: ManagedExecutionResult) {
        val updates = when (result) {
            is ManagedExecutionResult.Executions -> result.executions
            is ManagedExecutionResult.Snapshot -> listOf(result.execution)
            is ManagedExecutionResult.Logs -> listOf(result.execution)
            is ManagedExecutionResult.Stopped -> listOf(result.execution)
            is ManagedExecutionResult.Error -> emptyList()
        }
        if (updates.isEmpty()) return
        stateMutex.withLock {
            val merged = mutableState.value.executions.associateByTo(linkedMapOf()) { it.executionId }
            updates.forEach { merged[it.executionId] = it }
            mutableState.value = ManagedExecutionState(merged.values.toList())
        }
    }
}

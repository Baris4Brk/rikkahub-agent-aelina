package me.rerere.rikkahub.memory.dreaming.diagnostics

import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.DreamRun
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeState
import me.rerere.rikkahub.memory.dreaming.store.DreamObserverStore

/** Payload-free read seam; UI code never needs a DAO or mutation-capable Observer store. */
interface DreamObserverDiagnostics {
    suspend fun readScope(
        scopeId: DreamScopeId,
        recentRunLimit: Int = DEFAULT_DIAGNOSTIC_RUN_LIMIT,
    ): DreamObserverScopeDiagnostic?

    suspend fun readDirtyScopes(
        scopeLimit: Int = DEFAULT_DIAGNOSTIC_SCOPE_LIMIT,
        recentRunLimit: Int = DEFAULT_DIAGNOSTIC_RUN_LIMIT,
    ): List<DreamObserverScopeDiagnostic>
}

class StoreDreamObserverDiagnostics(
    private val store: DreamObserverStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : DreamObserverDiagnostics {
    override suspend fun readScope(
        scopeId: DreamScopeId,
        recentRunLimit: Int,
    ): DreamObserverScopeDiagnostic? {
        val state = store.readScopeState(scopeId) ?: return null
        return state.toDiagnostic(
            runs = store.listRecentRuns(scopeId, recentRunLimit),
            nowMs = checkedNow(),
        )
    }

    override suspend fun readDirtyScopes(
        scopeLimit: Int,
        recentRunLimit: Int,
    ): List<DreamObserverScopeDiagnostic> {
        val now = checkedNow()
        return store.findDirtyScopes(scopeLimit).map { state ->
            state.toDiagnostic(
                runs = store.listRecentRuns(state.scopeId, recentRunLimit),
                nowMs = now,
            )
        }
    }

    private fun checkedNow(): Long = nowMs().also { value ->
        require(value >= 0L) { "dream_diagnostics_clock_negative" }
    }
}

data class DreamObserverScopeDiagnostic(
    val scopeId: DreamScopeId,
    val status: DreamObserverScopeStatus,
    val memoryEpoch: Long,
    val observerCheckpointEpoch: Long,
    val pendingEpochCount: Long,
    val activeRunId: String?,
    val activeRunLeaseUntilMs: Long?,
    val updatedAtMs: Long,
    val lastReasonCode: AuthorityChangeReason?,
    val recentRuns: List<DreamObserverRunDiagnostic>,
)

enum class DreamObserverScopeStatus {
    CLEAN,
    DIRTY,
    RUNNING,
    STALE_LEASE,
}

data class DreamObserverRunDiagnostic(
    val runId: String,
    val mode: DreamRunMode,
    val status: DreamRunStatus,
    val baseMemoryEpoch: Long,
    val baseObserverCheckpointEpoch: Long,
    val checkpointEpoch: Long,
    val attempt: Int,
    val leaseUntilMs: Long?,
    val failureCode: DreamRunFailureCode?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val finishedAtMs: Long?,
)

private fun DreamScopeState.toDiagnostic(
    runs: List<DreamRun>,
    nowMs: Long,
): DreamObserverScopeDiagnostic {
    val status = when {
        activeRunId != null && activeRunLeaseUntilMs!! <= nowMs ->
            DreamObserverScopeStatus.STALE_LEASE
        activeRunId != null -> DreamObserverScopeStatus.RUNNING
        memoryEpoch > observerCheckpointEpoch -> DreamObserverScopeStatus.DIRTY
        else -> DreamObserverScopeStatus.CLEAN
    }
    return DreamObserverScopeDiagnostic(
        scopeId = scopeId,
        status = status,
        memoryEpoch = memoryEpoch,
        observerCheckpointEpoch = observerCheckpointEpoch,
        pendingEpochCount = memoryEpoch - observerCheckpointEpoch,
        activeRunId = activeRunId,
        activeRunLeaseUntilMs = activeRunLeaseUntilMs,
        updatedAtMs = updatedAtMs,
        lastReasonCode = lastReasonCode,
        recentRuns = runs.map { run -> run.toDiagnostic() },
    )
}

private fun DreamRun.toDiagnostic() = DreamObserverRunDiagnostic(
    runId = runId,
    mode = mode,
    status = status,
    baseMemoryEpoch = baseMemoryEpoch,
    baseObserverCheckpointEpoch = baseObserverCheckpointEpoch,
    checkpointEpoch = checkpointEpoch,
    attempt = attempt,
    leaseUntilMs = leaseUntilMs,
    failureCode = failureCode,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    finishedAtMs = finishedAtMs,
)

const val DEFAULT_DIAGNOSTIC_SCOPE_LIMIT = 100
const val DEFAULT_DIAGNOSTIC_RUN_LIMIT = 20

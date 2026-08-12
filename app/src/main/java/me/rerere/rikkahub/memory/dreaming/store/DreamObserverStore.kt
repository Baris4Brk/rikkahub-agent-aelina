package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReceipt
import me.rerere.rikkahub.memory.dreaming.model.DreamRun
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeState
import me.rerere.rikkahub.memory.dreaming.model.MAX_DREAM_RUN_LEASE_DURATION_MS
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import me.rerere.rikkahub.memory.dreaming.model.requireDreamLeaseOwner
import me.rerere.rikkahub.memory.dreaming.temporal.strictZoneOrNull

const val MAX_DIRTY_DREAM_SCOPES_PER_SCAN = 512
const val MAX_RECENT_DREAM_RUNS_PER_SCOPE = 200

data class RecordAuthorityChangesRequest(
    val changes: List<AuthorityChange>,
    val createdAtMs: Long,
) {
    init {
        require(createdAtMs >= 0L)
    }
}

data class ScopeEpochAdvance(
    val scopeId: DreamScopeId,
    val previousEpoch: Long,
    val memoryEpoch: Long,
) {
    init {
        require(previousEpoch >= 0L && memoryEpoch == previousEpoch + 1L)
    }
}

data class AuthorityMutationReceipt(
    val scopeEpochs: List<ScopeEpochAdvance>,
    val changes: List<AuthorityChangeReceipt>,
) {
    val changed: Boolean
        get() = changes.isNotEmpty()
}

data class CreateDreamRunRequest(
    val runId: String,
    val scopeId: DreamScopeId,
    val mode: DreamRunMode,
    val createdAtMs: Long,
) {
    init {
        requireCanonicalDreamRunId(runId)
        require(createdAtMs >= 0L)
    }
}

sealed interface CreateDreamRunResult {
    data class Created(val run: DreamRun) : CreateDreamRunResult

    /** Idempotent replay of the exact same run identity, scope, and mode. */
    data class Existing(val run: DreamRun) : CreateDreamRunResult

    data class Rejected(val reason: DreamStoreRejection) : CreateDreamRunResult
}

/**
 * Atomically creates (or finds) a pending run and acquires its first lease.
 *
 * The Observer Worker uses this boundary instead of committing a PENDING row before WorkManager
 * starts. A process death can therefore leave either no run or one recoverable RUNNING run, never
 * a newly orphaned PENDING row that would pin the prune watermark forever.
 */
data class StartDreamRunRequest(
    val runId: String,
    val scopeId: DreamScopeId,
    val mode: DreamRunMode,
    val leaseOwner: String,
    val nowMs: Long,
    val leaseDurationMs: Long,
    /** Null for Observer runs; synthesis freezes one strict IANA zone on first claim. */
    val sourceTimezoneId: String? = null,
) {
    init {
        requireCanonicalDreamRunId(runId)
        requireDreamLeaseOwner(leaseOwner)
        require(nowMs >= 0L)
        require(leaseDurationMs in 1L..MAX_DREAM_RUN_LEASE_DURATION_MS)
        require(nowMs <= Long.MAX_VALUE - leaseDurationMs)
        require(sourceTimezoneId == null || strictZoneOrNull(sourceTimezoneId) != null)
    }

    val requestedLeaseUntilMs: Long
        get() = nowMs + leaseDurationMs
}

sealed interface StartDreamRunResult {
    /** A new or legacy pending row was claimed in this atomic call. */
    data class Started(val run: DreamRun) : StartDreamRunResult

    /** Idempotent WorkManager replay resumed the same live run owned by the same worker identity. */
    data class Resumed(val run: DreamRun) : StartDreamRunResult

    /** The same durable run ID already reached a terminal state. It is never reopened. */
    data class Terminal(val run: DreamRun) : StartDreamRunResult

    data class Rejected(val reason: DreamStoreRejection) : StartDreamRunResult
}

data class DreamRunLeaseRequest(
    val runId: String,
    val scopeId: DreamScopeId,
    val leaseOwner: String,
    val nowMs: Long,
    val leaseDurationMs: Long,
) {
    init {
        requireCanonicalDreamRunId(runId)
        requireDreamLeaseOwner(leaseOwner)
        require(nowMs >= 0L)
        require(leaseDurationMs in 1L..MAX_DREAM_RUN_LEASE_DURATION_MS)
        require(nowMs <= Long.MAX_VALUE - leaseDurationMs)
    }

    val requestedLeaseUntilMs: Long
        get() = nowMs + leaseDurationMs
}

sealed interface ClaimDreamRunResult {
    data class Claimed(val run: DreamRun) : ClaimDreamRunResult

    data class Rejected(val reason: DreamStoreRejection) : ClaimDreamRunResult
}

sealed interface HeartbeatDreamRunResult {
    data class Extended(val run: DreamRun) : HeartbeatDreamRunResult

    data class Rejected(val reason: DreamStoreRejection) : HeartbeatDreamRunResult
}

data class DreamRunOwnerRequest(
    val runId: String,
    val scopeId: DreamScopeId,
    val leaseOwner: String,
    val nowMs: Long,
) {
    init {
        requireCanonicalDreamRunId(runId)
        requireDreamLeaseOwner(leaseOwner)
        require(nowMs >= 0L)
    }
}

data class ObserverReplayWindow(
    val run: DreamRun,
    val scopeState: DreamScopeState,
    val fromEpochExclusive: Long,
    val throughEpochInclusive: Long,
    val changes: List<AuthorityChangeReceipt>,
) {
    init {
        require(fromEpochExclusive >= 0L)
        require(throughEpochInclusive >= fromEpochExclusive)
        require(run.scopeId == scopeState.scopeId)
        require(run.baseObserverCheckpointEpoch == fromEpochExclusive)
        require(run.baseMemoryEpoch == throughEpochInclusive)
        require(changes.all {
            it.scopeId == run.scopeId &&
                it.memoryEpoch > fromEpochExclusive &&
                it.memoryEpoch <= throughEpochInclusive
        })
    }
}

sealed interface ReadObserverReplayResult {
    data class Ready(val replay: ObserverReplayWindow) : ReadObserverReplayResult

    data class Rejected(val reason: DreamStoreRejection) : ReadObserverReplayResult
}

enum class DreamRunFinishOutcome {
    SUCCEEDED,
    CANCELLED,
    DISCARDED,
}

data class FinishDreamRunRequest(
    val runId: String,
    val scopeId: DreamScopeId,
    val leaseOwner: String,
    val outcome: DreamRunFinishOutcome,
    val nowMs: Long,
) {
    init {
        requireCanonicalDreamRunId(runId)
        requireDreamLeaseOwner(leaseOwner)
        require(nowMs >= 0L)
    }
}

data class FailDreamRunRequest(
    val runId: String,
    val scopeId: DreamScopeId,
    val leaseOwner: String,
    val failureCode: DreamRunFailureCode,
    val nowMs: Long,
) {
    init {
        requireCanonicalDreamRunId(runId)
        requireDreamLeaseOwner(leaseOwner)
        require(nowMs >= 0L)
    }
}

sealed interface FinishDreamRunResult {
    data class Finished(
        val run: DreamRun,
        val scopeState: DreamScopeState,
    ) : FinishDreamRunResult

    data class Rejected(val reason: DreamStoreRejection) : FinishDreamRunResult
}

data class RecoverExpiredDreamRunsRequest(val nowMs: Long) {
    init {
        require(nowMs >= 0L)
    }
}

data class RecoverExpiredDreamRunsResult(
    val recoveredRuns: List<DreamRun>,
)

data class PruneObserverChangesRequest(
    val scopeId: DreamScopeId,
    val expectedMemoryEpoch: Long,
    val expectedObserverCheckpointEpoch: Long,
    val throughEpochInclusive: Long,
) {
    init {
        require(expectedMemoryEpoch >= 0L)
        require(expectedObserverCheckpointEpoch in 0L..expectedMemoryEpoch)
        require(throughEpochInclusive in 0L..expectedObserverCheckpointEpoch)
    }
}

sealed interface PruneObserverChangesResult {
    data class Pruned(
        val deletedCount: Int,
        val throughEpochInclusive: Long,
    ) : PruneObserverChangesResult

    data class Rejected(val reason: DreamStoreRejection) : PruneObserverChangesResult
}

enum class DreamStoreRejection {
    NOT_FOUND,
    SCOPE_MISMATCH,
    RUN_ID_CONFLICT,
    STATUS_MISMATCH,
    ACTIVE_RUN_CONFLICT,
    OWNER_MISMATCH,
    LEASE_EXPIRED,
    MEMORY_EPOCH_CONFLICT,
    OBSERVER_CHECKPOINT_CONFLICT,
    JOURNAL_GAP,
    PRUNE_WATERMARK_CONFLICT,
    CLOCK_ROLLBACK,
}

/**
 * Durable Observer boundary shared by Room and deterministic JVM fakes.
 *
 * No method performs model/network work. Implementations must serialize each mutation atomically.
 * [recordAuthorityChangesInCurrentTransaction] is intentionally named: the Room implementation is
 * called from the same outer transaction as the corresponding authority mutation, never after it.
 */
interface DreamObserverStore {
    suspend fun ensureScopeState(scopeId: DreamScopeId, nowMs: Long): DreamScopeState

    suspend fun readScopeState(scopeId: DreamScopeId): DreamScopeState?

    /** Stable oldest-first scan used by startup and periodic recovery. */
    suspend fun findDirtyScopes(limit: Int): List<DreamScopeState>

    suspend fun recordAuthorityChangesInCurrentTransaction(
        request: RecordAuthorityChangesRequest,
    ): AuthorityMutationReceipt

    suspend fun createPendingRun(request: CreateDreamRunRequest): CreateDreamRunResult

    /** Production Observer workers use this; separate create/claim remain as low-level contracts. */
    suspend fun startRun(request: StartDreamRunRequest): StartDreamRunResult

    suspend fun readRun(runId: String): DreamRun?

    /** Newest-first payload-free audit projection for diagnostics. */
    suspend fun listRecentRuns(scopeId: DreamScopeId, limit: Int): List<DreamRun>

    /** Claim atomically refreshes all three run epoch fields from the then-current scope state. */
    suspend fun claim(request: DreamRunLeaseRequest): ClaimDreamRunResult

    suspend fun heartbeat(request: DreamRunLeaseRequest): HeartbeatDreamRunResult

    /** Reads every complete epoch in `(baseObserverCheckpointEpoch, baseMemoryEpoch]`. */
    suspend fun readReplay(request: DreamRunOwnerRequest): ReadObserverReplayResult

    /**
     * SUCCEEDED performs the observer checkpoint CAS against both base epochs. A CAS or journal-gap
     * failure is committed as terminal CONFLICT and releases the lease; it never advances state.
     */
    suspend fun finish(request: FinishDreamRunRequest): FinishDreamRunResult

    suspend fun fail(request: FailDreamRunRequest): FinishDreamRunResult

    suspend fun recoverExpiredRuns(
        request: RecoverExpiredDreamRunsRequest,
    ): RecoverExpiredDreamRunsResult

    /** Pruning is inclusive and guarded by state CAS plus every pending/running run watermark. */
    suspend fun pruneChanges(
        request: PruneObserverChangesRequest,
    ): PruneObserverChangesResult
}

/** Final input wins for one entity; output order is stable and independent of map iteration. */
fun coalesceAuthorityChanges(changes: List<AuthorityChange>): List<AuthorityChange> =
    changes
        .associateBy { Triple(it.scopeId, it.entityKind, it.entityId) }
        .values
        .sortedWith(compareBy({ it.scopeId.value }, { it.entityKind.name }, { it.entityId }))

/** A mixed-reason transaction never pretends that one entity's reason describes the whole scope. */
fun authorityBatchReason(changes: List<AuthorityChange>) =
    changes.map { it.reasonCode }.distinct().singleOrNull()
        ?: me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason.MAINTENANCE

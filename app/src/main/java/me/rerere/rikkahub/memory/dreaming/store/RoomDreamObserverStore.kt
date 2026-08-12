package me.rerere.rikkahub.memory.dreaming.store

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.DreamDao
import me.rerere.rikkahub.data.db.entity.DreamRunEntity
import me.rerere.rikkahub.data.db.entity.MemoryScopeChangeEntity
import me.rerere.rikkahub.data.db.entity.MemoryScopeStateEntity
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReceipt
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.DreamObserverStorageCodec
import me.rerere.rikkahub.memory.dreaming.model.DreamRun
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeState

/**
 * Room-backed observer ledger.
 *
 * [MemoryScopeStateEntity] is the only lease authority. Every run-row lease update is an audit
 * mirror written in the same Room transaction and is rolled back if either side loses its fence.
 */
class RoomDreamObserverStore(
    private val database: AppDatabase,
    private val dreamDao: DreamDao,
) : DreamObserverStore {
    override suspend fun ensureScopeState(
        scopeId: DreamScopeId,
        nowMs: Long,
    ): DreamScopeState {
        require(nowMs >= 0L)
        return database.withTransaction {
            ensureScopeStateInCurrentTransaction(scopeId, nowMs).toModel()
        }
    }

    override suspend fun readScopeState(scopeId: DreamScopeId): DreamScopeState? =
        database.withTransaction {
            dreamDao.getScopeState(scopeId.value)?.toModel()
        }

    override suspend fun findDirtyScopes(limit: Int): List<DreamScopeState> {
        require(limit in 1..MAX_DIRTY_DREAM_SCOPES_PER_SCAN)
        return database.withTransaction {
            dreamDao.findDirtyScopes(limit).map(MemoryScopeStateEntity::toModel)
        }
    }

    override suspend fun recordAuthorityChangesInCurrentTransaction(
        request: RecordAuthorityChangesRequest,
    ): AuthorityMutationReceipt {
        check(database.inTransaction()) {
            "dream_authority_change_transaction_required"
        }
        val changes = coalesceAuthorityChanges(request.changes)
        if (changes.isEmpty()) {
            return AuthorityMutationReceipt(scopeEpochs = emptyList(), changes = emptyList())
        }

        val scopeEpochs = arrayListOf<ScopeEpochAdvance>()
        val persistedReceipts = arrayListOf<AuthorityChangeReceipt>()
        changes.groupBy(AuthorityChange::scopeId)
            .toSortedMap()
            .forEach { (scopeId, scopeChanges) ->
                val before = ensureScopeStateInCurrentTransaction(scopeId, request.createdAtMs)
                check(before.memoryEpoch < Long.MAX_VALUE) { "dream_memory_epoch_exhausted" }
                val nextEpoch = before.memoryEpoch + 1L
                val reason = authorityBatchReason(scopeChanges)
                check(
                    dreamDao.bumpMemoryEpoch(
                        scopeId = scopeId.value,
                        expectedMemoryEpoch = before.memoryEpoch,
                        reasonCode = reason.name,
                        nowMs = request.createdAtMs,
                    ) == 1,
                ) { "dream_memory_epoch_cas_lost" }

                scopeChanges
                    .map { change ->
                        change.toEntity(
                            memoryEpoch = nextEpoch,
                            createdAtMs = request.createdAtMs,
                        )
                    }
                    .chunked(AUTHORITY_RECEIPT_INSERT_CHUNK_SIZE)
                    .forEach { chunk -> dreamDao.insertChanges(chunk) }
                val inserted = dreamDao.listChanges(
                    scopeId = scopeId.value,
                    afterExclusiveEpoch = before.memoryEpoch,
                    throughInclusiveEpoch = nextEpoch,
                )
                check(inserted.size == scopeChanges.size) {
                    "dream_authority_receipt_count_mismatch"
                }
                scopeEpochs += ScopeEpochAdvance(
                    scopeId = scopeId,
                    previousEpoch = before.memoryEpoch,
                    memoryEpoch = nextEpoch,
                )
                persistedReceipts += inserted.map(MemoryScopeChangeEntity::toModel)
            }
        return AuthorityMutationReceipt(
            scopeEpochs = scopeEpochs,
            changes = persistedReceipts,
        )
    }

    override suspend fun createPendingRun(
        request: CreateDreamRunRequest,
    ): CreateDreamRunResult = database.withTransaction {
        val existing = dreamDao.getRunById(request.runId)
        if (existing != null) {
            val run = existing.toModel()
            return@withTransaction when {
                run.scopeId != request.scopeId || run.mode != request.mode ->
                    CreateDreamRunResult.Rejected(
                        DreamStoreRejection.RUN_ID_CONFLICT,
                    )

                else -> CreateDreamRunResult.Existing(run)
            }
        }

        val state = ensureScopeStateInCurrentTransaction(request.scopeId, request.createdAtMs)
        val entity = DreamRunEntity(
            runId = request.runId,
            scopeId = request.scopeId.value,
            mode = request.mode.name,
            status = DreamRunStatus.PENDING.name,
            baseMemoryEpoch = state.memoryEpoch,
            baseObserverCheckpointEpoch = state.observerCheckpointEpoch,
            baseDreamRevision = state.dreamStateRevision,
            checkpointEpoch = state.observerCheckpointEpoch,
            createdAtMs = request.createdAtMs,
            updatedAtMs = request.createdAtMs,
        )
        dreamDao.insertRun(entity)
        CreateDreamRunResult.Created(entity.toModel())
    }

    override suspend fun startRun(
        request: StartDreamRunRequest,
    ): StartDreamRunResult = database.withTransaction {
        val existing = dreamDao.getRunById(request.runId)?.toModel()
        if (existing != null) {
            val timezoneConflict = if (existing.status == DreamRunStatus.PENDING) {
                existing.sourceTimezoneId != null &&
                    existing.sourceTimezoneId != request.sourceTimezoneId
            } else {
                existing.sourceTimezoneId != request.sourceTimezoneId
            }
            if (existing.scopeId != request.scopeId || existing.mode != request.mode ||
                timezoneConflict
            ) {
                return@withTransaction StartDreamRunResult.Rejected(
                    DreamStoreRejection.RUN_ID_CONFLICT,
                )
            }
            return@withTransaction when (existing.status) {
                DreamRunStatus.PENDING -> when (
                    val claimed = claimInTransaction(
                        request.toLeaseRequest(),
                        request.sourceTimezoneId,
                    )
                ) {
                    is ClaimDreamRunResult.Claimed -> StartDreamRunResult.Started(claimed.run)
                    is ClaimDreamRunResult.Rejected ->
                        StartDreamRunResult.Rejected(claimed.reason)
                }

                DreamRunStatus.RUNNING -> {
                    if (existing.leaseOwner != request.leaseOwner) {
                        StartDreamRunResult.Rejected(DreamStoreRejection.OWNER_MISMATCH)
                    } else {
                        when (val heartbeat = heartbeat(request.toLeaseRequest())) {
                            is HeartbeatDreamRunResult.Extended ->
                                StartDreamRunResult.Resumed(heartbeat.run)
                            is HeartbeatDreamRunResult.Rejected ->
                                StartDreamRunResult.Rejected(heartbeat.reason)
                        }
                    }
                }

                else -> StartDreamRunResult.Terminal(existing)
            }
        }

        // Check the scope lease before inserting. If another run is live, this call returns with
        // no new PENDING row; a failed first claim can therefore never pin pruning.
        val state = ensureScopeStateInCurrentTransaction(request.scopeId, request.nowMs).toModel()
        if (state.activeRunId != null) {
            return@withTransaction StartDreamRunResult.Rejected(
                if (state.activeRunLeaseUntilMs!! <= request.nowMs) {
                    DreamStoreRejection.LEASE_EXPIRED
                } else {
                    DreamStoreRejection.ACTIVE_RUN_CONFLICT
                },
            )
        }
        val created = createPendingRun(
            CreateDreamRunRequest(
                runId = request.runId,
                scopeId = request.scopeId,
                mode = request.mode,
                createdAtMs = request.nowMs,
            ),
        )
        check(created is CreateDreamRunResult.Created) {
            "dream_atomic_start_create_lost"
        }
        val claimed = claimInTransaction(request.toLeaseRequest(), request.sourceTimezoneId)
        check(claimed is ClaimDreamRunResult.Claimed) {
            "dream_atomic_start_claim_lost"
        }
        StartDreamRunResult.Started(claimed.run)
    }

    override suspend fun readRun(runId: String): DreamRun? = database.withTransaction {
        dreamDao.getRunById(runId)?.toModel()
    }

    override suspend fun listRecentRuns(
        scopeId: DreamScopeId,
        limit: Int,
    ): List<DreamRun> {
        require(limit in 1..MAX_RECENT_DREAM_RUNS_PER_SCOPE)
        return database.withTransaction {
            dreamDao.listRecentRuns(scopeId.value, limit).map(DreamRunEntity::toModel)
        }
    }

    override suspend fun claim(request: DreamRunLeaseRequest): ClaimDreamRunResult =
        claimInTransaction(request, sourceTimezoneId = null)

    private suspend fun claimInTransaction(
        request: DreamRunLeaseRequest,
        sourceTimezoneId: String?,
    ): ClaimDreamRunResult = database.withTransaction {
            val run = dreamDao.getRunById(request.runId)?.toModel()
                ?: return@withTransaction ClaimDreamRunResult.Rejected(
                    DreamStoreRejection.NOT_FOUND,
                )
            if (run.scopeId != request.scopeId) {
                return@withTransaction ClaimDreamRunResult.Rejected(
                    DreamStoreRejection.SCOPE_MISMATCH,
                )
            }
            if (run.status != DreamRunStatus.PENDING) {
                return@withTransaction ClaimDreamRunResult.Rejected(
                    DreamStoreRejection.STATUS_MISMATCH,
                )
            }
            if (run.sourceTimezoneId != null && run.sourceTimezoneId != sourceTimezoneId) {
                return@withTransaction ClaimDreamRunResult.Rejected(
                    DreamStoreRejection.RUN_ID_CONFLICT,
                )
            }
            if (request.nowMs < run.createdAtMs) {
                return@withTransaction ClaimDreamRunResult.Rejected(
                    DreamStoreRejection.CLOCK_ROLLBACK,
                )
            }
            val stateEntity = dreamDao.getScopeState(request.scopeId.value)
                ?: return@withTransaction ClaimDreamRunResult.Rejected(
                    DreamStoreRejection.NOT_FOUND,
                )
            val state = stateEntity.toModel()
            if (state.activeRunId != null) {
                return@withTransaction ClaimDreamRunResult.Rejected(
                    if (state.activeRunLeaseUntilMs!! <= request.nowMs) {
                        DreamStoreRejection.LEASE_EXPIRED
                    } else {
                        DreamStoreRejection.ACTIVE_RUN_CONFLICT
                    },
                )
            }
            if (
                dreamDao.acquireScopeLease(
                    scopeId = request.scopeId.value,
                    runId = request.runId,
                    leaseUntilMs = request.requestedLeaseUntilMs,
                    nowMs = request.nowMs,
                    reasonCode = AuthorityChangeReason.RUN_CLAIMED.name,
                ) != 1
            ) {
                return@withTransaction ClaimDreamRunResult.Rejected(
                    DreamStoreRejection.ACTIVE_RUN_CONFLICT,
                )
            }
            check(
                dreamDao.startRunMirror(
                    runId = request.runId,
                    scopeId = request.scopeId.value,
                    leaseOwner = request.leaseOwner,
                    leaseUntilMs = request.requestedLeaseUntilMs,
                    baseMemoryEpoch = state.memoryEpoch,
                    baseObserverCheckpointEpoch = state.observerCheckpointEpoch,
                    baseDreamRevision = stateEntity.dreamStateRevision,
                    nowMs = request.nowMs,
                    sourceTimezoneId = sourceTimezoneId,
                ) == 1,
            ) { "dream_run_mirror_claim_cas_lost" }
            ClaimDreamRunResult.Claimed(
                checkNotNull(dreamDao.getRun(request.runId, request.scopeId.value)).toModel(),
            )
        }

    override suspend fun heartbeat(
        request: DreamRunLeaseRequest,
    ): HeartbeatDreamRunResult = database.withTransaction {
        val owned = ownedRunningContext(
            runId = request.runId,
            scopeId = request.scopeId,
            leaseOwner = request.leaseOwner,
            nowMs = request.nowMs,
        )
        if (owned is OwnedRunLookup.Rejected) {
            return@withTransaction HeartbeatDreamRunResult.Rejected(owned.reason)
        }
        owned as OwnedRunLookup.Ready
        val currentUntil = checkNotNull(owned.run.leaseUntilMs)
        if (request.requestedLeaseUntilMs <= currentUntil) {
            return@withTransaction HeartbeatDreamRunResult.Extended(owned.run)
        }
        check(
            dreamDao.heartbeatScopeLease(
                scopeId = request.scopeId.value,
                runId = request.runId,
                leaseUntilMs = request.requestedLeaseUntilMs,
                nowMs = request.nowMs,
                reasonCode = AuthorityChangeReason.RUN_HEARTBEAT.name,
            ) == 1,
        ) { "dream_scope_heartbeat_cas_lost" }
        check(
            dreamDao.heartbeatRunMirror(
                runId = request.runId,
                scopeId = request.scopeId.value,
                leaseOwner = request.leaseOwner,
                leaseUntilMs = request.requestedLeaseUntilMs,
                nowMs = request.nowMs,
            ) == 1,
        ) { "dream_run_heartbeat_cas_lost" }
        HeartbeatDreamRunResult.Extended(
            checkNotNull(dreamDao.getRun(request.runId, request.scopeId.value)).toModel(),
        )
    }

    override suspend fun readReplay(
        request: DreamRunOwnerRequest,
    ): ReadObserverReplayResult = database.withTransaction {
        val owned = ownedRunningContext(
            runId = request.runId,
            scopeId = request.scopeId,
            leaseOwner = request.leaseOwner,
            nowMs = request.nowMs,
        )
        if (owned is OwnedRunLookup.Rejected) {
            return@withTransaction ReadObserverReplayResult.Rejected(owned.reason)
        }
        owned as OwnedRunLookup.Ready
        when {
            owned.state.memoryEpoch != owned.run.baseMemoryEpoch ->
                return@withTransaction ReadObserverReplayResult.Rejected(
                    DreamStoreRejection.MEMORY_EPOCH_CONFLICT,
                )

            owned.state.observerCheckpointEpoch != owned.run.baseObserverCheckpointEpoch ->
                return@withTransaction ReadObserverReplayResult.Rejected(
                    DreamStoreRejection.OBSERVER_CHECKPOINT_CONFLICT,
                )
        }
        val changes = dreamDao.listChanges(
            scopeId = request.scopeId.value,
            afterExclusiveEpoch = owned.run.baseObserverCheckpointEpoch,
            throughInclusiveEpoch = owned.run.baseMemoryEpoch,
        ).map(MemoryScopeChangeEntity::toModel)
        if (!hasEveryEpoch(
                changes = changes,
                fromExclusive = owned.run.baseObserverCheckpointEpoch,
                throughInclusive = owned.run.baseMemoryEpoch,
            )
        ) {
            return@withTransaction ReadObserverReplayResult.Rejected(
                DreamStoreRejection.JOURNAL_GAP,
            )
        }
        check(
            dreamDao.advanceRunCheckpoint(
                runId = owned.run.runId,
                scopeId = owned.run.scopeId.value,
                leaseOwner = checkNotNull(owned.run.leaseOwner),
                expectedCheckpointEpoch = owned.run.checkpointEpoch,
                targetCheckpointEpoch = owned.run.baseMemoryEpoch,
                nowMs = request.nowMs,
            ) == 1,
        ) { "dream_replay_checkpoint_cas_lost" }
        val replayedRun = checkNotNull(
            dreamDao.getRun(owned.run.runId, owned.run.scopeId.value),
        ).toModel()
        ReadObserverReplayResult.Ready(
            ObserverReplayWindow(
                run = replayedRun,
                scopeState = owned.state,
                fromEpochExclusive = replayedRun.baseObserverCheckpointEpoch,
                throughEpochInclusive = replayedRun.baseMemoryEpoch,
                changes = changes,
            ),
        )
    }

    override suspend fun finish(request: FinishDreamRunRequest): FinishDreamRunResult =
        database.withTransaction {
            val owned = ownedRunningContext(
                runId = request.runId,
                scopeId = request.scopeId,
                leaseOwner = request.leaseOwner,
                nowMs = request.nowMs,
            )
            if (owned is OwnedRunLookup.Rejected) {
                return@withTransaction FinishDreamRunResult.Rejected(owned.reason)
            }
            owned as OwnedRunLookup.Ready
            if (request.outcome != DreamRunFinishOutcome.SUCCEEDED) {
                val status = when (request.outcome) {
                    DreamRunFinishOutcome.CANCELLED -> DreamRunStatus.CANCELLED
                    DreamRunFinishOutcome.DISCARDED -> DreamRunStatus.DISCARDED
                    DreamRunFinishOutcome.SUCCEEDED -> error("unreachable")
                }
                val failureCode = when (request.outcome) {
                    DreamRunFinishOutcome.CANCELLED ->
                        DreamRunFailureCode.CANCELLED_BY_POLICY
                    DreamRunFinishOutcome.DISCARDED -> DreamRunFailureCode.FEATURE_DISABLED
                    DreamRunFinishOutcome.SUCCEEDED -> error("unreachable")
                }
                return@withTransaction finishOwnedRun(
                    owned = owned,
                    terminalStatus = status,
                    failureCode = failureCode,
                    nowMs = request.nowMs,
                )
            }

            val conflict = successfulFinishConflict(owned)
            if (conflict != null) {
                return@withTransaction finishOwnedRun(
                    owned = owned,
                    terminalStatus = DreamRunStatus.CONFLICT,
                    failureCode = conflict,
                    nowMs = request.nowMs,
                )
            }
            val changes = dreamDao.listChanges(
                scopeId = request.scopeId.value,
                afterExclusiveEpoch = owned.run.baseObserverCheckpointEpoch,
                throughInclusiveEpoch = owned.run.baseMemoryEpoch,
            ).map(MemoryScopeChangeEntity::toModel)
            if (!hasEveryEpoch(
                    changes = changes,
                    fromExclusive = owned.run.baseObserverCheckpointEpoch,
                    throughInclusive = owned.run.baseMemoryEpoch,
                )
            ) {
                return@withTransaction finishOwnedRun(
                    owned = owned,
                    terminalStatus = DreamRunStatus.CONFLICT,
                    failureCode = DreamRunFailureCode.JOURNAL_GAP,
                    nowMs = request.nowMs,
                )
            }

            check(
                dreamDao.finishRunMirror(
                    runId = owned.run.runId,
                    scopeId = owned.run.scopeId.value,
                    leaseOwner = checkNotNull(owned.run.leaseOwner),
                    terminalStatus = DreamRunStatus.SUCCEEDED.name,
                    failureCode = null,
                    nowMs = request.nowMs,
                ) == 1,
            ) { "dream_run_finish_cas_lost" }
            check(
                dreamDao.advanceObserverCheckpoint(
                    scopeId = owned.run.scopeId.value,
                    runId = owned.run.runId,
                    expectedMemoryEpoch = owned.run.baseMemoryEpoch,
                    expectedCheckpointEpoch = owned.run.baseObserverCheckpointEpoch,
                    targetCheckpointEpoch = owned.run.baseMemoryEpoch,
                    reasonCode = AuthorityChangeReason.OBSERVER_CHECKPOINT_ADVANCED.name,
                    nowMs = request.nowMs,
                ) == 1,
            ) { "dream_observer_checkpoint_cas_lost" }
            check(
                dreamDao.releaseScopeLease(
                    scopeId = owned.run.scopeId.value,
                    runId = owned.run.runId,
                    reasonCode = AuthorityChangeReason.OBSERVER_CHECKPOINT_ADVANCED.name,
                    nowMs = request.nowMs,
                ) == 1,
            ) { "dream_scope_release_cas_lost" }
            finishedResult(owned.run.runId, owned.run.scopeId)
        }

    override suspend fun fail(request: FailDreamRunRequest): FinishDreamRunResult =
        database.withTransaction {
            val owned = ownedRunningContext(
                runId = request.runId,
                scopeId = request.scopeId,
                leaseOwner = request.leaseOwner,
                nowMs = request.nowMs,
            )
            if (owned is OwnedRunLookup.Rejected) {
                return@withTransaction FinishDreamRunResult.Rejected(owned.reason)
            }
            finishOwnedRun(
                owned = owned as OwnedRunLookup.Ready,
                terminalStatus = DreamRunStatus.FAILED,
                failureCode = request.failureCode,
                nowMs = request.nowMs,
            )
        }

    override suspend fun recoverExpiredRuns(
        request: RecoverExpiredDreamRunsRequest,
    ): RecoverExpiredDreamRunsResult = database.withTransaction {
        val expired = dreamDao.findExpiredRunningRuns(request.nowMs, Int.MAX_VALUE)
        if (expired.isEmpty()) {
            // Clear an impossible orphaned state lease as a fail-closed repair, while keeping the
            // state row as the sole authority even when a damaged run mirror is missing.
            dreamDao.recoverExpiredScopeLeases(
                nowMs = request.nowMs,
                reasonCode = AuthorityChangeReason.LEASE_RECOVERED.name,
            )
            return@withTransaction RecoverExpiredDreamRunsResult(emptyList())
        }
        val failedCount = dreamDao.failExpiredRunMirrors(
            nowMs = request.nowMs,
            failureCode = DreamRunFailureCode.LEASE_EXPIRED.name,
        )
        check(failedCount >= expired.size) { "dream_expired_run_recovery_mismatch" }
        // The state row is authoritative, but a damaged/old database may have a later state lease
        // than its run mirror. Clear it by exact run-id fencing after terminalizing the mirror;
        // returning 0 is safe when the state has already moved to a different run.
        expired.forEach { run ->
            dreamDao.releaseScopeLease(
                scopeId = run.scopeId,
                runId = run.runId,
                reasonCode = AuthorityChangeReason.LEASE_RECOVERED.name,
                nowMs = request.nowMs,
            )
        }
        dreamDao.recoverExpiredScopeLeases(
            nowMs = request.nowMs,
            reasonCode = AuthorityChangeReason.LEASE_RECOVERED.name,
        )
        val recovered = expired.map { old ->
            checkNotNull(dreamDao.getRun(old.runId, old.scopeId)).toModel()
        }
        check(recovered.all { it.status == DreamRunStatus.FAILED }) {
            "dream_expired_run_not_terminal"
        }
        RecoverExpiredDreamRunsResult(recovered)
    }

    override suspend fun pruneChanges(
        request: PruneObserverChangesRequest,
    ): PruneObserverChangesResult = database.withTransaction {
        val state = dreamDao.getScopeState(request.scopeId.value)?.toModel()
            ?: return@withTransaction PruneObserverChangesResult.Rejected(
                DreamStoreRejection.NOT_FOUND,
            )
        when {
            state.memoryEpoch != request.expectedMemoryEpoch ->
                return@withTransaction PruneObserverChangesResult.Rejected(
                    DreamStoreRejection.MEMORY_EPOCH_CONFLICT,
                )

            state.observerCheckpointEpoch != request.expectedObserverCheckpointEpoch ->
                return@withTransaction PruneObserverChangesResult.Rejected(
                    DreamStoreRejection.OBSERVER_CHECKPOINT_CONFLICT,
                )
        }
        val safeWatermark = checkNotNull(
            dreamDao.getSafeChangePruneWatermark(request.scopeId.value),
        ) { "dream_prune_scope_state_missing" }
        if (request.throughEpochInclusive > safeWatermark) {
            return@withTransaction PruneObserverChangesResult.Rejected(
                DreamStoreRejection.PRUNE_WATERMARK_CONFLICT,
            )
        }
        val eligibleCount = dreamDao.countChangesThrough(
            scopeId = request.scopeId.value,
            throughInclusiveEpoch = request.throughEpochInclusive,
        )
        val deleted = dreamDao.pruneChangesThrough(
            scopeId = request.scopeId.value,
            throughInclusiveEpoch = request.throughEpochInclusive,
        )
        if (deleted != eligibleCount) {
            return@withTransaction PruneObserverChangesResult.Rejected(
                DreamStoreRejection.PRUNE_WATERMARK_CONFLICT,
            )
        }
        PruneObserverChangesResult.Pruned(
            deletedCount = deleted,
            throughEpochInclusive = request.throughEpochInclusive,
        )
    }

    private suspend fun ensureScopeStateInCurrentTransaction(
        scopeId: DreamScopeId,
        nowMs: Long,
    ): MemoryScopeStateEntity {
        check(database.inTransaction()) { "dream_scope_state_transaction_required" }
        dreamDao.insertScopeStateIfAbsent(
            MemoryScopeStateEntity(scopeId = scopeId.value, updatedAtMs = nowMs),
        )
        return checkNotNull(dreamDao.getScopeState(scopeId.value)) {
            "dream_scope_state_insert_missing"
        }
    }

    private suspend fun ownedRunningContext(
        runId: String,
        scopeId: DreamScopeId,
        leaseOwner: String,
        nowMs: Long,
    ): OwnedRunLookup {
        val run = dreamDao.getRunById(runId)?.toModel()
            ?: return OwnedRunLookup.Rejected(DreamStoreRejection.NOT_FOUND)
        if (run.scopeId != scopeId) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.SCOPE_MISMATCH)
        }
        if (run.status != DreamRunStatus.RUNNING) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.STATUS_MISMATCH)
        }
        if (run.leaseOwner != leaseOwner) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.OWNER_MISMATCH)
        }
        if (run.leaseUntilMs == null || run.leaseUntilMs <= nowMs) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.LEASE_EXPIRED)
        }
        if (nowMs < run.updatedAtMs) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.CLOCK_ROLLBACK)
        }
        val state = dreamDao.getScopeState(scopeId.value)?.toModel()
            ?: return OwnedRunLookup.Rejected(DreamStoreRejection.NOT_FOUND)
        if (state.activeRunId != runId) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.ACTIVE_RUN_CONFLICT)
        }
        if (state.activeRunLeaseUntilMs == null || state.activeRunLeaseUntilMs <= nowMs) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.LEASE_EXPIRED)
        }
        if (state.activeRunLeaseUntilMs != run.leaseUntilMs) {
            return OwnedRunLookup.Rejected(DreamStoreRejection.ACTIVE_RUN_CONFLICT)
        }
        return OwnedRunLookup.Ready(run, state)
    }

    private suspend fun finishOwnedRun(
        owned: OwnedRunLookup.Ready,
        terminalStatus: DreamRunStatus,
        failureCode: DreamRunFailureCode,
        nowMs: Long,
    ): FinishDreamRunResult {
        check(terminalStatus.isTerminal && terminalStatus != DreamRunStatus.SUCCEEDED)
        check(
            dreamDao.finishRunMirror(
                runId = owned.run.runId,
                scopeId = owned.run.scopeId.value,
                leaseOwner = checkNotNull(owned.run.leaseOwner),
                terminalStatus = terminalStatus.name,
                failureCode = failureCode.name,
                nowMs = nowMs,
            ) == 1,
        ) { "dream_run_terminal_cas_lost" }
        check(
            dreamDao.releaseScopeLease(
                scopeId = owned.run.scopeId.value,
                runId = owned.run.runId,
                reasonCode = AuthorityChangeReason.RUN_FINISHED.name,
                nowMs = nowMs,
            ) == 1,
        ) { "dream_scope_terminal_release_cas_lost" }
        return finishedResult(owned.run.runId, owned.run.scopeId)
    }

    private suspend fun finishedResult(
        runId: String,
        scopeId: DreamScopeId,
    ): FinishDreamRunResult = FinishDreamRunResult.Finished(
        run = checkNotNull(dreamDao.getRun(runId, scopeId.value)).toModel(),
        scopeState = checkNotNull(dreamDao.getScopeState(scopeId.value)).toModel(),
    )

}

private sealed interface OwnedRunLookup {
    data class Ready(
        val run: DreamRun,
        val state: DreamScopeState,
    ) : OwnedRunLookup

    data class Rejected(val reason: DreamStoreRejection) : OwnedRunLookup
}

private const val AUTHORITY_RECEIPT_INSERT_CHUNK_SIZE = 512

private fun StartDreamRunRequest.toLeaseRequest() = DreamRunLeaseRequest(
    runId = runId,
    scopeId = scopeId,
    leaseOwner = leaseOwner,
    nowMs = nowMs,
    leaseDurationMs = leaseDurationMs,
)

private fun successfulFinishConflict(owned: OwnedRunLookup.Ready): DreamRunFailureCode? = when {
    owned.state.memoryEpoch != owned.run.baseMemoryEpoch ->
        DreamRunFailureCode.MEMORY_EPOCH_CONFLICT

    owned.state.observerCheckpointEpoch != owned.run.baseObserverCheckpointEpoch ->
        DreamRunFailureCode.OBSERVER_CHECKPOINT_CONFLICT

    owned.run.checkpointEpoch != owned.run.baseMemoryEpoch ->
        DreamRunFailureCode.JOURNAL_GAP

    else -> null
}

private fun hasEveryEpoch(
    changes: List<AuthorityChangeReceipt>,
    fromExclusive: Long,
    throughInclusive: Long,
): Boolean {
    if (fromExclusive == throughInclusive) return changes.isEmpty()
    var lastCompleteEpoch = fromExclusive
    for (change in changes) {
        if (change.memoryEpoch == lastCompleteEpoch) continue
        if (lastCompleteEpoch == Long.MAX_VALUE) return false
        lastCompleteEpoch += 1L
        if (change.memoryEpoch != lastCompleteEpoch) return false
    }
    return lastCompleteEpoch == throughInclusive
}

private fun AuthorityChange.toEntity(
    memoryEpoch: Long,
    createdAtMs: Long,
) = MemoryScopeChangeEntity(
    scopeId = scopeId.value,
    memoryEpoch = memoryEpoch,
    entityKind = entityKind.name,
    entityId = entityId,
    entityRevision = entityRevision,
    operation = operation.name,
    reasonCode = reasonCode.name,
    createdAtMs = createdAtMs,
)

private fun MemoryScopeStateEntity.toModel(): DreamScopeState = DreamScopeState(
    scopeId = checkNotNull(DreamScopeId.parseOrNull(scopeId)) { "dream_scope_corrupt" },
    memoryEpoch = memoryEpoch,
    observerCheckpointEpoch = observerCheckpointEpoch,
    activeRunId = activeRunId,
    activeRunLeaseUntilMs = activeRunLeaseUntilMs,
    updatedAtMs = updatedAtMs,
    lastReasonCode = lastReasonCode?.let { raw ->
        checkNotNull(DreamObserverStorageCodec.authorityReasonOrNull(raw)) {
            "dream_scope_reason_corrupt"
        }
    },
)

private fun MemoryScopeChangeEntity.toModel(): AuthorityChangeReceipt = AuthorityChangeReceipt(
    changeId = changeId,
    scopeId = checkNotNull(DreamScopeId.parseOrNull(scopeId)) { "dream_change_scope_corrupt" },
    memoryEpoch = memoryEpoch,
    entityKind = checkNotNull(DreamObserverStorageCodec.authorityEntityKindOrNull(entityKind)) {
        "dream_change_kind_corrupt"
    },
    entityId = entityId,
    entityRevision = entityRevision,
    operation = checkNotNull(DreamObserverStorageCodec.authorityOperationOrNull(operation)) {
        "dream_change_operation_corrupt"
    },
    reasonCode = checkNotNull(DreamObserverStorageCodec.authorityReasonOrNull(reasonCode)) {
        "dream_change_reason_corrupt"
    },
    createdAtMs = createdAtMs,
)

private fun DreamRunEntity.toModel(): DreamRun = DreamRun(
    runId = runId,
    scopeId = checkNotNull(DreamScopeId.parseOrNull(scopeId)) { "dream_run_scope_corrupt" },
    mode = checkNotNull(DreamObserverStorageCodec.runModeOrNull(mode)) {
        "dream_run_mode_corrupt"
    },
    status = checkNotNull(DreamObserverStorageCodec.runStatusOrNull(status)) {
        "dream_run_status_corrupt"
    },
    baseMemoryEpoch = baseMemoryEpoch,
    baseObserverCheckpointEpoch = baseObserverCheckpointEpoch,
    attempt = attempt,
    leaseOwner = leaseOwner,
    leaseUntilMs = leaseUntilMs,
    checkpointEpoch = checkpointEpoch,
    failureCode = failureCode?.let { raw ->
        checkNotNull(DreamObserverStorageCodec.runFailureCodeOrNull(raw)) {
            "dream_run_failure_corrupt"
        }
    },
    createdAtMs = createdAtMs,
    startedAtMs = startedAtMs,
    updatedAtMs = updatedAtMs,
    finishedAtMs = finishedAtMs,
    sourceTimezoneId = sourceTimezoneId,
)

package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReceipt
import me.rerere.rikkahub.memory.dreaming.model.DreamRun
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeState

/** Deterministic, process-state-free reference implementation for the Observer store contract. */
class FakeDreamObserverStore : DreamObserverStore {
    private val lock = Any()
    private val scopes = linkedMapOf<DreamScopeId, DreamScopeState>()
    private val runs = linkedMapOf<String, DreamRun>()
    private val journal = mutableListOf<AuthorityChangeReceipt>()
    private var nextChangeId = 1L

    override suspend fun ensureScopeState(scopeId: DreamScopeId, nowMs: Long): DreamScopeState =
        synchronized(lock) {
            require(nowMs >= 0L)
            scopes.getOrPut(scopeId) {
                DreamScopeState(scopeId = scopeId, updatedAtMs = nowMs)
            }
        }

    override suspend fun readScopeState(scopeId: DreamScopeId): DreamScopeState? =
        synchronized(lock) { scopes[scopeId] }

    override suspend fun findDirtyScopes(limit: Int): List<DreamScopeState> = synchronized(lock) {
        require(limit in 1..MAX_DIRTY_DREAM_SCOPES_PER_SCAN)
        scopes.values
            .filter { state -> state.memoryEpoch > state.observerCheckpointEpoch }
            .sortedWith(compareBy({ it.updatedAtMs }, { it.scopeId.value }))
            .take(limit)
    }

    override suspend fun recordAuthorityChangesInCurrentTransaction(
        request: RecordAuthorityChangesRequest,
    ): AuthorityMutationReceipt = synchronized(lock) {
        val coalesced = coalesceAuthorityChanges(request.changes)
        if (coalesced.isEmpty()) return@synchronized AuthorityMutationReceipt(emptyList(), emptyList())

        val byScope = coalesced.groupBy { it.scopeId }.toSortedMap()
        byScope.keys.forEach { scopeId ->
            val currentEpoch = scopes[scopeId]?.memoryEpoch ?: 0L
            check(currentEpoch < Long.MAX_VALUE) { "Memory epoch exhausted" }
        }
        check(nextChangeId <= Long.MAX_VALUE - coalesced.size) { "Change ID exhausted" }

        val advances = mutableListOf<ScopeEpochAdvance>()
        val receipts = mutableListOf<AuthorityChangeReceipt>()
        byScope.forEach { (scopeId, scopeChanges) ->
            val old = scopes[scopeId] ?: DreamScopeState(
                scopeId = scopeId,
                updatedAtMs = request.createdAtMs,
            )
            val newEpoch = old.memoryEpoch + 1L
            val batchReason = authorityBatchReason(scopeChanges)
            val updated = old.copy(
                memoryEpoch = newEpoch,
                updatedAtMs = maxOf(old.updatedAtMs, request.createdAtMs),
                lastReasonCode = batchReason,
            )
            scopes[scopeId] = updated
            advances += ScopeEpochAdvance(scopeId, old.memoryEpoch, newEpoch)
            scopeChanges.forEach { change ->
                receipts += AuthorityChangeReceipt(
                    changeId = nextChangeId++,
                    scopeId = scopeId,
                    memoryEpoch = newEpoch,
                    entityKind = change.entityKind,
                    entityId = change.entityId,
                    entityRevision = change.entityRevision,
                    operation = change.operation,
                    reasonCode = change.reasonCode,
                    createdAtMs = request.createdAtMs,
                )
            }
        }
        journal += receipts
        AuthorityMutationReceipt(advances, receipts)
    }

    override suspend fun createPendingRun(request: CreateDreamRunRequest): CreateDreamRunResult =
        synchronized(lock) {
            runs[request.runId]?.let { existing ->
                return@synchronized when {
                    existing.scopeId != request.scopeId ->
                        CreateDreamRunResult.Rejected(DreamStoreRejection.SCOPE_MISMATCH)
                    existing.mode != request.mode ->
                        CreateDreamRunResult.Rejected(DreamStoreRejection.RUN_ID_CONFLICT)
                    else -> CreateDreamRunResult.Existing(existing)
                }
            }
            val state = scopes.getOrPut(request.scopeId) {
                DreamScopeState(request.scopeId, updatedAtMs = request.createdAtMs)
            }
            val run = DreamRun(
                runId = request.runId,
                scopeId = request.scopeId,
                mode = request.mode,
                status = DreamRunStatus.PENDING,
                baseMemoryEpoch = state.memoryEpoch,
                baseObserverCheckpointEpoch = state.observerCheckpointEpoch,
                attempt = 0,
                leaseOwner = null,
                leaseUntilMs = null,
                checkpointEpoch = state.observerCheckpointEpoch,
                failureCode = null,
                createdAtMs = request.createdAtMs,
                startedAtMs = null,
                updatedAtMs = request.createdAtMs,
                finishedAtMs = null,
            )
            runs[run.runId] = run
            CreateDreamRunResult.Created(run)
        }

    override suspend fun startRun(request: StartDreamRunRequest): StartDreamRunResult =
        synchronized(lock) {
            val existing = runs[request.runId]
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
                    return@synchronized StartDreamRunResult.Rejected(
                        DreamStoreRejection.RUN_ID_CONFLICT,
                    )
                }
                return@synchronized when (existing.status) {
                    DreamRunStatus.PENDING -> startPendingLocked(existing, request)
                    DreamRunStatus.RUNNING -> resumeRunningLocked(request)
                    else -> StartDreamRunResult.Terminal(existing)
                }
            }

            val state = scopes.getOrPut(request.scopeId) {
                DreamScopeState(request.scopeId, updatedAtMs = request.nowMs)
            }
            if (state.activeRunId != null) {
                return@synchronized StartDreamRunResult.Rejected(
                    if (state.activeRunLeaseUntilMs!! <= request.nowMs) {
                        DreamStoreRejection.LEASE_EXPIRED
                    } else {
                        DreamStoreRejection.ACTIVE_RUN_CONFLICT
                    },
                )
            }
            val pending = DreamRun(
                runId = request.runId,
                scopeId = request.scopeId,
                mode = request.mode,
                status = DreamRunStatus.PENDING,
                baseMemoryEpoch = state.memoryEpoch,
                baseObserverCheckpointEpoch = state.observerCheckpointEpoch,
                attempt = 0,
                leaseOwner = null,
                leaseUntilMs = null,
                checkpointEpoch = state.observerCheckpointEpoch,
                failureCode = null,
                createdAtMs = request.nowMs,
                startedAtMs = null,
                updatedAtMs = request.nowMs,
                finishedAtMs = null,
                sourceTimezoneId = request.sourceTimezoneId,
            )
            runs[pending.runId] = pending
            startPendingLocked(pending, request)
        }

    override suspend fun readRun(runId: String): DreamRun? = synchronized(lock) { runs[runId] }

    override suspend fun listRecentRuns(
        scopeId: DreamScopeId,
        limit: Int,
    ): List<DreamRun> = synchronized(lock) {
        require(limit in 1..MAX_RECENT_DREAM_RUNS_PER_SCOPE)
        runs.values
            .filter { run -> run.scopeId == scopeId }
            .sortedWith(compareByDescending<DreamRun> { it.createdAtMs }.thenBy { it.runId })
            .take(limit)
    }

    override suspend fun claim(request: DreamRunLeaseRequest): ClaimDreamRunResult =
        synchronized(lock) {
            val run = runs[request.runId]
                ?: return@synchronized ClaimDreamRunResult.Rejected(DreamStoreRejection.NOT_FOUND)
            if (run.scopeId != request.scopeId) {
                return@synchronized ClaimDreamRunResult.Rejected(DreamStoreRejection.SCOPE_MISMATCH)
            }
            if (run.status != DreamRunStatus.PENDING) {
                return@synchronized ClaimDreamRunResult.Rejected(DreamStoreRejection.STATUS_MISMATCH)
            }
            if (request.nowMs < run.createdAtMs) {
                return@synchronized ClaimDreamRunResult.Rejected(DreamStoreRejection.CLOCK_ROLLBACK)
            }
            val state = scopes[request.scopeId]
                ?: return@synchronized ClaimDreamRunResult.Rejected(DreamStoreRejection.NOT_FOUND)
            if (state.activeRunId != null) {
                return@synchronized ClaimDreamRunResult.Rejected(
                    if (state.activeRunLeaseUntilMs!! <= request.nowMs) {
                        DreamStoreRejection.LEASE_EXPIRED
                    } else {
                        DreamStoreRejection.ACTIVE_RUN_CONFLICT
                    },
                )
            }
            val claimed = run.copy(
                status = DreamRunStatus.RUNNING,
                baseMemoryEpoch = state.memoryEpoch,
                baseObserverCheckpointEpoch = state.observerCheckpointEpoch,
                attempt = run.attempt + 1,
                leaseOwner = request.leaseOwner,
                leaseUntilMs = request.requestedLeaseUntilMs,
                checkpointEpoch = state.observerCheckpointEpoch,
                startedAtMs = request.nowMs,
                updatedAtMs = request.nowMs,
            )
            val claimedState = state.copy(
                activeRunId = run.runId,
                activeRunLeaseUntilMs = request.requestedLeaseUntilMs,
                updatedAtMs = maxOf(state.updatedAtMs, request.nowMs),
                lastReasonCode = AuthorityChangeReason.RUN_CLAIMED,
            )
            runs[run.runId] = claimed
            scopes[state.scopeId] = claimedState
            ClaimDreamRunResult.Claimed(claimed)
        }

    override suspend fun heartbeat(request: DreamRunLeaseRequest): HeartbeatDreamRunResult =
        synchronized(lock) {
            val access = accessRunningRun(
                request.runId,
                request.scopeId,
                request.leaseOwner,
                request.nowMs,
            )
            if (access is RunningAccess.Rejected) {
                return@synchronized HeartbeatDreamRunResult.Rejected(access.reason)
            }
            access as RunningAccess.Granted
            if (request.requestedLeaseUntilMs <= access.run.leaseUntilMs!!) {
                return@synchronized HeartbeatDreamRunResult.Extended(access.run)
            }
            val run = access.run.copy(
                leaseUntilMs = request.requestedLeaseUntilMs,
                updatedAtMs = request.nowMs,
            )
            val state = access.state.copy(
                activeRunLeaseUntilMs = request.requestedLeaseUntilMs,
                updatedAtMs = maxOf(access.state.updatedAtMs, request.nowMs),
                lastReasonCode = AuthorityChangeReason.RUN_HEARTBEAT,
            )
            runs[run.runId] = run
            scopes[state.scopeId] = state
            HeartbeatDreamRunResult.Extended(run)
        }

    override suspend fun readReplay(request: DreamRunOwnerRequest): ReadObserverReplayResult =
        synchronized(lock) {
            val access = accessRunningRun(
                request.runId,
                request.scopeId,
                request.leaseOwner,
                request.nowMs,
            )
            if (access is RunningAccess.Rejected) {
                return@synchronized ReadObserverReplayResult.Rejected(access.reason)
            }
            access as RunningAccess.Granted
            replayRejection(access.run, access.state)?.let { reason ->
                return@synchronized ReadObserverReplayResult.Rejected(reason)
            }
            val changes = replayChanges(access.run)
            val replayed = access.run.copy(
                checkpointEpoch = access.run.baseMemoryEpoch,
                updatedAtMs = request.nowMs,
            )
            runs[replayed.runId] = replayed
            ReadObserverReplayResult.Ready(
                ObserverReplayWindow(
                    run = replayed,
                    scopeState = access.state,
                    fromEpochExclusive = replayed.baseObserverCheckpointEpoch,
                    throughEpochInclusive = replayed.baseMemoryEpoch,
                    changes = changes,
                ),
            )
        }

    override suspend fun finish(request: FinishDreamRunRequest): FinishDreamRunResult =
        synchronized(lock) {
            val access = accessRunningRun(
                request.runId,
                request.scopeId,
                request.leaseOwner,
                request.nowMs,
            )
            if (access is RunningAccess.Rejected) {
                return@synchronized FinishDreamRunResult.Rejected(access.reason)
            }
            access as RunningAccess.Granted
            when (request.outcome) {
                DreamRunFinishOutcome.SUCCEEDED -> finishSuccessOrConflict(access, request.nowMs)
                DreamRunFinishOutcome.CANCELLED -> finishTerminal(
                    access,
                    DreamRunStatus.CANCELLED,
                    DreamRunFailureCode.CANCELLED_BY_POLICY,
                    request.nowMs,
                )

                DreamRunFinishOutcome.DISCARDED -> finishTerminal(
                    access,
                    DreamRunStatus.DISCARDED,
                    DreamRunFailureCode.FEATURE_DISABLED,
                    request.nowMs,
                )
            }
        }

    override suspend fun fail(request: FailDreamRunRequest): FinishDreamRunResult =
        synchronized(lock) {
            val access = accessRunningRun(
                request.runId,
                request.scopeId,
                request.leaseOwner,
                request.nowMs,
            )
            if (access is RunningAccess.Rejected) {
                return@synchronized FinishDreamRunResult.Rejected(access.reason)
            }
            finishTerminal(
                access as RunningAccess.Granted,
                DreamRunStatus.FAILED,
                request.failureCode,
                request.nowMs,
            )
        }

    override suspend fun recoverExpiredRuns(
        request: RecoverExpiredDreamRunsRequest,
    ): RecoverExpiredDreamRunsResult = synchronized(lock) {
        val recovered = runs.values
            .filter { run ->
                run.status == DreamRunStatus.RUNNING && run.leaseUntilMs!! <= request.nowMs
            }
            .sortedBy { it.runId }
            .map { run ->
                val failed = run.copy(
                    status = DreamRunStatus.FAILED,
                    leaseOwner = null,
                    leaseUntilMs = null,
                    failureCode = DreamRunFailureCode.LEASE_EXPIRED,
                    updatedAtMs = maxOf(run.updatedAtMs, request.nowMs),
                    finishedAtMs = maxOf(run.updatedAtMs, request.nowMs),
                )
                runs[run.runId] = failed
                scopes[run.scopeId]?.takeIf { it.activeRunId == run.runId }?.let { state ->
                    scopes[run.scopeId] = state.copy(
                        activeRunId = null,
                        activeRunLeaseUntilMs = null,
                        updatedAtMs = maxOf(state.updatedAtMs, request.nowMs),
                        lastReasonCode = AuthorityChangeReason.LEASE_RECOVERED,
                    )
                }
                failed
            }
        scopes.values.filter { state ->
            state.activeRunId != null && state.activeRunLeaseUntilMs!! <= request.nowMs
        }.forEach { state ->
            scopes[state.scopeId] = state.copy(
                activeRunId = null,
                activeRunLeaseUntilMs = null,
                updatedAtMs = maxOf(state.updatedAtMs, request.nowMs),
                lastReasonCode = AuthorityChangeReason.LEASE_RECOVERED,
            )
        }
        RecoverExpiredDreamRunsResult(recovered)
    }

    override suspend fun pruneChanges(
        request: PruneObserverChangesRequest,
    ): PruneObserverChangesResult = synchronized(lock) {
        val state = scopes[request.scopeId]
            ?: return@synchronized PruneObserverChangesResult.Rejected(DreamStoreRejection.NOT_FOUND)
        if (state.memoryEpoch != request.expectedMemoryEpoch) {
            return@synchronized PruneObserverChangesResult.Rejected(
                DreamStoreRejection.MEMORY_EPOCH_CONFLICT,
            )
        }
        if (state.observerCheckpointEpoch != request.expectedObserverCheckpointEpoch) {
            return@synchronized PruneObserverChangesResult.Rejected(
                DreamStoreRejection.OBSERVER_CHECKPOINT_CONFLICT,
            )
        }
        val protectedRunWatermark = runs.values
            .asSequence()
            .filter { it.scopeId == request.scopeId }
            .filter { it.status == DreamRunStatus.PENDING || it.status == DreamRunStatus.RUNNING }
            .map { it.baseObserverCheckpointEpoch }
            .minOrNull()
        val safeWatermark = minOf(
            state.observerCheckpointEpoch,
            protectedRunWatermark ?: Long.MAX_VALUE,
        )
        if (request.throughEpochInclusive > safeWatermark) {
            return@synchronized PruneObserverChangesResult.Rejected(
                DreamStoreRejection.PRUNE_WATERMARK_CONFLICT,
            )
        }
        val before = journal.size
        journal.removeAll { receipt ->
            receipt.scopeId == request.scopeId &&
                receipt.memoryEpoch <= request.throughEpochInclusive
        }
        PruneObserverChangesResult.Pruned(
            deletedCount = before - journal.size,
            throughEpochInclusive = request.throughEpochInclusive,
        )
    }

    internal fun removeJournalEpochForTest(scopeId: DreamScopeId, epoch: Long) =
        synchronized(lock) {
            journal.removeAll { it.scopeId == scopeId && it.memoryEpoch == epoch }
        }

    internal fun journalForTest(scopeId: DreamScopeId): List<AuthorityChangeReceipt> =
        synchronized(lock) { journal.filter { it.scopeId == scopeId } }

    private fun startPendingLocked(
        run: DreamRun,
        request: StartDreamRunRequest,
    ): StartDreamRunResult {
        if (request.nowMs < run.createdAtMs) {
            return StartDreamRunResult.Rejected(DreamStoreRejection.CLOCK_ROLLBACK)
        }
        if (run.sourceTimezoneId != null && run.sourceTimezoneId != request.sourceTimezoneId) {
            return StartDreamRunResult.Rejected(DreamStoreRejection.RUN_ID_CONFLICT)
        }
        val state = scopes[request.scopeId]
            ?: return StartDreamRunResult.Rejected(DreamStoreRejection.NOT_FOUND)
        if (state.activeRunId != null) {
            return StartDreamRunResult.Rejected(
                if (state.activeRunLeaseUntilMs!! <= request.nowMs) {
                    DreamStoreRejection.LEASE_EXPIRED
                } else {
                    DreamStoreRejection.ACTIVE_RUN_CONFLICT
                },
            )
        }
        val running = run.copy(
            status = DreamRunStatus.RUNNING,
            baseMemoryEpoch = state.memoryEpoch,
            baseObserverCheckpointEpoch = state.observerCheckpointEpoch,
            attempt = run.attempt + 1,
            leaseOwner = request.leaseOwner,
            leaseUntilMs = request.requestedLeaseUntilMs,
            checkpointEpoch = state.observerCheckpointEpoch,
            startedAtMs = request.nowMs,
            updatedAtMs = request.nowMs,
            sourceTimezoneId = run.sourceTimezoneId ?: request.sourceTimezoneId,
        )
        runs[run.runId] = running
        scopes[state.scopeId] = state.copy(
            activeRunId = run.runId,
            activeRunLeaseUntilMs = request.requestedLeaseUntilMs,
            updatedAtMs = maxOf(state.updatedAtMs, request.nowMs),
            lastReasonCode = AuthorityChangeReason.RUN_CLAIMED,
        )
        return StartDreamRunResult.Started(running)
    }

    private fun resumeRunningLocked(request: StartDreamRunRequest): StartDreamRunResult {
        val existing = runs[request.runId]
            ?: return StartDreamRunResult.Rejected(DreamStoreRejection.NOT_FOUND)
        if (existing.sourceTimezoneId != request.sourceTimezoneId) {
            return StartDreamRunResult.Rejected(DreamStoreRejection.RUN_ID_CONFLICT)
        }
        val access = accessRunningRun(
            request.runId,
            request.scopeId,
            request.leaseOwner,
            request.nowMs,
        )
        if (access is RunningAccess.Rejected) {
            return StartDreamRunResult.Rejected(access.reason)
        }
        access as RunningAccess.Granted
        if (request.requestedLeaseUntilMs <= access.run.leaseUntilMs!!) {
            return StartDreamRunResult.Resumed(access.run)
        }
        val run = access.run.copy(
            leaseUntilMs = request.requestedLeaseUntilMs,
            updatedAtMs = request.nowMs,
        )
        val state = access.state.copy(
            activeRunLeaseUntilMs = request.requestedLeaseUntilMs,
            updatedAtMs = maxOf(access.state.updatedAtMs, request.nowMs),
            lastReasonCode = AuthorityChangeReason.RUN_HEARTBEAT,
        )
        runs[run.runId] = run
        scopes[state.scopeId] = state
        return StartDreamRunResult.Resumed(run)
    }

    private fun finishSuccessOrConflict(
        access: RunningAccess.Granted,
        nowMs: Long,
    ): FinishDreamRunResult {
        val rejection = replayRejection(access.run, access.state)
            ?: if (access.run.checkpointEpoch != access.run.baseMemoryEpoch) {
                DreamStoreRejection.JOURNAL_GAP
            } else {
                null
            }
        if (rejection != null) {
            val failureCode = when (rejection) {
                DreamStoreRejection.MEMORY_EPOCH_CONFLICT ->
                    DreamRunFailureCode.MEMORY_EPOCH_CONFLICT
                DreamStoreRejection.OBSERVER_CHECKPOINT_CONFLICT ->
                    DreamRunFailureCode.OBSERVER_CHECKPOINT_CONFLICT
                else -> DreamRunFailureCode.JOURNAL_GAP
            }
            return finishTerminal(access, DreamRunStatus.CONFLICT, failureCode, nowMs)
        }
        val state = access.state.copy(
            observerCheckpointEpoch = access.run.baseMemoryEpoch,
            activeRunId = null,
            activeRunLeaseUntilMs = null,
            updatedAtMs = maxOf(access.state.updatedAtMs, nowMs),
            lastReasonCode = AuthorityChangeReason.OBSERVER_CHECKPOINT_ADVANCED,
        )
        val run = access.run.copy(
            status = DreamRunStatus.SUCCEEDED,
            leaseOwner = null,
            leaseUntilMs = null,
            failureCode = null,
            updatedAtMs = maxOf(access.run.updatedAtMs, nowMs),
            finishedAtMs = maxOf(access.run.updatedAtMs, nowMs),
        )
        scopes[state.scopeId] = state
        runs[run.runId] = run
        return FinishDreamRunResult.Finished(run, state)
    }

    private fun finishTerminal(
        access: RunningAccess.Granted,
        status: DreamRunStatus,
        failureCode: DreamRunFailureCode,
        nowMs: Long,
    ): FinishDreamRunResult {
        val state = access.state.copy(
            activeRunId = null,
            activeRunLeaseUntilMs = null,
            updatedAtMs = maxOf(access.state.updatedAtMs, nowMs),
            lastReasonCode = AuthorityChangeReason.RUN_FINISHED,
        )
        val run = access.run.copy(
            status = status,
            leaseOwner = null,
            leaseUntilMs = null,
            failureCode = failureCode,
            updatedAtMs = maxOf(access.run.updatedAtMs, nowMs),
            finishedAtMs = maxOf(access.run.updatedAtMs, nowMs),
        )
        scopes[state.scopeId] = state
        runs[run.runId] = run
        return FinishDreamRunResult.Finished(run, state)
    }

    private fun replayRejection(run: DreamRun, state: DreamScopeState): DreamStoreRejection? {
        if (state.memoryEpoch != run.baseMemoryEpoch) {
            return DreamStoreRejection.MEMORY_EPOCH_CONFLICT
        }
        if (state.observerCheckpointEpoch != run.baseObserverCheckpointEpoch) {
            return DreamStoreRejection.OBSERVER_CHECKPOINT_CONFLICT
        }
        val epochs = replayChanges(run).asSequence().map { it.memoryEpoch }.toSet()
        var epoch = run.baseObserverCheckpointEpoch
        while (epoch < run.baseMemoryEpoch) {
            epoch += 1L
            if (epoch !in epochs) return DreamStoreRejection.JOURNAL_GAP
        }
        return null
    }

    private fun replayChanges(run: DreamRun): List<AuthorityChangeReceipt> = journal
        .asSequence()
        .filter { receipt ->
            receipt.scopeId == run.scopeId &&
                receipt.memoryEpoch > run.baseObserverCheckpointEpoch &&
                receipt.memoryEpoch <= run.baseMemoryEpoch
        }
        .sortedWith(compareBy({ it.memoryEpoch }, { it.changeId }))
        .toList()

    private fun accessRunningRun(
        runId: String,
        scopeId: DreamScopeId,
        owner: String,
        nowMs: Long,
    ): RunningAccess {
        val run = runs[runId] ?: return RunningAccess.Rejected(DreamStoreRejection.NOT_FOUND)
        if (run.scopeId != scopeId) {
            return RunningAccess.Rejected(DreamStoreRejection.SCOPE_MISMATCH)
        }
        if (run.status != DreamRunStatus.RUNNING) {
            return RunningAccess.Rejected(DreamStoreRejection.STATUS_MISMATCH)
        }
        if (run.leaseOwner != owner) {
            return RunningAccess.Rejected(DreamStoreRejection.OWNER_MISMATCH)
        }
        val state = scopes[scopeId]
            ?: return RunningAccess.Rejected(DreamStoreRejection.NOT_FOUND)
        if (state.activeRunId != runId) {
            return RunningAccess.Rejected(DreamStoreRejection.ACTIVE_RUN_CONFLICT)
        }
        if (state.activeRunLeaseUntilMs != run.leaseUntilMs) {
            return RunningAccess.Rejected(DreamStoreRejection.ACTIVE_RUN_CONFLICT)
        }
        if (run.leaseUntilMs!! <= nowMs) {
            return RunningAccess.Rejected(DreamStoreRejection.LEASE_EXPIRED)
        }
        if (nowMs < run.updatedAtMs) {
            return RunningAccess.Rejected(DreamStoreRejection.CLOCK_ROLLBACK)
        }
        return RunningAccess.Granted(run, state)
    }

    private sealed interface RunningAccess {
        data class Granted(val run: DreamRun, val state: DreamScopeState) : RunningAccess

        data class Rejected(val reason: DreamStoreRejection) : RunningAccess
    }
}

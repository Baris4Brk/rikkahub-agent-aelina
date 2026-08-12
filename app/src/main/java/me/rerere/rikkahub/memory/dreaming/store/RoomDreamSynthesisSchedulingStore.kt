package me.rerere.rikkahub.memory.dreaming.store

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.DreamDao
import me.rerere.rikkahub.data.db.entity.DreamRunEntity
import me.rerere.rikkahub.data.db.entity.MemoryScopeStateEntity
import me.rerere.rikkahub.memory.dreaming.model.DreamObserverStorageCodec
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.runtime.DreamDailyUsage
import me.rerere.rikkahub.memory.dreaming.runtime.DreamDailyUsageQuery
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSynthesisDirtyScope
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSynthesisSchedulingStore
import me.rerere.rikkahub.memory.dreaming.runtime.EnsurePendingSynthesisRunRequest
import me.rerere.rikkahub.memory.dreaming.runtime.EnsurePendingSynthesisRunResult
import me.rerere.rikkahub.memory.dreaming.temporal.strictZoneOrNull

/** Short Room transactions used only by the background scheduling coordinator. */
class RoomDreamSynthesisSchedulingStore(
    private val database: AppDatabase,
    private val dreamDao: DreamDao,
) : DreamSynthesisSchedulingStore {
    override suspend fun findDirtyScopes(limit: Int): List<DreamSynthesisDirtyScope> {
        require(limit in 1..me.rerere.rikkahub.memory.dreaming.runtime.MAX_DREAM_SYNTHESIS_SCAN_LIMIT)
        return dreamDao.findSynthesisDirtyScopes(limit).map(MemoryScopeStateEntity::toSchedulingScope)
    }

    override suspend fun readDirtyScope(scopeId: DreamScopeId): DreamSynthesisDirtyScope? =
        dreamDao.getScopeState(scopeId.value)?.takeIf {
            it.lastAppliedMemoryEpoch < it.memoryEpoch &&
                it.observerCheckpointEpoch == it.memoryEpoch
        }?.toSchedulingScope()

    override suspend fun ensurePendingRun(
        request: EnsurePendingSynthesisRunRequest,
        allowCreate: Boolean,
    ): EnsurePendingSynthesisRunResult = database.withTransaction {
        val state = dreamDao.getScopeState(request.scopeId.value)
            ?: return@withTransaction EnsurePendingSynthesisRunResult.ScopeNotDirty
        if (!state.hasValidSchedulingShape()) {
            return@withTransaction EnsurePendingSynthesisRunResult.CorruptState
        }
        if (state.lastAppliedMemoryEpoch >= state.memoryEpoch) {
            return@withTransaction EnsurePendingSynthesisRunResult.ScopeNotDirty
        }
        if (state.observerCheckpointEpoch != state.memoryEpoch) {
            return@withTransaction EnsurePendingSynthesisRunResult.ObserverNotCaughtUp
        }
        val existing = dreamDao.findPendingOrRunningSynthesisRun(request.scopeId.value)
        if (existing != null) {
            val mode = DreamObserverStorageCodec.runModeOrNull(existing.mode)
                ?: return@withTransaction EnsurePendingSynthesisRunResult.CorruptState
            val status = DreamObserverStorageCodec.runStatusOrNull(existing.status)
                ?: return@withTransaction EnsurePendingSynthesisRunResult.CorruptState
            if ((mode != DreamRunMode.INCREMENTAL && mode != DreamRunMode.FULL) ||
                (status != DreamRunStatus.PENDING && status != DreamRunStatus.RUNNING) ||
                existing.scopeId != request.scopeId.value ||
                (request.mode == DreamRunMode.FULL && mode != DreamRunMode.FULL)
            ) {
                return@withTransaction EnsurePendingSynthesisRunResult.RunIdentityConflict
            }
            val validEpochs = existing.baseMemoryEpoch >= 0L &&
                existing.baseObserverCheckpointEpoch in 0L..existing.baseMemoryEpoch &&
                existing.checkpointEpoch in
                existing.baseObserverCheckpointEpoch..existing.baseMemoryEpoch &&
                existing.baseDreamRevision >= 0L
            if (!validEpochs) {
                return@withTransaction EnsurePendingSynthesisRunResult.CorruptState
            }
            val validLifecycle = when (status) {
                DreamRunStatus.PENDING -> existing.leaseOwner == null &&
                    existing.leaseUntilMs == null && existing.startedAtMs == null &&
                    existing.finishedAtMs == null && existing.failureCode == null &&
                    existing.attempt == 0 && existing.sourceTimezoneId == null
                DreamRunStatus.RUNNING -> existing.leaseOwner != null &&
                    existing.leaseOwner.isNotBlank() &&
                    existing.leaseUntilMs != null && existing.leaseUntilMs > request.createdAtMs &&
                    existing.startedAtMs != null && existing.finishedAtMs == null &&
                    existing.failureCode == null && existing.attempt > 0 &&
                    existing.sourceTimezoneId != null &&
                    strictZoneOrNull(existing.sourceTimezoneId) != null &&
                    state.activeRunId == existing.runId &&
                    state.activeRunLeaseUntilMs == existing.leaseUntilMs
                else -> false
            }
            if (!validLifecycle) {
                return@withTransaction EnsurePendingSynthesisRunResult.CorruptState
            }
            return@withTransaction EnsurePendingSynthesisRunResult.Ready(
                runId = existing.runId,
                mode = mode,
                created = false,
                running = status == DreamRunStatus.RUNNING,
            )
        }
        if (!allowCreate) {
            return@withTransaction EnsurePendingSynthesisRunResult.CreationDeferred
        }
        if (dreamDao.countPendingSynthesisRuns() != 0L ||
            dreamDao.countRunningSynthesisRuns() != 0L
        ) {
            return@withTransaction EnsurePendingSynthesisRunResult.CreationDeferred
        }
        if (dreamDao.getRunById(request.runId) != null) {
            return@withTransaction EnsurePendingSynthesisRunResult.RunIdentityConflict
        }
        dreamDao.insertRun(
            DreamRunEntity(
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
            ),
        )
        EnsurePendingSynthesisRunResult.Ready(
            runId = request.runId,
            mode = request.mode,
            created = true,
            running = false,
        )
    }

    override suspend fun countGlobalPendingRuns(): Int =
        Math.toIntExact(dreamDao.countPendingSynthesisRuns())

    override suspend fun countGlobalRunningRuns(): Int =
        Math.toIntExact(dreamDao.countRunningSynthesisRuns())

    override suspend fun readGlobalUtcUsage(query: DreamDailyUsageQuery): DreamDailyUsage {
        val row = dreamDao.readGlobalDreamDailyUsage(
            startInclusiveEpochMs = query.window.startInclusiveEpochMs,
            endExclusiveEpochMs = query.window.endExclusiveEpochMs,
            excludingRunId = query.excludingRunId,
        )
        return DreamDailyUsage(
            startedRunCount = Math.toIntExact(row.startedRunCount),
            knownInputTokens = row.knownInputTokens,
            knownOutputTokens = row.knownOutputTokens,
            unmeasuredInputRunCount = Math.toIntExact(row.unmeasuredInputRunCount),
            unmeasuredOutputRunCount = Math.toIntExact(row.unmeasuredOutputRunCount),
        )
    }

    override suspend fun cancelScopeRuns(scopeId: DreamScopeId, nowMs: Long): Int {
        require(nowMs >= 0L)
        return database.withTransaction {
            var cancelled = dreamDao.cancelPendingSynthesisRuns(scopeId.value, nowMs)
            val running = dreamDao.findPendingOrRunningSynthesisRun(scopeId.value)
                ?.takeIf { it.status == DreamRunStatus.RUNNING.name }
            if (running != null && running.leaseOwner != null && running.leaseUntilMs != null &&
                running.leaseUntilMs > nowMs
            ) {
                val finished = dreamDao.finishRunMirror(
                    runId = running.runId,
                    scopeId = scopeId.value,
                    leaseOwner = running.leaseOwner,
                    terminalStatus = DreamRunStatus.CANCELLED.name,
                    failureCode = me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
                        .FEATURE_DISABLED.name,
                    nowMs = nowMs,
                )
                if (finished == 1) {
                    check(
                        dreamDao.releaseScopeLease(
                            scopeId = scopeId.value,
                            runId = running.runId,
                            reasonCode = me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
                                .RUN_FINISHED.name,
                            nowMs = nowMs,
                        ) == 1,
                    ) { "dream_cancel_scope_lease_release_lost" }
                    cancelled += 1
                }
            }
            cancelled
        }
    }
}

private fun MemoryScopeStateEntity.toSchedulingScope(): DreamSynthesisDirtyScope =
    DreamSynthesisDirtyScope(
        scopeId = checkNotNull(DreamScopeId.parseOrNull(scopeId)) { "dream_scope_corrupt" },
        memoryEpoch = memoryEpoch,
        observerCheckpointEpoch = observerCheckpointEpoch,
        lastAppliedMemoryEpoch = lastAppliedMemoryEpoch,
        dreamStateRevision = dreamStateRevision,
        activeRunId = activeRunId,
        activeRunLeaseUntilMs = activeRunLeaseUntilMs,
        updatedAtMs = updatedAtMs,
    )

private fun MemoryScopeStateEntity.hasValidSchedulingShape(): Boolean =
    memoryEpoch >= 0L && observerCheckpointEpoch in 0L..memoryEpoch &&
        lastAppliedMemoryEpoch in 0L..memoryEpoch && dreamStateRevision >= 0L &&
        (activeRunId == null) == (activeRunLeaseUntilMs == null) &&
        (activeRunLeaseUntilMs == null || activeRunLeaseUntilMs >= 0L) && updatedAtMs >= 0L

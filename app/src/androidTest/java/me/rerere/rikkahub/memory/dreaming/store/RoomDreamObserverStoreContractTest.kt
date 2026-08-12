package me.rerere.rikkahub.memory.dreaming.store

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.DreamDao
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomDreamObserverStoreContractTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: DreamDao
    private lateinit var store: RoomDreamObserverStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = database.dreamDao()
        store = RoomDreamObserverStore(database, dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun authorityRecord_requiresOuterTransaction_andForcedRollbackLeavesNoLedger() = runBlocking {
        val request = recordRequest(PRIVATE_SCOPE, "memory-a", nowMs = 10L)
        var outsideRejected = false
        try {
            store.recordAuthorityChangesInCurrentTransaction(request)
        } catch (_: IllegalStateException) {
            outsideRejected = true
        }
        assertTrue(outsideRejected)

        try {
            database.withTransaction {
                store.recordAuthorityChangesInCurrentTransaction(request)
                throw ForcedRollback()
            }
        } catch (_: ForcedRollback) {
            // Expected: authority mutation and observer receipt must share this rollback.
        }
        assertNull(store.readScopeState(PRIVATE_SCOPE))
        assertEquals(0, dao.countChangesThrough(PRIVATE_SCOPE.value, Long.MAX_VALUE))

        val committed = record(request)
        assertEquals(1L, committed.scopeEpochs.single().memoryEpoch)
        assertEquals(1, committed.changes.size)
        assertTrue(committed.changes.single().changeId > 0L)
    }

    @Test
    fun atomicStart_claimsWithoutCommittingBlockedPendingRow_andSupportsResume() = runBlocking {
        record(recordRequest(PRIVATE_SCOPE, "memory-a", 10L))
        assertEquals(listOf(PRIVATE_SCOPE), store.findDirtyScopes(10).map { it.scopeId })
        val firstRunId = runId(20)
        val started = store.startRun(
            StartDreamRunRequest(
                firstRunId,
                PRIVATE_SCOPE,
                DreamRunMode.OBSERVER_REPLAY,
                "observer-a",
                20L,
                100L,
            ),
        ) as StartDreamRunResult.Started
        assertEquals(DreamRunStatus.RUNNING, started.run.status)

        val blockedRunId = runId(21)
        val blocked = store.startRun(
            StartDreamRunRequest(
                blockedRunId,
                PRIVATE_SCOPE,
                DreamRunMode.OBSERVER_REPLAY,
                "observer-b",
                21L,
                100L,
            ),
        ) as StartDreamRunResult.Rejected
        assertEquals(DreamStoreRejection.ACTIVE_RUN_CONFLICT, blocked.reason)
        assertNull(store.readRun(blockedRunId))

        val resumed = store.startRun(
            StartDreamRunRequest(
                firstRunId,
                PRIVATE_SCOPE,
                DreamRunMode.OBSERVER_REPLAY,
                "observer-a",
                30L,
                200L,
            ),
        ) as StartDreamRunResult.Resumed
        assertEquals(230L, resumed.run.leaseUntilMs)
        assertEquals(listOf(firstRunId), store.listRecentRuns(PRIVATE_SCOPE, 10).map { it.runId })
    }

    @Test
    fun receiptInsert_isChunkedWithoutSplittingOneScopeEpoch() = runBlocking {
        val changes = (1..4_097).map { index -> change(PRIVATE_SCOPE, "memory-$index") }
        val receipt = database.withTransaction {
            store.recordAuthorityChangesInCurrentTransaction(
                RecordAuthorityChangesRequest(changes, createdAtMs = 10L),
            )
        }

        assertEquals(1, receipt.scopeEpochs.size)
        assertEquals(1L, receipt.scopeEpochs.single().memoryEpoch)
        assertEquals(4_097, receipt.changes.size)
        assertEquals(4_097, dao.countChangesThrough(PRIVATE_SCOPE.value, 1L))
        assertTrue(receipt.changes.all { it.memoryEpoch == 1L })
    }

    @Test
    fun claim_refreshesQueuedBase_andReplayPersistsCompleteCheckpoint() = runBlocking {
        record(recordRequest(PRIVATE_SCOPE, "memory-a", 10L))
        val runId = runId(1)
        assertTrue(store.createPendingRun(createRun(runId, PRIVATE_SCOPE, 20L)) is
            CreateDreamRunResult.Created)
        record(recordRequest(PRIVATE_SCOPE, "memory-b", 30L))

        val claimed = store.claim(lease(runId, PRIVATE_SCOPE, "worker-a", 40L, 100L))
            as ClaimDreamRunResult.Claimed
        assertEquals(2L, claimed.run.baseMemoryEpoch)
        assertEquals(0L, claimed.run.baseObserverCheckpointEpoch)
        assertEquals(0L, claimed.run.checkpointEpoch)

        val replay = store.readReplay(owner(runId, PRIVATE_SCOPE, "worker-a", 50L))
            as ReadObserverReplayResult.Ready
        assertEquals(listOf(1L, 2L), replay.replay.changes.map { it.memoryEpoch }.distinct())
        assertEquals(2L, replay.replay.run.checkpointEpoch)
        assertEquals(2L, store.readRun(runId)?.checkpointEpoch)
    }

    @Test
    fun missingJournalEpoch_failsClosed_andSuccessRequestCommitsConflict() = runBlocking {
        record(recordRequest(PRIVATE_SCOPE, "memory-a", 10L))
        record(recordRequest(PRIVATE_SCOPE, "memory-b", 20L))
        val runId = runId(2)
        store.createPendingRun(createRun(runId, PRIVATE_SCOPE, 30L))
        store.claim(lease(runId, PRIVATE_SCOPE, "worker-a", 40L, 100L))
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM memory_scope_changes WHERE scope_id = ? AND memory_epoch = ?",
            arrayOf<Any>(PRIVATE_SCOPE.value, 1L),
        )

        val replay = store.readReplay(owner(runId, PRIVATE_SCOPE, "worker-a", 50L))
            as ReadObserverReplayResult.Rejected
        assertEquals(DreamStoreRejection.JOURNAL_GAP, replay.reason)
        val finish = store.finish(
            FinishDreamRunRequest(
                runId,
                PRIVATE_SCOPE,
                "worker-a",
                DreamRunFinishOutcome.SUCCEEDED,
                60L,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.CONFLICT, finish.run.status)
        assertEquals(DreamRunFailureCode.JOURNAL_GAP, finish.run.failureCode)
        assertEquals(0L, finish.scopeState.observerCheckpointEpoch)
        assertNull(finish.scopeState.activeRunId)
    }

    @Test
    fun successfulFinish_updatesRunAndScopeCheckpoint_withBothLeasesCleared() = runBlocking {
        record(recordRequest(PRIVATE_SCOPE, "memory-a", 10L))
        record(recordRequest(PRIVATE_SCOPE, "memory-b", 20L))
        val runId = runId(3)
        store.createPendingRun(createRun(runId, PRIVATE_SCOPE, 30L))
        store.claim(lease(runId, PRIVATE_SCOPE, "worker-a", 40L, 100L))
        store.readReplay(owner(runId, PRIVATE_SCOPE, "worker-a", 50L))

        val finish = store.finish(
            FinishDreamRunRequest(
                runId,
                PRIVATE_SCOPE,
                "worker-a",
                DreamRunFinishOutcome.SUCCEEDED,
                60L,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.SUCCEEDED, finish.run.status)
        assertEquals(2L, finish.run.checkpointEpoch)
        assertEquals(2L, finish.scopeState.observerCheckpointEpoch)
        assertNull(finish.run.leaseOwner)
        assertNull(finish.run.leaseUntilMs)
        assertNull(finish.scopeState.activeRunId)
        assertNull(finish.scopeState.activeRunLeaseUntilMs)
    }

    @Test
    fun epochDrift_afterReplay_commitsConflictWithoutAdvancingObserverCheckpoint() = runBlocking {
        record(recordRequest(PRIVATE_SCOPE, "memory-a", 10L))
        val runId = runId(4)
        store.createPendingRun(createRun(runId, PRIVATE_SCOPE, 20L))
        store.claim(lease(runId, PRIVATE_SCOPE, "worker-a", 30L, 100L))
        store.readReplay(owner(runId, PRIVATE_SCOPE, "worker-a", 40L))
        record(recordRequest(PRIVATE_SCOPE, "memory-b", 50L))

        val finish = store.finish(
            FinishDreamRunRequest(
                runId,
                PRIVATE_SCOPE,
                "worker-a",
                DreamRunFinishOutcome.SUCCEEDED,
                60L,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.CONFLICT, finish.run.status)
        assertEquals(DreamRunFailureCode.MEMORY_EPOCH_CONFLICT, finish.run.failureCode)
        assertEquals(0L, finish.scopeState.observerCheckpointEpoch)
        assertNull(finish.scopeState.activeRunId)
    }

    @Test
    fun mismatchedRunAndStateLease_isTerminalizedFromEitherExpiredSide() =
        runBlocking {
            val runId = runId(5)
            store.createPendingRun(createRun(runId, PRIVATE_SCOPE, 10L))
            store.claim(lease(runId, PRIVATE_SCOPE, "worker-a", 20L, 10L))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE memory_scope_state SET active_run_lease_until_ms = ? WHERE scope_id = ?",
                arrayOf<Any>(100L, PRIVATE_SCOPE.value),
            )

            val recovered = store.recoverExpiredRuns(RecoverExpiredDreamRunsRequest(30L))
            assertEquals(1, recovered.recoveredRuns.size)
            assertEquals(DreamRunStatus.FAILED, recovered.recoveredRuns.single().status)
            assertEquals(
                DreamRunFailureCode.LEASE_EXPIRED,
                recovered.recoveredRuns.single().failureCode,
            )
            val state = store.readScopeState(PRIVATE_SCOPE)!!
            assertNull(state.activeRunId)
            assertNull(state.activeRunLeaseUntilMs)

            val stateExpiredFirstRunId = runId(15)
            store.createPendingRun(createRun(stateExpiredFirstRunId, PRIVATE_SCOPE, 40L))
            store.claim(
                lease(stateExpiredFirstRunId, PRIVATE_SCOPE, "worker-b", 50L, 100L),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE memory_scope_state SET active_run_lease_until_ms = ? WHERE scope_id = ?",
                arrayOf<Any>(60L, PRIVATE_SCOPE.value),
            )

            val stateAuthorityRecovery = store.recoverExpiredRuns(
                RecoverExpiredDreamRunsRequest(60L),
            )
            assertEquals(1, stateAuthorityRecovery.recoveredRuns.size)
            assertEquals(
                stateExpiredFirstRunId,
                stateAuthorityRecovery.recoveredRuns.single().runId,
            )
            assertEquals(
                DreamRunStatus.FAILED,
                stateAuthorityRecovery.recoveredRuns.single().status,
            )
            assertNull(store.readScopeState(PRIVATE_SCOPE)!!.activeRunId)
        }

    @Test
    fun pendingBaseCheckpointAndUnappliedSynthesis_protectPruneWatermark() = runBlocking {
        record(recordRequest(PRIVATE_SCOPE, "memory-a", 10L))
        completeRun(runId(6), PRIVATE_SCOPE, 20L)
        store.createPendingRun(createRun(runId(7), PRIVATE_SCOPE, 40L)) // base checkpoint = 1
        record(recordRequest(PRIVATE_SCOPE, "memory-b", 50L))
        completeRun(runId(8), PRIVATE_SCOPE, 60L) // observer checkpoint = 2

        val state = store.readScopeState(PRIVATE_SCOPE)!!
        val rejected = store.pruneChanges(
            PruneObserverChangesRequest(PRIVATE_SCOPE, state.memoryEpoch, 2L, 2L),
        ) as PruneObserverChangesResult.Rejected
        assertEquals(DreamStoreRejection.PRUNE_WATERMARK_CONFLICT, rejected.reason)

        val synthesisProtected = store.pruneChanges(
            PruneObserverChangesRequest(PRIVATE_SCOPE, state.memoryEpoch, 2L, 1L),
        ) as PruneObserverChangesResult.Rejected
        assertEquals(
            DreamStoreRejection.PRUNE_WATERMARK_CONFLICT,
            synthesisProtected.reason,
        )
        assertEquals(2, dao.countChangesThrough(PRIVATE_SCOPE.value, Long.MAX_VALUE))
    }

    @Test
    fun replay_isStrictlyScoped_betweenPrivateAndGlobalLedgers() = runBlocking {
        database.withTransaction {
            store.recordAuthorityChangesInCurrentTransaction(
                RecordAuthorityChangesRequest(
                    changes = listOf(
                        change(PRIVATE_SCOPE, "private-memory"),
                        change(DreamScopeId.Global, "global-memory"),
                    ),
                    createdAtMs = 10L,
                ),
            )
        }
        val runId = runId(9)
        store.createPendingRun(createRun(runId, PRIVATE_SCOPE, 20L))
        store.claim(lease(runId, PRIVATE_SCOPE, "worker-a", 30L, 100L))

        val replay = store.readReplay(owner(runId, PRIVATE_SCOPE, "worker-a", 40L))
            as ReadObserverReplayResult.Ready
        assertEquals(listOf("private-memory"), replay.replay.changes.map { it.entityId })
    }

    private suspend fun record(request: RecordAuthorityChangesRequest): AuthorityMutationReceipt =
        database.withTransaction {
            store.recordAuthorityChangesInCurrentTransaction(request)
        }

    private suspend fun completeRun(runId: String, scopeId: DreamScopeId, nowMs: Long) {
        val worker = "worker-${runId.takeLast(4)}"
        store.createPendingRun(createRun(runId, scopeId, nowMs))
        store.claim(lease(runId, scopeId, worker, nowMs + 1L, 100L))
        store.readReplay(owner(runId, scopeId, worker, nowMs + 2L))
        val result = store.finish(
            FinishDreamRunRequest(
                runId,
                scopeId,
                worker,
                DreamRunFinishOutcome.SUCCEEDED,
                nowMs + 3L,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.SUCCEEDED, result.run.status)
    }

    private fun recordRequest(
        scopeId: DreamScopeId,
        entityId: String,
        nowMs: Long,
    ) = RecordAuthorityChangesRequest(listOf(change(scopeId, entityId)), nowMs)

    private fun change(scopeId: DreamScopeId, entityId: String) = AuthorityChange(
        scopeId = scopeId,
        entityKind = AuthorityEntityKind.MEMORY,
        entityId = entityId,
        entityRevision = 1L,
        operation = AuthorityChangeOperation.UPDATE,
        reasonCode = AuthorityChangeReason.USER_MUTATION,
    )

    private fun createRun(runId: String, scopeId: DreamScopeId, nowMs: Long) =
        CreateDreamRunRequest(runId, scopeId, DreamRunMode.OBSERVER_REPLAY, nowMs)

    private fun lease(
        runId: String,
        scopeId: DreamScopeId,
        owner: String,
        nowMs: Long,
        durationMs: Long,
    ) = DreamRunLeaseRequest(runId, scopeId, owner, nowMs, durationMs)

    private fun owner(
        runId: String,
        scopeId: DreamScopeId,
        owner: String,
        nowMs: Long,
    ) = DreamRunOwnerRequest(runId, scopeId, owner, nowMs)

    private fun runId(number: Int): String =
        "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"

    private class ForcedRollback : RuntimeException()

    companion object {
        private val PRIVATE_SCOPE = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )
    }
}

package me.rerere.rikkahub.memory.dreaming.store

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamObserverStoreContractTest {
    private val privateScope = DreamScopeId.requireCanonical(
        "123e4567-e89b-12d3-a456-426614174000",
    )

    @Test
    fun `coalescing is stable final-wins and one receipt per scoped entity`() {
        val input = listOf(
            change(privateScope, "memory-b", AuthorityChangeOperation.UPDATE, 1),
            change(DreamScopeId.Global, "memory-a", AuthorityChangeOperation.ARCHIVE, 2),
            change(privateScope, "memory-b", AuthorityChangeOperation.REVIEW, 3),
            change(privateScope, "memory-a", AuthorityChangeOperation.CREATE, 1),
        )

        val result = coalesceAuthorityChanges(input)

        assertEquals(3, result.size)
        assertEquals(
            listOf(
                privateScope to "memory-a",
                privateScope to "memory-b",
                DreamScopeId.Global to "memory-a",
            ),
            result.map { it.scopeId to it.entityId },
        )
        assertEquals(AuthorityChangeOperation.REVIEW, result[1].operation)
        assertEquals(3L, result[1].entityRevision)
    }

    @Test
    fun `empty and duplicate batches obey per-scope epoch contract`() = runBlocking {
        val store = FakeDreamObserverStore()
        val empty = store.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(emptyList(), 10),
        )
        assertFalse(empty.changed)
        assertNull(store.readScopeState(privateScope))

        val result = store.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(
                changes = listOf(
                    change(privateScope, "memory-a", AuthorityChangeOperation.CREATE, 1),
                    change(privateScope, "memory-a", AuthorityChangeOperation.UPDATE, 2),
                    change(privateScope, "memory-b", AuthorityChangeOperation.CREATE, 1),
                    change(DreamScopeId.Global, "global-a", AuthorityChangeOperation.CREATE, 1),
                ),
                createdAtMs = 20,
            ),
        )

        assertEquals(2, result.scopeEpochs.size)
        assertEquals(3, result.changes.size)
        assertTrue(result.changes.all { it.memoryEpoch == 1L })
        assertEquals(1L, store.readScopeState(privateScope)!!.memoryEpoch)
        assertEquals(1L, store.readScopeState(DreamScopeId.Global)!!.memoryEpoch)
    }

    @Test
    fun `mixed batch reason never mislabels the whole scope`() = runBlocking {
        val store = FakeDreamObserverStore()
        store.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(
                listOf(
                    change(
                        privateScope,
                        "memory-a",
                        AuthorityChangeOperation.UPDATE,
                        2,
                        AuthorityChangeReason.USER_MUTATION,
                    ),
                    change(
                        privateScope,
                        "link-a",
                        AuthorityChangeOperation.INVALIDATE,
                        1,
                        AuthorityChangeReason.SOURCE_INVALIDATION,
                        AuthorityEntityKind.LINK,
                    ),
                ),
                30,
            ),
        )

        assertEquals(
            AuthorityChangeReason.MAINTENANCE,
            store.readScopeState(privateScope)!!.lastReasonCode,
        )
    }

    @Test
    fun `claim replay and successful finish advance one complete checkpoint`() = runBlocking {
        val store = FakeDreamObserverStore()
        recordEpoch(store, privateScope, "memory-a", 10)
        recordEpoch(store, privateScope, "memory-b", 20)
        val runId = runId(1)
        assertTrue(
            store.createPendingRun(createRun(runId, privateScope, 30))
                is CreateDreamRunResult.Created,
        )
        val claimed = store.claim(lease(runId, privateScope, "worker-a", 40, 100))
            as ClaimDreamRunResult.Claimed
        assertEquals(2L, claimed.run.baseMemoryEpoch)
        assertEquals(0L, claimed.run.baseObserverCheckpointEpoch)
        assertEquals(0L, claimed.run.checkpointEpoch)

        val replay = store.readReplay(owner(runId, privateScope, "worker-a", 50))
            as ReadObserverReplayResult.Ready
        assertEquals(listOf(1L, 2L), replay.replay.changes.map { it.memoryEpoch }.distinct())
        assertEquals(2L, replay.replay.run.checkpointEpoch)

        val finish = store.finish(
            FinishDreamRunRequest(
                runId,
                privateScope,
                "worker-a",
                DreamRunFinishOutcome.SUCCEEDED,
                60,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.SUCCEEDED, finish.run.status)
        assertEquals(2L, finish.scopeState.observerCheckpointEpoch)
        assertNull(finish.run.leaseOwner)
        assertNull(finish.scopeState.activeRunId)
    }

    @Test
    fun `claim refreshes queued base epochs to the current scope state`() = runBlocking {
        val store = FakeDreamObserverStore()
        recordEpoch(store, privateScope, "memory-a", 10)
        val runId = runId(16)
        val pending = store.createPendingRun(createRun(runId, privateScope, 20))
            as CreateDreamRunResult.Created
        assertEquals(1L, pending.run.baseMemoryEpoch)
        recordEpoch(store, privateScope, "memory-b", 30)

        val claimed = store.claim(lease(runId, privateScope, "worker-a", 40, 50))
            as ClaimDreamRunResult.Claimed
        assertEquals(2L, claimed.run.baseMemoryEpoch)
        assertEquals(0L, claimed.run.baseObserverCheckpointEpoch)
        assertEquals(0L, claimed.run.checkpointEpoch)
    }

    @Test
    fun `run creation is idempotent but cross-scope ID reuse fails closed`() = runBlocking {
        val store = FakeDreamObserverStore()
        val runId = runId(17)
        val request = createRun(runId, privateScope, 10)
        val created = store.createPendingRun(request) as CreateDreamRunResult.Created
        val existing = store.createPendingRun(request.copy(createdAtMs = 20))
            as CreateDreamRunResult.Existing
        assertEquals(created.run, existing.run)
        val rejected = store.createPendingRun(createRun(runId, DreamScopeId.Global, 20))
            as CreateDreamRunResult.Rejected
        assertEquals(DreamStoreRejection.SCOPE_MISMATCH, rejected.reason)
    }

    @Test
    fun `authority epoch change after claim makes success terminal conflict without checkpoint`() =
        runBlocking {
            val store = FakeDreamObserverStore()
            recordEpoch(store, privateScope, "memory-a", 10)
            val runId = runId(2)
            store.createPendingRun(createRun(runId, privateScope, 20))
            store.claim(lease(runId, privateScope, "worker-a", 30, 100))
            recordEpoch(store, privateScope, "memory-b", 40)

            val read = store.readReplay(owner(runId, privateScope, "worker-a", 50))
            assertEquals(
                DreamStoreRejection.MEMORY_EPOCH_CONFLICT,
                (read as ReadObserverReplayResult.Rejected).reason,
            )
            val finish = store.finish(
                FinishDreamRunRequest(
                    runId,
                    privateScope,
                    "worker-a",
                    DreamRunFinishOutcome.SUCCEEDED,
                    60,
                ),
            ) as FinishDreamRunResult.Finished
            assertEquals(DreamRunStatus.CONFLICT, finish.run.status)
            assertEquals(DreamRunFailureCode.MEMORY_EPOCH_CONFLICT, finish.run.failureCode)
            assertEquals(0L, finish.scopeState.observerCheckpointEpoch)
            assertNull(finish.scopeState.activeRunId)
        }

    @Test
    fun `missing epoch fails closed and never advances checkpoint`() = runBlocking {
        val store = FakeDreamObserverStore()
        recordEpoch(store, privateScope, "memory-a", 10)
        recordEpoch(store, privateScope, "memory-b", 20)
        val runId = runId(3)
        store.createPendingRun(createRun(runId, privateScope, 30))
        store.claim(lease(runId, privateScope, "worker-a", 40, 100))
        store.removeJournalEpochForTest(privateScope, 1)

        val replay = store.readReplay(owner(runId, privateScope, "worker-a", 50))
        assertEquals(
            DreamStoreRejection.JOURNAL_GAP,
            (replay as ReadObserverReplayResult.Rejected).reason,
        )
        val finish = store.finish(
            FinishDreamRunRequest(
                runId,
                privateScope,
                "worker-a",
                DreamRunFinishOutcome.SUCCEEDED,
                60,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.CONFLICT, finish.run.status)
        assertEquals(DreamRunFailureCode.JOURNAL_GAP, finish.run.failureCode)
        assertEquals(0L, finish.scopeState.observerCheckpointEpoch)
    }

    @Test
    fun `owner scope lease and active-run checks fail closed`() = runBlocking {
        val store = FakeDreamObserverStore()
        val runA = runId(4)
        val runB = runId(5)
        store.createPendingRun(createRun(runA, privateScope, 10))
        store.createPendingRun(createRun(runB, privateScope, 11))
        store.claim(lease(runA, privateScope, "worker-a", 20, 20))

        assertEquals(
            DreamStoreRejection.ACTIVE_RUN_CONFLICT,
            (store.claim(lease(runB, privateScope, "worker-b", 21, 20))
                as ClaimDreamRunResult.Rejected).reason,
        )
        assertEquals(
            DreamStoreRejection.OWNER_MISMATCH,
            (store.heartbeat(lease(runA, privateScope, "worker-b", 22, 20))
                as HeartbeatDreamRunResult.Rejected).reason,
        )
        assertEquals(
            DreamStoreRejection.SCOPE_MISMATCH,
            (store.readReplay(owner(runA, DreamScopeId.Global, "worker-a", 22))
                as ReadObserverReplayResult.Rejected).reason,
        )
        assertEquals(
            DreamStoreRejection.LEASE_EXPIRED,
            (store.heartbeat(lease(runA, privateScope, "worker-a", 40, 20))
                as HeartbeatDreamRunResult.Rejected).reason,
        )
    }

    @Test
    fun `expired recovery is durable and permits the next pending run`() = runBlocking {
        val store = FakeDreamObserverStore()
        val expired = runId(6)
        val next = runId(7)
        store.createPendingRun(createRun(expired, privateScope, 10))
        store.createPendingRun(createRun(next, privateScope, 11))
        store.claim(lease(expired, privateScope, "worker-a", 20, 10))

        val recovery = store.recoverExpiredRuns(RecoverExpiredDreamRunsRequest(30))
        assertEquals(1, recovery.recoveredRuns.size)
        assertEquals(DreamRunStatus.FAILED, recovery.recoveredRuns.single().status)
        assertEquals(DreamRunFailureCode.LEASE_EXPIRED, recovery.recoveredRuns.single().failureCode)
        assertNull(store.readScopeState(privateScope)!!.activeRunId)

        assertTrue(
            store.claim(lease(next, privateScope, "worker-b", 31, 10))
                is ClaimDreamRunResult.Claimed,
        )
    }

    @Test
    fun `heartbeat extends both lease mirrors and exact boundary is expired`() = runBlocking {
        val store = FakeDreamObserverStore()
        val runId = runId(8)
        store.createPendingRun(createRun(runId, privateScope, 10))
        store.claim(lease(runId, privateScope, "worker-a", 20, 10))

        val heartbeat = store.heartbeat(lease(runId, privateScope, "worker-a", 25, 20))
            as HeartbeatDreamRunResult.Extended
        assertEquals(45L, heartbeat.run.leaseUntilMs)
        assertEquals(45L, store.readScopeState(privateScope)!!.activeRunLeaseUntilMs)
        val noShorten = store.heartbeat(lease(runId, privateScope, "worker-a", 26, 5))
            as HeartbeatDreamRunResult.Extended
        assertEquals(45L, noShorten.run.leaseUntilMs)
        assertEquals(45L, store.readScopeState(privateScope)!!.activeRunLeaseUntilMs)
        assertEquals(
            DreamStoreRejection.LEASE_EXPIRED,
            (store.readReplay(owner(runId, privateScope, "worker-a", 45))
                as ReadObserverReplayResult.Rejected).reason,
        )
    }

    @Test
    fun `explicit failure releases lease and preserves only enum diagnostics`() = runBlocking {
        val store = FakeDreamObserverStore()
        val runId = runId(9)
        store.createPendingRun(createRun(runId, privateScope, 10))
        store.claim(lease(runId, privateScope, "worker-a", 20, 20))

        val result = store.fail(
            FailDreamRunRequest(
                runId,
                privateScope,
                "worker-a",
                DreamRunFailureCode.STORE_FAILURE,
                25,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.FAILED, result.run.status)
        assertEquals(DreamRunFailureCode.STORE_FAILURE, result.run.failureCode)
        assertNull(result.run.leaseOwner)
        assertNull(result.scopeState.activeRunId)
    }

    @Test
    fun `clock rollback is rejected without changing run or lease state`() = runBlocking {
        val store = FakeDreamObserverStore()
        val runId = runId(15)
        store.createPendingRun(createRun(runId, privateScope, 100))
        store.claim(lease(runId, privateScope, "worker-a", 110, 100))
        store.heartbeat(lease(runId, privateScope, "worker-a", 120, 100))
        val beforeRun = store.readRun(runId)
        val beforeScope = store.readScopeState(privateScope)

        val rejected = store.finish(
            FinishDreamRunRequest(
                runId,
                privateScope,
                "worker-a",
                DreamRunFinishOutcome.CANCELLED,
                119,
            ),
        ) as FinishDreamRunResult.Rejected

        assertEquals(DreamStoreRejection.CLOCK_ROLLBACK, rejected.reason)
        assertEquals(beforeRun, store.readRun(runId))
        assertEquals(beforeScope, store.readScopeState(privateScope))
    }

    @Test
    fun `pending run protects its base checkpoint from pruning`() = runBlocking {
        val store = FakeDreamObserverStore()
        recordEpoch(store, privateScope, "memory-a", 10)
        completeRun(store, runId(10), privateScope, 20)
        val oldPending = runId(11)
        store.createPendingRun(createRun(oldPending, privateScope, 40)) // base checkpoint = 1
        recordEpoch(store, privateScope, "memory-b", 50)
        completeRun(store, runId(12), privateScope, 60) // checkpoint can become 2

        val state = store.readScopeState(privateScope)!!
        val rejected = store.pruneChanges(
            PruneObserverChangesRequest(privateScope, state.memoryEpoch, 2, 2),
        ) as PruneObserverChangesResult.Rejected
        assertEquals(DreamStoreRejection.PRUNE_WATERMARK_CONFLICT, rejected.reason)

        val allowed = store.pruneChanges(
            PruneObserverChangesRequest(privateScope, state.memoryEpoch, 2, 1),
        ) as PruneObserverChangesResult.Pruned
        assertEquals(1, allowed.deletedCount)
        assertEquals(listOf(2L), store.journalForTest(privateScope).map { it.memoryEpoch }.distinct())
    }

    @Test
    fun `stale prune CAS cannot delete a newer observer journal`() = runBlocking {
        val store = FakeDreamObserverStore()
        recordEpoch(store, privateScope, "memory-a", 10)
        completeRun(store, runId(13), privateScope, 20)
        recordEpoch(store, privateScope, "memory-b", 40)

        val result = store.pruneChanges(
            PruneObserverChangesRequest(
                scopeId = privateScope,
                expectedMemoryEpoch = 1,
                expectedObserverCheckpointEpoch = 1,
                throughEpochInclusive = 1,
            ),
        ) as PruneObserverChangesResult.Rejected
        assertEquals(DreamStoreRejection.MEMORY_EPOCH_CONFLICT, result.reason)
        assertEquals(2, store.journalForTest(privateScope).size)
    }

    @Test
    fun `private and global runs never observe each other's changes`() = runBlocking {
        val store = FakeDreamObserverStore()
        recordEpoch(store, privateScope, "private-memory", 10)
        recordEpoch(store, DreamScopeId.Global, "global-memory", 11)
        val runId = runId(14)
        store.createPendingRun(createRun(runId, privateScope, 20))
        store.claim(lease(runId, privateScope, "worker-a", 30, 50))

        val replay = store.readReplay(owner(runId, privateScope, "worker-a", 35))
            as ReadObserverReplayResult.Ready
        assertEquals(listOf("private-memory"), replay.replay.changes.map { it.entityId })
    }

    private suspend fun recordEpoch(
        store: DreamObserverStore,
        scope: DreamScopeId,
        entityId: String,
        nowMs: Long,
    ) {
        store.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(
                listOf(change(scope, entityId, AuthorityChangeOperation.UPDATE, 1)),
                nowMs,
            ),
        )
    }

    private suspend fun completeRun(
        store: DreamObserverStore,
        runId: String,
        scope: DreamScopeId,
        nowMs: Long,
    ) {
        store.createPendingRun(createRun(runId, scope, nowMs))
        store.claim(lease(runId, scope, "worker-$runId", nowMs + 1, 100))
        store.readReplay(owner(runId, scope, "worker-$runId", nowMs + 2))
        val result = store.finish(
            FinishDreamRunRequest(
                runId,
                scope,
                "worker-$runId",
                DreamRunFinishOutcome.SUCCEEDED,
                nowMs + 3,
            ),
        ) as FinishDreamRunResult.Finished
        assertEquals(DreamRunStatus.SUCCEEDED, result.run.status)
    }

    private fun change(
        scope: DreamScopeId,
        entityId: String,
        operation: AuthorityChangeOperation,
        revision: Long,
        reason: AuthorityChangeReason = AuthorityChangeReason.USER_MUTATION,
        kind: AuthorityEntityKind = AuthorityEntityKind.MEMORY,
    ) = AuthorityChange(
        scopeId = scope,
        entityKind = kind,
        entityId = entityId,
        entityRevision = revision,
        operation = operation,
        reasonCode = reason,
    )

    private fun createRun(runId: String, scope: DreamScopeId, nowMs: Long) =
        CreateDreamRunRequest(runId, scope, DreamRunMode.OBSERVER_REPLAY, nowMs)

    private fun lease(
        runId: String,
        scope: DreamScopeId,
        owner: String,
        nowMs: Long,
        durationMs: Long,
    ) = DreamRunLeaseRequest(runId, scope, owner, nowMs, durationMs)

    private fun owner(runId: String, scope: DreamScopeId, owner: String, nowMs: Long) =
        DreamRunOwnerRequest(runId, scope, owner, nowMs)

    private fun runId(number: Int): String = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
}

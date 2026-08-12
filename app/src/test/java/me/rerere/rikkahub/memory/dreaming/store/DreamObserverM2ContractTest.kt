package me.rerere.rikkahub.memory.dreaming.store

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamObserverM2ContractTest {
    @Test
    fun `atomic start never commits a new orphan pending run`() = runBlocking {
        val store = FakeDreamObserverStore()
        record(store, PRIVATE_SCOPE, "memory-a", 10L)
        val first = store.startRun(start(runId(1), PRIVATE_SCOPE, "observer-a", 20L))
            as StartDreamRunResult.Started
        assertEquals(DreamRunStatus.RUNNING, first.run.status)

        val blockedRunId = runId(2)
        val blocked = store.startRun(start(blockedRunId, PRIVATE_SCOPE, "observer-b", 21L))
            as StartDreamRunResult.Rejected
        assertEquals(DreamStoreRejection.ACTIVE_RUN_CONFLICT, blocked.reason)
        assertNull(store.readRun(blockedRunId))
    }

    @Test
    fun `same durable work identity resumes and extends its running lease`() = runBlocking {
        val store = FakeDreamObserverStore()
        val runId = runId(3)
        val started = store.startRun(start(runId, PRIVATE_SCOPE, "observer-a", 10L, 100L))
            as StartDreamRunResult.Started
        val resumed = store.startRun(start(runId, PRIVATE_SCOPE, "observer-a", 20L, 200L))
            as StartDreamRunResult.Resumed

        assertEquals(started.run.attempt, resumed.run.attempt)
        assertEquals(220L, resumed.run.leaseUntilMs)
        assertEquals(listOf(runId), store.listRecentRuns(PRIVATE_SCOPE, 10).map { it.runId })
    }

    @Test
    fun `dirty scan is bounded stable and excludes checkpointed scopes`() = runBlocking {
        val store = FakeDreamObserverStore()
        record(store, DreamScopeId.Global, "global", 10L)
        record(store, PRIVATE_SCOPE, "private", 20L)

        assertEquals(
            listOf(DreamScopeId.Global),
            store.findDirtyScopes(1).map { it.scopeId },
        )

        val runId = runId(4)
        store.startRun(start(runId, DreamScopeId.Global, "observer-a", 30L))
        store.readReplay(DreamRunOwnerRequest(runId, DreamScopeId.Global, "observer-a", 31L))
        store.finish(
            FinishDreamRunRequest(
                runId,
                DreamScopeId.Global,
                "observer-a",
                DreamRunFinishOutcome.SUCCEEDED,
                32L,
            ),
        )
        assertEquals(listOf(PRIVATE_SCOPE), store.findDirtyScopes(10).map { it.scopeId })
    }

    @Test
    fun `large authority transaction has one epoch without arbitrary request rejection`() =
        runBlocking {
            val store = FakeDreamObserverStore()
            val changes = (1..4_100).map { index ->
                change(PRIVATE_SCOPE, "memory-$index")
            }
            val receipt = store.recordAuthorityChangesInCurrentTransaction(
                RecordAuthorityChangesRequest(changes, createdAtMs = 10L),
            )

            assertEquals(1, receipt.scopeEpochs.size)
            assertEquals(1L, receipt.scopeEpochs.single().memoryEpoch)
            assertEquals(4_100, receipt.changes.size)
            assertTrue(receipt.changes.all { it.memoryEpoch == 1L })
        }

    private suspend fun record(
        store: DreamObserverStore,
        scopeId: DreamScopeId,
        entityId: String,
        nowMs: Long,
    ) {
        store.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(listOf(change(scopeId, entityId)), nowMs),
        )
    }

    private fun change(scopeId: DreamScopeId, entityId: String) = AuthorityChange(
        scopeId = scopeId,
        entityKind = AuthorityEntityKind.MEMORY,
        entityId = entityId,
        entityRevision = 1L,
        operation = AuthorityChangeOperation.UPDATE,
        reasonCode = AuthorityChangeReason.USER_MUTATION,
    )

    private fun start(
        runId: String,
        scopeId: DreamScopeId,
        owner: String,
        nowMs: Long,
        durationMs: Long = 100L,
    ) = StartDreamRunRequest(
        runId = runId,
        scopeId = scopeId,
        mode = DreamRunMode.OBSERVER_REPLAY,
        leaseOwner = owner,
        nowMs = nowMs,
        leaseDurationMs = durationMs,
    )

    private fun runId(value: Int): String =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

    private companion object {
        val PRIVATE_SCOPE: DreamScopeId = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )
    }
}

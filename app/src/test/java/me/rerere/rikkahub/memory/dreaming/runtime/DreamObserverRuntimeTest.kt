package me.rerere.rikkahub.memory.dreaming.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.store.DreamObserverStore
import me.rerere.rikkahub.memory.dreaming.store.DreamRunOwnerRequest
import me.rerere.rikkahub.memory.dreaming.store.FakeDreamObserverStore
import me.rerere.rikkahub.memory.dreaming.store.ReadObserverReplayResult
import me.rerere.rikkahub.memory.dreaming.store.RecordAuthorityChangesRequest
import me.rerere.rikkahub.memory.dreaming.store.StartDreamRunRequest
import me.rerere.rikkahub.memory.dreaming.work.DreamObserverWorkScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamObserverRuntimeTest {
    @Test
    fun `startup scan recovers from durable dirty state and schedules canonical work`() =
        runBlocking {
            val store = FakeDreamObserverStore()
            record(store, DreamScopeId.Global, "global-memory", 10L)
            record(store, PRIVATE_SCOPE, "private-memory", 20L)
            val scheduler = RecordingScheduler()
            var nextRun = 1
            val runtime = DreamObserverRuntime(
                store = store,
                scheduler = scheduler,
                nowMs = { 100L },
                runIdGenerator = { runId(nextRun++) },
            )

            val result = runtime.scanDirtyScopes()

            assertEquals(listOf(DreamScopeId.Global, PRIVATE_SCOPE), result.scheduledScopes)
            assertEquals(result.scheduledScopes, scheduler.scopeWork.map { it.first })
            assertEquals(listOf(runId(1), runId(2)), scheduler.scopeWork.map { it.second })
            assertEquals(0, result.recoveredRunCount)
        }

    @Test
    fun `observer pass replays checkpoints and safely prunes without generation`() = runBlocking {
        val store = FakeDreamObserverStore()
        record(store, PRIVATE_SCOPE, "memory-a", 10L)
        record(store, PRIVATE_SCOPE, "memory-b", 20L)
        val scheduler = RecordingScheduler()
        var now = 100L
        val runtime = DreamObserverRuntime(store, scheduler, nowMs = { now++ })

        val result = runtime.observe(PRIVATE_SCOPE, runId(3))

        assertEquals(DreamObserverWorkerDirective.COMPLETE, result.directive)
        assertEquals(DreamRunStatus.SUCCEEDED, result.run?.status)
        assertEquals(2, result.prunedChangeCount)
        val state = store.readScopeState(PRIVATE_SCOPE)!!
        assertEquals(2L, state.memoryEpoch)
        assertEquals(2L, state.observerCheckpointEpoch)
        assertNull(state.activeRunId)
        assertTrue(store.journalForTest(PRIVATE_SCOPE).isEmpty())
        assertTrue(scheduler.scopeWork.isEmpty())
    }

    @Test
    fun `process death after replay resumes same run identity and commits once`() = runBlocking {
        val store = FakeDreamObserverStore()
        record(store, PRIVATE_SCOPE, "memory-a", 10L)
        val runId = runId(4)
        val owner = dreamObserverLeaseOwner(runId)
        store.startRun(
            StartDreamRunRequest(
                runId,
                PRIVATE_SCOPE,
                DreamRunMode.OBSERVER_REPLAY,
                owner,
                20L,
                DREAM_OBSERVER_LEASE_DURATION_MS,
            ),
        )
        store.readReplay(DreamRunOwnerRequest(runId, PRIVATE_SCOPE, owner, 21L))

        val runtime = DreamObserverRuntime(store, RecordingScheduler(), nowMs = { 30L })
        val result = runtime.observe(PRIVATE_SCOPE, runId)

        assertEquals(DreamObserverWorkerDirective.COMPLETE, result.directive)
        assertEquals(DreamRunStatus.SUCCEEDED, store.readRun(runId)?.status)
        assertEquals(1, store.readRun(runId)?.attempt)
        assertEquals(1L, store.readScopeState(PRIVATE_SCOPE)?.observerCheckpointEpoch)
    }

    @Test
    fun `startup scan reuses a live active run id instead of creating a competing pending run`() =
        runBlocking {
            val store = FakeDreamObserverStore()
            record(store, PRIVATE_SCOPE, "memory-a", 10L)
            val activeRunId = runId(7)
            store.startRun(
                StartDreamRunRequest(
                    activeRunId,
                    PRIVATE_SCOPE,
                    DreamRunMode.OBSERVER_REPLAY,
                    dreamObserverLeaseOwner(activeRunId),
                    20L,
                    DREAM_OBSERVER_LEASE_DURATION_MS,
                ),
            )
            val scheduler = RecordingScheduler()
            val runtime = DreamObserverRuntime(
                store,
                scheduler,
                nowMs = { 30L },
                runIdGenerator = { error("must_not_allocate_competing_run") },
            )

            runtime.scanDirtyScopes()

            assertEquals(listOf(PRIVATE_SCOPE to activeRunId), scheduler.scopeWork)
        }

    @Test
    fun `authority change during replay terminalizes conflict and requests fresh scan`() =
        runBlocking {
            val backing = FakeDreamObserverStore()
            record(backing, PRIVATE_SCOPE, "memory-a", 10L)
            val mutatingStore = object : DreamObserverStore by backing {
                private var injected = false

                override suspend fun readReplay(
                    request: DreamRunOwnerRequest,
                ): ReadObserverReplayResult {
                    val result = backing.readReplay(request)
                    if (result is ReadObserverReplayResult.Ready && !injected) {
                        injected = true
                        this@DreamObserverRuntimeTest.record(
                            backing,
                            PRIVATE_SCOPE,
                            "memory-b",
                            request.nowMs + 1L,
                        )
                    }
                    return result
                }
            }
            var now = 100L
            val runtime = DreamObserverRuntime(
                store = mutatingStore,
                scheduler = RecordingScheduler(),
                nowMs = { now++ },
            )

            val result = runtime.observe(PRIVATE_SCOPE, runId(5))

            assertEquals(DreamObserverWorkerDirective.RESCAN, result.directive)
            assertEquals(DreamRunStatus.CONFLICT, result.run?.status)
            assertEquals(DreamRunFailureCode.MEMORY_EPOCH_CONFLICT, result.run?.failureCode)
            assertEquals(0L, backing.readScopeState(PRIVATE_SCOPE)?.observerCheckpointEpoch)
        }

    @Test
    fun `expired process lease is terminalized before requesting a fresh run id`() = runBlocking {
        val store = FakeDreamObserverStore()
        record(store, PRIVATE_SCOPE, "memory-a", 1L)
        val runId = runId(6)
        store.startRun(
            StartDreamRunRequest(
                runId,
                PRIVATE_SCOPE,
                DreamRunMode.OBSERVER_REPLAY,
                dreamObserverLeaseOwner(runId),
                10L,
                5L,
            ),
        )
        val runtime = DreamObserverRuntime(store, RecordingScheduler(), nowMs = { 20L })

        val result = runtime.observe(PRIVATE_SCOPE, runId)

        assertEquals(DreamObserverWorkerDirective.RESCAN, result.directive)
        assertEquals(DreamRunStatus.FAILED, store.readRun(runId)?.status)
        assertEquals(DreamRunFailureCode.LEASE_EXPIRED, store.readRun(runId)?.failureCode)
        assertNull(store.readScopeState(PRIVATE_SCOPE)?.activeRunId)
    }

    private suspend fun record(
        store: DreamObserverStore,
        scopeId: DreamScopeId,
        entityId: String,
        nowMs: Long,
    ) {
        store.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(
                changes = listOf(
                    AuthorityChange(
                        scopeId = scopeId,
                        entityKind = AuthorityEntityKind.MEMORY,
                        entityId = entityId,
                        entityRevision = 1L,
                        operation = AuthorityChangeOperation.UPDATE,
                        reasonCode = AuthorityChangeReason.USER_MUTATION,
                    ),
                ),
                createdAtMs = nowMs,
            ),
        )
    }

    private class RecordingScheduler : DreamObserverWorkScheduler {
        val scopeWork = mutableListOf<Pair<DreamScopeId, String>>()
        var dirtyScanCount: Int = 0

        override fun enqueueScope(scopeId: DreamScopeId, runId: String) {
            scopeWork += scopeId to runId
        }

        override fun enqueueDirtyScan() {
            dirtyScanCount++
        }
    }

    private companion object {
        val PRIVATE_SCOPE: DreamScopeId = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )

        fun runId(value: Int): String =
            "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
    }
}

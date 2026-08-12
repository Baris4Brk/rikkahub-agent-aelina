package me.rerere.rikkahub.memory.dreaming.runtime

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.store.DreamRunOwnerRequest
import me.rerere.rikkahub.memory.dreaming.store.RecordAuthorityChangesRequest
import me.rerere.rikkahub.memory.dreaming.store.RoomDreamObserverStore
import me.rerere.rikkahub.memory.dreaming.store.StartDreamRunRequest
import me.rerere.rikkahub.memory.dreaming.work.DreamObserverWorkScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class DreamObserverProcessDeathRoomTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun committedBeforeSchedule_isFoundAfterReopen() = runBlocking {
        val store = RoomDreamObserverStore(database, database.dreamDao())
        record(store, "memory-a", 10L)
        database.close()
        database = openDatabase()

        val scheduler = RecordingScheduler()
        val reopened = RoomDreamObserverStore(database, database.dreamDao())
        val runtime = DreamObserverRuntime(
            store = reopened,
            scheduler = scheduler,
            nowMs = { 20L },
            runIdGenerator = { runId(1) },
        )
        val scan = runtime.scanDirtyScopes()

        assertEquals(listOf(PRIVATE_SCOPE), scan.scheduledScopes)
        assertEquals(listOf(PRIVATE_SCOPE to runId(1)), scheduler.scopeWork)
    }

    @Test
    fun killedAfterClaimAndReplay_resumesSameRunAndAdvancesCheckpointOnce() = runBlocking {
        var store = RoomDreamObserverStore(database, database.dreamDao())
        record(store, "memory-a", 10L)
        val runId = runId(2)
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
        database.close()
        database = openDatabase()
        store = RoomDreamObserverStore(database, database.dreamDao())

        val runtime = DreamObserverRuntime(store, RecordingScheduler(), nowMs = { 30L })
        val result = runtime.observe(PRIVATE_SCOPE, runId)

        assertEquals(DreamObserverWorkerDirective.COMPLETE, result.directive)
        assertEquals(DreamRunStatus.SUCCEEDED, store.readRun(runId)?.status)
        assertEquals(1L, store.readScopeState(PRIVATE_SCOPE)?.observerCheckpointEpoch)
        assertEquals(1, store.readRun(runId)?.attempt)
        assertTrue(database.dreamDao().countChangesThrough(PRIVATE_SCOPE.value, Long.MAX_VALUE) == 0)
    }

    private suspend fun record(
        store: RoomDreamObserverStore,
        entityId: String,
        nowMs: Long,
    ) {
        database.withTransaction {
            store.recordAuthorityChangesInCurrentTransaction(
                RecordAuthorityChangesRequest(
                    listOf(
                        AuthorityChange(
                            scopeId = PRIVATE_SCOPE,
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
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        DATABASE_NAME,
    ).build()

    private class RecordingScheduler : DreamObserverWorkScheduler {
        val scopeWork = mutableListOf<Pair<DreamScopeId, String>>()

        override fun enqueueScope(scopeId: DreamScopeId, runId: String) {
            scopeWork += scopeId to runId
        }

        override fun enqueueDirtyScan() = Unit
    }

    private fun runId(value: Int): String =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

    private companion object {
        const val DATABASE_NAME = "dream-observer-process-death-test.db"
        val PRIVATE_SCOPE: DreamScopeId = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )
    }
}

package me.rerere.rikkahub.memory.dreaming.work

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
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.store.RecordAuthorityChangesRequest
import me.rerere.rikkahub.memory.dreaming.store.RoomDreamObserverStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class DreamObserverCommitSignalRoomTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun signal_isPostCommitHint_andRollbackDoesNotEnqueue() = runBlocking {
        val scheduler = RecordingScheduler()
        @Suppress("UNUSED_VARIABLE")
        val signal = DreamObserverCommitSignal(database, scheduler)
        val store = RoomDreamObserverStore(database, database.dreamDao())
        val request = RecordAuthorityChangesRequest(
            listOf(
                AuthorityChange(
                    scopeId = PRIVATE_SCOPE,
                    entityKind = AuthorityEntityKind.MEMORY,
                    entityId = "memory-a",
                    entityRevision = 1L,
                    operation = AuthorityChangeOperation.UPDATE,
                    reasonCode = AuthorityChangeReason.USER_MUTATION,
                ),
            ),
            createdAtMs = 10L,
        )

        try {
            database.withTransaction {
                store.recordAuthorityChangesInCurrentTransaction(request)
                error("forced_rollback")
            }
        } catch (_: IllegalStateException) {
            // Expected: Room must roll back both the authority row and observer invalidation.
        }
        assertFalse(scheduler.signal.await(250L, TimeUnit.MILLISECONDS))

        database.withTransaction {
            store.recordAuthorityChangesInCurrentTransaction(request)
        }
        assertTrue(scheduler.signal.await(5L, TimeUnit.SECONDS))
    }

    private class RecordingScheduler : DreamObserverWorkScheduler {
        val signal = CountDownLatch(1)

        override fun enqueueScope(scopeId: DreamScopeId, runId: String) = Unit

        override fun enqueueDirtyScan() {
            signal.countDown()
        }
    }

    private companion object {
        val PRIVATE_SCOPE: DreamScopeId = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )
    }
}

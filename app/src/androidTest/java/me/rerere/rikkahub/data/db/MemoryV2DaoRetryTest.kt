package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.entity.MemoryCaptureEntity
import me.rerere.rikkahub.memory.CompletedMemoryTurn
import me.rerere.rikkahub.memory.DefaultMemoryV2Coordinator
import me.rerere.rikkahub.memory.MemoryAutoSaveMode
import me.rerere.rikkahub.memory.MemoryCaptureOrigin
import me.rerere.rikkahub.memory.MemoryCaptureResult
import me.rerere.rikkahub.memory.MemoryWorkRequest
import me.rerere.rikkahub.memory.MemoryWorkScheduler
import me.rerere.rikkahub.memory.RoomMemoryCaptureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * Exercises the real Room SQL behind the user-visible “retry failed captures” action.
 *
 * Automatic processing intentionally stops after three attempts. A user-selected retry must
 * create a fresh automatic retry budget instead of leaving the terminal record stranded.
 */
@RunWith(AndroidJUnit4::class)
class MemoryV2DaoRetryTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MemoryV2Dao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.memoryV2Dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun userRetry_requeuesTerminalFailureWithFreshAutomaticRetryBudget() = runBlocking {
        val scopeId = "scope"
        val nowMs = 10_000L
        dao.insertCapture(
            MemoryCaptureEntity(
                id = "terminal-failure",
                assistantId = "assistant",
                scopeId = scopeId,
                conversationId = "conversation",
                userMessageId = "user-message",
                assistantMessageId = "assistant-message",
                origin = "APP_UI",
                autoSaveMode = "SAFE_NEW_ONLY",
                userText = "remember this",
                assistantText = "acknowledged",
                state = "FAILED",
                retryCount = 3,
                lastErrorCode = "memory_extraction_provider_error",
                lastErrorMessage = "HTTP 400",
                createdAtMs = 1L,
                updatedAtMs = 2L,
                leaseOwner = "old-worker",
                leaseUntilMs = 3L,
            ),
        )

        assertEquals(1, dao.retryScope(scopeId, nowMs))

        val requeued = dao.findClaimableCaptures(
            scopeId = scopeId,
            conversationId = "conversation",
            captureSource = "AUTOMATIC_TURN",
            limit = 1,
        ).single()
        assertEquals("PENDING", requeued.state)
        assertEquals(0, requeued.retryCount)
        assertNull(requeued.lastErrorCode)
        assertNull(requeued.lastErrorMessage)
        assertNull(requeued.leaseOwner)
        assertNull(requeued.leaseUntilMs)
        assertEquals(nowMs, requeued.updatedAtMs)

        assertEquals(
            1,
            dao.claimCapture(
                id = requeued.id,
                scopeId = scopeId,
                workerId = "new-worker",
                leaseUntilMs = nowMs + 1_000L,
                nowMs = nowMs,
            ),
        )
    }

    @Test
    fun configurationFailure_remainsVisibleButCannotBeAutomaticallyClaimedBeforeUserRetries() =
        runBlocking {
            val scopeId = "scope"
            val nowMs = 10_000L
            dao.insertCapture(
                MemoryCaptureEntity(
                    id = "configuration-failure",
                    assistantId = "assistant",
                    scopeId = scopeId,
                    conversationId = "conversation",
                    userMessageId = "user-message",
                    assistantMessageId = "assistant-message",
                    origin = "APP_UI",
                    autoSaveMode = "SAFE_NEW_ONLY",
                    userText = "remember this",
                    assistantText = "acknowledged",
                    state = "PROCESSING",
                    retryCount = 1,
                    createdAtMs = 1L,
                    updatedAtMs = 2L,
                    leaseOwner = "worker",
                    leaseUntilMs = nowMs + 1_000L,
                ),
            )

            assertEquals(
                1,
                dao.markCapturesFailed(
                    ids = listOf("configuration-failure"),
                    scopeId = scopeId,
                    workerId = "worker",
                    state = "FAILED",
                    code = "memory_extraction_model_missing",
                    message = "The selected extraction model is unavailable.",
                    requiresManualRetry = true,
                    nowMs = nowMs,
                ),
            )

            val failed = dao.findCaptureByTurn(
                conversationId = "conversation",
                assistantMessageId = "assistant-message",
                captureSource = "AUTOMATIC_TURN",
            )!!
            assertEquals("FAILED", failed.state)
            assertEquals(3, failed.retryCount)
            assertEquals("memory_extraction_model_missing", failed.lastErrorCode)
            assertEquals(0, dao.countPendingCaptures(scopeId))
            assertTrue(dao.findPendingCaptureGroups(scopeId, limit = 3).isEmpty())
            assertTrue(
                dao.findClaimableCaptures(
                    scopeId = scopeId,
                    conversationId = "conversation",
                    captureSource = "AUTOMATIC_TURN",
                    limit = 1,
                ).isEmpty(),
            )

            assertEquals(1, dao.retryScope(scopeId, nowMs + 1L))
            val requeued = dao.findCaptureByTurn(
                conversationId = "conversation",
                assistantMessageId = "assistant-message",
                captureSource = "AUTOMATIC_TURN",
            )!!
            assertEquals("PENDING", requeued.state)
            assertEquals(0, requeued.retryCount)
            assertNull(requeued.lastErrorCode)
            assertNull(requeued.lastErrorMessage)
        }

    @Test
    fun terminalFailure_isNotAutomaticWorkUntilUserExplicitlyRetries() = runBlocking {
        val scopeId = "scope"
        val terminalConversationId = "terminal-conversation"
        val nowMs = 10_000L
        dao.insertCapture(
            MemoryCaptureEntity(
                id = "terminal-failure",
                assistantId = "assistant",
                scopeId = scopeId,
                conversationId = terminalConversationId,
                userMessageId = "terminal-user-message",
                assistantMessageId = "terminal-assistant-message",
                origin = "APP_UI",
                autoSaveMode = "SAFE_NEW_ONLY",
                userText = "remember this",
                assistantText = "acknowledged",
                state = "FAILED",
                retryCount = 3,
                createdAtMs = 1L,
                updatedAtMs = 2L,
            ),
        )

        // A terminal failure neither consumes the automatic scheduling threshold nor becomes
        // claimable again until the user selects the explicit retry action.
        assertEquals(0, dao.countPendingCaptures(scopeId))
        assertTrue(dao.findPendingCaptureGroups(scopeId, limit = 3).isEmpty())
        assertTrue(
            dao.findClaimableCaptures(
                scopeId = scopeId,
                conversationId = terminalConversationId,
                captureSource = "AUTOMATIC_TURN",
                limit = 1,
            ).isEmpty(),
        )
        assertEquals(
            0,
            dao.claimCapture(
                id = "terminal-failure",
                scopeId = scopeId,
                workerId = "automatic-worker",
                leaseUntilMs = nowMs + 1_000L,
                nowMs = nowMs,
            ),
        )

        val scheduler = RecordingScheduler()
        val coordinator = DefaultMemoryV2Coordinator(
            captureStore = RoomMemoryCaptureStore(dao),
            workScheduler = scheduler,
            idGenerator = { "new-capture" },
        )
        val queued = coordinator.capture(
            CompletedMemoryTurn(
                assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                scopeId = scopeId,
                conversationId = Uuid.parse("00000000-0000-0000-0000-000000000002"),
                userMessageId = Uuid.parse("00000000-0000-0000-0000-000000000003"),
                assistantMessageId = Uuid.parse("00000000-0000-0000-0000-000000000004"),
                origin = MemoryCaptureOrigin.APP_UI,
                userText = "a new durable preference",
                assistantText = "acknowledged",
                memoryEnabled = true,
                autoSaveMode = MemoryAutoSaveMode.SAFE_NEW_ONLY,
                allowedOrigins = setOf(MemoryCaptureOrigin.APP_UI),
                isHeadless = false,
                needsFinalAnswer = false,
                idleDelayMs = 60_000L,
                immediateCaptureThreshold = 2,
            ),
        ) as MemoryCaptureResult.Queued

        assertEquals(1, queued.pendingCount)
        assertEquals(60_000L, queued.delayMs)
        assertEquals(listOf(MemoryWorkRequest(scopeId, 60_000L)), scheduler.requests)

        assertEquals(1, dao.retryScope(scopeId, nowMs))
        assertEquals(2, dao.countPendingCaptures(scopeId))
        val requeued = dao.findClaimableCaptures(
            scopeId = scopeId,
            conversationId = terminalConversationId,
            captureSource = "AUTOMATIC_TURN",
            limit = 1,
        ).single()
        assertEquals("PENDING", requeued.state)
        assertEquals(0, requeued.retryCount)
    }

    @Test
    fun interruptedLease_returnsToQueueWithoutSpendingItsAttempt() = runBlocking {
        dao.insertCapture(
            MemoryCaptureEntity(
                id = "interrupted",
                assistantId = "assistant",
                scopeId = "scope",
                conversationId = "conversation",
                userMessageId = "user",
                assistantMessageId = "assistant-message",
                origin = "APP_UI",
                autoSaveMode = "SAFE_NEW_ONLY",
                userText = "remember this",
                assistantText = "acknowledged",
                state = "PROCESSING",
                retryCount = 3,
                createdAtMs = 1L,
                updatedAtMs = 2L,
                leaseOwner = "dead-worker",
                leaseUntilMs = 9_999L,
            ),
        )

        assertEquals(1, dao.recoverExpiredLeases(10_000L))
        val recovered = dao.findClaimableCaptures(
            scopeId = "scope",
            conversationId = "conversation",
            captureSource = "AUTOMATIC_TURN",
            limit = 1,
        ).single()
        assertEquals("PENDING", recovered.state)
        assertEquals(2, recovered.retryCount)
        assertNull(recovered.leaseOwner)
        assertNull(recovered.leaseUntilMs)
    }

    @Test
    fun reclaimedLease_rejectsEveryMutationFromTheOldWorker() = runBlocking {
        dao.insertCapture(
            MemoryCaptureEntity(
                id = "reclaimed",
                assistantId = "assistant",
                scopeId = "scope",
                conversationId = "conversation",
                userMessageId = "user",
                assistantMessageId = "assistant-message",
                origin = "APP_UI",
                autoSaveMode = "SAFE_NEW_ONLY",
                userText = "remember this",
                assistantText = "acknowledged",
                state = "PROCESSING",
                retryCount = 1,
                createdAtMs = 1L,
                updatedAtMs = 2L,
                leaseOwner = "old-worker",
                leaseUntilMs = 100L,
            ),
        )
        assertEquals(1, dao.recoverExpiredLeases(100L))
        assertEquals(
            1,
            dao.claimCapture(
                id = "reclaimed",
                scopeId = "scope",
                workerId = "new-worker",
                leaseUntilMs = 1_000L,
                nowMs = 101L,
            ),
        )

        assertEquals(
            0,
            dao.releaseClaimedCaptures(
                ids = listOf("reclaimed"),
                scopeId = "scope",
                workerId = "old-worker",
                nowMs = 102L,
            ),
        )
        assertEquals(
            0,
            dao.markCapturesFailed(
                ids = listOf("reclaimed"),
                scopeId = "scope",
                workerId = "old-worker",
                state = "FAILED",
                code = "OLD_WORKER",
                message = null,
                requiresManualRetry = false,
                nowMs = 102L,
            ),
        )
        assertEquals(
            1,
            dao.markCapturesProcessed(
                ids = listOf("reclaimed"),
                scopeId = "scope",
                assistantId = "assistant",
                conversationId = "conversation",
                workerId = "new-worker",
                nowMs = 102L,
                processingOutcome = "NO_LONG_TERM_SIGNAL",
                candidateCount = 0,
            ),
        )
    }

    private class RecordingScheduler : MemoryWorkScheduler {
        val requests = mutableListOf<MemoryWorkRequest>()

        override suspend fun schedule(request: MemoryWorkRequest) {
            requests += request
        }
    }
}

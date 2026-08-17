package me.rerere.rikkahub.learning.handoff

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity
import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.AllowAllLearningScopeConsentSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomLearningReconciliationScannerTest {
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
    fun flagsOffAuthorityChangesAreRepairedAfterReenableAndReplayIsIdempotent() = runBlocking {
        insertSentinel()
        insertCurrentSourceHeadsWithoutOutbox()
        insertFeedbackRevisionWithoutOutbox()
        val scanner = RoomLearningReconciliationScanner(
            database = database,
            supportedWindowMs = 1_000L,
            scopeConsent = AllowAllLearningScopeConsentSource,
        )
        val fixedH0 = LearningOutboxDescriptor(STREAM, headSequence = 1L)
        val cursorAccess = InMemoryCursorAccess(STREAM.toString())

        val coverage = scanner.scanAndRepairProvableTerminalEvents(
            stream = fixedH0,
            cursorAccess = cursorAccess,
            frozenNowMs = 100L,
            limits = LearningBootstrapScanLimits(maxRowsPerPage = 64, maxPages = 10),
        )

        assertNull(coverage.commandCoverageStartMs)
        assertNull(coverage.executionCoverageStartMs)
        assertEquals(20L, coverage.sourceAuthorityCoverageStartMs)
        assertEquals(30L, coverage.feedbackCoverageStartMs)
        assertEquals(20L, coverage.coverageStartMs)
        assertEquals(
            listOf(
                LearningEventType.SOURCE_INVALIDATED.name,
                LearningEventType.SOURCE_INVALIDATED.name,
                LearningEventType.USER_FEEDBACK_RECORDED.name,
                LearningEventType.USER_FEEDBACK_RECORDED.name,
            ),
            database.learningOutboxDao().listAfter(STREAM.toString(), 1L, 10)
                .map { it.eventType }
                .sorted(),
        )
        assertEquals(
            listOf(1L, 2L),
            database.learningOutboxDao().listAfter(STREAM.toString(), 1L, 10)
                .filter { it.eventType == LearningEventType.USER_FEEDBACK_RECORDED.name }
                .map { it.sourceRevision },
        )

        scanner.scanAndRepairProvableTerminalEvents(
            stream = fixedH0,
            cursorAccess = cursorAccess,
            frozenNowMs = 100L,
            limits = LearningBootstrapScanLimits(maxRowsPerPage = 64, maxPages = 10),
        )
        assertEquals(4, database.learningOutboxDao().listAfter(STREAM.toString(), 1L, 10).size)
    }

    @Test
    fun incompleteSharedPageBudgetFailsWithoutReturningPartialCoverage() = runBlocking {
        insertSentinel()
        val scanner = RoomLearningReconciliationScanner(
            database = database,
            supportedWindowMs = 1_000L,
            scopeConsent = AllowAllLearningScopeConsentSource,
        )

        val failure = runCatching {
            scanner.scanAndRepairProvableTerminalEvents(
                stream = LearningOutboxDescriptor(STREAM, headSequence = 1L),
                cursorAccess = InMemoryCursorAccess(STREAM.toString()),
                frozenNowMs = 100L,
                // Five authority families each require even an empty terminal page.
                limits = LearningBootstrapScanLimits(maxRowsPerPage = 64, maxPages = 4),
            )
        }.exceptionOrNull()

        assertTrue(failure is LearningReconciliationWorkRemainsException)
    }

    @Test
    fun durableCursorResumesAcrossTinyBudgetsAndEventuallyReachesFeedback() = runBlocking {
        insertSentinel()
        insertCurrentSourceHeadsWithoutOutbox()
        insertFeedbackRevisionWithoutOutbox()
        val scanner = RoomLearningReconciliationScanner(
            database = database,
            supportedWindowMs = 1_000L,
            scopeConsent = AllowAllLearningScopeConsentSource,
        )
        val fixedH0 = LearningOutboxDescriptor(STREAM, headSequence = 1L)
        val cursorAccess = InMemoryCursorAccess(STREAM.toString())
        var coverage: LearningBootstrapCoverage? = null
        var workRemainsCount = 0

        repeat(12) {
            if (coverage != null) return@repeat
            val result = runCatching {
                scanner.scanAndRepairProvableTerminalEvents(
                    stream = fixedH0,
                    cursorAccess = cursorAccess,
                    frozenNowMs = 100L,
                    limits = LearningBootstrapScanLimits(maxRowsPerPage = 1, maxPages = 1),
                )
            }
            val failure = result.exceptionOrNull()
            if (failure is LearningReconciliationWorkRemainsException) {
                workRemainsCount += 1
            } else {
                result.exceptionOrNull()?.let { throw it }
                coverage = result.getOrThrow()
            }
        }

        assertTrue(workRemainsCount >= 5)
        assertEquals(20L, requireNotNull(coverage).sourceAuthorityCoverageStartMs)
        assertEquals(30L, requireNotNull(coverage).feedbackCoverageStartMs)
        assertEquals(
            listOf(1L, 2L),
            database.learningOutboxDao().listAfter(STREAM.toString(), 1L, 10)
                .filter { it.eventType == LearningEventType.USER_FEEDBACK_RECORDED.name }
                .map { it.sourceRevision },
        )
    }

    private suspend fun insertSentinel() {
        check(
            database.learningOutboxDao().insertIgnore(
                LearningOutboxEntity(
                    streamId = STREAM.toString(),
                    eventId = LEARNING_STREAM_INIT_EVENT_ID,
                    eventType = LearningEventType.STREAM_INIT.name,
                    eventSchemaVersion = 1,
                    terminalState = null,
                    sourceType = null,
                    sourceId = null,
                    sourceRevision = null,
                    missingRevisionReason = null,
                    scopeKind = null,
                    scopeId = null,
                    conversationId = null,
                    commandId = null,
                    lineageId = null,
                    parentCommandId = null,
                    branchAnchorMessageId = null,
                    generationRunId = null,
                    executionId = null,
                    toolCallId = null,
                    messageId = null,
                    occurredAtMs = null,
                    createdAtMs = 0L,
                ),
            ) != -1L,
        )
    }

    private suspend fun insertCurrentSourceHeadsWithoutOutbox() {
        val dao = database.learningSourceAuthorityDao()
        check(
            dao.insertConversationInitialIgnore(
                LearningConversationSourceAuthorityEntity(
                    scopeKind = "ASSISTANT",
                    scopeId = ASSISTANT_ID,
                    conversationId = CONVERSATION_ID,
                    assistantIdSnapshot = ASSISTANT_ID,
                    sourceRevision = 2L,
                    previousSourceRevision = 1L,
                    sourceState = "ACTIVE",
                    changeKind = "UPDATED",
                    branchHeadMessageId = MESSAGE_ID,
                    branchHeadMessageRevision = 2L,
                    occurredAtMs = 20L,
                    updatedAtMs = 20L,
                ),
            ) != -1L,
        )
        check(
            dao.insertMessageInitialIgnore(
                LearningMessageSourceAuthorityEntity(
                    scopeKind = "ASSISTANT",
                    scopeId = ASSISTANT_ID,
                    conversationId = CONVERSATION_ID,
                    messageId = MESSAGE_ID,
                    messageRole = "ASSISTANT",
                    sourceRevision = 2L,
                    previousSourceRevision = 1L,
                    sourceState = "ACTIVE",
                    changeKind = "UPDATED",
                    payloadIntegritySha256 = DIGEST,
                    occurredAtMs = 20L,
                    updatedAtMs = 20L,
                ),
            ) != -1L,
        )
    }

    private suspend fun insertFeedbackRevisionWithoutOutbox() {
        val firstRevision = RewardFeedbackAuthorityEntity(
            feedbackId = FEEDBACK_ID,
            scopeKind = "ASSISTANT",
            scopeId = ASSISTANT_ID,
            conversationId = CONVERSATION_ID,
            conversationSourceRevision = 2L,
            commandId = COMMAND_ID,
            commandRevision = 2L,
            lineageId = COMMAND_ID,
            branchAnchorMessageId = MESSAGE_ID,
            branchAnchorMessageRevision = 2L,
            targetAssistantMessageId = MESSAGE_ID,
            targetAssistantMessageRevision = 2L,
            dimension = "USER",
            signalKind = "EXPLICIT_USER_FEEDBACK",
            valueMilli = 1_000,
            sourceState = "ACTIVE",
            sourceRevision = 1L,
            previousSourceRevision = null,
            integritySha256 = DIGEST,
            createdAtMs = 30L,
            updatedAtMs = 30L,
        )
        val currentHead = firstRevision.copy(
            valueMilli = null,
            sourceState = "TOMBSTONED",
            sourceRevision = 2L,
            previousSourceRevision = 1L,
            updatedAtMs = 31L,
        )
        val dao = database.rewardFeedbackAuthorityDao()
        check(dao.insertHeadIgnore(currentHead) != -1L)
        // Reconciliation must replay the append-only journal, not just the current tombstone head.
        dao.insertRevision(firstRevision.toRevisionEntity())
        dao.insertRevision(currentHead.toRevisionEntity())
    }

    private companion object {
        val STREAM: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000051")
        const val ASSISTANT_ID = "00000000-0000-0000-0000-00000000000a"
        const val CONVERSATION_ID = "conversation-reconcile"
        const val MESSAGE_ID = "message-reconcile"
        const val COMMAND_ID = "command-reconcile"
        const val FEEDBACK_ID = "feedback-reconcile"
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }

    private class InMemoryCursorAccess(
        override val streamId: String,
    ) : LearningReconciliationCursorAccess {
        override val replayGeneration: Long = 1L
        private var value: String? = null

        override suspend fun load(): String? = value

        override suspend fun compareAndSet(
            expectedCursorJson: String?,
            newCursorJson: String?,
        ): Boolean {
            if (value != expectedCursorJson) return false
            value = newCursorJson
            return true
        }
    }
}

package me.rerere.rikkahub.learning.retention

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity
import me.rerere.rikkahub.learning.handoff.LEARNING_STREAM_INIT_EVENT_ID
import me.rerere.rikkahub.learning.model.LearningEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomLearningOutboxRetentionStoreTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun allConsumerAgeAndFloorGatesDeleteBoundedRowsButNeverSentinel() = runBlocking {
        insertSentinel()
        repeat(4) { index -> insertOpaqueEvent(index + 1) }
        val result = RoomLearningPrimaryOutboxRetentionPort(database).pruneOnce(
            LearningOutboxRetentionRequest(
                checkpoints = listOf(checkpoint(sequence = 5L, complete = true)),
                frozenNowMs = 100L,
                minimumAgeMs = 10L,
                safetyFloorRows = 2L,
                batchSize = 10,
            ),
        )

        assertEquals(LearningOutboxRetentionResult.Completed(2, false), result)
        assertEquals(
            listOf(1L, 4L, 5L),
            database.learningOutboxDao().listAfter(STREAM_ID, 0L, 10).map { it.seq },
        )
        assertEquals(
            LearningEventType.STREAM_INIT.name,
            database.learningOutboxDao().listStreamSentinels().single().eventType,
        )
    }

    @Test
    fun incompleteConsumerCheckpointDeletesNothing() = runBlocking {
        insertSentinel()
        insertOpaqueEvent(1)
        val result = RoomLearningPrimaryOutboxRetentionPort(database).pruneOnce(
            LearningOutboxRetentionRequest(
                checkpoints = listOf(checkpoint(sequence = 2L, complete = false)),
                frozenNowMs = 100L,
                minimumAgeMs = 10L,
                safetyFloorRows = 1L,
            ),
        )

        assertEquals(
            LearningOutboxRetentionResult.Unavailable(
                LearningOutboxPruneUnavailableReason.CONSUMER_NOT_BOOTSTRAPPED,
            ),
            result,
        )
        assertEquals(2, database.learningOutboxDao().listAfter(STREAM_ID, 0L, 10).size)
    }

    @Test
    fun boundedPagesRepeatAndKeepSentinel() = runBlocking {
        insertSentinel()
        repeat(3) { index -> insertOpaqueEvent(index + 1) }
        val port = RoomLearningPrimaryOutboxRetentionPort(database)
        val request = LearningOutboxRetentionRequest(
            checkpoints = listOf(checkpoint(sequence = 4L, complete = true)),
            frozenNowMs = 100L,
            minimumAgeMs = 10L,
            safetyFloorRows = 1L,
            batchSize = 1,
        )

        assertEquals(LearningOutboxRetentionResult.Completed(1, true), port.pruneOnce(request))
        assertEquals(LearningOutboxRetentionResult.Completed(1, true), port.pruneOnce(request))
        assertEquals(LearningOutboxRetentionResult.Completed(0, false), port.pruneOnce(request))
        assertEquals(
            listOf(1L, 4L),
            database.learningOutboxDao().listAfter(STREAM_ID, 0L, 10).map { it.seq },
        )
        assertEquals(
            LearningEventType.STREAM_INIT.name,
            database.learningOutboxDao().listStreamSentinels().single().eventType,
        )
    }

    @Test
    fun eachOfContiguousAgeAndSafetyFloorGatesCanIndependentlyPreventDeletion() = runBlocking {
        suspend fun freshPort(): RoomLearningPrimaryOutboxRetentionPort {
            database.clearAllTables()
            insertSentinel()
            insertOpaqueEvent(1)
            return RoomLearningPrimaryOutboxRetentionPort(database)
        }

        var port = freshPort()
        assertEquals(
            LearningOutboxRetentionResult.Completed(0, false),
            port.pruneOnce(
                LearningOutboxRetentionRequest(
                    checkpoints = listOf(checkpoint(sequence = 1L, complete = true)),
                    frozenNowMs = 100L,
                    minimumAgeMs = 10L,
                    safetyFloorRows = 1L,
                ),
            ),
        )
        port = freshPort()
        assertEquals(
            LearningOutboxRetentionResult.Completed(0, false),
            port.pruneOnce(
                LearningOutboxRetentionRequest(
                    checkpoints = listOf(checkpoint(sequence = 2L, complete = true)),
                    frozenNowMs = 5L,
                    minimumAgeMs = 10L,
                    safetyFloorRows = 1L,
                ),
            ),
        )
        port = freshPort()
        assertEquals(
            LearningOutboxRetentionResult.Completed(0, false),
            port.pruneOnce(
                LearningOutboxRetentionRequest(
                    checkpoints = listOf(checkpoint(sequence = 2L, complete = true)),
                    frozenNowMs = 100L,
                    minimumAgeMs = 10L,
                    safetyFloorRows = 10L,
                ),
            ),
        )
    }

    @Test
    fun mixedAuthorityStreamsDeleteNothing() = runBlocking {
        insertSentinel()
        insertOpaqueEvent(1)
        database.learningOutboxDao().insertIgnore(
            row(
                eventId = "foreign-stream-event",
                eventType = "FUTURE_EVENT",
                createdAtMs = 1L,
                streamId = OTHER_STREAM_ID,
            ),
        )

        val result = RoomLearningPrimaryOutboxRetentionPort(database).pruneOnce(
            LearningOutboxRetentionRequest(
                checkpoints = listOf(checkpoint(sequence = 2L, complete = true)),
                frozenNowMs = 100L,
                minimumAgeMs = 10L,
                safetyFloorRows = 1L,
            ),
        )

        assertEquals(LearningOutboxRetentionResult.AuthorityUnavailable, result)
        assertEquals(2, database.learningOutboxDao().listAfter(STREAM_ID, 0L, 10).size)
        assertEquals(
            OTHER_STREAM_ID,
            database.learningOutboxDao().findByEventId("foreign-stream-event")?.streamId,
        )
    }

    private suspend fun insertSentinel() {
        database.learningOutboxDao().insertIgnore(
            row(
                eventId = LEARNING_STREAM_INIT_EVENT_ID,
                eventType = LearningEventType.STREAM_INIT.name,
                createdAtMs = 0L,
            ),
        )
    }

    private suspend fun insertOpaqueEvent(index: Int) {
        database.learningOutboxDao().insertIgnore(
            row(eventId = "retention-event-$index", eventType = "FUTURE_EVENT", createdAtMs = 1L),
        )
    }

    private fun row(
        eventId: String,
        eventType: String,
        createdAtMs: Long,
        streamId: String = STREAM_ID,
    ) = LearningOutboxEntity(
        streamId = streamId,
        eventId = eventId,
        eventType = eventType,
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
        createdAtMs = createdAtMs,
    )

    private fun checkpoint(sequence: Long, complete: Boolean) = LearningDurableConsumerCheckpoint(
        consumerId = LearningDurableConsumerId.LEARNING_DERIVED_RUNTIME,
        streamId = STREAM_ID,
        replayGeneration = 3L,
        lastContiguousSequence = sequence,
        bootstrapComplete = complete,
    )

    private companion object {
        const val STREAM_ID = "00000000-0000-4000-8000-0000000000a1"
        const val OTHER_STREAM_ID = "00000000-0000-4000-8000-0000000000a2"
    }
}

package me.rerere.rikkahub.learning.handoff

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningInboxBatchStoreTest {
    private lateinit var database: LearningDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun autoincrementSequenceHolesAreLegalAbsorbedWatermarks() = runBlocking {
        database.checkpointDao().insert(checkpoint())

        val result = LearningInboxBatchStore(database).ingest(
            LearningIngestBatch(
                streamId = STREAM,
                replayGeneration = 1L,
                expectedPreviousSeq = 1L,
                observedHeadSeq = 7L,
                events = listOf(unknownEvent(3L), unknownEvent(7L)),
                ingestedAtMs = 100L,
            ),
        )

        assertEquals(2, result.insertedEvents)
        assertEquals(0, result.insertedJobs)
        assertEquals(7L, result.lastContiguousSeq)
        val stored = requireNotNull(database.checkpointDao().find(STREAM.toString()))
        assertEquals(7L, stored.lastContiguousSeq)
        assertEquals(7L, stored.lastSeenHeadSeq)
    }

    @Test
    fun typedSourceInvalidationCreatesExactlyOneFencedInvalidationJob() = runBlocking {
        database.checkpointDao().insert(checkpoint())
        val event = sourceInvalidationEvent(sequence = 2L)

        val result = LearningInboxBatchStore(database).ingest(
            LearningIngestBatch(
                streamId = STREAM,
                replayGeneration = 1L,
                expectedPreviousSeq = 1L,
                observedHeadSeq = 2L,
                events = listOf(event),
                ingestedAtMs = 100L,
            ),
        )

        assertEquals(1, result.insertedEvents)
        assertEquals(1, result.insertedJobs)
        val jobs = database.jobDao().listBySourceEventAndType(
            STREAM.toString(),
            1L,
            event.eventId,
            LearningJobType.INVALIDATE_SOURCE_V1.name,
        )
        assertEquals(1, jobs.size)
        assertEquals("source-invalidation-v1", jobs.single().algorithmIdentity)
        assertEquals("learning-source-validity-output-v1", jobs.single().outputSchemaIdentity)
    }

    @Test
    fun emptyPageBeforeObservedHeadFailsWithoutAdvancingCheckpoint() = runBlocking {
        database.checkpointDao().insert(checkpoint())

        val failure = runCatching {
            LearningInboxBatchStore(database).ingest(
                LearningIngestBatch(
                    streamId = STREAM,
                    replayGeneration = 1L,
                    expectedPreviousSeq = 1L,
                    observedHeadSeq = 7L,
                    events = emptyList(),
                    ingestedAtMs = 100L,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is LearningHandoffIdentityConflictException)
        assertEquals(1L, database.checkpointDao().find(STREAM.toString())?.lastContiguousSeq)
    }

    @Test
    fun identityConflictRollsBackEarlierInboxInsertAndCheckpoint() = runBlocking {
        database.checkpointDao().insert(checkpoint())
        database.inboxDao().insertIgnore(
            unknownEvent(sequence = 6L, eventId = "conflicting-event")
                .toInboxEntity(ingestedAtMs = 100L, replayGeneration = 1L),
        )

        val failure = runCatching {
            LearningInboxBatchStore(database).ingest(
                LearningIngestBatch(
                    streamId = STREAM,
                    replayGeneration = 1L,
                    expectedPreviousSeq = 1L,
                    observedHeadSeq = 7L,
                    events = listOf(
                        unknownEvent(sequence = 3L, eventId = "inserted-before-conflict"),
                        unknownEvent(sequence = 7L, eventId = "conflicting-event"),
                    ),
                    ingestedAtMs = 100L,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is LearningHandoffIdentityConflictException)
        assertNull(database.inboxDao().find(STREAM.toString(), "inserted-before-conflict"))
        assertEquals(1L, database.checkpointDao().find(STREAM.toString())?.lastContiguousSeq)
    }

    @Test
    fun reinterpreterCommitsDerivedCasAndNewEligibleJobTogether() = runBlocking {
        val admitted = admittedEvent(sequence = 3L)
        database.inboxDao().insertIgnore(
            admitted.toInboxEntity(ingestedAtMs = 50L, replayGeneration = 1L).copy(
                decodeState = LearningEventDecodeState.UNKNOWN_NO_JOB.name,
                interpretationVersion = 1,
            ),
        )
        val reinterpreter = LearningInboxReinterpreter(
            database = database,
            interpreter = LearningInboxEventInterpreter { _, _ ->
                LearningEventDecodeState.KNOWN
            },
            jobFactory = LearningReinterpretedJobFactory { event, _, nowMs ->
                initialJob(event, nowMs)
            },
        )

        val first = reinterpreter.reinterpretNextPage(
            streamId = STREAM,
            replayGeneration = 1L,
            afterSequence = 0L,
            targetInterpretationVersion = 2,
            reinterpretedAtMs = 100L,
        )
        val second = reinterpreter.reinterpretNextPage(
            streamId = STREAM,
            replayGeneration = 1L,
            afterSequence = 0L,
            targetInterpretationVersion = 2,
            reinterpretedAtMs = 101L,
        )

        assertEquals(1, first.updatedInterpretations)
        assertEquals(1, first.insertedJobs)
        assertEquals(0, second.scannedEvents)
        val stored = requireNotNull(database.inboxDao().find(STREAM.toString(), admitted.eventId))
        assertEquals(LearningEventDecodeState.KNOWN.name, stored.decodeState)
        assertEquals(2, stored.interpretationVersion)
        assertNotNull(database.jobDao().findByDedupeKey("reinterpret-job-dedupe-v1"))
    }

    @Test
    fun reinterpreterJobIdentityConflictRollsBackDerivedCas() = runBlocking {
        val admitted = admittedEvent(sequence = 3L)
        database.inboxDao().insertIgnore(
            admitted.toInboxEntity(ingestedAtMs = 50L, replayGeneration = 1L).copy(
                decodeState = LearningEventDecodeState.UNKNOWN_NO_JOB.name,
                interpretationVersion = 1,
            ),
        )
        database.jobDao().insertIgnore(
            pendingJob(
                id = "conflicting-job-v1",
                sourceEventId = "different-source-event",
                nowMs = 90L,
            ),
        )
        val reinterpreter = LearningInboxReinterpreter(
            database = database,
            interpreter = LearningInboxEventInterpreter { _, _ ->
                LearningEventDecodeState.KNOWN
            },
            jobFactory = LearningReinterpretedJobFactory { event, _, nowMs ->
                initialJob(event, nowMs)
            },
        )

        val failure = runCatching {
            reinterpreter.reinterpretNextPage(
                streamId = STREAM,
                replayGeneration = 1L,
                afterSequence = 0L,
                targetInterpretationVersion = 2,
                reinterpretedAtMs = 100L,
            )
        }.exceptionOrNull()

        assertTrue(failure is LearningHandoffIdentityConflictException)
        val stored = requireNotNull(database.inboxDao().find(STREAM.toString(), admitted.eventId))
        assertEquals(LearningEventDecodeState.UNKNOWN_NO_JOB.name, stored.decodeState)
        assertEquals(1, stored.interpretationVersion)
    }

    private fun checkpoint() = LearningStreamCheckpointEntity(
        streamId = STREAM.toString(),
        lastContiguousSeq = 1L,
        lastSeenHeadSeq = 1L,
        replayGeneration = 1L,
        resetReason = null,
        bootstrapState = LearningBootstrapState.COMPLETE.name,
        bootstrapHeadSeq = 1L,
        coverageStartMs = null,
        commandCoverageStartMs = null,
        executionCoverageStartMs = null,
        updatedAtMs = 0L,
    )

    private fun unknownEvent(
        sequence: Long,
        eventId: String = "future-event-$sequence",
    ) = LearningHandoffEvent(
        streamId = STREAM,
        eventId = eventId,
        outboxSeq = sequence,
        eventCode = LearningEventCode("FUTURE_EVENT", 1),
        source = source("future-source-$sequence"),
        correlation = LearningCorrelation(),
        createdAtMs = 10L,
    )

    private fun admittedEvent(sequence: Long): LearningHandoffEvent {
        val source = source("command-source")
        val eventId = LearningCanonicalId.eventId(
            streamId = STREAM,
            eventType = LearningEventType.COMMAND_ADMITTED,
            eventSchemaVersion = 1,
            sourceKindCode = LearningSourceKind.COMMAND.name,
            sourceId = source.sourceId,
            sourceRevision = source.sourceRevision,
            terminalState = null,
        )
        return LearningHandoffEvent(
            streamId = STREAM,
            eventId = eventId,
            outboxSeq = sequence,
            eventCode = LearningEventCode(LearningEventType.COMMAND_ADMITTED.name, 1),
            source = source,
            correlation = LearningCorrelation(
                conversationId = "conversation-id",
                commandId = source.sourceId,
                lineageId = "lineage-id",
                branchAnchorMessageId = "anchor-message-id",
            ),
            createdAtMs = 10L,
        )
    }

    private fun sourceInvalidationEvent(sequence: Long): LearningHandoffEvent {
        val source = LearningSourceRef(
            sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
            sourceId = "message-source",
            sourceRevision = 2L,
            missingRevisionReason = null,
            databaseStreamId = STREAM,
            scope = LearningScope.Assistant(ASSISTANT),
            occurredAtMs = 10L,
        )
        val correlation = LearningCorrelation(
            previousSourceRevision = 1L,
            sourceStateCode = "TOMBSTONED",
        )
        val eventId = LearningCanonicalId.eventId(
            streamId = STREAM,
            eventType = LearningEventType.SOURCE_INVALIDATED,
            eventSchemaVersion = 2,
            sourceKindCode = source.sourceKind.name,
            sourceId = source.sourceId,
            sourceRevision = source.sourceRevision,
            terminalState = null,
            previousSourceRevision = correlation.previousSourceRevision,
            sourceStateCode = correlation.sourceStateCode,
            correlation = correlation,
        )
        return LearningHandoffEvent(
            streamId = STREAM,
            eventId = eventId,
            outboxSeq = sequence,
            eventCode = LearningEventCode(LearningEventType.SOURCE_INVALIDATED.name, 2),
            source = source,
            correlation = correlation,
            createdAtMs = 10L,
        )
    }

    private fun source(id: String) = LearningSourceRef(
        sourceKind = LearningSourceKind.COMMAND,
        sourceId = id,
        sourceRevision = 1L,
        missingRevisionReason = null,
        databaseStreamId = STREAM,
        scope = LearningScope.Assistant(ASSISTANT),
        occurredAtMs = 10L,
    )

    private fun initialJob(
        event: LearningInboxAuthoritativeEvent,
        nowMs: Long,
    ) = pendingJob(
        id = "reinterpret-job-v1",
        sourceEventId = event.eventId,
        nowMs = nowMs,
        streamId = event.streamId.toString(),
        scopeKind = requireNotNull(event.scopeKindCode),
        scopeId = requireNotNull(event.scopeId),
        replayGeneration = event.replayGeneration,
    )

    private fun pendingJob(
        id: String,
        sourceEventId: String,
        nowMs: Long,
        streamId: String = STREAM.toString(),
        scopeKind: String = "ASSISTANT",
        scopeId: String = ASSISTANT.toString(),
        replayGeneration: Long = 1L,
    ) = LearningJobEntity(
        id = id,
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "reinterpret-job-dedupe-v1",
        streamId = streamId,
        sourceEventId = sourceEventId,
        scopeKind = scopeKind,
        scopeId = scopeId,
        state = LearningJobState.PENDING.name,
        priority = 0,
        attempts = 0,
        maxAttempts = 5,
        notBeforeMs = nowMs,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0L,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = nowMs,
        updatedAtMs = nowMs,
        finishedAtMs = null,
        replayGeneration = replayGeneration,
    )

    private companion object {
        val STREAM: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000061")
        val ASSISTANT: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000062")
    }
}

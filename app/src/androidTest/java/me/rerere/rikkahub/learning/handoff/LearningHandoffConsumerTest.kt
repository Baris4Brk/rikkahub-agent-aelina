package me.rerere.rikkahub.learning.handoff

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowPrivacyPort
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowResetReceipt
import me.rerere.rikkahub.learning.privacy.DurableScopeLearnedWorkflowEraseReceipt
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowEraseBatchReceipt
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowErasePort
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import me.rerere.rikkahub.learning.storage.LearningStreamResetReason
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
class LearningHandoffConsumerTest {
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
    fun streamSwapBetweenPageReadAndCommitResetsWithoutIngestingOldTimeline() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        var inspections = 0
        val reader = object : LearningOutboxReader {
            override suspend fun inspect(): LearningOutboxDescriptor {
                inspections += 1
                return if (inspections == 1) {
                    LearningOutboxDescriptor(STREAM_A, 5L)
                } else {
                    LearningOutboxDescriptor(STREAM_B, 1L)
                }
            }

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> = listOf(unknownEvent(STREAM_A, 3L))
        }

        val result = resetCapableConsumer(reader).consumeOnce(frozenNowMs = 100L)

        assertEquals(
            LearningStreamResetReason.NEW_STREAM,
            (result as LearningConsumeResult.ResetRequired).reason,
        )
        assertNull(database.inboxDao().find(STREAM_A.toString(), "future-event-3"))
        assertEquals(STREAM_B.toString(), database.checkpointDao().listAll().single().streamId)
    }

    @Test
    fun sameStreamRewindDuringReadUsesObservedPageHeadAsRestoreFence() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        var inspections = 0
        val reader = object : LearningOutboxReader {
            override suspend fun inspect(): LearningOutboxDescriptor {
                inspections += 1
                val head = if (inspections == 1) 5L else 3L
                return LearningOutboxDescriptor(STREAM_A, head)
            }

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> = listOf(unknownEvent(STREAM_A, 3L))
        }

        val result = resetCapableConsumer(reader).consumeOnce(frozenNowMs = 100L)

        assertEquals(
            LearningStreamResetReason.HEAD_REWIND,
            (result as LearningConsumeResult.ResetRequired).reason,
        )
        assertNull(database.inboxDao().find(STREAM_A.toString(), "future-event-3"))
        val reset = database.checkpointDao().listAll().single()
        assertEquals(3L, reset.lastSeenHeadSeq)
        assertEquals(LearningBootstrapState.REQUIRED.name, reset.bootstrapState)
    }

    @Test
    fun configuredCountBoundIsPassedToReaderAndNumericHolesAreConsumed() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        val reader = object : LearningOutboxReader {
            override suspend fun inspect() = LearningOutboxDescriptor(STREAM_A, 7L)

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> {
                assertEquals(2, limit)
                return listOf(unknownEvent(STREAM_A, 3L), unknownEvent(STREAM_A, 7L))
            }
        }

        val result = LearningHandoffConsumer(
            database = database,
            outboxReader = reader,
            batchLimit = 2,
        ).consumeOnce(frozenNowMs = 100L)

        assertEquals(7L, (result as LearningConsumeResult.Consumed).result.lastContiguousSeq)
        assertEquals(7L, database.checkpointDao().find(STREAM_A.toString())?.lastContiguousSeq)
    }

    @Test
    fun competingCheckpointCasRollsBackTheLosingConsumerPage() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        var inspections = 0
        val reader = object : LearningOutboxReader {
            override suspend fun inspect(): LearningOutboxDescriptor {
                inspections += 1
                if (inspections == 2) {
                    LearningInboxBatchStore(database).ingest(
                        LearningIngestBatch(
                            streamId = STREAM_A,
                            replayGeneration = 1L,
                            expectedPreviousSeq = 1L,
                            observedHeadSeq = 3L,
                            events = listOf(unknownEvent(STREAM_A, 2L)),
                            ingestedAtMs = 50L,
                        ),
                    )
                }
                return LearningOutboxDescriptor(STREAM_A, 3L)
            }

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> = listOf(unknownEvent(STREAM_A, 3L))
        }

        val failure = runCatching {
            LearningHandoffConsumer(database, reader).consumeOnce(frozenNowMs = 100L)
        }.exceptionOrNull()

        assertTrue(failure is LearningCheckpointConflictException)
        assertEquals(2L, database.checkpointDao().find(STREAM_A.toString())?.lastContiguousSeq)
        assertTrue(database.inboxDao().find(STREAM_A.toString(), "future-event-2") != null)
        assertNull(database.inboxDao().find(STREAM_A.toString(), "future-event-3"))
    }

    @Test
    fun resetWithoutAppDatabasePrivacyPortsFailsBeforeDeletingLearningState() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        val reader = object : LearningOutboxReader {
            override suspend fun inspect() = LearningOutboxDescriptor(STREAM_B, 1L)

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> = emptyList()
        }

        val failure = runCatching {
            LearningHandoffConsumer(database, reader).consumeOnce(frozenNowMs = 100L)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "derived_reset_candidate_fence_unconfigured",
            failure?.message,
        )
        assertEquals(STREAM_A.toString(), database.checkpointDao().listAll().single().streamId)
    }

    @Test
    fun incompleteDurableResetReceiptFailsBeforeDeletingLearningState() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        val reader = object : LearningOutboxReader {
            override suspend fun inspect() = LearningOutboxDescriptor(STREAM_B, 1L)

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> = emptyList()
        }
        val consumer = LearningHandoffConsumer(
            database = database,
            outboxReader = reader,
            learnedWorkflowErasePort = ExactScopeLearnedWorkflowErasePort { ids, _ ->
                ExactScopeLearnedWorkflowEraseBatchReceipt(ids.size, 0, ids.size)
            },
            durableLearnedWorkflowPrivacyPort = object : DurableLearnedWorkflowPrivacyPort {
                override suspend fun redactExactScope(
                    scope: LearningScope,
                    frozenNowMs: Long,
                ) = DurableScopeLearnedWorkflowEraseReceipt(0, 0, 0, 0)

                override suspend fun redactAllForDerivedReset(
                    frozenNowMs: Long,
                ) = DurableLearnedWorkflowResetReceipt(0, 0, complete = false)
            },
        )

        val failure = runCatching { consumer.consumeOnce(frozenNowMs = 100L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("derived_reset_orphan_quarantine_incomplete", failure?.message)
        assertEquals(STREAM_A.toString(), database.checkpointDao().listAll().single().streamId)
    }

    @Test
    fun elapsedTimeBoundReturnsRetryableBudgetResultWithoutAdvancingCheckpoint() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        val reader = object : LearningOutboxReader {
            override suspend fun inspect() = LearningOutboxDescriptor(STREAM_A, 2L)

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> {
                delay(1_000L)
                return emptyList()
            }
        }

        val result = LearningHandoffConsumer(
            database = database,
            outboxReader = reader,
            maxBatchElapsedMs = 10L,
        ).consumeOnce(frozenNowMs = 100L)

        assertTrue(result is LearningConsumeResult.BudgetExhausted)
        assertEquals(1L, database.checkpointDao().find(STREAM_A.toString())?.lastContiguousSeq)
    }

    @Test
    fun parentCancellationStillPropagatesWithoutAdvancingCheckpoint() = runBlocking {
        database.checkpointDao().insert(checkpoint(STREAM_A))
        val reader = object : LearningOutboxReader {
            override suspend fun inspect() = LearningOutboxDescriptor(STREAM_A, 2L)

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> {
                delay(1_000L)
                return emptyList()
            }
        }

        val failure = runCatching {
            withTimeout(10L) {
                LearningHandoffConsumer(
                    database = database,
                    outboxReader = reader,
                    maxBatchElapsedMs = 5_000L,
                ).consumeOnce(frozenNowMs = 100L)
            }
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
        assertEquals(1L, database.checkpointDao().find(STREAM_A.toString())?.lastContiguousSeq)
    }

    private fun checkpoint(streamId: Uuid) = LearningStreamCheckpointEntity(
        streamId = streamId.toString(),
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

    private fun unknownEvent(streamId: Uuid, sequence: Long) = LearningHandoffEvent(
        streamId = streamId,
        eventId = "future-event-$sequence",
        outboxSeq = sequence,
        eventCode = LearningEventCode("FUTURE_EVENT", 1),
        source = LearningSourceRef(
            sourceKind = LearningSourceKind.COMMAND,
            sourceId = "future-source-$sequence",
            sourceRevision = 1L,
            missingRevisionReason = null,
            databaseStreamId = streamId,
            scope = LearningScope.Assistant(ASSISTANT),
            occurredAtMs = 10L,
        ),
        correlation = LearningCorrelation(),
        createdAtMs = 10L,
    )

    private fun resetCapableConsumer(reader: LearningOutboxReader) = LearningHandoffConsumer(
        database = database,
        outboxReader = reader,
        learnedWorkflowErasePort = ExactScopeLearnedWorkflowErasePort { ids, _ ->
            ExactScopeLearnedWorkflowEraseBatchReceipt(
                fencedCandidateIds = ids.size,
                redactedWorkflowDefinitions = 0,
                insertedFenceClaims = ids.size,
            )
        },
        durableLearnedWorkflowPrivacyPort = object : DurableLearnedWorkflowPrivacyPort {
            override suspend fun redactExactScope(
                scope: LearningScope,
                frozenNowMs: Long,
            ) = DurableScopeLearnedWorkflowEraseReceipt(0, 0, 0, 0)

            override suspend fun redactAllForDerivedReset(
                frozenNowMs: Long,
            ) = DurableLearnedWorkflowResetReceipt(0, 0, complete = true)
        },
    )

    private companion object {
        val STREAM_A: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000071")
        val STREAM_B: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000072")
        val ASSISTANT: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000073")
    }
}

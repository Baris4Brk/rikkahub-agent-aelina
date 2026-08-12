package me.rerere.rikkahub.learning.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.jobs.LearningJobClaimResult
import me.rerere.rikkahub.learning.jobs.LearningJobClock
import me.rerere.rikkahub.learning.jobs.LearningJobCoordinator
import me.rerere.rikkahub.learning.jobs.LearningLostLeaseException
import me.rerere.rikkahub.learning.jobs.LearningJobFailureCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningDatabaseFencingTest {
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
    fun p0Schema_containsExactlyThreeLearningTables() {
        val names = buildSet {
            database.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'learning_%'",
            ).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(
            setOf(
                "learning_inbox_events",
                "learning_stream_checkpoints",
                "learning_jobs",
            ),
            names,
        )
    }

    @Test
    fun reclaimedLease_fencesOldWorkerFinish() = runBlocking {
        database.jobDao().insertIgnore(job(id = "job-source"))
        val clock = MutableLearningJobClock(100)
        val firstCoordinator = coordinator(PROCESS_A, clock)
        val first = firstCoordinator.claimNext(Uuid.parse(WORKER_A), leaseDurationMs = 100)
            as LearningJobClaimResult.Claimed
        clock.nowMs = 201
        val secondCoordinator = coordinator(PROCESS_B, clock)
        val second = secondCoordinator.claimNext(Uuid.parse(WORKER_B), leaseDurationMs = 100)
            as LearningJobClaimResult.Claimed

        clock.nowMs = 202
        val staleCommit = runCatching {
            firstCoordinator.failPermanently(
                first.lease,
                LearningJobFailureCode.SOURCE_STALE,
            )
        }
        assertTrue(staleCommit.exceptionOrNull() is LearningLostLeaseException)
        assertEquals(LearningJobState.RUNNING.name, database.jobDao().findById("job-source")?.state)

        secondCoordinator.failPermanently(
            second.lease,
            LearningJobFailureCode.SOURCE_STALE,
        )
        assertEquals(
            LearningJobState.DEAD_LETTER.name,
            database.jobDao().findById("job-source")?.state,
        )
    }

    @Test
    fun clockRollback_isReportedAndDoesNotClaim() = runBlocking {
        database.jobDao().insertIgnore(job(id = "future-job", updatedAtMs = 500))
        val clock = MutableLearningJobClock(100)
        val result = coordinator(PROCESS_A, clock)
            .claimNext(Uuid.parse(WORKER_A), leaseDurationMs = 100)
        assertEquals(
            LearningJobClaimResult.ClockRollback("future-job"),
            result,
        )
        assertNull(database.jobDao().findById("future-job")?.leaseProcessSessionId)
    }

    @Test
    fun resettingDerivedState_removesFutureTimelineRows() = runBlocking {
        database.jobDao().insertIgnore(job(id = "old-job"))
        database.inboxDao().insertIgnore(inbox(id = "old-event"))
        database.checkpointDao().insert(checkpoint(streamId = STREAM_A, replayGeneration = 2))

        val reset = me.rerere.rikkahub.learning.handoff.LearningDerivedStateResetter(database).reset(
            streamId = kotlin.uuid.Uuid.parse(STREAM_B),
            observedHeadSeq = 9,
            reason = LearningStreamResetReason.RESTORE,
            frozenNowMs = 1_000,
        )

        assertNull(database.jobDao().findById("old-job"))
        assertNull(database.inboxDao().find(STREAM_A, "old-event"))
        assertEquals(STREAM_B, reset.streamId)
        assertEquals(3L, reset.replayGeneration)
        assertNotNull(database.checkpointDao().find(STREAM_B))
    }

    @Test
    fun resettingWithoutCheckpoint_advancesPastLiveJobAndInboxGeneration() = runBlocking {
        database.jobDao().insertIgnore(job(id = "orphan-job", replayGeneration = 7))
        database.inboxDao().insertIgnore(inbox(id = "orphan-event", replayGeneration = 8))

        val reset = me.rerere.rikkahub.learning.handoff.LearningDerivedStateResetter(database).reset(
            streamId = kotlin.uuid.Uuid.parse(STREAM_B),
            observedHeadSeq = 1,
            reason = LearningStreamResetReason.CORRUPTION,
            frozenNowMs = 1_000,
        )

        assertEquals(9L, reset.replayGeneration)
        assertNull(database.jobDao().findById("orphan-job"))
        assertNull(database.inboxDao().find(STREAM_A, "orphan-event"))
    }

    private fun job(
        id: String,
        updatedAtMs: Long = 0,
        replayGeneration: Long = 0,
    ) = LearningJobEntity(
        id = id,
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "dedupe-$id",
        streamId = STREAM_A,
        sourceEventId = "event-$id",
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000003",
        state = LearningJobState.PENDING.name,
        priority = 0,
        attempts = 0,
        maxAttempts = 2,
        notBeforeMs = 0,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = 0,
        updatedAtMs = updatedAtMs,
        finishedAtMs = null,
        replayGeneration = replayGeneration,
    )

    private fun inbox(id: String, replayGeneration: Long = 2) = LearningInboxEventEntity(
        streamId = STREAM_A,
        eventId = id,
        outboxSeq = 2,
        eventTypeCode = "FUTURE_EVENT",
        eventSchemaVersion = 1,
        terminalState = null,
        decodeState = "UNKNOWN_NO_JOB",
        interpretationVersion = 1,
        sourceType = "COMMAND",
        sourceId = "source",
        sourceRevision = 1,
        missingRevisionReason = null,
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000003",
        conversationId = null,
        commandId = null,
        lineageId = null,
        parentCommandId = null,
        branchAnchorMessageId = null,
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        messageId = null,
        occurredAtMs = 0,
        createdAtMs = 0,
        ingestedAtMs = 0,
        replayGeneration = replayGeneration,
    )

    private fun checkpoint(streamId: String, replayGeneration: Long) =
        LearningStreamCheckpointEntity(
            streamId = streamId,
            lastContiguousSeq = 0,
            lastSeenHeadSeq = 0,
            replayGeneration = replayGeneration,
            resetReason = LearningStreamResetReason.NEW_STREAM.name,
            bootstrapState = LearningBootstrapState.REQUIRED.name,
            bootstrapHeadSeq = 0,
            coverageStartMs = null,
            commandCoverageStartMs = null,
            executionCoverageStartMs = null,
            updatedAtMs = 0,
        )

    private fun coordinator(
        processSessionId: String,
        clock: LearningJobClock,
    ) = LearningJobCoordinator(
        database = database,
        processSessionId = Uuid.parse(processSessionId),
        clock = clock,
        maxLeaseDurationMs = 100,
    )

    private class MutableLearningJobClock(var nowMs: Long) : LearningJobClock {
        override fun nowMs(): Long = nowMs
    }

    private companion object {
        const val STREAM_A = "00000000-0000-0000-0000-000000000001"
        const val STREAM_B = "00000000-0000-0000-0000-000000000002"
        const val PROCESS_A = "00000000-0000-0000-0000-000000000010"
        const val PROCESS_B = "00000000-0000-0000-0000-000000000020"
        const val WORKER_A = "00000000-0000-0000-0000-000000000011"
        const val WORKER_B = "00000000-0000-0000-0000-000000000021"
    }
}

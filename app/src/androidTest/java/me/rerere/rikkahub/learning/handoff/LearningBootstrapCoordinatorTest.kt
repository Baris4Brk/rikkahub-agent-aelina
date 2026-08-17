package me.rerere.rikkahub.learning.handoff

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
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
class LearningBootstrapCoordinatorTest {
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
    fun bootstrapConsumesFixedH0AndLeavesConcurrentTailForNormalConsumer() = runBlocking {
        database.checkpointDao().insert(
            LearningStreamCheckpointEntity(
                streamId = STREAM.toString(),
                lastContiguousSeq = 0L,
                lastSeenHeadSeq = 1L,
                replayGeneration = 1L,
                resetReason = LearningStreamResetReason.DERIVED_DATABASE_RECREATED.name,
                bootstrapState = LearningBootstrapState.REQUIRED.name,
                bootstrapHeadSeq = 1L,
                coverageStartMs = null,
                commandCoverageStartMs = null,
                executionCoverageStartMs = null,
                updatedAtMs = 0L,
            ),
        )
        val reader = MutableHeadReader(head = 1L)
        val coverage = LearningBootstrapCoverage(
            coverageStartMs = 10L,
            commandCoverageStartMs = 10L,
            executionCoverageStartMs = 20L,
            sourceAuthorityCoverageStartMs = 30L,
            feedbackCoverageStartMs = 40L,
        )
        val coordinator = LearningBootstrapCoordinator(
            database = database,
            outboxReader = reader,
            scanner = LearningReconciliationScanner { descriptor, cursorAccess, frozenNowMs, limits ->
                assertEquals(64, limits.maxRowsPerPage)
                assertEquals(1, limits.maxPages)
                // Simulates an authoritative write while the bounded scanner is running.
                reader.head = 2L
                cursorAccess.completeFakeScan(descriptor, frozenNowMs)
                coverage
            },
            clockMs = { 100L },
            monotonicMs = { 1L },
            maxReplayBatches = 1,
        )

        assertEquals(coverage, coordinator.bootstrap(frozenNowMs = 100L))

        val checkpoint = requireNotNull(database.checkpointDao().find(STREAM.toString()))
        assertEquals(LearningBootstrapState.COMPLETE.name, checkpoint.bootstrapState)
        assertEquals(1L, checkpoint.lastContiguousSeq)
        assertEquals(2L, checkpoint.lastSeenHeadSeq)
        assertEquals(1L, checkpoint.bootstrapHeadSeq)
        assertEquals(30L, checkpoint.sourceAuthorityCoverageStartMs)
        assertEquals(40L, checkpoint.feedbackCoverageStartMs)
        assertEquals(1, database.inboxDao().listAfter(STREAM.toString(), 0L, 10).size)
    }

    @Test
    fun degradedRetryKeepsPersistedH0InsteadOfChasingLiveHead() = runBlocking {
        database.checkpointDao().insert(
            checkpoint(
                state = LearningBootstrapState.RUNNING,
                lastSeenHead = 1L,
                bootstrapHead = 1L,
            ),
        )
        val reader = MutableHeadReader(head = 2L)
        var scannerHead = -1L
        val coordinator = LearningBootstrapCoordinator(
            database = database,
            outboxReader = reader,
            scanner = LearningReconciliationScanner { descriptor, cursorAccess, frozenNowMs, _ ->
                scannerHead = descriptor.headSequence
                cursorAccess.completeFakeScan(descriptor, frozenNowMs)
                LearningBootstrapCoverage(null, null, null)
            },
            clockMs = { 100L },
            monotonicMs = { 1L },
        )

        assertEquals(1, coordinator.recoverInterruptedBootstrap(frozenNowMs = 100L))
        coordinator.bootstrap(frozenNowMs = 100L)

        val stored = requireNotNull(database.checkpointDao().find(STREAM.toString()))
        assertEquals(1L, scannerHead)
        assertEquals(1L, stored.bootstrapHeadSeq)
        assertEquals(1L, stored.lastContiguousSeq)
        assertEquals(2L, stored.lastSeenHeadSeq)
        assertEquals(LearningBootstrapState.COMPLETE.name, stored.bootstrapState)
    }

    @Test
    fun emptyReplayPageBeforeH0IsExplicitCorruptionAndBecomesRetryable() = runBlocking {
        database.checkpointDao().insert(
            checkpoint(
                state = LearningBootstrapState.REQUIRED,
                lastSeenHead = 2L,
                bootstrapHead = 2L,
            ),
        )
        val reader = object : LearningOutboxReader {
            override suspend fun inspect() = LearningOutboxDescriptor(STREAM, 2L)

            override suspend fun readAfterThrough(
                descriptor: LearningOutboxDescriptor,
                afterSequence: Long,
                limit: Int,
            ): List<LearningHandoffEvent> = emptyList()
        }
        val coordinator = LearningBootstrapCoordinator(
            database = database,
            outboxReader = reader,
            scanner = LearningReconciliationScanner { descriptor, cursorAccess, frozenNowMs, _ ->
                cursorAccess.completeFakeScan(descriptor, frozenNowMs)
                LearningBootstrapCoverage(null, null, null)
            },
            clockMs = { 100L },
            monotonicMs = { 1L },
        )

        val failure = runCatching { coordinator.bootstrap(frozenNowMs = 100L) }
            .exceptionOrNull()

        assertTrue(failure is LearningBootstrapException)
        assertEquals(
            LearningBootstrapFailureCode.EMPTY_REPLAY_PAGE,
            (failure as LearningBootstrapException).code,
        )
        assertEquals(
            LearningBootstrapState.DEGRADED.name,
            database.checkpointDao().find(STREAM.toString())?.bootstrapState,
        )
    }

    @Test
    fun cancellationIsRethrownAndOwnedAttemptBecomesRetryable() = runBlocking {
        database.checkpointDao().insert(checkpoint())
        val coordinator = LearningBootstrapCoordinator(
            database = database,
            outboxReader = MutableHeadReader(head = 2L),
            scanner = LearningReconciliationScanner { _, _, _, _ ->
                throw CancellationException("test cancellation")
            },
            clockMs = { 100L },
            monotonicMs = { 1L },
        )

        val failure = runCatching { coordinator.bootstrap(frozenNowMs = 100L) }
            .exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(
            LearningBootstrapState.DEGRADED.name,
            database.checkpointDao().find(STREAM.toString())?.bootstrapState,
        )
        assertEquals(2L, database.checkpointDao().find(STREAM.toString())?.lastSeenHeadSeq)
    }

    @Test
    fun checkpointAtH0WithoutInboxEvidenceCannotCompleteBootstrap() = runBlocking {
        database.checkpointDao().insert(
            checkpoint().copy(lastContiguousSeq = 1L),
        )
        val coordinator = LearningBootstrapCoordinator(
            database = database,
            outboxReader = MutableHeadReader(head = 1L),
            scanner = LearningReconciliationScanner { descriptor, cursorAccess, frozenNowMs, _ ->
                cursorAccess.completeFakeScan(descriptor, frozenNowMs)
                LearningBootstrapCoverage(null, null, null)
            },
            clockMs = { 100L },
            monotonicMs = { 1L },
        )

        val failure = runCatching { coordinator.bootstrap(frozenNowMs = 100L) }
            .exceptionOrNull()

        assertTrue(failure is LearningBootstrapException)
        assertEquals(
            LearningBootstrapFailureCode.INBOX_COVERAGE_MISMATCH,
            (failure as LearningBootstrapException).code,
        )
        assertEquals(
            LearningBootstrapState.DEGRADED.name,
            database.checkpointDao().find(STREAM.toString())?.bootstrapState,
        )
    }

    @Test
    fun workRemainsKeepsRunningCursorAndRetryAtomicallyClearsItOnCompletion() = runBlocking {
        database.checkpointDao().insert(checkpoint())
        var scanCalls = 0
        val coordinator = LearningBootstrapCoordinator(
            database = database,
            outboxReader = MutableHeadReader(head = 1L),
            scanner = LearningReconciliationScanner { descriptor, cursorAccess, frozenNowMs, _ ->
                scanCalls += 1
                if (scanCalls == 1) {
                    val advanced = LearningReconciliationCursorV1.initialize(
                        streamId = descriptor.streamId.toString(),
                        frozenHeadSequence = descriptor.headSequence,
                        windowStartMs = 0L,
                        windowEndMs = frozenNowMs,
                    ).nextPhase()
                    check(
                        cursorAccess.compareAndSet(
                            expectedCursorJson = cursorAccess.load(),
                            newCursorJson = LearningReconciliationCursorV1Codec.encode(advanced),
                        ),
                    )
                    throw LearningReconciliationWorkRemainsException()
                }
                val resumed = requireNotNull(
                    LearningReconciliationCursorV1Codec.decode(cursorAccess.load()),
                )
                assertEquals(LearningReconciliationPhaseV1.EXECUTION, resumed.phase)
                var completed = resumed
                while (completed.phase != LearningReconciliationPhaseV1.FEEDBACK_REVISION) {
                    completed = completed.nextPhase()
                }
                check(
                    cursorAccess.compareAndSet(
                        expectedCursorJson = cursorAccess.load(),
                        newCursorJson = LearningReconciliationCursorV1Codec.encode(
                            completed.complete(),
                        ),
                    ),
                )
                LearningBootstrapCoverage(null, null, null)
            },
            clockMs = { 100L },
            monotonicMs = { 1L },
        )

        val firstFailure = runCatching { coordinator.bootstrap(frozenNowMs = 100L) }
            .exceptionOrNull()
        assertTrue(firstFailure is LearningReconciliationWorkRemainsException)
        val running = requireNotNull(database.checkpointDao().find(STREAM.toString()))
        assertEquals(LearningBootstrapState.RUNNING.name, running.bootstrapState)
        assertTrue(running.reconciliationCursorV1Json != null)

        coordinator.bootstrap(frozenNowMs = 100L)

        val complete = requireNotNull(database.checkpointDao().find(STREAM.toString()))
        assertEquals(LearningBootstrapState.COMPLETE.name, complete.bootstrapState)
        assertNull(complete.reconciliationCursorV1Json)
        assertEquals(2, scanCalls)
    }

    private fun checkpoint(
        state: LearningBootstrapState = LearningBootstrapState.REQUIRED,
        lastSeenHead: Long = 1L,
        bootstrapHead: Long? = 1L,
    ) = LearningStreamCheckpointEntity(
        streamId = STREAM.toString(),
        lastContiguousSeq = 0L,
        lastSeenHeadSeq = lastSeenHead,
        replayGeneration = 1L,
        resetReason = LearningStreamResetReason.DERIVED_DATABASE_RECREATED.name,
        bootstrapState = state.name,
        bootstrapHeadSeq = bootstrapHead,
        coverageStartMs = null,
        commandCoverageStartMs = null,
        executionCoverageStartMs = null,
        updatedAtMs = 0L,
    )

    private suspend fun LearningReconciliationCursorAccess.completeFakeScan(
        descriptor: LearningOutboxDescriptor,
        frozenNowMs: Long,
    ) {
        var cursor = LearningReconciliationCursorV1.initialize(
            streamId = descriptor.streamId.toString(),
            frozenHeadSequence = descriptor.headSequence,
            windowStartMs = 0L,
            windowEndMs = frozenNowMs,
        )
        while (cursor.phase != LearningReconciliationPhaseV1.FEEDBACK_REVISION) {
            cursor = cursor.nextPhase()
        }
        val completeJson = LearningReconciliationCursorV1Codec.encode(cursor.complete())
        check(compareAndSet(load(), completeJson))
    }

    private class MutableHeadReader(var head: Long) : LearningOutboxReader {
        override suspend fun inspect(): LearningOutboxDescriptor =
            LearningOutboxDescriptor(STREAM, head)

        override suspend fun readAfterThrough(
            descriptor: LearningOutboxDescriptor,
            afterSequence: Long,
            limit: Int,
        ): List<LearningHandoffEvent> = if (afterSequence < 1L && descriptor.headSequence >= 1L) {
            listOf(
                LearningHandoffEvent(
                    streamId = STREAM,
                    eventId = LEARNING_STREAM_INIT_EVENT_ID,
                    outboxSeq = 1L,
                    eventCode = LearningEventCode("STREAM_INIT", 1),
                    source = null,
                    correlation = LearningCorrelation(),
                    createdAtMs = 0L,
                ),
            )
        } else {
            emptyList()
        }
    }

    private companion object {
        val STREAM: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000051")
    }
}

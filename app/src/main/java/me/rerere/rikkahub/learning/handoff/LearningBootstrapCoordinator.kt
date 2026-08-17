package me.rerere.rikkahub.learning.handoff

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningDatabase
import kotlin.math.max

private const val DEFAULT_MAX_BOOTSTRAP_REPLAY_BATCHES = 256
private const val DEFAULT_MAX_BOOTSTRAP_ELAPSED_MS = 30_000L
private const val DEFAULT_BOOTSTRAP_REPLAY_BATCH_SIZE = 64

data class LearningBootstrapCoverage(
    val coverageStartMs: Long?,
    val commandCoverageStartMs: Long?,
    val executionCoverageStartMs: Long?,
    val sourceAuthorityCoverageStartMs: Long? = null,
    val feedbackCoverageStartMs: Long? = null,
)

enum class LearningBootstrapFailureCode {
    INVALID_COVERAGE,
    STREAM_CHANGED,
    HEAD_REWIND,
    INVALID_CHECKPOINT,
    EMPTY_REPLAY_PAGE,
    INBOX_COVERAGE_MISMATCH,
    REPLAY_BUDGET_EXHAUSTED,
    CLOCK_ROLLBACK,
}

class LearningBootstrapException(
    val code: LearningBootstrapFailureCode,
) : IllegalStateException("Learning bootstrap failed: $code")

/** A durable reconciliation cursor was advanced, but this bounded invocation used its page budget. */
class LearningReconciliationWorkRemainsException :
    IllegalStateException("Learning reconciliation work remains")

data class LearningBootstrapScanLimits(
    val maxRowsPerPage: Int,
    val maxPages: Int,
) {
    init {
        require(maxRowsPerPage in 1..DEFAULT_BOOTSTRAP_REPLAY_BATCH_SIZE) {
            "Unsafe bootstrap scan page size"
        }
        require(maxPages in 1..DEFAULT_MAX_BOOTSTRAP_REPLAY_BATCHES) {
            "Unsafe bootstrap scan page count"
        }
    }
}

/**
 * The scanner may only reconstruct terminal facts provable from current authoritative rows. It
 * must never invent intermediate command states or generation IDs absent from the source. Every
 * source query must honor [LearningBootstrapScanLimits.maxRowsPerPage]; if the complete supported
 * window cannot be scanned within [LearningBootstrapScanLimits.maxPages], it must fail rather than
 * return partial coverage.
 */
fun interface LearningReconciliationScanner {
    suspend fun scanAndRepairProvableTerminalEvents(
        stream: LearningOutboxDescriptor,
        cursorAccess: LearningReconciliationCursorAccess,
        frozenNowMs: Long,
        limits: LearningBootstrapScanLimits,
    ): LearningBootstrapCoverage
}

/** Content-free, call-scoped persistence capability; its implementation owns all DB references. */
interface LearningReconciliationCursorAccess {
    val streamId: String
    val replayGeneration: Long

    suspend fun load(): String?

    suspend fun compareAndSet(expectedCursorJson: String?, newCursorJson: String?): Boolean
}

class LearningBootstrapCoordinator(
    private val database: LearningDatabase,
    private val outboxReader: LearningOutboxReader,
    private val scanner: LearningReconciliationScanner,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxReplayBatches: Int = DEFAULT_MAX_BOOTSTRAP_REPLAY_BATCHES,
    private val maxElapsedMs: Long = DEFAULT_MAX_BOOTSTRAP_ELAPSED_MS,
    private val replayBatchSize: Int = DEFAULT_BOOTSTRAP_REPLAY_BATCH_SIZE,
) {
    init {
        require(maxReplayBatches in 1..DEFAULT_MAX_BOOTSTRAP_REPLAY_BATCHES) {
            "Unsafe bootstrap replay batch limit"
        }
        require(maxElapsedMs in 1L..DEFAULT_MAX_BOOTSTRAP_ELAPSED_MS) {
            "Unsafe bootstrap elapsed-time limit"
        }
        require(replayBatchSize in 1..DEFAULT_BOOTSTRAP_REPLAY_BATCH_SIZE) {
            "Unsafe bootstrap replay batch size"
        }
    }

    /** Call once after opening the derived DB, before any new bootstrap worker is scheduled. */
    suspend fun recoverInterruptedBootstrap(frozenNowMs: Long): Int {
        require(frozenNowMs >= 0L) { "Negative bootstrap recovery clock" }
        return database.checkpointDao().recoverInterruptedBootstrap(frozenNowMs)
    }

    suspend fun bootstrap(frozenNowMs: Long): LearningBootstrapCoverage {
        require(frozenNowMs >= 0L) { "Negative bootstrap clock" }
        var ownedAttempt: BootstrapAttempt? = null
        try {
            val completedCoverage = withTimeoutOrNull(maxElapsedMs) {
                val observedDescriptor = outboxReader.inspect()
                val checkpoint = database.checkpointDao()
                    .find(observedDescriptor.streamId.toString())
                    ?: throw LearningCheckpointConflictException()
                val fixedHead = checkpoint.bootstrapHeadSeq ?: observedDescriptor.headSequence
                if (fixedHead <= 0L) {
                    throw LearningBootstrapException(
                        LearningBootstrapFailureCode.INVALID_CHECKPOINT,
                    )
                }
                val highestPersistedObservation = max(
                    checkpoint.lastContiguousSeq,
                    max(checkpoint.lastSeenHeadSeq, fixedHead),
                )
                if (observedDescriptor.headSequence < highestPersistedObservation) {
                    throw LearningBootstrapException(LearningBootstrapFailureCode.HEAD_REWIND)
                }

                if (checkpoint.bootstrapState != LearningBootstrapState.RUNNING.name) {
                    val started = database.checkpointDao().startBootstrap(
                        streamId = observedDescriptor.streamId.toString(),
                        replayGeneration = checkpoint.replayGeneration,
                        bootstrapHeadSeq = fixedHead,
                        observedHeadSeq = observedDescriptor.headSequence,
                        updatedAtMs = frozenNowMs,
                    )
                    if (started != 1) throw LearningCheckpointConflictException()
                }
                ownedAttempt = BootstrapAttempt(
                    streamId = observedDescriptor.streamId.toString(),
                    replayGeneration = checkpoint.replayGeneration,
                )

                val runningCheckpoint = database.checkpointDao()
                    .find(observedDescriptor.streamId.toString())
                    ?: throw LearningCheckpointConflictException()
                if (
                    runningCheckpoint.replayGeneration != checkpoint.replayGeneration ||
                    runningCheckpoint.bootstrapState != LearningBootstrapState.RUNNING.name ||
                    runningCheckpoint.bootstrapHeadSeq != fixedHead
                ) {
                    throw LearningCheckpointConflictException()
                }

                val fixedDescriptor = observedDescriptor.copy(headSequence = fixedHead)
                val startedAt = monotonicMs()
                val scanned = scanner.scanAndRepairProvableTerminalEvents(
                    fixedDescriptor,
                    cursorAccess(
                        streamId = fixedDescriptor.streamId.toString(),
                        replayGeneration = checkpoint.replayGeneration,
                        updatedAtMs = frozenNowMs,
                    ),
                    frozenNowMs,
                    LearningBootstrapScanLimits(
                        maxRowsPerPage = replayBatchSize,
                        maxPages = maxReplayBatches,
                    ),
                )
                validateCoverage(scanned, frozenNowMs)
                ensureElapsedBudget(startedAt)
                replayThroughFixedHead(
                    descriptor = fixedDescriptor,
                    replayGeneration = checkpoint.replayGeneration,
                    startedAtMonotonicMs = startedAt,
                    notBeforeWallClockMs = frozenNowMs,
                )
                ensureElapsedBudget(startedAt)

                val afterReplay = outboxReader.inspect()
                when {
                    afterReplay.streamId != fixedDescriptor.streamId ->
                        throw LearningBootstrapException(
                            LearningBootstrapFailureCode.STREAM_CHANGED,
                        )

                    afterReplay.headSequence < fixedDescriptor.headSequence ->
                        throw LearningBootstrapException(LearningBootstrapFailureCode.HEAD_REWIND)
                }

                val completedAtMs = safeCompletionTime(frozenNowMs)
                val completedCursorJson = database.checkpointDao()
                    .findReconciliationCursor(
                        streamId = fixedDescriptor.streamId.toString(),
                        replayGeneration = checkpoint.replayGeneration,
                    ) ?: throw LearningCheckpointConflictException()
                val completedCursor = LearningReconciliationCursorV1Codec.decode(
                    completedCursorJson,
                ) ?: throw LearningCheckpointConflictException()
                if (
                    completedCursor.state != LearningReconciliationCursorStateV1.COMPLETE ||
                    completedCursor.streamId != fixedDescriptor.streamId.toString() ||
                    completedCursor.frozenHeadSequence != fixedDescriptor.headSequence
                ) {
                    throw LearningCheckpointConflictException()
                }
                database.withTransaction {
                    val absorbedSentinel = database.inboxDao().find(
                        streamId = fixedDescriptor.streamId.toString(),
                        eventId = LEARNING_STREAM_INIT_EVENT_ID,
                    )
                    val absorbedHead = database.inboxDao().maxSequence(
                        streamId = fixedDescriptor.streamId.toString(),
                        replayGeneration = checkpoint.replayGeneration,
                    )
                    if (
                        absorbedHead != fixedDescriptor.headSequence ||
                        absorbedSentinel == null ||
                        absorbedSentinel.replayGeneration != checkpoint.replayGeneration ||
                        absorbedSentinel.eventTypeCode != LearningEventType.STREAM_INIT.name ||
                        absorbedSentinel.decodeState != LearningEventDecodeState.KNOWN.name ||
                        LearningEventCode(
                            absorbedSentinel.eventTypeCode,
                            absorbedSentinel.eventSchemaVersion,
                        ).decodeState != LearningEventDecodeState.KNOWN ||
                        absorbedSentinel.terminalState != null ||
                        absorbedSentinel.sourceType != null ||
                        absorbedSentinel.sourceId != null ||
                        absorbedSentinel.scopeKind != null ||
                        absorbedSentinel.conversationId != null ||
                        absorbedSentinel.commandId != null ||
                        absorbedSentinel.lineageId != null ||
                        absorbedSentinel.parentCommandId != null ||
                        absorbedSentinel.branchAnchorMessageId != null ||
                        absorbedSentinel.generationRunId != null ||
                        absorbedSentinel.executionId != null ||
                        absorbedSentinel.toolCallId != null ||
                        absorbedSentinel.messageId != null
                    ) {
                        throw LearningBootstrapException(
                            LearningBootstrapFailureCode.INBOX_COVERAGE_MISMATCH,
                        )
                    }
                    val completed = database.checkpointDao().completeBootstrap(
                        streamId = fixedDescriptor.streamId.toString(),
                        replayGeneration = checkpoint.replayGeneration,
                        expectedBootstrapHeadSeq = fixedDescriptor.headSequence,
                        observedHeadSeq = afterReplay.headSequence,
                        coverageStartMs = scanned.coverageStartMs,
                        commandCoverageStartMs = scanned.commandCoverageStartMs,
                        executionCoverageStartMs = scanned.executionCoverageStartMs,
                        sourceAuthorityCoverageStartMs =
                            scanned.sourceAuthorityCoverageStartMs,
                        feedbackCoverageStartMs = scanned.feedbackCoverageStartMs,
                        expectedReconciliationCursorJson = completedCursorJson,
                        updatedAtMs = completedAtMs,
                    )
                    if (completed != 1) throw LearningCheckpointConflictException()
                }
                scanned
            }
            return completedCoverage ?: throw LearningBootstrapException(
                LearningBootstrapFailureCode.REPLAY_BUDGET_EXHAUSTED,
            )
        } catch (cancelled: CancellationException) {
            markOwnedAttemptDegraded(ownedAttempt, frozenNowMs, cancelled)
            throw cancelled
        } catch (workRemains: LearningReconciliationWorkRemainsException) {
            throw workRemains
        } catch (failure: Exception) {
            markOwnedAttemptDegraded(ownedAttempt, frozenNowMs, failure)
            throw failure
        }
    }

    private suspend fun replayThroughFixedHead(
        descriptor: LearningOutboxDescriptor,
        replayGeneration: Long,
        startedAtMonotonicMs: Long,
        notBeforeWallClockMs: Long,
    ) {
        var batches = 0
        while (true) {
            ensureElapsedBudget(startedAtMonotonicMs)
            val checkpoint = database.checkpointDao().find(descriptor.streamId.toString())
                ?: throw LearningCheckpointConflictException()
            if (
                checkpoint.replayGeneration != replayGeneration ||
                checkpoint.bootstrapState != LearningBootstrapState.RUNNING.name ||
                checkpoint.bootstrapHeadSeq != descriptor.headSequence ||
                checkpoint.lastContiguousSeq > descriptor.headSequence
            ) {
                throw LearningCheckpointConflictException()
            }
            if (checkpoint.lastContiguousSeq == descriptor.headSequence) return
            if (batches >= maxReplayBatches) {
                throw LearningBootstrapException(
                    LearningBootstrapFailureCode.REPLAY_BUDGET_EXHAUSTED,
                )
            }

            val events = outboxReader.readAfterThrough(
                descriptor = descriptor,
                afterSequence = checkpoint.lastContiguousSeq,
                limit = replayBatchSize,
            )
            if (events.isEmpty()) {
                throw LearningBootstrapException(LearningBootstrapFailureCode.EMPTY_REPLAY_PAGE)
            }
            val ingestedAtMs = safeCompletionTime(notBeforeWallClockMs)
            if (events.any { it.createdAtMs > ingestedAtMs }) {
                throw LearningBootstrapException(LearningBootstrapFailureCode.CLOCK_ROLLBACK)
            }
            LearningInboxBatchStore(database).ingest(
                LearningIngestBatch(
                    streamId = descriptor.streamId,
                    replayGeneration = replayGeneration,
                    expectedPreviousSeq = checkpoint.lastContiguousSeq,
                    observedHeadSeq = checkpoint.lastSeenHeadSeq,
                    events = events,
                    ingestedAtMs = ingestedAtMs,
                ),
            )
            batches += 1
        }
    }

    private suspend fun markOwnedAttemptDegraded(
        attempt: BootstrapAttempt?,
        frozenNowMs: Long,
        originalFailure: Exception,
    ) {
        if (attempt == null) return
        try {
            withContext(NonCancellable) {
                database.checkpointDao().markBootstrapDegraded(
                    streamId = attempt.streamId,
                    replayGeneration = attempt.replayGeneration,
                    updatedAtMs = frozenNowMs,
                )
            }
        } catch (cleanupFailure: Exception) {
            originalFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun validateCoverage(coverage: LearningBootstrapCoverage, frozenNowMs: Long) {
        val sourceFloors = listOfNotNull(
            coverage.commandCoverageStartMs,
            coverage.executionCoverageStartMs,
            coverage.sourceAuthorityCoverageStartMs,
            coverage.feedbackCoverageStartMs,
        )
        val allValues = listOfNotNull(coverage.coverageStartMs) + sourceFloors
        if (allValues.any { it < 0L || it > frozenNowMs }) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.INVALID_COVERAGE)
        }
        if (
            sourceFloors.isEmpty() != (coverage.coverageStartMs == null) ||
            (sourceFloors.isNotEmpty() && coverage.coverageStartMs != sourceFloors.minOrNull())
        ) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.INVALID_COVERAGE)
        }
    }

    private fun ensureElapsedBudget(startedAtMs: Long) {
        val current = monotonicMs()
        if (current < startedAtMs) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.CLOCK_ROLLBACK)
        }
        if (current - startedAtMs > maxElapsedMs) {
            throw LearningBootstrapException(
                LearningBootstrapFailureCode.REPLAY_BUDGET_EXHAUSTED,
            )
        }
    }

    private fun safeCompletionTime(notBeforeMs: Long): Long {
        val current = clockMs()
        if (current < notBeforeMs) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.CLOCK_ROLLBACK)
        }
        return current
    }

    private fun cursorAccess(
        streamId: String,
        replayGeneration: Long,
        updatedAtMs: Long,
    ): LearningReconciliationCursorAccess {
        val dao = database.checkpointDao()
        return object : LearningReconciliationCursorAccess {
            override val streamId: String = streamId
            override val replayGeneration: Long = replayGeneration

            override suspend fun load(): String? =
                dao.findReconciliationCursor(streamId, replayGeneration)

            override suspend fun compareAndSet(
                expectedCursorJson: String?,
                newCursorJson: String?,
            ): Boolean = dao.compareAndSetReconciliationCursor(
                streamId = streamId,
                replayGeneration = replayGeneration,
                expectedCursorJson = expectedCursorJson,
                newCursorJson = newCursorJson,
                updatedAtMs = updatedAtMs,
            ) == 1
        }
    }

    private data class BootstrapAttempt(
        val streamId: String,
        val replayGeneration: Long,
    )
}

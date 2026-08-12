package me.rerere.rikkahub.learning.runtime

import me.rerere.rikkahub.learning.handoff.LearningBootstrapCoordinator
import me.rerere.rikkahub.learning.handoff.LearningCheckpointConflictException
import me.rerere.rikkahub.learning.handoff.LearningConsumeResult
import me.rerere.rikkahub.learning.handoff.LearningHandoffConsumer
import me.rerere.rikkahub.learning.handoff.LearningOutboxReader
import me.rerere.rikkahub.learning.handoff.LearningReconciliationScanner
import me.rerere.rikkahub.learning.jobs.LearningDrainResult
import me.rerere.rikkahub.learning.jobs.LearningDrainMode
import me.rerere.rikkahub.learning.jobs.LearningJobBatchStopReason
import me.rerere.rikkahub.learning.jobs.LearningJobCoordinator
import me.rerere.rikkahub.learning.jobs.LearningJobHandlerRegistry
import me.rerere.rikkahub.learning.jobs.LearningJobRunner
import me.rerere.rikkahub.learning.storage.LearningDatabase
import kotlin.uuid.Uuid

private const val MAX_MAINTENANCE_JOBS = 16
private const val MAX_CONSUMER_ELAPSED_MS = 5_000L
private const val MAX_BOOTSTRAP_ELAPSED_MS = 30_000L

data class LearningRuntimeMaintenanceRequest(
    val maxJobs: Int,
    val monotonicDeadlineMs: Long,
    val processJobs: Boolean = false,
    val mode: LearningDrainMode = LearningDrainMode.RECONCILE_AND_DRAIN,
) {
    init {
        require(maxJobs in 1..MAX_MAINTENANCE_JOBS) { "Unsafe maintenance job limit" }
        require(monotonicDeadlineMs >= 0L) { "Negative maintenance deadline" }
    }
}

sealed interface LearningRuntimeMaintenanceResult {
    data class Completed(val drainResult: LearningDrainResult) : LearningRuntimeMaintenanceResult

    data object Disabled : LearningRuntimeMaintenanceResult

    data class Unavailable(
        val errorCode: LearningRuntimeErrorCode,
    ) : LearningRuntimeMaintenanceResult
}

fun interface LearningRuntimeMaintenancePort {
    suspend fun runMaintenance(
        request: LearningRuntimeMaintenanceRequest,
    ): LearningRuntimeMaintenanceResult
}

/**
 * One bounded maintenance cycle while [LearningRuntimeFacade] owns the database/session fence.
 * The database is a call-scoped parameter and must never be retained or returned by this layer.
 */
internal suspend fun runLearningRuntimeMaintenanceCycle(
    database: LearningDatabase,
    session: LearningRuntimeSession,
    request: LearningRuntimeMaintenanceRequest,
    outboxReader: LearningOutboxReader,
    reconciliationScanner: LearningReconciliationScanner,
    frozenNowMs: Long,
    wallClockMs: () -> Long,
    monotonicMs: () -> Long,
    processSessionId: Uuid,
    jobHandlerRegistry: LearningJobHandlerRegistry,
): LearningDrainResult {
    require(frozenNowMs >= 0L) { "Negative maintenance clock" }
    if (!session.isCurrent()) return LearningDrainResult.RETRY

    val consumerBudgetMs = remainingBudgetMs(request.monotonicDeadlineMs, monotonicMs)
    if (consumerBudgetMs <= 0L) return LearningDrainResult.RETRY
    val consumeResult = LearningHandoffConsumer(
        database = database,
        outboxReader = outboxReader,
        maxBatchElapsedMs = minOf(MAX_CONSUMER_ELAPSED_MS, consumerBudgetMs),
    ).consumeOnce(frozenNowMs)
    if (!session.isCurrent()) return LearningDrainResult.RETRY

    return when (consumeResult) {
        is LearningConsumeResult.ResetRequired,
        is LearningConsumeResult.BootstrapRequired,
        -> {
            val bootstrapBudgetMs = remainingBudgetMs(
                request.monotonicDeadlineMs,
                monotonicMs,
            )
            if (bootstrapBudgetMs <= 0L) {
                LearningDrainResult.WORK_REMAINS
            } else {
                LearningBootstrapCoordinator(
                    database = database,
                    outboxReader = outboxReader,
                    scanner = reconciliationScanner,
                    clockMs = wallClockMs,
                    monotonicMs = monotonicMs,
                    maxElapsedMs = minOf(MAX_BOOTSTRAP_ELAPSED_MS, bootstrapBudgetMs),
                ).bootstrap(frozenNowMs)
                LearningDrainResult.WORK_REMAINS
            }
        }

        is LearningConsumeResult.Consumed -> {
            val checkpoint = database.checkpointDao().listAll().singleOrNull()
                ?: throw LearningCheckpointConflictException()
            when {
                checkpoint.lastContiguousSeq < checkpoint.lastSeenHeadSeq ->
                    LearningDrainResult.WORK_REMAINS

                request.mode == LearningDrainMode.RECONCILE_AND_DRAIN ->
                    reconcileAndDrainOnce(
                        database = database,
                        session = session,
                        request = request,
                        outboxReader = outboxReader,
                        reconciliationScanner = reconciliationScanner,
                        frozenNowMs = frozenNowMs,
                        wallClockMs = wallClockMs,
                        monotonicMs = monotonicMs,
                        alreadyDidWork = true,
                        processSessionId = processSessionId,
                        jobHandlerRegistry = jobHandlerRegistry,
                    )

                request.processJobs -> runJobsOnce(
                    database = database,
                    session = session,
                    request = request,
                    wallClockMs = wallClockMs,
                    monotonicMs = monotonicMs,
                    processSessionId = processSessionId,
                    jobHandlerRegistry = jobHandlerRegistry,
                    alreadyDidWork = true,
                )
                else -> LearningDrainResult.DID_WORK
            }
        }

        LearningConsumeResult.BudgetExhausted -> LearningDrainResult.RETRY

        LearningConsumeResult.Idle -> {
            if (request.mode == LearningDrainMode.RECONCILE_AND_DRAIN) {
                reconcileAndDrainOnce(
                    database = database,
                    session = session,
                    request = request,
                    outboxReader = outboxReader,
                    reconciliationScanner = reconciliationScanner,
                    frozenNowMs = frozenNowMs,
                    wallClockMs = wallClockMs,
                    monotonicMs = monotonicMs,
                    alreadyDidWork = false,
                    processSessionId = processSessionId,
                    jobHandlerRegistry = jobHandlerRegistry,
                )
            } else if (request.processJobs) {
                runJobsOnce(
                    database = database,
                    session = session,
                    request = request,
                    wallClockMs = wallClockMs,
                    monotonicMs = monotonicMs,
                    processSessionId = processSessionId,
                    jobHandlerRegistry = jobHandlerRegistry,
                    alreadyDidWork = false,
                )
            } else {
                LearningDrainResult.IDLE
            }
        }
    }
}

private suspend fun reconcileAndDrainOnce(
    database: LearningDatabase,
    session: LearningRuntimeSession,
    request: LearningRuntimeMaintenanceRequest,
    outboxReader: LearningOutboxReader,
    reconciliationScanner: LearningReconciliationScanner,
    frozenNowMs: Long,
    wallClockMs: () -> Long,
    monotonicMs: () -> Long,
    alreadyDidWork: Boolean,
    processSessionId: Uuid,
    jobHandlerRegistry: LearningJobHandlerRegistry,
): LearningDrainResult {
    if (!session.isCurrent() || remainingBudgetMs(request.monotonicDeadlineMs, monotonicMs) <= 0L) {
        return LearningDrainResult.WORK_REMAINS
    }
    val fixedDescriptor = outboxReader.inspect()
    reconciliationScanner.scanAndRepairProvableTerminalEvents(
        stream = fixedDescriptor,
        frozenNowMs = frozenNowMs,
        limits = me.rerere.rikkahub.learning.handoff.LearningBootstrapScanLimits(
            maxRowsPerPage = 64,
            maxPages = 16,
        ),
    )
    if (!session.isCurrent() || remainingBudgetMs(request.monotonicDeadlineMs, monotonicMs) <= 0L) {
        return LearningDrainResult.WORK_REMAINS
    }
    val afterScan = outboxReader.inspect()
    if (afterScan.streamId != fixedDescriptor.streamId ||
        afterScan.headSequence < fixedDescriptor.headSequence
    ) {
        return LearningDrainResult.RETRY
    }
    val repaired = LearningHandoffConsumer(
        database = database,
        outboxReader = outboxReader,
        maxBatchElapsedMs = minOf(
            MAX_CONSUMER_ELAPSED_MS,
            remainingBudgetMs(request.monotonicDeadlineMs, monotonicMs).coerceAtLeast(1L),
        ),
    ).consumeOnce(frozenNowMs)
    if (!session.isCurrent()) return LearningDrainResult.RETRY
    return when (repaired) {
        is LearningConsumeResult.ResetRequired,
        is LearningConsumeResult.BootstrapRequired,
        -> LearningDrainResult.WORK_REMAINS

        is LearningConsumeResult.Consumed -> LearningDrainResult.WORK_REMAINS
        LearningConsumeResult.BudgetExhausted -> LearningDrainResult.RETRY
        LearningConsumeResult.Idle -> when {
            request.processJobs -> runJobsOnce(
                database = database,
                session = session,
                request = request,
                wallClockMs = wallClockMs,
                monotonicMs = monotonicMs,
                processSessionId = processSessionId,
                jobHandlerRegistry = jobHandlerRegistry,
                alreadyDidWork = alreadyDidWork,
            )
            alreadyDidWork -> LearningDrainResult.DID_WORK
            else -> LearningDrainResult.IDLE
        }
    }
}

private suspend fun runJobsOnce(
    database: LearningDatabase,
    session: LearningRuntimeSession,
    request: LearningRuntimeMaintenanceRequest,
    wallClockMs: () -> Long,
    monotonicMs: () -> Long,
    processSessionId: Uuid,
    jobHandlerRegistry: LearningJobHandlerRegistry,
    alreadyDidWork: Boolean,
): LearningDrainResult {
    if (!session.isCurrent()) return LearningDrainResult.RETRY
    val runner = LearningJobRunner(
        coordinator = LearningJobCoordinator(
            database = database,
            processSessionId = processSessionId,
            clock = { wallClockMs().coerceAtLeast(0L) },
        ),
        registry = jobHandlerRegistry,
        monotonicMs = monotonicMs,
    )
    val batch = runner.runBounded(
        maxJobs = request.maxJobs,
        monotonicDeadlineMs = request.monotonicDeadlineMs,
        isRuntimeCurrent = session::isCurrent,
    )
    return when (batch.stopReason) {
        LearningJobBatchStopReason.LIMIT_REACHED -> LearningDrainResult.WORK_REMAINS
        LearningJobBatchStopReason.DEADLINE_REACHED,
        LearningJobBatchStopReason.CONTENDED,
        LearningJobBatchStopReason.CLOCK_ROLLBACK,
        LearningJobBatchStopReason.LOST_LEASE,
        -> LearningDrainResult.RETRY

        LearningJobBatchStopReason.IDLE,
        LearningJobBatchStopReason.NO_REGISTERED_HANDLER,
        LearningJobBatchStopReason.WAITING_CONFIGURATION,
        -> if (alreadyDidWork || batch.didWork) {
            LearningDrainResult.DID_WORK
        } else {
            LearningDrainResult.IDLE
        }
    }
}

private fun remainingBudgetMs(
    monotonicDeadlineMs: Long,
    monotonicMs: () -> Long,
): Long {
    val currentMs = monotonicMs()
    check(currentMs >= 0L) { "Negative monotonic clock" }
    return if (currentMs >= monotonicDeadlineMs) 0L else monotonicDeadlineMs - currentMs
}

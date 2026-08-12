package me.rerere.rikkahub.learning.jobs

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticCode
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticSample
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticState
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticsStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val MAX_JOBS_PER_DRAIN = 16
// A provider-backed handler is allowed 120 seconds. The batch must leave enough monotonic budget
// for that request to finish and for its fenced output transaction to commit.
private const val MAX_DRAIN_RUNTIME_MS = 150_000L

/**
 * WorkManager is only a durable wake-up mechanism. Job ownership, retries and fencing live in the
 * Learning database and are never inferred from WorkManager's attempt counter.
 */
class LearningDrainWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val coordinator: LearningDrainCoordinator by inject()
    private val scheduler: LearningWorkScheduler by inject()
    private val diagnostics: LearningDiagnosticsStore by inject()

    override suspend fun doWork(): Result {
        val mode = LearningDrainMode.parseOrReconcile(inputData.getString(LEARNING_DRAIN_MODE_KEY))
        val deadline = runCatching {
            Math.addExact(SystemClock.elapsedRealtime(), MAX_DRAIN_RUNTIME_MS)
        }.getOrElse { return Result.retry() }
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val drainResult = coordinator.drainBounded(
                    maxJobs = MAX_JOBS_PER_DRAIN,
                    monotonicDeadlineMs = deadline,
                    mode = mode,
                )
            recordRuntimeDiagnostic(
                result = drainResult,
                elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            )
            when (drainResult) {
                LearningDrainResult.IDLE,
                LearningDrainResult.DID_WORK,
                LearningDrainResult.DISABLED,
                -> Result.success()

                LearningDrainResult.WORK_REMAINS -> {
                    // Leave a fresh bounded trigger; do not encode durable job state in this
                    // WorkRequest's runAttemptCount/backoff history.
                    scheduler.wake(mode)
                    Result.success()
                }

                LearningDrainResult.RETRY -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordRuntimeDiagnostic(
                result = LearningDrainResult.RETRY,
                elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
            )
            Result.retry()
        }
    }

    private fun recordRuntimeDiagnostic(result: LearningDrainResult, elapsedMs: Long) {
        diagnostics.record(
            LearningDiagnosticSample(
                recordedAtMs = System.currentTimeMillis().coerceAtLeast(0L),
                code = LearningDiagnosticCode.WORKER_RUNTIME,
                state = when (result) {
                    LearningDrainResult.IDLE -> LearningDiagnosticState.IDLE
                    LearningDrainResult.DID_WORK -> LearningDiagnosticState.DONE
                    LearningDrainResult.WORK_REMAINS,
                    LearningDrainResult.RETRY,
                    -> LearningDiagnosticState.RETRY
                    LearningDrainResult.DISABLED -> LearningDiagnosticState.DISABLED
                },
                primaryValue = elapsedMs,
            ),
        )
    }
}

enum class LearningDrainResult {
    IDLE,
    DID_WORK,
    WORK_REMAINS,
    RETRY,
    DISABLED,
}

/**
 * A lightweight authority wake only drains committed outbox rows. Startup and periodic recovery
 * first reconcile terminal authority snapshots so time spent with handoff disabled cannot create
 * a permanent gap. Unknown/legacy WorkManager input deliberately chooses the safer reconcile path.
 */
enum class LearningDrainMode {
    DRAIN_ONLY,
    RECONCILE_AND_DRAIN,
    ;

    companion object {
        fun parseOrReconcile(raw: String?): LearningDrainMode =
            entries.firstOrNull { it.name == raw } ?: RECONCILE_AND_DRAIN
    }
}

internal const val LEARNING_DRAIN_MODE_KEY: String = "learning_drain_mode_v1"

fun interface LearningDrainCoordinator {
    suspend fun drainBounded(
        maxJobs: Int,
        monotonicDeadlineMs: Long,
        mode: LearningDrainMode,
    ): LearningDrainResult
}

/** Safe Koin fallback until P0 handoff/jobs are explicitly enabled. */
object DisabledLearningDrainCoordinator : LearningDrainCoordinator {
    override suspend fun drainBounded(
        maxJobs: Int,
        monotonicDeadlineMs: Long,
        mode: LearningDrainMode,
    ): LearningDrainResult = LearningDrainResult.DISABLED
}

package me.rerere.rikkahub.memory.dreaming.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import me.rerere.rikkahub.memory.dreaming.runtime.DreamObserverRuntime
import me.rerere.rikkahub.memory.dreaming.runtime.DreamObserverWorkerDirective
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Local journal replay only: this worker has no provider, model, prompt, or network dependency. */
class DreamObserverWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val runtime: DreamObserverRuntime by inject()
    private val scheduler: DreamObserverWorkScheduler by inject()

    override suspend fun doWork(): Result {
        val scopeId = DreamScopeId.parseOrNull(inputData.getString(KEY_SCOPE_ID))
            ?: return Result.failure()
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        if (runCatching { requireCanonicalDreamRunId(runId) }.isFailure) return Result.failure()

        return try {
            when (runtime.observe(scopeId, runId).directive) {
                DreamObserverWorkerDirective.COMPLETE -> Result.success()
                DreamObserverWorkerDirective.RESCAN -> {
                    scheduler.enqueueDirtyScan()
                    Result.success()
                }

                DreamObserverWorkerDirective.RETRY -> retryOrRecover()
                DreamObserverWorkerDirective.BLOCKED -> Result.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Observer replay failed", error)
            retryOrRecover()
        }
    }

    private fun retryOrRecover(): Result =
        if (runAttemptCount < MAX_OBSERVER_WORK_RETRIES) {
            Result.retry()
        } else {
            // Do not reopen this run ID. Its lease will be recovered and the persistent dirty scan
            // will allocate a fresh ID; enqueue failure still leaves the periodic scan as fallback.
            runCatching { scheduler.enqueueDirtyScan() }
            Result.failure()
        }

    companion object {
        const val KEY_SCOPE_ID = "dream_scope_id"
        const val KEY_RUN_ID = "dream_run_id"
        private const val TAG = "DreamObserverWorker"
    }
}

/** Startup/periodic reconciliation for commits that happened before a best-effort enqueue. */
class DreamObserverSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val runtime: DreamObserverRuntime by inject()

    override suspend fun doWork(): Result = try {
        val scan = runtime.scanDirtyScopes()
        if (scan.saturated && runAttemptCount < MAX_OBSERVER_SCAN_RETRIES) {
            Result.retry()
        } else {
            Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Observer dirty-scope scan failed", error)
        if (runAttemptCount < MAX_OBSERVER_SCAN_RETRIES) Result.retry() else Result.failure()
    }

    private companion object {
        const val TAG = "DreamObserverSweep"
    }
}

private const val MAX_OBSERVER_WORK_RETRIES = 5
private const val MAX_OBSERVER_SCAN_RETRIES = 5

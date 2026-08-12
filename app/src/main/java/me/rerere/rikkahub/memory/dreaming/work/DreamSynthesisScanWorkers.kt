package me.rerere.rikkahub.memory.dreaming.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSynthesisCoordinator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Startup, post-commit, follow-up, UTC-rollover, and periodic scans share one bounded worker. */
class DreamSynthesisSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val coordinator: DreamSynthesisCoordinator by inject()
    private val scheduler: DreamSynthesisWorkScheduler by inject()

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_SCAN_REASON)?.let { raw ->
            DreamSynthesisScanReason.entries.singleOrNull { it.name == raw }
        } ?: return Result.failure()
        return try {
            val result = coordinator.scanDirtyScopes(reason)
            if (result.saturated) {
                scheduler.enqueueDirtyScan(DreamSynthesisScanReason.FOLLOW_UP)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Dream synthesis dirty scan failed", error)
            if (runAttemptCount < MAX_SYNTHESIS_SCAN_RETRIES) Result.retry()
            else Result.failure()
        }
    }

    companion object {
        const val KEY_SCAN_REASON = "dream_synthesis_scan_reason"
        private const val TAG = "DreamSynthesisSweep"
    }
}

private const val MAX_SYNTHESIS_SCAN_RETRIES = 3

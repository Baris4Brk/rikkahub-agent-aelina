package me.rerere.rikkahub.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

class MemoryExtractionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val coordinator: MemoryV2Coordinator by inject()
    private val dao: MemoryV2Dao by inject()
    private val scheduler: MemoryWorkScheduler by inject()

    override suspend fun doWork(): Result {
        val scopeId = inputData.getString(KEY_SCOPE_ID) ?: return Result.failure()
        if (inputData.getBoolean(KEY_DISPATCH_ONLY, false)) {
            // This lightweight worker owns only the idle debounce. It hands actual extraction to
            // the append-only processing chain, so replacing a newer debounce never cancels an
            // already claimed model call.
            scheduler.continueNow(scopeId)
            return Result.success()
        }
        val processResult = coordinator.process(
            MemoryProcessRequest(
                scopeId = scopeId,
                workerId = "memory-worker-${Uuid.random()}",
            ),
        )
        return when (val result = processResult) {
            MemoryProcessResult.NothingToDo,
            is MemoryProcessResult.Paused,
            -> Result.success()

            is MemoryProcessResult.Completed -> {
                val pending = dao.countPendingCaptures(scopeId)
                when (
                    memoryWorkerFollowUpAction(
                        pendingCaptures = pending,
                        automaticRetryFailedCaptures = result.automaticRetryFailedCaptures,
                        runAttemptCount = runAttemptCount,
                    )
                ) {
                    MemoryWorkerFollowUpAction.CONTINUE -> {
                        scheduler.continueNow(scopeId)
                        Result.success()
                    }

                    MemoryWorkerFollowUpAction.RETRY -> Result.retry()
                    MemoryWorkerFollowUpAction.SUCCESS -> Result.success()
                }
            }

            is MemoryProcessResult.Failed -> {
                if (result.retryable && runAttemptCount < MAX_WORK_RETRIES) Result.retry()
                else Result.failure()
            }
        }
    }

    companion object {
        const val KEY_SCOPE_ID = "memory_scope_id"
        const val KEY_DISPATCH_ONLY = "memory_dispatch_only"
    }
}

/** Chooses a follow-up without letting a manual-only failure block unrelated queued captures. */
internal fun memoryWorkerFollowUpAction(
    pendingCaptures: Int,
    automaticRetryFailedCaptures: Int,
    runAttemptCount: Int,
): MemoryWorkerFollowUpAction = when {
    automaticRetryFailedCaptures > 0 && runAttemptCount < MAX_WORK_RETRIES ->
        MemoryWorkerFollowUpAction.RETRY

    pendingCaptures > 0 -> MemoryWorkerFollowUpAction.CONTINUE
    else -> MemoryWorkerFollowUpAction.SUCCESS
}

internal enum class MemoryWorkerFollowUpAction {
    CONTINUE,
    RETRY,
    SUCCESS,
}

private const val MAX_WORK_RETRIES = 3

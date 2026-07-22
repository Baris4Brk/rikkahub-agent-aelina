package me.rerere.rikkahub.memory

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class AndroidMemoryWorkScheduler(
    context: Context,
) : MemoryWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    override suspend fun schedule(request: MemoryWorkRequest) {
        // Debouncing must be allowed to replace an earlier *delayed* trigger, but must never
        // replace the extraction chain itself. `REPLACE` on a shared unique name cancels a
        // running CoroutineWorker and used to turn a successfully claimed batch into a failed
        // provider attempt whenever a new chat turn arrived.
        enqueue(memoryDebounceWorkPlan(request.scopeId, request.delayMs))
    }

    override suspend fun continueNow(scopeId: String) {
        // APPEND_OR_REPLACE preserves an active extraction and puts any follow-up claim behind
        // it. A failed/cancelled old chain is safely replaced, so an explicit retry can recover.
        enqueue(memoryProcessingWorkPlan(scopeId))
    }

    override suspend fun cancel(scopeId: String) {
        workManager.cancelUniqueWork(memoryDebounceWorkName(scopeId))
        workManager.cancelUniqueWork(memoryProcessingWorkName(scopeId))
        // v171-v173 used this single chain name. Keep an explicit off/cancel action effective
        // for work persisted before the split; do not cancel it during normal scheduling.
        workManager.cancelUniqueWork(legacyMemoryWorkName(scopeId))
    }

    private fun enqueue(plan: MemoryWorkEnqueuePlan) {
        workManager.enqueueUniqueWork(
            plan.uniqueWorkName,
            plan.policy,
            workRequest(plan),
        )
    }

    private fun workRequest(plan: MemoryWorkEnqueuePlan) =
        OneTimeWorkRequestBuilder<MemoryExtractionWorker>()
            .setInitialDelay(plan.delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(MemoryExtractionWorker.KEY_SCOPE_ID, plan.scopeId)
                    .putBoolean(MemoryExtractionWorker.KEY_DISPATCH_ONLY, plan.dispatchOnly)
                    .build(),
            )
            .build()
}

/**
 * A delayed trigger and an extraction use independent unique-work chains.
 *
 * Replacing only the former keeps the "idle since latest turn" debounce exact, while append-only
 * processing serializes claims and prevents a new turn or Process Now from cancelling an active
 * model call.
 */
internal data class MemoryWorkEnqueuePlan(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
    val scopeId: String,
    val delayMs: Long,
    val dispatchOnly: Boolean,
)

internal fun memoryDebounceWorkPlan(scopeId: String, delayMs: Long): MemoryWorkEnqueuePlan =
    MemoryWorkEnqueuePlan(
        uniqueWorkName = memoryDebounceWorkName(scopeId),
        policy = ExistingWorkPolicy.REPLACE,
        scopeId = scopeId,
        delayMs = delayMs.coerceAtLeast(0L),
        dispatchOnly = true,
    )

internal fun memoryProcessingWorkPlan(scopeId: String): MemoryWorkEnqueuePlan =
    MemoryWorkEnqueuePlan(
        uniqueWorkName = memoryProcessingWorkName(scopeId),
        policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        scopeId = scopeId,
        delayMs = 0L,
        dispatchOnly = false,
    )

private fun memoryDebounceWorkName(scopeId: String): String =
    "memory_v2_debounce_${scopeId.hashCode().toUInt()}"

private fun memoryProcessingWorkName(scopeId: String): String =
    "memory_v2_process_${scopeId.hashCode().toUInt()}"

private fun legacyMemoryWorkName(scopeId: String): String =
    "memory_v2_${scopeId.hashCode().toUInt()}"

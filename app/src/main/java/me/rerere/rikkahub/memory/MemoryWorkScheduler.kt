package me.rerere.rikkahub.memory

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class AndroidMemoryWorkScheduler(
    context: Context,
) : MemoryWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    init {
        // Retention is a privacy/correctness deadline, not a side effect of extraction. Arm both
        // an immediate process-start catch-up and persistent daily maintenance even when the user
        // never creates another capture.
        runCatching { enqueueMaintenance(memoryMaintenanceSchedulePlan()) }
            .onFailure { error -> Log.w(TAG, "Unable to schedule memory maintenance", error) }
    }

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
        // New work uses a collision-resistant scope key. During migration, also cancel all
        // 32-bit names that may already be persisted by WorkManager. Those legacy names must
        // never be used for new scheduling because unrelated scopes can share String.hashCode().
        memoryWorkNamesToCancel(scopeId).forEach { workName ->
            workManager.cancelUniqueWork(workName)
        }
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

    private fun enqueueMaintenance(plan: MemoryMaintenanceSchedulePlan) {
        val startup = OneTimeWorkRequestBuilder<MemoryMaintenanceWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            plan.startupUniqueWorkName,
            plan.startupPolicy,
            startup,
        )

        val periodic = PeriodicWorkRequestBuilder<MemoryMaintenanceWorker>(
            plan.repeatIntervalMs,
            TimeUnit.MILLISECONDS,
        )
            // The one-time request owns startup catch-up; defer the first periodic pass to avoid
            // immediately performing the same retention scan twice.
            .setInitialDelay(plan.initialPeriodicDelayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            plan.periodicUniqueWorkName,
            plan.periodicPolicy,
            periodic,
        )
    }

    private companion object {
        const val TAG = "MemoryWorkScheduler"
    }
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

internal data class MemoryMaintenanceSchedulePlan(
    val startupUniqueWorkName: String,
    val startupPolicy: ExistingWorkPolicy,
    val periodicUniqueWorkName: String,
    val periodicPolicy: ExistingPeriodicWorkPolicy,
    val repeatIntervalMs: Long,
    val initialPeriodicDelayMs: Long,
)

internal fun memoryMaintenanceSchedulePlan(): MemoryMaintenanceSchedulePlan =
    MemoryMaintenanceSchedulePlan(
        startupUniqueWorkName = "memory_v2_retention_startup_v1",
        startupPolicy = ExistingWorkPolicy.KEEP,
        periodicUniqueWorkName = "memory_v2_retention_daily_v1",
        periodicPolicy = ExistingPeriodicWorkPolicy.KEEP,
        repeatIntervalMs = TimeUnit.DAYS.toMillis(1),
        initialPeriodicDelayMs = TimeUnit.DAYS.toMillis(1),
    )

private const val MEMORY_SCOPE_WORK_KEY_VERSION = "scope_v2"

/**
 * Stable, opaque identity for WorkManager names.
 *
 * Full SHA-256 is retained (256 bits, 43 Base64URL characters) rather than truncating to a
 * 32-bit String hash. The explicit version lets a future key format coexist with persisted work.
 */
internal fun memoryScopeWorkKey(scopeId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(scopeId.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun memoryDebounceWorkName(scopeId: String): String =
    "memory_v2_debounce_${MEMORY_SCOPE_WORK_KEY_VERSION}_${memoryScopeWorkKey(scopeId)}"

private fun memoryProcessingWorkName(scopeId: String): String =
    "memory_v2_process_${MEMORY_SCOPE_WORK_KEY_VERSION}_${memoryScopeWorkKey(scopeId)}"

internal fun memoryWorkNamesToCancel(scopeId: String): Set<String> = linkedSetOf(
    memoryDebounceWorkName(scopeId),
    memoryProcessingWorkName(scopeId),
    legacyMemoryDebounceWorkName(scopeId),
    legacyMemoryProcessingWorkName(scopeId),
    legacyMemoryWorkName(scopeId),
)

private fun legacyMemoryDebounceWorkName(scopeId: String): String =
    "memory_v2_debounce_${legacyMemoryScopeWorkKey(scopeId)}"

private fun legacyMemoryProcessingWorkName(scopeId: String): String =
    "memory_v2_process_${legacyMemoryScopeWorkKey(scopeId)}"

private fun legacyMemoryWorkName(scopeId: String): String =
    "memory_v2_${legacyMemoryScopeWorkKey(scopeId)}"

private fun legacyMemoryScopeWorkKey(scopeId: String): UInt = scopeId.hashCode().toUInt()

package me.rerere.rikkahub.memory.dreaming.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Best-effort scheduling only. Durable scope epochs and journal receipts remain the correctness
 * source when enqueueing fails or the process dies before WorkManager observes a request.
 */
interface DreamObserverWorkScheduler {
    fun enqueueScope(scopeId: DreamScopeId, runId: String)

    fun enqueueDirtyScan()
}

class AndroidDreamObserverWorkScheduler(
    context: Context,
) : DreamObserverWorkScheduler {
    private val workManager = WorkManager.getInstance(context)

    init {
        runCatching { armRecoveryScans() }
            .onFailure { error -> Log.w(TAG, "Unable to arm Observer recovery scans", error) }
    }

    override fun enqueueScope(scopeId: DreamScopeId, runId: String) {
        val plan = dreamObserverScopeWorkPlan(scopeId, runId)
        val payload = dreamObserverWorkPayload(plan)
        val request = OneTimeWorkRequestBuilder<DreamObserverWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    // No Memory text, entity IDs, mode, owner, or prompt data enters WorkManager.
                    .putString(
                        DreamObserverWorker.KEY_SCOPE_ID,
                        payload.getValue(DreamObserverWorker.KEY_SCOPE_ID),
                    )
                    .putString(
                        DreamObserverWorker.KEY_RUN_ID,
                        payload.getValue(DreamObserverWorker.KEY_RUN_ID),
                    )
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(plan.uniqueWorkName, plan.policy, request)
    }

    override fun enqueueDirtyScan() {
        workManager.enqueueUniqueWork(
            DIRTY_SCAN_KICK_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            dirtyScanRequest(DIRTY_SCAN_KICK_DELAY_SECONDS),
        )
    }

    private fun armRecoveryScans() {
        workManager.enqueueUniqueWork(
            STARTUP_SCAN_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            dirtyScanRequest(),
        )
        val periodic = PeriodicWorkRequestBuilder<DreamObserverSweepWorker>(
            PERIODIC_SCAN_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setInitialDelay(PERIODIC_SCAN_INTERVAL_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SCAN_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    private fun dirtyScanRequest(initialDelaySeconds: Long = 0L) =
        OneTimeWorkRequestBuilder<DreamObserverSweepWorker>()
            // Follow-up scans wait for the current scope's KEEP work to become terminal before
            // attempting to coalesce a fresh run ID for that same scope.
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

    private companion object {
        const val TAG = "DreamObserverScheduler"
    }
}

internal data class DreamObserverScopeWorkPlan(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
    val scopeId: DreamScopeId,
    val runId: String,
)

internal fun dreamObserverScopeWorkPlan(
    scopeId: DreamScopeId,
    runId: String,
): DreamObserverScopeWorkPlan {
    requireCanonicalDreamRunId(runId)
    return DreamObserverScopeWorkPlan(
        uniqueWorkName = "dream_observer_scope_v1_${dreamObserverScopeWorkKey(scopeId)}",
        policy = ExistingWorkPolicy.KEEP,
        scopeId = scopeId,
        runId = runId,
    )
}

internal fun dreamObserverWorkPayload(plan: DreamObserverScopeWorkPlan): Map<String, String> =
    mapOf(
        DreamObserverWorker.KEY_SCOPE_ID to plan.scopeId.value,
        DreamObserverWorker.KEY_RUN_ID to plan.runId,
    )

/** Full SHA-256 avoids the String.hashCode collision class used by older memory work names. */
internal fun dreamObserverScopeWorkKey(scopeId: DreamScopeId): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(scopeId.value.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private const val STARTUP_SCAN_WORK_NAME = "dream_observer_scan_startup_v1"
private const val DIRTY_SCAN_KICK_WORK_NAME = "dream_observer_scan_kick_v1"
private const val PERIODIC_SCAN_WORK_NAME = "dream_observer_scan_periodic_v1"
private const val PERIODIC_SCAN_INTERVAL_HOURS = 6L
private const val DIRTY_SCAN_KICK_DELAY_SECONDS = 1L
private const val RETRY_BACKOFF_SECONDS = 30L

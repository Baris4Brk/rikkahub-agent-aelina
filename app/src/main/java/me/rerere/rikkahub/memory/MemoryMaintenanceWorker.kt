package me.rerere.rikkahub.memory

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Runs privacy retention and expiry materialization independently of memory extraction. */
class MemoryMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val mutationCoordinator: MemoryMutationCoordinator by inject()

    override suspend fun doWork(): Result = try {
        runMaintenancePasses()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Memory maintenance failed", error)
        if (runAttemptCount < MAX_MEMORY_MAINTENANCE_RETRIES) Result.retry()
        else Result.failure()
    }

    private suspend fun runMaintenancePasses(): Result {
        var completedPasses = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val changedRows = mutationCoordinator.runRetention()
            completedPasses++
            when (memoryMaintenanceFollowUpAction(changedRows, completedPasses)) {
                MemoryMaintenanceFollowUpAction.CONTINUE -> Unit
                MemoryMaintenanceFollowUpAction.SUCCESS -> return Result.success()
                MemoryMaintenanceFollowUpAction.RETRY -> return Result.retry()
            }
        }
    }

    private companion object {
        const val TAG = "MemoryMaintenanceWorker"
    }
}

internal fun memoryMaintenanceFollowUpAction(
    changedRows: Int,
    completedPasses: Int,
): MemoryMaintenanceFollowUpAction = when {
    changedRows <= 0 -> MemoryMaintenanceFollowUpAction.SUCCESS
    completedPasses >= MAX_MEMORY_MAINTENANCE_PASSES -> MemoryMaintenanceFollowUpAction.RETRY
    else -> MemoryMaintenanceFollowUpAction.CONTINUE
}

internal enum class MemoryMaintenanceFollowUpAction {
    CONTINUE,
    SUCCESS,
    RETRY,
}

internal const val MAX_MEMORY_MAINTENANCE_PASSES = 16
private const val MAX_MEMORY_MAINTENANCE_RETRIES = 3

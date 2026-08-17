package me.rerere.rikkahub.learning.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.retention.LearningRetentionCoordinatorResult
import me.rerere.rikkahub.learning.retention.LearningRetentionMaintenanceCoordinator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** One bounded, low-priority retention page. It never invokes a model, embedding or network. */
class LearningRetentionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val coordinator: LearningRetentionMaintenanceCoordinator by inject()
    private val scheduler: LearningWorkScheduler by inject()

    override suspend fun doWork(): Result = try {
        when (coordinator.runOneBatch()) {
            LearningRetentionCoordinatorResult.IDLE,
            LearningRetentionCoordinatorResult.DID_WORK,
            LearningRetentionCoordinatorResult.DEFERRED,
            -> Result.success()

            LearningRetentionCoordinatorResult.WORK_REMAINS -> {
                // Aggregate receipt includes both derived categories and primary-outbox pages.
                scheduler.wakeMaintenance()
                Result.success()
            }

            LearningRetentionCoordinatorResult.RETRY -> Result.retry()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

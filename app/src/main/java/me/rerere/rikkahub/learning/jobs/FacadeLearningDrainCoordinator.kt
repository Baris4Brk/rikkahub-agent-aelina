package me.rerere.rikkahub.learning.jobs

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.runtime.LearningRuntimeErrorCode
import me.rerere.rikkahub.learning.runtime.LearningRuntimeMaintenancePort
import me.rerere.rikkahub.learning.runtime.LearningRuntimeMaintenanceRequest
import me.rerere.rikkahub.learning.runtime.LearningRuntimeMaintenanceResult

/** Production coordinator that keeps WorkManager outside the Room/runtime ownership boundary. */
class FacadeLearningDrainCoordinator(
    private val runtime: LearningRuntimeMaintenancePort,
    private val featureFlags: LearningFeatureFlagSource,
) : LearningDrainCoordinator {
    override suspend fun drainBounded(
        maxJobs: Int,
        monotonicDeadlineMs: Long,
        mode: LearningDrainMode,
    ): LearningDrainResult {
        if (maxJobs !in 1..16 || monotonicDeadlineMs < 0L) {
            return LearningDrainResult.DISABLED
        }
        val flags = try {
            featureFlags.current()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LearningDrainResult.RETRY
        }
        if (!flags.isValid || !flags.effective.handoff) {
            return LearningDrainResult.DISABLED
        }

        return try {
            when (
                val result = runtime.runMaintenance(
                    LearningRuntimeMaintenanceRequest(
                        maxJobs = maxJobs,
                        monotonicDeadlineMs = monotonicDeadlineMs,
                        processJobs = flags.effective.jobs,
                        mode = mode,
                    ),
                )
            ) {
                is LearningRuntimeMaintenanceResult.Completed -> result.drainResult
                LearningRuntimeMaintenanceResult.Disabled -> LearningDrainResult.DISABLED
                is LearningRuntimeMaintenanceResult.Unavailable -> when (result.errorCode) {
                    LearningRuntimeErrorCode.RESTORE_IN_PROGRESS,
                    LearningRuntimeErrorCode.RESTORE_FAILED_RESTART_REQUIRED,
                    LearningRuntimeErrorCode.WRONG_PROCESS,
                    -> LearningDrainResult.DISABLED

                    else -> LearningDrainResult.RETRY
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LearningDrainResult.RETRY
        }
    }
}

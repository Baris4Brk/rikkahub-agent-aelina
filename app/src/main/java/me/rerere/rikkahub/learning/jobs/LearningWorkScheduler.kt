package me.rerere.rikkahub.learning.jobs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource

private const val LEARNING_DRAIN_WORK_NAME = "agent_learning_drain_v1"
private const val LEARNING_STARTUP_WORK_NAME = "agent_learning_startup_v1"
private const val LEARNING_PERIODIC_WORK_NAME = "agent_learning_recovery_v1"
private const val LEARNING_PERIODIC_HOURS = 6L
private const val LEARNING_RETENTION_WORK_NAME = "agent_learning_retention_v1"
private const val LEARNING_RETENTION_PERIODIC_WORK_NAME = "agent_learning_retention_periodic_v1"
private const val LEARNING_RETENTION_PERIODIC_HOURS = 24L

interface LearningWorkScheduler {
    fun wake(mode: LearningDrainMode = LearningDrainMode.DRAIN_ONLY)

    fun scheduleStartupAndRecovery()

    /** Privacy/correctness maintenance remains scheduled even when capture/provider flags are off. */
    fun wakeMaintenance()

    fun scheduleMaintenance()

    fun cancelAll()
}

/** Feature-off default; constructing the application graph must not enqueue background work. */
object NoOpLearningWorkScheduler : LearningWorkScheduler {
    override fun wake(mode: LearningDrainMode) = Unit

    override fun scheduleStartupAndRecovery() = Unit

    override fun wakeMaintenance() = Unit

    override fun scheduleMaintenance() = Unit

    override fun cancelAll() = Unit
}

/** Identity-free scheduling: no raw scope, source or job ID enters WorkManager data or names. */
class AndroidLearningWorkScheduler(
    context: Context,
) : LearningWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun wake(mode: LearningDrainMode) {
        val plan = learningWakeWorkPlan()
        workManager.enqueueUniqueWork(
            plan.uniqueWorkName,
            plan.policy,
            oneTimeRequest(mode),
        )
    }

    override fun scheduleStartupAndRecovery() {
        val plan = learningRecoverySchedulePlan()
        workManager.enqueueUniqueWork(
            plan.startupUniqueWorkName,
            plan.startupPolicy,
            oneTimeRequest(LearningDrainMode.RECONCILE_AND_DRAIN),
        )
        workManager.enqueueUniquePeriodicWork(
            plan.periodicUniqueWorkName,
            plan.periodicPolicy,
            PeriodicWorkRequestBuilder<LearningDrainWorker>(
                plan.repeatIntervalHours,
                TimeUnit.HOURS,
            )
                .setConstraints(backgroundConstraints())
                .setInputData(
                    workDataOf(
                        LEARNING_DRAIN_MODE_KEY to
                            LearningDrainMode.RECONCILE_AND_DRAIN.name,
                    ),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build(),
        )
    }

    override fun wakeMaintenance() {
        val plan = learningRetentionSchedulePlan()
        workManager.enqueueUniqueWork(
            plan.oneTimeUniqueWorkName,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            retentionOneTimeRequest(),
        )
    }

    override fun scheduleMaintenance() {
        val plan = learningRetentionSchedulePlan()
        workManager.enqueueUniqueWork(
            plan.oneTimeUniqueWorkName,
            ExistingWorkPolicy.KEEP,
            retentionOneTimeRequest(),
        )
        workManager.enqueueUniquePeriodicWork(
            plan.periodicUniqueWorkName,
            plan.periodicPolicy,
            PeriodicWorkRequestBuilder<LearningRetentionWorker>(
                plan.repeatIntervalHours,
                TimeUnit.HOURS,
            )
                .setConstraints(backgroundConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build(),
        )
    }

    override fun cancelAll() {
        // Rollout cancellation stops capture/provider work, not bounded privacy maintenance.
        learningWorkNamesToCancel().forEach(workManager::cancelUniqueWork)
    }

    private fun oneTimeRequest(mode: LearningDrainMode) =
        OneTimeWorkRequestBuilder<LearningDrainWorker>()
        .setConstraints(backgroundConstraints())
        .setInputData(workDataOf(LEARNING_DRAIN_MODE_KEY to mode.name))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
        .build()

    private fun retentionOneTimeRequest() =
        OneTimeWorkRequestBuilder<LearningRetentionWorker>()
            .setConstraints(backgroundConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()

    private fun backgroundConstraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()
}

/** Keeps construction safe while making persisted rollout changes effective without a restart. */
class FlagGatedLearningWorkScheduler(
    context: Context,
    private val featureFlags: LearningFeatureFlagSource,
) : LearningWorkScheduler {
    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidLearningWorkScheduler(context.applicationContext)
    }

    override fun wake(mode: LearningDrainMode) {
        if (isHandoffEnabled()) delegate.wake(mode)
    }

    override fun scheduleStartupAndRecovery() {
        delegate.scheduleMaintenance()
        if (isHandoffEnabled()) {
            delegate.scheduleStartupAndRecovery()
        } else {
            delegate.cancelAll()
        }
    }

    override fun wakeMaintenance() {
        delegate.wakeMaintenance()
    }

    override fun scheduleMaintenance() {
        delegate.scheduleMaintenance()
    }

    override fun cancelAll() {
        delegate.cancelAll()
        delegate.scheduleMaintenance()
    }

    private fun isHandoffEnabled(): Boolean = runCatching {
        featureFlags.current().let { it.isValid && it.effective.handoff }
    }.getOrDefault(false)
}

internal data class LearningWakeWorkPlan(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
)

internal fun learningWakeWorkPlan(): LearningWakeWorkPlan = LearningWakeWorkPlan(
    uniqueWorkName = LEARNING_DRAIN_WORK_NAME,
    // A wake arriving at the end of a bounded worker must leave a follow-up rather than be lost.
    policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
)

internal data class LearningRecoverySchedulePlan(
    val startupUniqueWorkName: String,
    val startupPolicy: ExistingWorkPolicy,
    val periodicUniqueWorkName: String,
    val periodicPolicy: ExistingPeriodicWorkPolicy,
    val repeatIntervalHours: Long,
)

internal fun learningRecoverySchedulePlan(): LearningRecoverySchedulePlan =
    LearningRecoverySchedulePlan(
        startupUniqueWorkName = LEARNING_STARTUP_WORK_NAME,
        startupPolicy = ExistingWorkPolicy.KEEP,
        periodicUniqueWorkName = LEARNING_PERIODIC_WORK_NAME,
        periodicPolicy = ExistingPeriodicWorkPolicy.KEEP,
        repeatIntervalHours = LEARNING_PERIODIC_HOURS,
    )

internal fun learningWorkNamesToCancel(): Set<String> = setOf(
    LEARNING_DRAIN_WORK_NAME,
    LEARNING_STARTUP_WORK_NAME,
    LEARNING_PERIODIC_WORK_NAME,
)

internal data class LearningRetentionSchedulePlan(
    val oneTimeUniqueWorkName: String,
    val periodicUniqueWorkName: String,
    val periodicPolicy: ExistingPeriodicWorkPolicy,
    val repeatIntervalHours: Long,
)

internal fun learningRetentionSchedulePlan(): LearningRetentionSchedulePlan =
    LearningRetentionSchedulePlan(
        oneTimeUniqueWorkName = LEARNING_RETENTION_WORK_NAME,
        periodicUniqueWorkName = LEARNING_RETENTION_PERIODIC_WORK_NAME,
        periodicPolicy = ExistingPeriodicWorkPolicy.KEEP,
        repeatIntervalHours = LEARNING_RETENTION_PERIODIC_HOURS,
    )

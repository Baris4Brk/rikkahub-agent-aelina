package me.rerere.rikkahub.learning.jobs

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningWorkSchedulerTest {
    @Test
    fun featureOffSchedulerIsAnExplicitNoOp() {
        NoOpLearningWorkScheduler.wake()
        NoOpLearningWorkScheduler.wake(LearningDrainMode.RECONCILE_AND_DRAIN)
        NoOpLearningWorkScheduler.scheduleStartupAndRecovery()
        NoOpLearningWorkScheduler.wakeMaintenance()
        NoOpLearningWorkScheduler.scheduleMaintenance()
        NoOpLearningWorkScheduler.cancelAll()
    }

    @Test
    fun missingOrUnknownPersistedModeFailsClosedToReconciliation() {
        assertEquals(
            LearningDrainMode.RECONCILE_AND_DRAIN,
            LearningDrainMode.parseOrReconcile(null),
        )
        assertEquals(
            LearningDrainMode.RECONCILE_AND_DRAIN,
            LearningDrainMode.parseOrReconcile("future_mode"),
        )
        assertEquals(
            LearningDrainMode.DRAIN_ONLY,
            LearningDrainMode.parseOrReconcile(LearningDrainMode.DRAIN_ONLY.name),
        )
    }

    @Test
    fun wakeUsesOneVersionedIdentityFreeAppendChain() {
        val plan = learningWakeWorkPlan()

        assertEquals("agent_learning_drain_v1", plan.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, plan.policy)
        assertFalse(plan.uniqueWorkName.contains("scope"))
        assertFalse(plan.uniqueWorkName.contains("hash"))
    }

    @Test
    fun startupAndPeriodicRecoveryUseStableKeepNames() {
        val plan = learningRecoverySchedulePlan()

        assertEquals(ExistingWorkPolicy.KEEP, plan.startupPolicy)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, plan.periodicPolicy)
        assertTrue(plan.startupUniqueWorkName.endsWith("_v1"))
        assertTrue(plan.periodicUniqueWorkName.endsWith("_v1"))
        assertEquals(6L, plan.repeatIntervalHours)
    }

    @Test
    fun cancellationCoversEveryCurrentUniqueChain() {
        assertEquals(
            setOf(
                "agent_learning_drain_v1",
                "agent_learning_startup_v1",
                "agent_learning_recovery_v1",
            ),
            learningWorkNamesToCancel(),
        )
        assertTrue(
            learningWorkNamesToCancel().intersect(
                setOf(
                    learningRetentionSchedulePlan().oneTimeUniqueWorkName,
                    learningRetentionSchedulePlan().periodicUniqueWorkName,
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun retentionHasIndependentContentFreeLowFrequencySchedule() {
        val plan = learningRetentionSchedulePlan()

        assertEquals("agent_learning_retention_v1", plan.oneTimeUniqueWorkName)
        assertEquals("agent_learning_retention_periodic_v1", plan.periodicUniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, plan.periodicPolicy)
        assertEquals(24L, plan.repeatIntervalHours)
        assertFalse(plan.oneTimeUniqueWorkName.contains("scope"))
        assertFalse(plan.periodicUniqueWorkName.contains("policy"))
    }
}

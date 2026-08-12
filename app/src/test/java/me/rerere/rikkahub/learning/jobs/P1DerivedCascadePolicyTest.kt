package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1DerivedCascadePolicyTest {
    @Test
    fun disabledFlagsProduceNoChildWork() {
        val plan = P1DerivedCascadePolicy.afterEpisode(
            episodeStatus = StoredLearningEpisodeStatus.SUCCESS.name,
            captureEnabled = false,
            reflectionEnabled = true,
            policyEnabled = true,
            hasStableSource = true,
            lessonAlreadyExists = false,
            reflectionJobAlreadyExists = false,
            modelConfigured = true,
        )
        assertFalse(plan.enqueueRewardClose)
        assertFalse(plan.enqueueReflection)
        assertFalse(plan.attemptPolicyDistillation)
    }

    @Test
    fun reflectionRequiresStableSourceSingleReservationAndModel() {
        fun plan(existingLesson: Boolean = false, existingJob: Boolean = false, model: Boolean = true) =
            P1DerivedCascadePolicy.afterEpisode(
                episodeStatus = StoredLearningEpisodeStatus.SUCCESS.name,
                captureEnabled = true,
                reflectionEnabled = true,
                policyEnabled = true,
                hasStableSource = true,
                lessonAlreadyExists = existingLesson,
                reflectionJobAlreadyExists = existingJob,
                modelConfigured = model,
            )
        assertTrue(plan().enqueueReflection)
        assertFalse(plan(existingLesson = true).enqueueReflection)
        assertFalse(plan(existingJob = true).enqueueReflection)
        assertFalse(plan(model = false).enqueueReflection)
    }

    @Test
    fun distillationNeedsTwoCompleteEpisodesAndCannotCascade() {
        assertFalse(P1DerivedCascadePolicy.afterLessonOrReward(true, 1))
        assertTrue(P1DerivedCascadePolicy.afterLessonOrReward(true, 2))
        assertFalse(P1DerivedCascadePolicy.afterLessonOrReward(false, 10))
        assertEquals(emptySet<LearningJobType>(), P1DerivedCascadePolicy.afterPolicyDistillation())
    }

    @Test
    fun distillationUsesOneNewestBoundedCohortInsteadOfEveryPrefix() {
        val newestFirst = (20 downTo 1).toList()

        assertEquals(
            (20 downTo 5).toList(),
            P1DerivedCascadePolicy.singleDistillationCohort(newestFirst, limit = 16),
        )
    }
}

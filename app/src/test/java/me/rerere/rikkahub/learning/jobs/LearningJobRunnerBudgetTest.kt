package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.LearningJobType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningJobRunnerBudgetTest {
    @Test
    fun insufficientBudgetDefersOnlyProviderEffectJobs() {
        val ready = setOf(
            LearningJobType.ASSEMBLE_EPISODE_SHADOW,
            LearningJobType.RECONCILE_SOURCE,
            LearningJobType.REFLECT_EPISODE_V1,
            LearningJobType.CLOSE_REWARD_WINDOW_V1,
            LearningJobType.DISTILL_POLICY_V1,
            LearningJobType.INVALIDATE_SOURCE_V1,
        )

        assertEquals(
            setOf(
                LearningJobType.ASSEMBLE_EPISODE_SHADOW,
                LearningJobType.RECONCILE_SOURCE,
                LearningJobType.CLOSE_REWARD_WINDOW_V1,
                LearningJobType.INVALIDATE_SOURCE_V1,
            ),
            eligibleLearningJobTypesForBudget(ready, MIN_PROVIDER_EFFECT_JOB_BUDGET_MS - 1L),
        )
    }

    @Test
    fun fullBudgetAllowsProviderEffectJobs() {
        val ready = setOf(
            LearningJobType.REFLECT_EPISODE_V1,
            LearningJobType.DISTILL_POLICY_V1,
        )

        assertEquals(
            ready,
            eligibleLearningJobTypesForBudget(ready, MIN_PROVIDER_EFFECT_JOB_BUDGET_MS),
        )
    }

    @Test
    fun providerOnlyBatchWithShortBudgetClaimsNothing() {
        assertTrue(
            eligibleLearningJobTypesForBudget(
                setOf(LearningJobType.REFLECT_EPISODE_V1),
                MIN_PROVIDER_EFFECT_JOB_BUDGET_MS - 1L,
            ).isEmpty(),
        )
    }

    @Test
    fun oneProviderEffectClaimPerDrainIsTheHardCallCap() {
        val ready = setOf(
            LearningJobType.ASSEMBLE_EPISODE_SHADOW,
            LearningJobType.REFLECT_EPISODE_V1,
            LearningJobType.DISTILL_POLICY_V1,
        )

        assertEquals(
            setOf(LearningJobType.ASSEMBLE_EPISODE_SHADOW),
            eligibleLearningJobTypesForBudget(
                readyTypes = ready,
                remainingBudgetMs = MIN_PROVIDER_EFFECT_JOB_BUDGET_MS,
                providerEffectAlreadyClaimed = true,
            ),
        )
    }

    @Test
    fun synchronousEpisodeCatchUpCannotClaimAnyOtherReadyStage() {
        val ready = setOf(
            LearningJobType.ASSEMBLE_EPISODE_SHADOW,
            LearningJobType.REFLECT_EPISODE_V1,
            LearningJobType.CLOSE_REWARD_WINDOW_V1,
            LearningJobType.INVALIDATE_SOURCE_V1,
        )

        assertEquals(
            setOf(LearningJobType.ASSEMBLE_EPISODE_SHADOW),
            restrictReadyLearningJobTypes(
                readyTypes = ready,
                allowedJobTypes = setOf(LearningJobType.ASSEMBLE_EPISODE_SHADOW),
            ),
        )
    }
}

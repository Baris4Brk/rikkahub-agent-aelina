package me.rerere.rikkahub.learning.workflow.runtime

import me.rerere.rikkahub.learning.model.LearningFeatureCapabilities
import me.rerere.rikkahub.learning.model.LearningFeatureFlagPolicy
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningFeatureFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRolloutGateTest {
    @Test
    fun invalidOrPartiallyEnabledProjectionFailsClosed() {
        val invalid = FeatureFlagWorkflowRolloutGate(source(
            LearningFeatureFlags(workflowCandidate = true),
        ))
        assertFalse(invalid.candidateEnabled())
        assertFalse(invalid.promotionEnabled())

        val candidateOnly = FeatureFlagWorkflowRolloutGate(source(validFlags(
            workflowCandidate = true,
            workflowPromotion = false,
        )))
        assertTrue(candidateOnly.candidateEnabled())
        assertFalse(candidateOnly.promotionEnabled())

        val promotion = FeatureFlagWorkflowRolloutGate(source(validFlags(
            workflowCandidate = true,
            workflowPromotion = true,
        )))
        assertTrue(promotion.candidateEnabled())
        assertTrue(promotion.promotionEnabled())
    }

    private fun source(configured: LearningFeatureFlags): LearningFeatureFlagSource =
        LearningFeatureFlagSource {
            LearningFeatureFlagPolicy.resolve(
                configured,
                LearningFeatureCapabilities(
                    schemaReady = true,
                    typedJobExecutionReady = true,
                    reviewedPolicyInjectionReady = true,
                    workflowCandidateReady = true,
                    workflowPromotionReady = true,
                ),
            )
        }

    private fun validFlags(
        workflowCandidate: Boolean,
        workflowPromotion: Boolean,
    ) = LearningFeatureFlags(
        schemaReady = true,
        handoff = true,
        capture = true,
        jobs = true,
        reflectionShadow = true,
        policyCandidate = true,
        policyRetrievalShadow = true,
        policyInjection = true,
        workflowCandidate = workflowCandidate,
        workflowPromotion = workflowPromotion,
    )
}

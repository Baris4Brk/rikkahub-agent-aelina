package me.rerere.rikkahub.learning.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPositiveMutationGateTest {
    @Test
    fun allOffAndInvalidStagedConfigurationsDenyEveryPositiveMutation() {
        val off = FeatureFlagLearningPositiveMutationGate(source(LearningFeatureFlags()))
        LearningPositiveMutation.entries.forEach { assertFalse(off.allows(it)) }

        val invalid = FeatureFlagLearningPositiveMutationGate(source(
            LearningFeatureFlags(curatorUpdate = true),
        ))
        LearningPositiveMutation.entries.forEach { assertFalse(invalid.allows(it)) }
    }

    @Test
    fun stageEAllowsPolicyButCuratorAndWorkflowRemainIndependent() {
        val gate = FeatureFlagLearningPositiveMutationGate(source(validStageE(
            curatorMerge = true,
            workflowCandidate = true,
            workflowPromotion = false,
        )))
        assertTrue(gate.allows(LearningPositiveMutation.POLICY_APPROVE_OR_RESUME))
        assertTrue(gate.allows(LearningPositiveMutation.POLICY_RESTORE_ARCHIVED_REVISION))
        assertFalse(gate.allows(LearningPositiveMutation.CURATOR_UPDATE_CANDIDATE))
        assertTrue(gate.allows(LearningPositiveMutation.CURATOR_MERGE_CANDIDATE))
        assertFalse(gate.allows(LearningPositiveMutation.CURATOR_SPLIT_CANDIDATE))
        assertFalse(gate.allows(LearningPositiveMutation.CURATOR_SUPERSEDE_CANDIDATE))
        assertTrue(gate.allows(LearningPositiveMutation.WORKFLOW_CANDIDATE_SUBMISSION))
        assertFalse(gate.allows(LearningPositiveMutation.WORKFLOW_PROMOTION_OR_ENABLE))
    }

    @Test
    fun eachCuratorSwitchOpensOnlyItsExactOperation() {
        val curatorMutations = listOf(
            LearningPositiveMutation.CURATOR_UPDATE_CANDIDATE,
            LearningPositiveMutation.CURATOR_MERGE_CANDIDATE,
            LearningPositiveMutation.CURATOR_SPLIT_CANDIDATE,
            LearningPositiveMutation.CURATOR_SUPERSEDE_CANDIDATE,
        )
        val configurations = listOf(
            validStageE(false, false, false).copy(curatorUpdate = true),
            validStageE(false, false, false).copy(curatorMerge = true),
            validStageE(false, false, false).copy(curatorSplit = true),
            validStageE(false, false, false).copy(curatorSupersede = true),
        )
        configurations.forEachIndexed { enabledIndex, flags ->
            val gate = FeatureFlagLearningPositiveMutationGate(source(flags))
            curatorMutations.forEachIndexed { mutationIndex, mutation ->
                if (mutationIndex == enabledIndex) assertTrue(gate.allows(mutation))
                else assertFalse(gate.allows(mutation))
            }
        }
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
                    curatorV1Ready = true,
                ),
            )
        }

    private fun validStageE(
        curatorMerge: Boolean,
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
        curatorMerge = curatorMerge,
    )
}

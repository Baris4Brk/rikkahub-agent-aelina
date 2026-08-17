package me.rerere.rikkahub.learning.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningFeatureFlagsTest {
    @Test
    fun defaultsAreCompletelyDisabled() {
        val resolved = LearningFeatureFlagPolicy.resolve(LearningFeatureFlags())
        assertTrue(resolved.isValid)
        assertEquals(LearningFeatureFlags(), resolved.effective)
        assertFalse(resolved.effective.hasBusinessWritesEnabled)
        assertFalse(resolved.effective.hasProviderEffectEnabled)
        assertFalse(resolved.effective.hasCuratorMutationEnabled)
        assertEquals(resolved, DisabledLearningFeatureFlagSource.current())
    }

    @Test
    fun injectionCannotSkipEarlierRolloutGates() {
        val resolved = LearningFeatureFlagPolicy.resolve(
            LearningFeatureFlags(policyInjection = true),
        )
        assertFalse(resolved.isValid)
        assertTrue(resolved.errors.contains(LearningFlagDependencyError.SCHEMA_REQUIRED))
        assertTrue(resolved.errors.contains(LearningFlagDependencyError.HANDOFF_REQUIRED))
        assertTrue(resolved.errors.contains(LearningFlagDependencyError.JOBS_REQUIRED))
        assertTrue(resolved.errors.contains(LearningFlagDependencyError.POLICY_CANDIDATE_REQUIRED))
        assertTrue(resolved.errors.contains(LearningFlagDependencyError.POLICY_SHADOW_REQUIRED))
        assertEquals(LearningFeatureFlags(), resolved.effective)
    }

    @Test
    fun validShadowChainPreservesConfiguredFlags() {
        val configured = LearningFeatureFlags(
            schemaReady = true,
            handoff = true,
            capture = true,
            jobs = true,
            reflectionShadow = true,
            policyCandidate = true,
            policyRetrievalShadow = true,
        )
        val resolved = LearningFeatureFlagPolicy.resolve(
            configured,
            LearningFeatureCapabilities(schemaReady = true, typedJobExecutionReady = true),
        )
        assertTrue(resolved.isValid)
        assertEquals(configured, resolved.effective)
    }

    @Test
    fun jobsCannotBeEnabledWithoutTypedHandlerCapability() {
        val resolved = LearningFeatureFlagPolicy.resolve(
            LearningFeatureFlags(schemaReady = true, handoff = true, jobs = true),
        )

        assertFalse(resolved.isValid)
        assertTrue(resolved.errors.contains(LearningFlagDependencyError.JOB_HANDLER_REQUIRED))
        assertEquals(LearningFeatureFlags(), resolved.effective)
    }

    @Test
    fun persistedSchemaReadyCannotOverrideInstalledCapability() {
        val resolved = LearningFeatureFlagPolicy.resolve(
            LearningFeatureFlags(schemaReady = true, handoff = true),
            LearningFeatureCapabilities(schemaReady = false, typedJobExecutionReady = true),
        )

        assertFalse(resolved.isValid)
        assertTrue(resolved.errors.contains(LearningFlagDependencyError.SCHEMA_REQUIRED))
        assertEquals(LearningFeatureFlags(), resolved.effective)
    }

    @Test
    fun reviewedPolicyInjectionRequiresInstalledP2RuntimeCapability() {
        val configured = LearningFeatureFlags(
            schemaReady = true,
            handoff = true,
            capture = true,
            jobs = true,
            reflectionShadow = true,
            policyCandidate = true,
            policyRetrievalShadow = true,
            policyInjection = true,
        )
        val unavailable = LearningFeatureFlagPolicy.resolve(
            configured,
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
            ),
        )
        assertFalse(unavailable.isValid)
        assertTrue(
            LearningFlagDependencyError.POLICY_INJECTION_RUNTIME_REQUIRED in unavailable.errors,
        )

        val ready = LearningFeatureFlagPolicy.resolve(
            configured,
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
                reviewedPolicyInjectionReady = true,
            ),
        )
        assertTrue(ready.isValid)
        assertTrue(ready.effective.policyInjection)
    }

    @Test
    fun workflowFlagsRequireOrderedRuntimeCapabilities() {
        val configured = LearningFeatureFlags(
            schemaReady = true,
            handoff = true,
            capture = true,
            jobs = true,
            reflectionShadow = true,
            policyCandidate = true,
            policyRetrievalShadow = true,
            policyInjection = true,
            workflowCandidate = true,
            workflowPromotion = true,
        )
        val unavailable = LearningFeatureFlagPolicy.resolve(
            configured,
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
                reviewedPolicyInjectionReady = true,
            ),
        )
        assertFalse(unavailable.isValid)
        assertTrue(LearningFlagDependencyError.WORKFLOW_CANDIDATE_REQUIRED in unavailable.errors)
        assertTrue(
            LearningFlagDependencyError.WORKFLOW_PROMOTION_RUNTIME_REQUIRED in unavailable.errors,
        )

        val ready = LearningFeatureFlagPolicy.resolve(
            configured,
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
                reviewedPolicyInjectionReady = true,
                workflowCandidateReady = true,
                workflowPromotionReady = true,
            ),
        )
        assertTrue(ready.isValid)
        assertTrue(ready.effective.workflowCandidate)
        assertTrue(ready.effective.workflowPromotion)
    }

    @Test
    fun curatorOperationsRequireStageEAndInstalledRuntimeIndependently() {
        val base = LearningFeatureFlags(
            schemaReady = true,
            handoff = true,
            capture = true,
            jobs = true,
            reflectionShadow = true,
            policyCandidate = true,
            policyRetrievalShadow = true,
            policyInjection = true,
            curatorMerge = true,
        )
        val withoutRuntime = LearningFeatureFlagPolicy.resolve(
            base,
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
                reviewedPolicyInjectionReady = true,
            ),
        )
        assertFalse(withoutRuntime.isValid)
        assertTrue(LearningFlagDependencyError.CURATOR_RUNTIME_REQUIRED in withoutRuntime.errors)
        assertEquals(LearningFeatureFlags(), withoutRuntime.effective)

        val ready = LearningFeatureFlagPolicy.resolve(
            base,
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
                reviewedPolicyInjectionReady = true,
                curatorV1Ready = true,
            ),
        )
        assertTrue(ready.isValid)
        assertFalse(ready.effective.curatorUpdate)
        assertTrue(ready.effective.curatorMerge)
        assertFalse(ready.effective.curatorSplit)
        assertFalse(ready.effective.curatorSupersede)

        val skippedStage = LearningFeatureFlagPolicy.resolve(
            base.copy(policyInjection = false),
            LearningFeatureCapabilities(
                schemaReady = true,
                typedJobExecutionReady = true,
                reviewedPolicyInjectionReady = true,
                curatorV1Ready = true,
            ),
        )
        assertFalse(skippedStage.isValid)
        assertTrue(LearningFlagDependencyError.POLICY_INJECTION_REQUIRED in skippedStage.errors)
        assertEquals(LearningFeatureFlags(), skippedStage.effective)
    }
}

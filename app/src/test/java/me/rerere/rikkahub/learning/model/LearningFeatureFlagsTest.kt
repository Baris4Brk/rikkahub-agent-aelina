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
}

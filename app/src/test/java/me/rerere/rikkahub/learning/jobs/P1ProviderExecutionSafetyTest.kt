package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.model.LearningProviderKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1ProviderExecutionSafetyTest {
    @Test
    fun localLiteRtNeedsAllExplicitSafetyGates() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                remoteFlagEnabled = false,
            ),
        )
        assertTrue(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                remoteFlagEnabled = false,
                localRuntimeAttestationReady = true,
                durableProviderBudgetReady = true,
                frozenProviderInputReady = true,
            ),
        )
    }

    @Test
    fun remoteNeedsConsentIdempotencyBudgetAndFrozenInput() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.REMOTE,
                remoteFlagEnabled = true,
            ),
        )
        assertTrue(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.REMOTE,
                remoteFlagEnabled = true,
                remoteTransportIdempotencyReady = true,
                durableProviderBudgetReady = true,
                frozenProviderInputReady = true,
            ),
        )
    }

    @Test
    fun exactProviderIdentityStillNeedsDurableBatchTokenAndCostBudget() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                remoteFlagEnabled = false,
                localRuntimeAttestationReady = true,
            ),
        )
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.REMOTE,
                remoteFlagEnabled = true,
                remoteTransportIdempotencyReady = true,
            ),
        )
    }

    @Test
    fun providerCannotRunUntilEveryPromptInputCohortIsDurablyFrozen() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.LOCAL_LITERT,
                remoteFlagEnabled = false,
                localRuntimeAttestationReady = true,
                durableProviderBudgetReady = true,
            ),
        )
    }

    @Test
    fun aicoreIsNeverAuthorizedForBackgroundLearning() {
        assertFalse(
            isP1ProviderExecutionAuthorized(
                providerKind = LearningProviderKind.AICORE,
                remoteFlagEnabled = true,
                remoteTransportIdempotencyReady = true,
            ),
        )
    }
}

package me.rerere.rikkahub.learning.review

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyReviewContractsTest {
    @Test
    fun `review is recommended only when projected utility interval is fully negative`() {
        assertTrue(item(delta = -0.30, uncertainty = 0.10).observedUtilityReviewRecommended)
        assertFalse(item(delta = -0.10, uncertainty = 0.20).observedUtilityReviewRecommended)
        assertFalse(item(delta = 0.20, uncertainty = 0.05).observedUtilityReviewRecommended)
        assertFalse(item(delta = null, uncertainty = null).observedUtilityReviewRecommended)
    }

    private fun item(delta: Double?, uncertainty: Double?): PolicyReviewListItem =
        PolicyReviewListItem(
            fence = PolicyReviewFence(
                policyId = "policy-1",
                scope = LearningScope.Assistant(Uuid.parse("00000000-0000-0000-0000-000000000001")),
                stateVersion = 1L,
                contentRevision = 1L,
                artifactSha256 = "a".repeat(64),
                sourceStreamId = null,
            ),
            status = LearningPolicyStatus.ACTIVE,
            triggerSummary = "trigger",
            distinctEpisodeSupport = 2L,
            positiveEpisodeCount = 1L,
            negativeEpisodeCount = 1L,
            confidence = 0.8,
            observedUtilityDelta = delta,
            utilityUncertainty = uncertainty,
            staleReason = null,
            exposure = PolicyReviewExposureSummary(
                shadowRecallCount = 0L,
                shadowExactTaskRecallCount = 0L,
                shadowEstimatedTokenCost = 0L,
                shadowLastObservedAtMs = null,
                actualRetrievedCount = 0L,
                injectedHitCount = 0L,
                hostDispatchedHitCount = 0L,
                droppedItemCount = 0L,
                dropReasons = emptyList(),
                estimatedTokenCost = 0L,
            ),
            updatedAtMs = 1L,
        )
}

package me.rerere.rikkahub.learning.policy

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyCanonicalMutationTest {
    private val scope = LearningScope.Assistant(Uuid.random())
    private val fence = PolicyMutationFence(
        policyId = "policy-1",
        scope = scope,
        expectedRevision = 3L,
        expectedArtifactHash = "a".repeat(64),
    )

    @Test
    fun candidateToShadowUsesFrozenClockAndIncrementsExactlyOneRevision() {
        val current = PolicyLifecycleState(
            status = LearningPolicyStatus.CANDIDATE,
            revision = 3L,
            artifactHash = "a".repeat(64),
            reason = PolicyLifecycleReason.CREATED_FROM_VALIDATED_DRAFT,
            updatedAtMs = 50L,
        )

        val result = PolicyLifecycle.transition(
            current = current,
            expectedRevision = 3L,
            expectedArtifactHash = "a".repeat(64),
            target = LearningPolicyStatus.SHADOW,
            reason = PolicyLifecycleReason.SHADOW_ELIGIBLE,
            frozenNowMs = 99L,
        ) as PolicyLifecycleResult.Applied

        assertEquals(LearningPolicyStatus.SHADOW, result.state.status)
        assertEquals(4L, result.state.revision)
        assertEquals(99L, result.state.updatedAtMs)
        assertNull(result.state.observedUtilityDelta)
        assertNull(result.state.utilityUncertainty)
    }

    @Test
    fun shadowLifecycleEmitsOneDeterministicFencedCanonicalMutation() = runBlocking {
        val requests = mutableListOf<PolicyMutationRequest>()
        val lifecycle = PolicyShadowLifecycle(
            PolicyMutationStore { request ->
                requests += request
                PolicyMutationResult.Applied("policy-1", 4L, LearningPolicyStatus.SHADOW)
            },
        )

        val result = lifecycle.promote(PolicyShadowPromotionCommand(fence, frozenNowMs = 99L))

        assertTrue(result is PolicyMutationResult.Applied)
        assertEquals(
            listOf(
                PolicyMutationRequest.Transition(
                    fence = fence,
                    target = LearningPolicyStatus.SHADOW,
                    reason = PolicyLifecycleReason.SHADOW_ELIGIBLE,
                    frozenNowMs = 99L,
                    actor = PolicyMutationActor.CURATOR_REVIEW,
                ),
            ),
            requests,
        )
    }

    @Test
    fun invalidShadowActorIsRejectedBeforeStorageTransaction() = runBlocking {
        var transactionCalls = 0
        val store = ValidatingPolicyMutationStore(
            PolicyMutationTransaction {
                transactionCalls += 1
                error("must not be called")
            },
        )

        val result = store.mutate(
            PolicyMutationRequest.Transition(
                fence = fence,
                target = LearningPolicyStatus.SHADOW,
                reason = PolicyLifecycleReason.SHADOW_ELIGIBLE,
                frozenNowMs = 99L,
                actor = PolicyMutationActor.DISTILLER,
            ),
        )

        assertEquals(
            PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION),
            result,
        )
        assertEquals(0, transactionCalls)
    }
}

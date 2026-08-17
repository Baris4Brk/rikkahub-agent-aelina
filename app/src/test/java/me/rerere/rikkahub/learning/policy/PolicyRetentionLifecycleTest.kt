package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyRetentionLifecycleTest {
    @Test
    fun retentionMayArchiveOnlyUnreviewedCandidateLifecycleStates() {
        val candidate = state(LearningPolicyStatus.CANDIDATE)
        val archived = PolicyLifecycle.transition(
            current = candidate,
            expectedRevision = candidate.revision,
            expectedContentRevision = candidate.contentRevision,
            expectedArtifactHash = candidate.artifactHash,
            target = LearningPolicyStatus.ARCHIVED,
            reason = PolicyLifecycleReason.RETENTION_EXPIRED,
            frozenNowMs = 11,
        )
        assertTrue(archived is PolicyLifecycleResult.Applied)

        listOf(
            LearningPolicyStatus.PROBATION,
            LearningPolicyStatus.ACTIVE,
            LearningPolicyStatus.SUSPENDED,
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
        ).forEach { protected ->
            assertEquals(
                PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.INVALID_TRANSITION),
                PolicyLifecycle.transition(
                    current = state(protected),
                    expectedRevision = 1,
                    expectedContentRevision = 1,
                    expectedArtifactHash = HASH,
                    target = LearningPolicyStatus.ARCHIVED,
                    reason = PolicyLifecycleReason.RETENTION_EXPIRED,
                    frozenNowMs = 11,
                ),
            )
        }
    }

    @Test
    fun retentionActorMustUseRetentionReason() = runBlocking {
        var writes = 0
        val store = ValidatingPolicyMutationStore(
            PolicyMutationTransaction {
                writes += 1
                PolicyMutationResult.Applied("policy", 2, LearningPolicyStatus.ARCHIVED)
            },
        )
        val fence = PolicyMutationFence(
            policyId = "policy",
            scope = LearningScope.Assistant(ASSISTANT),
            expectedRevision = 1,
            expectedContentRevision = 1,
            expectedArtifactHash = HASH,
        )

        assertEquals(
            PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION),
            store.mutate(
                PolicyMutationRequest.Transition(
                    fence,
                    LearningPolicyStatus.ARCHIVED,
                    PolicyLifecycleReason.USER_ARCHIVED,
                    11,
                    PolicyMutationActor.RETENTION,
                ),
            ),
        )
        assertEquals(0, writes)
        assertTrue(
            store.mutate(
                PolicyMutationRequest.Transition(
                    fence,
                    LearningPolicyStatus.ARCHIVED,
                    PolicyLifecycleReason.RETENTION_EXPIRED,
                    11,
                    PolicyMutationActor.RETENTION,
                ),
            ) is PolicyMutationResult.Applied,
        )
        assertEquals(1, writes)
    }

    private fun state(status: LearningPolicyStatus) = PolicyLifecycleState(
        status = status,
        revision = 1,
        contentRevision = 1,
        artifactHash = HASH,
        reason = when (status) {
            LearningPolicyStatus.CANDIDATE -> PolicyLifecycleReason.CREATED_FROM_VALIDATED_DRAFT
            LearningPolicyStatus.SHADOW -> PolicyLifecycleReason.SHADOW_ELIGIBLE
            LearningPolicyStatus.PROBATION,
            LearningPolicyStatus.ACTIVE,
            -> PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE
            LearningPolicyStatus.SUSPENDED -> PolicyLifecycleReason.USER_SUSPENDED
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW ->
                PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED
            else -> PolicyLifecycleReason.USER_ARCHIVED
        },
        staleReason = when (status) {
            LearningPolicyStatus.SUSPENDED -> PolicyLifecycleReason.USER_SUSPENDED
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW ->
                PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED
            else -> null
        },
        updatedAtMs = 10,
    )

    private companion object {
        val ASSISTANT: Uuid = Uuid.parse("00000000-0000-4000-8000-000000000001")
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

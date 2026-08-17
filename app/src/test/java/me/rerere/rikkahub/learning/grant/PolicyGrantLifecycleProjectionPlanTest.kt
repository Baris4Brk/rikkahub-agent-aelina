package me.rerere.rikkahub.learning.grant

import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyGrantLifecycleProjectionPlanTest {
    @Test
    fun `granted saga admits shadow then resumes activation after crash`() {
        assertEquals(
            PolicyGrantLifecycleProjectionStep.ADMIT_PROBATION,
            nextPolicyGrantLifecycleProjectionStep(
                PolicyGrantAuthorityState.GRANTED,
                LearningPolicyStatus.SHADOW,
            ),
        )
        // This is the durable crash-between-steps state. Replay must skip the first mutation.
        assertEquals(
            PolicyGrantLifecycleProjectionStep.ACTIVATE,
            nextPolicyGrantLifecycleProjectionStep(
                PolicyGrantAuthorityState.GRANTED,
                LearningPolicyStatus.PROBATION,
            ),
        )
        assertEquals(
            PolicyGrantLifecycleProjectionStep.ALREADY_SATISFIED,
            nextPolicyGrantLifecycleProjectionStep(
                PolicyGrantAuthorityState.GRANTED,
                LearningPolicyStatus.ACTIVE,
            ),
        )
    }

    @Test
    fun `per-consumer revocation never downgrades the shared technical policy`() {
        listOf(
            LearningPolicyStatus.CANDIDATE,
            LearningPolicyStatus.SHADOW,
            LearningPolicyStatus.PROBATION,
            LearningPolicyStatus.ACTIVE,
            LearningPolicyStatus.SUSPENDED,
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ).forEach { status ->
            assertEquals(
                PolicyGrantLifecycleProjectionStep.ALREADY_SATISFIED,
                nextPolicyGrantLifecycleProjectionStep(PolicyGrantAuthorityState.REVOKED, status),
            )
        }
    }

    @Test
    fun `a later explicit consumer grant can reactivate a user-suspended technical row`() {
        assertEquals(
            PolicyGrantLifecycleProjectionStep.ACTIVATE,
            nextPolicyGrantLifecycleProjectionStep(
                PolicyGrantAuthorityState.GRANTED,
                LearningPolicyStatus.SUSPENDED,
            ),
        )
    }
}

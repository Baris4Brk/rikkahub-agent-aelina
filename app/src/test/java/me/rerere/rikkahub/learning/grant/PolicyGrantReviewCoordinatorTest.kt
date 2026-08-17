package me.rerere.rikkahub.learning.grant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.DisabledLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyGrantReviewCoordinatorTest {
    @Test
    fun `rollout off blocks grant authority write but never blocks revoke`() = runBlocking {
        var authorityCalls = 0
        val coordinator = AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService { command ->
                authorityCalls += 1
                if (command.fence == PolicyGrantFence.REVOKE) {
                    PolicyGrantReviewResult.Applied(revokedSnapshot())
                } else {
                    PolicyGrantReviewResult.Applied(grantedSnapshot())
                }
            },
            lifecycle = PolicyGrantLifecycleProjector {
                PolicyGrantLifecycleProjectionResult.Applied(it.policyId, 9L)
            },
            positiveMutations = DisabledLearningPositiveMutationGate,
        )

        val denied = coordinator.review(command())
        assertEquals(
            PolicyGrantCoordinatedReviewResult.AuthorityRejected(
                PolicyGrantReviewResult.Conflict(PolicyGrantConflict.ROLLOUT_DISABLED),
            ),
            denied,
        )
        assertEquals(0, authorityCalls)

        val revoked = coordinator.review(command(
            fence = PolicyGrantFence.REVOKE,
            expectedVersion = 1L,
            reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
        ))
        assertTrue(revoked is PolicyGrantCoordinatedReviewResult.Completed)
        assertEquals(1, authorityCalls)
    }

    @Test
    fun `authority commits before lifecycle projection`() = runBlocking {
        val order = mutableListOf<String>()
        val snapshot = grantedSnapshot()
        val coordinator = AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService {
                order += "authority"
                PolicyGrantReviewResult.Applied(snapshot)
            },
            lifecycle = PolicyGrantLifecycleProjector {
                order += "lifecycle"
                PolicyGrantLifecycleProjectionResult.Applied(snapshot.policyId, 4L)
            },
            positiveMutations = ALLOW_POSITIVE,
        )

        val result = coordinator.review(command())

        assertEquals(listOf("authority", "lifecycle"), order)
        assertEquals(
            PolicyGrantCoordinatedReviewResult.Completed(
                snapshot,
                lifecycleRevision = 4L,
                authorityWasDuplicate = false,
                lifecycleWasDuplicate = false,
            ),
            result,
        )
    }

    @Test
    fun `authority conflict never touches derived lifecycle`() = runBlocking {
        var lifecycleCalls = 0
        val conflict = PolicyGrantReviewResult.Conflict(
            PolicyGrantConflict.STALE_STATE_VERSION,
            currentStateVersion = 3L,
        )
        val coordinator = AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService { conflict },
            lifecycle = PolicyGrantLifecycleProjector {
                lifecycleCalls += 1
                error("must not project rejected authority")
            },
            positiveMutations = ALLOW_POSITIVE,
        )

        val result = coordinator.review(command())

        assertEquals(PolicyGrantCoordinatedReviewResult.AuthorityRejected(conflict), result)
        assertEquals(0, lifecycleCalls)
    }

    @Test
    fun `non exact authority receipt never reaches lifecycle`() = runBlocking {
        var lifecycleCalls = 0
        val wrongReceipt = grantedSnapshot().copy(updatedAtEpochMs = 11L)
        val coordinator = AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService { PolicyGrantReviewResult.Duplicate(wrongReceipt) },
            lifecycle = PolicyGrantLifecycleProjector {
                lifecycleCalls += 1
                error("must not project a non-exact authority receipt")
            },
            positiveMutations = ALLOW_POSITIVE,
        )

        val result = coordinator.review(command())

        assertEquals(0, lifecycleCalls)
        assertEquals(
            PolicyGrantCoordinatedReviewResult.AuthorityRejected(
                PolicyGrantReviewResult.Conflict(
                    PolicyGrantConflict.IDENTITY_MISMATCH,
                    currentStateVersion = 1L,
                ),
            ),
            result,
        )
    }

    @Test
    fun `post-authority failure is explicit replayable pending not false completion`() = runBlocking {
        val snapshot = grantedSnapshot()
        val coordinator = AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService { PolicyGrantReviewResult.Applied(snapshot) },
            lifecycle = PolicyGrantLifecycleProjector {
                PolicyGrantLifecycleProjectionResult.Pending(
                    PolicyGrantLifecyclePendingReason.RUNTIME_UNAVAILABLE,
                )
            },
            positiveMutations = ALLOW_POSITIVE,
        )

        val result = coordinator.review(command())

        assertEquals(
            PolicyGrantCoordinatedReviewResult.AuthorityCommittedDerivedPending(
                snapshot,
                PolicyGrantLifecyclePendingReason.RUNTIME_UNAVAILABLE,
                authorityWasDuplicate = false,
            ),
            result,
        )
    }

    @Test
    fun `crash replay resumes duplicate authority and completes duplicate projection`() = runBlocking {
        val snapshot = grantedSnapshot()
        var projections = 0
        val coordinator = AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService { PolicyGrantReviewResult.Duplicate(snapshot) },
            lifecycle = PolicyGrantLifecycleProjector {
                projections += 1
                PolicyGrantLifecycleProjectionResult.Duplicate(snapshot.policyId, 4L)
            },
            positiveMutations = ALLOW_POSITIVE,
        )

        val result = coordinator.review(command())

        assertEquals(1, projections)
        assertEquals(
            PolicyGrantCoordinatedReviewResult.Completed(
                snapshot,
                lifecycleRevision = 4L,
                authorityWasDuplicate = true,
                lifecycleWasDuplicate = true,
            ),
            result,
        )
    }

    @Test
    fun `revoked authority snapshot is projected unchanged`() = runBlocking {
        val snapshot = revokedSnapshot()
        var projected: PolicyGrantAuthoritySnapshot? = null
        val coordinator = AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService { PolicyGrantReviewResult.Applied(snapshot) },
            lifecycle = PolicyGrantLifecycleProjector {
                projected = it
                PolicyGrantLifecycleProjectionResult.Applied(it.policyId, 9L)
            },
            positiveMutations = ALLOW_POSITIVE,
        )

        val result = coordinator.review(
            command(
                fence = PolicyGrantFence.REVOKE,
                expectedVersion = 1L,
                reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
            ),
        )

        assertEquals(snapshot, projected)
        assertTrue(result is PolicyGrantCoordinatedReviewResult.Completed)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation from lifecycle is never converted to pending`() {
        runBlocking {
        val snapshot = grantedSnapshot()
        AppFirstPolicyGrantReviewCoordinator(
            authority = PolicyGrantService { PolicyGrantReviewResult.Applied(snapshot) },
            lifecycle = PolicyGrantLifecycleProjector { throw CancellationException("cancel") },
            positiveMutations = ALLOW_POSITIVE,
        ).review(command())
        }
    }
}

private fun command(
    fence: PolicyGrantFence = PolicyGrantFence.GRANT,
    expectedVersion: Long = 0L,
    reason: PolicyGrantReason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
): PolicyGrantReviewCommand = PolicyGrantReviewCommand(
    fence = fence,
    sourceStreamId = COORDINATOR_STREAM,
    scope = COORDINATOR_SCOPE,
    consumingAssistantId = COORDINATOR_CONSUMER,
    policyId = COORDINATOR_POLICY,
    contentRevision = 2L,
    artifactSha256 = COORDINATOR_SHA,
    expectedGrantStateVersion = expectedVersion,
    frozenNowEpochMs = if (fence == PolicyGrantFence.REVOKE) 20L else 10L,
    reason = reason,
)

private fun grantedSnapshot(): PolicyGrantAuthoritySnapshot = PolicyGrantAuthoritySnapshot(
    grantId = policyGrantId(
        COORDINATOR_STREAM,
        COORDINATOR_SCOPE,
        COORDINATOR_CONSUMER,
        COORDINATOR_POLICY,
    ),
    sourceStreamId = COORDINATOR_STREAM,
    scope = COORDINATOR_SCOPE,
    consumingAssistantId = COORDINATOR_CONSUMER,
    policyId = COORDINATOR_POLICY,
    contentRevision = 2L,
    artifactSha256 = COORDINATOR_SHA,
    state = PolicyGrantAuthorityState.GRANTED,
    stateVersion = 1L,
    grantedAtEpochMs = 10L,
    revokedAtEpochMs = null,
    reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
    createdAtEpochMs = 10L,
    updatedAtEpochMs = 10L,
)

private fun revokedSnapshot(): PolicyGrantAuthoritySnapshot = grantedSnapshot().copy(
    state = PolicyGrantAuthorityState.REVOKED,
    stateVersion = 2L,
    revokedAtEpochMs = 20L,
    reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
    updatedAtEpochMs = 20L,
)

private val COORDINATOR_SCOPE = LearningScope.Assistant(
    Uuid.parse("70000000-0000-0000-0000-000000000007"),
)
private val COORDINATOR_CONSUMER = COORDINATOR_SCOPE.assistantId
private const val COORDINATOR_STREAM = "80000000-0000-0000-0000-000000000008"
private const val COORDINATOR_POLICY = "policy-review-coordinator"
private val COORDINATOR_SHA = "e".repeat(64)
private val ALLOW_POSITIVE = LearningPositiveMutationGate { true }

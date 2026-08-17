package me.rerere.rikkahub.learning.policy

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.grant.policyGrantId
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
        expectedContentRevision = 2L,
        expectedArtifactHash = "a".repeat(64),
    )

    @Test
    fun candidateToShadowUsesFrozenClockAndIncrementsExactlyOneRevision() {
        val current = PolicyLifecycleState(
            status = LearningPolicyStatus.CANDIDATE,
            revision = 3L,
            contentRevision = 2L,
            artifactHash = "a".repeat(64),
            reason = PolicyLifecycleReason.CREATED_FROM_VALIDATED_DRAFT,
            updatedAtMs = 50L,
        )

        val result = PolicyLifecycle.transition(
            current = current,
            expectedRevision = 3L,
            expectedContentRevision = 2L,
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
                    actor = PolicyMutationActor.SHADOW_GATE,
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

    @Test
    fun canonicalP2TransitionMatrixKeepsContentIdentityAndUsageSeparate() {
        val artifact = "a".repeat(64)
        fun state(
            status: LearningPolicyStatus,
            reason: PolicyLifecycleReason,
            staleReason: PolicyLifecycleReason? = null,
        ) = PolicyLifecycleState(
            status = status,
            revision = 7L,
            contentRevision = 4L,
            artifactHash = artifact,
            reason = reason,
            staleReason = staleReason,
            updatedAtMs = 50L,
            usageCount = 3L,
            lastUsedAtMs = 45L,
            observedUtilityDelta = 0.25,
            utilityUncertainty = 0.1,
        )

        val allowed = listOf(
            Triple(
                state(LearningPolicyStatus.SHADOW, PolicyLifecycleReason.SHADOW_ELIGIBLE),
                LearningPolicyStatus.PROBATION,
                PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            ),
            Triple(
                state(
                    LearningPolicyStatus.PROBATION,
                    PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                ),
                LearningPolicyStatus.ACTIVE,
                PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            ),
            Triple(
                state(
                    LearningPolicyStatus.ACTIVE,
                    PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                ),
                LearningPolicyStatus.SUSPENDED,
                PolicyLifecycleReason.USER_SUSPENDED,
            ),
            Triple(
                state(
                    LearningPolicyStatus.ACTIVE,
                    PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                ),
                LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
                PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED,
            ),
            Triple(
                state(
                    LearningPolicyStatus.PROBATION,
                    PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                ),
                LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
                PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED,
            ),
        )

        allowed.forEach { (current, target, reason) ->
            val result = PolicyLifecycle.transition(
                current = current,
                expectedRevision = 7L,
                expectedContentRevision = 4L,
                expectedArtifactHash = artifact,
                target = target,
                reason = reason,
                frozenNowMs = 60L,
            ) as PolicyLifecycleResult.Applied

            assertEquals(target, result.state.status)
            assertEquals(8L, result.state.revision)
            assertEquals(4L, result.state.contentRevision)
            assertEquals(artifact, result.state.artifactHash)
            assertEquals(3L, result.state.usageCount)
            assertEquals(45L, result.state.lastUsedAtMs)
            assertEquals(0.25, result.state.observedUtilityDelta!!, 0.0)
        }
    }

    @Test
    fun contentRevisionIsAnIndependentCompareAndSetFence() {
        val result = PolicyLifecycle.transition(
            current = PolicyLifecycleState(
                status = LearningPolicyStatus.SHADOW,
                revision = 7L,
                contentRevision = 4L,
                artifactHash = "a".repeat(64),
                reason = PolicyLifecycleReason.SHADOW_ELIGIBLE,
                updatedAtMs = 50L,
            ),
            expectedRevision = 7L,
            expectedContentRevision = 3L,
            expectedArtifactHash = "a".repeat(64),
            target = LearningPolicyStatus.PROBATION,
            reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            frozenNowMs = 60L,
        )

        assertEquals(
            PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.CONTENT_REVISION_CONFLICT),
            result,
        )
    }

    @Test
    fun lifecycleDuplicateRequiresEveryIdentityFence() {
        val current = PolicyLifecycleState(
            status = LearningPolicyStatus.ACTIVE,
            revision = 9L,
            contentRevision = 6L,
            artifactHash = "c".repeat(64),
            reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            updatedAtMs = 100L,
        )
        fun transition(
            revision: Long = 9L,
            contentRevision: Long = 6L,
            artifact: String = "c".repeat(64),
        ) = PolicyLifecycle.transition(
            current = current,
            expectedRevision = revision,
            expectedContentRevision = contentRevision,
            expectedArtifactHash = artifact,
            target = LearningPolicyStatus.ACTIVE,
            reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            frozenNowMs = 100L,
        )

        assertEquals(PolicyLifecycleResult.Duplicate(current), transition())
        assertEquals(
            PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.REVISION_CONFLICT),
            transition(revision = 8L),
        )
        assertEquals(
            PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.CONTENT_REVISION_CONFLICT),
            transition(contentRevision = 5L),
        )
        assertEquals(
            PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.ARTIFACT_CONFLICT),
            transition(artifact = "d".repeat(64)),
        )
    }

    @Test
    fun probationAndActiveBothRequireExactGrantBindingProof() = runBlocking {
        var transactionCalls = 0
        val store = ValidatingPolicyMutationStore(
            PolicyMutationTransaction { request ->
                transactionCalls += 1
                val transition = request as PolicyMutationRequest.Transition
                PolicyMutationResult.Applied(
                    transition.fence.policyId,
                    transition.fence.expectedRevision + 1L,
                    transition.target,
                )
            },
        )

        val noProof = store.mutate(
            PolicyMutationRequest.Transition(
                fence = fence,
                target = LearningPolicyStatus.PROBATION,
                reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                frozenNowMs = 99L,
                actor = PolicyMutationActor.USER,
            ),
        )
        assertEquals(
            PolicyMutationResult.Conflict(PolicyMutationConflict.GRANT_BINDING_CONFLICT),
            noProof,
        )
        assertEquals(0, transactionCalls)

        val exactProof = exactProof(fence)
        val probation = store.mutate(
            PolicyMutationRequest.Transition(
                fence = fence,
                target = LearningPolicyStatus.PROBATION,
                reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                frozenNowMs = 99L,
                actor = PolicyMutationActor.USER,
                grantBindingProof = exactProof,
            ),
        )
        assertTrue(probation is PolicyMutationResult.Applied)

        val active = store.mutate(
            PolicyMutationRequest.Transition(
                fence = fence,
                target = LearningPolicyStatus.ACTIVE,
                reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                frozenNowMs = 99L,
                actor = PolicyMutationActor.GRANT_BINDER,
                grantBindingProof = exactProof,
            ),
        )
        assertTrue(active is PolicyMutationResult.Applied)
        assertEquals(2, transactionCalls)
    }

    @Test
    fun mismatchedGrantTupleFailsBeforeStorage() = runBlocking {
        var transactionCalls = 0
        val store = ValidatingPolicyMutationStore(
            PolicyMutationTransaction {
                transactionCalls += 1
                error("must not be called")
            },
        )
        val mismatchedFence = fence.copy(expectedContentRevision = 9L)

        val result = store.mutate(
            PolicyMutationRequest.Transition(
                fence = mismatchedFence,
                target = LearningPolicyStatus.ACTIVE,
                reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
                frozenNowMs = 99L,
                actor = PolicyMutationActor.GRANT_BINDER,
                grantBindingProof = exactProof(fence),
            ),
        )

        assertEquals(
            PolicyMutationResult.Conflict(PolicyMutationConflict.GRANT_BINDING_CONFLICT),
            result,
        )
        assertEquals(0, transactionCalls)
    }

    @Test
    fun staleAndArchivedPoliciesCannotBeRestoredDirectlyToAuthority() {
        val stale = PolicyLifecycleState(
            status = LearningPolicyStatus.STALE_SOURCE,
            revision = 7L,
            contentRevision = 4L,
            artifactHash = "a".repeat(64),
            reason = PolicyLifecycleReason.SOURCE_INVALIDATED,
            staleReason = PolicyLifecycleReason.SOURCE_INVALIDATED,
            updatedAtMs = 50L,
        )
        val archived = PolicyLifecycleState(
            status = LearningPolicyStatus.ARCHIVED,
            revision = 7L,
            contentRevision = 4L,
            artifactHash = "a".repeat(64),
            reason = PolicyLifecycleReason.USER_ARCHIVED,
            updatedAtMs = 50L,
        )

        assertRejectedTransition(stale, LearningPolicyStatus.SHADOW)
        assertRejectedTransition(stale, LearningPolicyStatus.ACTIVE)
        assertRejectedTransition(archived, LearningPolicyStatus.ACTIVE)
        val restore = PolicyLifecycle.transition(
            current = archived,
            expectedRevision = 7L,
            expectedContentRevision = 4L,
            expectedArtifactHash = "a".repeat(64),
            target = LearningPolicyStatus.SHADOW,
            reason = PolicyLifecycleReason.USER_RESTORED_REVISION,
            frozenNowMs = 60L,
        ) as PolicyLifecycleResult.Applied
        assertEquals(LearningPolicyStatus.SHADOW, restore.state.status)
    }

    @Test
    fun everyLiveStateCanFailClosedToEachTypedStaleState() {
        val live = listOf(
            LearningPolicyStatus.CANDIDATE to
                PolicyLifecycleReason.CREATED_FROM_VALIDATED_DRAFT,
            LearningPolicyStatus.SHADOW to PolicyLifecycleReason.SHADOW_ELIGIBLE,
            LearningPolicyStatus.PROBATION to
                PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            LearningPolicyStatus.ACTIVE to
                PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            LearningPolicyStatus.SUSPENDED to PolicyLifecycleReason.USER_SUSPENDED,
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW to
                PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED,
        )
        val staleTargets = listOf(
            LearningPolicyStatus.STALE_SOURCE to PolicyLifecycleReason.SOURCE_INVALIDATED,
            LearningPolicyStatus.STALE_SCHEMA to PolicyLifecycleReason.TOOL_SCHEMA_CHANGED,
            LearningPolicyStatus.STALE_AUTHORITY to PolicyLifecycleReason.AUTHORITY_CHANGED,
        )

        live.forEach { (status, currentReason) ->
            val current = PolicyLifecycleState(
                status = status,
                revision = 11L,
                contentRevision = 5L,
                artifactHash = "b".repeat(64),
                reason = currentReason,
                staleReason = when (status) {
                    LearningPolicyStatus.SUSPENDED,
                    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW -> currentReason
                    else -> null
                },
                updatedAtMs = 70L,
            )
            staleTargets.forEach { (target, reason) ->
                val result = PolicyLifecycle.transition(
                    current = current,
                    expectedRevision = 11L,
                    expectedContentRevision = 5L,
                    expectedArtifactHash = "b".repeat(64),
                    target = target,
                    reason = reason,
                    frozenNowMs = 71L,
                ) as PolicyLifecycleResult.Applied

                assertEquals(target, result.state.status)
                assertEquals(reason, result.state.staleReason)
            }
        }
    }

    private fun exactProof(forFence: PolicyMutationFence): PolicyGrantBindingProof {
        val assistantScope = forFence.scope as LearningScope.Assistant
        val streamId = Uuid.random().toString()
        return PolicyGrantBindingProof(
            grantId = policyGrantId(
                streamId,
                assistantScope,
                assistantScope.assistantId,
                forFence.policyId,
            ),
            sourceStreamId = streamId,
            scope = assistantScope,
            consumingAssistantId = assistantScope.assistantId,
            policyId = forFence.policyId,
            contentRevision = forFence.expectedContentRevision,
            artifactSha256 = forFence.expectedArtifactHash,
            grantStateVersion = 2L,
        )
    }

    private fun assertRejectedTransition(
        current: PolicyLifecycleState,
        target: LearningPolicyStatus,
    ) {
        val result = PolicyLifecycle.transition(
            current = current,
            expectedRevision = current.revision,
            expectedContentRevision = current.contentRevision,
            expectedArtifactHash = current.artifactHash,
            target = target,
            reason = PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            frozenNowMs = current.updatedAtMs + 1L,
        )
        assertEquals(
            PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.INVALID_TRANSITION),
            result,
        )
    }
}

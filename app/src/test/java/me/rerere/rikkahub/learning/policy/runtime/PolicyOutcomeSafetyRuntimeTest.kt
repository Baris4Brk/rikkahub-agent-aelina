package me.rerere.rikkahub.learning.policy.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.curation.PolicyCuratorQueueDisposition
import me.rerere.rikkahub.learning.curation.PolicyCuratorV0
import me.rerere.rikkahub.learning.curation.PolicyDistillationRequestQueue
import me.rerere.rikkahub.learning.curation.PolicyHarmReviewQueue
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposureOutcomeAuthority
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyAdvisoryHarmSignal
import me.rerere.rikkahub.learning.policy.PolicyAdvisoryHarmSource
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeTerminalOutcome
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.policy.PolicyMutationStore
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernor
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernorResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyOutcomeSafetyRuntimeTest {
    @Test
    fun `single completed response plus authoritative failure hits fixed rule and suspends`() =
        runBlocking {
            val mutations = mutableListOf<PolicyMutationRequest>()
            var reviews = 0
            val governor = governor(
                onMutation = { request ->
                    mutations += request
                    PolicyMutationResult.Applied(
                        FENCE.policyId,
                        FENCE.expectedRevision + 1L,
                        LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
                    )
                },
                onReview = { reviews += 1 },
            )
            val receipt = linkedReceipt(listOf(policyRef(FENCE)))
            val material = DurablePolicyOutcomeSafetyMaterial(
                receipt = receipt,
                terminal = AuthoritativeTerminalSafetyFact(
                    AUTHORITY,
                    PolicyAuthoritativeTerminalOutcome.FAILURE,
                    terminalContractVersion = 1,
                ),
                policyHeads = listOf(ExactSafetyPolicyHead(FENCE, LearningPolicyStatus.ACTIVE)),
            )
            val runtime = PolicyOutcomeSafetyRuntime(
                source = PolicyOutcomeSafetyMaterialSource {
                    PolicyOutcomeSafetyMaterialResult.Ready(material)
                },
                governor = governor,
            )

            val result = runtime.onOutcomeLinked(
                PolicyOutcomeSafetyTrigger(
                    reservationId = receipt.reservation.key.reservationId,
                    expectedExposureStateVersion = receipt.stateVersion,
                    outcomeAuthority = AUTHORITY,
                    frozenNowMs = 200L,
                ),
            )

            result as PolicyOutcomeSafetyRuntimeResult.Evaluated
            assertEquals(1, result.deterministicHits)
            assertEquals(0, result.noRule)
            assertTrue(result.governorResults.single() is
                PolicySafetyGovernorResult.SuspendedPendingReview)
            assertEquals(1, reviews)
            assertEquals(1, mutations.size)
            val transition = mutations.single() as PolicyMutationRequest.Transition
            assertEquals(FENCE, transition.fence)
            assertEquals(FENCE, transition.lifecycleEvidence?.fence)
        }

    @Test
    fun `co-exposure cannot manufacture an individual deterministic rule hit`() = runBlocking {
        var mutations = 0
        val governor = governor(
            onMutation = {
                mutations += 1
                PolicyMutationResult.Conflict(
                    me.rerere.rikkahub.learning.policy.PolicyMutationConflict.INVALID_TRANSITION,
                )
            },
        )
        val otherFence = FENCE.copy(
            policyId = "policy-safety-two",
            expectedArtifactHash = "b".repeat(64),
        )
        val receipt = linkedReceipt(listOf(policyRef(FENCE), policyRef(otherFence, rank = 2)))
        val material = DurablePolicyOutcomeSafetyMaterial(
            receipt,
            AuthoritativeTerminalSafetyFact(
                AUTHORITY,
                PolicyAuthoritativeTerminalOutcome.FAILURE,
                1,
            ),
            listOf(
                ExactSafetyPolicyHead(FENCE, LearningPolicyStatus.ACTIVE),
                ExactSafetyPolicyHead(otherFence, LearningPolicyStatus.ACTIVE),
            ),
        )
        val result = PolicyOutcomeSafetyRuntime(
            PolicyOutcomeSafetyMaterialSource {
                PolicyOutcomeSafetyMaterialResult.Ready(material)
            },
            governor,
        ).onOutcomeLinked(
            PolicyOutcomeSafetyTrigger(
                receipt.reservation.key.reservationId,
                receipt.stateVersion,
                AUTHORITY,
                200L,
            ),
        ) as PolicyOutcomeSafetyRuntimeResult.Evaluated

        assertEquals(0, result.deterministicHits)
        assertEquals(2, result.noRule)
        assertEquals(0, mutations)
    }

    @Test
    fun `advisory queues review and never calls mutation store`() = runBlocking {
        var mutations = 0
        var reviews = 0
        val runtime = PolicySafetyAdvisoryRuntime(
            governor(
                onMutation = {
                    mutations += 1
                    PolicyMutationResult.Conflict(
                        me.rerere.rikkahub.learning.policy.PolicyMutationConflict.INVALID_TRANSITION,
                    )
                },
                onReview = { reviews += 1 },
            ),
        )

        val result = runtime.queue(
            FENCE,
            PolicyAdvisoryHarmSignal(
                PolicyAdvisoryHarmSource.MATCHED_COHORT_OBSERVED_UTILITY,
                evidenceContractVersion = 1,
                evidenceDigest = "c".repeat(64),
            ),
            frozenNowMs = 200L,
        )

        assertTrue(result is PolicySafetyGovernorResult.HarmReviewQueued)
        assertEquals(1, reviews)
        assertEquals(0, mutations)
    }

    private fun governor(
        onMutation: suspend (PolicyMutationRequest) -> PolicyMutationResult,
        onReview: () -> Unit = {},
    ) = PolicySafetyGovernor(
        curator = PolicyCuratorV0(
            distillationQueue = PolicyDistillationRequestQueue {
                error("Safety runtime must not enqueue distillation")
            },
            harmReviewQueue = PolicyHarmReviewQueue {
                onReview()
                PolicyCuratorQueueDisposition.QUEUED
            },
        ),
        mutationStore = PolicyMutationStore { request -> onMutation(request) },
    )

    private fun policyRef(fence: PolicyMutationFence, rank: Int = 1) = PolicyExposurePolicyRef(
        policyId = fence.policyId,
        policyRevision = fence.expectedContentRevision,
        artifactSha256 = fence.expectedArtifactHash,
        scope = fence.scope,
        rank = rank,
        estimatedTokens = 20,
        applicabilityCohortDigest = "a".repeat(64),
    )

    private fun linkedReceipt(refs: List<PolicyExposurePolicyRef>): PolicyExposureReceipt {
        val bundle = PolicyExposureBundle.create(refs)
        return PolicyExposureReceipt.restore(
            reservation = PolicyExposureReservation(
                key = PolicyExposureReservationKey(
                    streamId = Uuid.parse("00000000-0000-0000-0000-000000000611"),
                    episodeId = requireNotNull(
                        EpisodeId.parseOrNull("episode-v1:${"d".repeat(64)}"),
                    ),
                    logicalRunId = Uuid.parse("00000000-0000-0000-0000-000000000612"),
                    attemptOrdinal = 1,
                    policySetDigest = bundle.policySetDigest,
                ),
                bundle = bundle,
            ),
            observedStates = setOf(
                PolicyExposureState.RETRIEVED,
                PolicyExposureState.COMPILED,
                PolicyExposureState.INJECTED,
                PolicyExposureState.HOST_DISPATCHED,
                PolicyExposureState.RESPONSE_FINISHED,
                PolicyExposureState.OUTCOME_LINKED,
            ),
            stateVersion = 6L,
            terminalOutcome = ProviderAttemptTerminalOutcome.COMPLETED,
        )
    }

    private companion object {
        val SCOPE = LearningScope.Assistant(
            Uuid.parse("00000000-0000-0000-0000-000000000613"),
        )
        val FENCE = PolicyMutationFence(
            policyId = "policy-safety-one",
            scope = SCOPE,
            expectedRevision = 8L,
            expectedContentRevision = 4L,
            expectedArtifactHash = "a".repeat(64),
        )
        val AUTHORITY = PolicyExposureOutcomeAuthority(
            sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
            sourceId = "00000000-0000-0000-0000-000000000614",
            sourceRevision = 9L,
        )
    }
}

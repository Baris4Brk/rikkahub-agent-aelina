package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.curation.PolicyCuratorQueueDisposition
import me.rerere.rikkahub.learning.curation.PolicyCuratorV0
import me.rerere.rikkahub.learning.curation.PolicyDeltaCandidate
import me.rerere.rikkahub.learning.curation.PolicyDeltaOperation
import me.rerere.rikkahub.learning.curation.PolicyDistillationRequestQueue
import me.rerere.rikkahub.learning.curation.PolicyHarmReviewQueue
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PolicySafetyGovernorTest {
    @Test
    fun `exact dispatch authoritative failure and rule hit suspend only after durable evidence and review`() =
        runBlocking {
            val calls = mutableListOf<String>()
            var audit: PolicyLifecycleEvidenceRecord? = null
            var review: PolicyDeltaCandidate? = null
            var mutation: PolicyMutationRequest.Transition? = null
            val governor = governor(
                onReview = {
                    calls += "review"
                    review = it
                },
                onMutation = {
                    calls += "mutation"
                    mutation = it
                    audit = it.lifecycleEvidence
                },
            )

            val result = governor.evaluate(hardFailureCommand())

            assertTrue(result is PolicySafetyGovernorResult.SuspendedPendingReview)
            assertEquals(listOf("review", "mutation"), calls)
            assertEquals(PolicyLifecycleEvidenceKind.SAFETY_RULE_FAILURE, audit?.evidenceKind)
            assertEquals(LearningPolicyStatus.SUSPENDED_PENDING_REVIEW, audit?.target)
            assertEquals(PolicyDeltaOperation.QUEUE_HARM_REVIEW, review?.operation)
            assertEquals(LearningPolicyStatus.SUSPENDED_PENDING_REVIEW, mutation?.target)
            assertEquals(PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED, mutation?.reason)
            assertEquals(PolicyMutationActor.SAFETY_GOVERNOR, mutation?.actor)
            assertEquals(audit, mutation?.lifecycleEvidence)
        }

    @Test
    fun `missing outcome link or authoritative failure abstains with zero writes`() = runBlocking {
        var reviewCalls = 0
        var mutationCalls = 0
        val governor = governor(
            onReview = { reviewCalls += 1 },
            onMutation = { mutationCalls += 1 },
        )
        val noOutcomeLink = hardFailureCommand().copy(
            exposureReceipt = exposureReceipt(linked = false),
        )
        val success = hardFailureCommand().copy(
            authoritativeOutcome = authoritativeOutcome(
                PolicyAuthoritativeTerminalOutcome.SUCCESS,
            ),
        )

        assertEquals(
            PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.EXPOSURE_NOT_PROVEN,
            ),
            governor.evaluate(noOutcomeLink),
        )
        assertEquals(
            PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.AUTHORITATIVE_FAILURE_NOT_PROVEN,
            ),
            governor.evaluate(success),
        )
        assertEquals(0, reviewCalls)
        assertEquals(0, mutationCalls)
    }

    @Test
    fun `artifact mismatch and non fail closed rule never reach durable side effects`() = runBlocking {
        var writes = 0
        val governor = governor(
            onReview = { writes += 1 },
            onMutation = { writes += 1 },
        )
        val mismatch = hardFailureCommand().copy(
            signal = PolicySafetySignal.DeterministicRuleFailure(
                safetyRule().copy(matchedPolicyArtifactSha256 = "f".repeat(64)),
            ),
        )
        val advisoryRule = hardFailureCommand().copy(
            signal = PolicySafetySignal.DeterministicRuleFailure(
                safetyRule().copy(failClosed = false),
            ),
        )

        assertEquals(
            PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.POLICY_EXPOSURE_IDENTITY_MISMATCH,
            ),
            governor.evaluate(mismatch),
        )
        assertEquals(
            PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.RULE_NOT_A_DETERMINISTIC_HIT,
            ),
            governor.evaluate(advisoryRule),
        )
        assertEquals(0, writes)
    }

    @Test
    fun `helpfulness judge and observed association only queue harm review`() = runBlocking {
        var mutationCalls = 0
        val reviews = mutableListOf<PolicyDeltaCandidate>()
        val governor = governor(
            onReview = { reviews += it },
            onMutation = { mutationCalls += 1 },
        )

        PolicyAdvisoryHarmSource.entries.forEachIndexed { index, source ->
            val result = governor.evaluate(
                PolicySafetyGovernorCommand(
                    fence = FENCE,
                    signal = PolicySafetySignal.Advisory(
                        PolicyAdvisoryHarmSignal(
                            source = source,
                            evidenceContractVersion = 1,
                            evidenceDigest = (index + 1).toString().repeat(64),
                        ),
                    ),
                    frozenNowMs = 200L + index,
                ),
            )
            assertTrue(result is PolicySafetyGovernorResult.HarmReviewQueued)
        }

        assertEquals(3, reviews.size)
        assertEquals(0, mutationCalls)
        assertTrue(reviews.all { it.operation == PolicyDeltaOperation.QUEUE_HARM_REVIEW })
    }

    @Test
    fun `mutation storage failure abstains without claiming suspension`() = runBlocking {
        var reviewCalls = 0
        val curator = curator { reviewCalls += 1 }
        val governor = PolicySafetyGovernor(
            curator = curator,
            mutationStore = PolicyMutationStore { error("storage unavailable") },
        )

        val result = governor.evaluate(hardFailureCommand())

        assertEquals(
            PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.MUTATION_UNAVAILABLE,
            ),
            result,
        )
        assertEquals(1, reviewCalls)
    }

    @Test
    fun `canonical mutation rejects safety suspension without embedded evidence`() = runBlocking {
        var transactionCalls = 0
        val store = ValidatingPolicyMutationStore(
            PolicyMutationTransaction {
                transactionCalls += 1
                error("must fail before storage")
            },
        )

        val result = store.mutate(
            PolicyMutationRequest.Transition(
                fence = FENCE,
                target = LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
                reason = PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED,
                frozenNowMs = 200L,
                actor = PolicyMutationActor.SAFETY_GOVERNOR,
            ),
        )

        assertEquals(
            PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION),
            result,
        )
        assertEquals(0, transactionCalls)
    }

    private fun governor(
        onReview: (PolicyDeltaCandidate) -> Unit,
        onMutation: (PolicyMutationRequest.Transition) -> Unit,
    ): PolicySafetyGovernor {
        val curator = curator(onReview)
        val mutationStore = ValidatingPolicyMutationStore(
            PolicyMutationTransaction { request ->
                val transition = request as PolicyMutationRequest.Transition
                onMutation(transition)
                PolicyMutationResult.Applied(
                    transition.fence.policyId,
                    transition.fence.expectedRevision + 1L,
                    transition.target,
                )
            },
        )
        return PolicySafetyGovernor(curator, mutationStore)
    }

    private fun curator(
        onReview: (PolicyDeltaCandidate) -> Unit,
    ) = PolicyCuratorV0(
        distillationQueue = PolicyDistillationRequestQueue {
            error("Safety governor must not enqueue a draft")
        },
        harmReviewQueue = PolicyHarmReviewQueue { candidate ->
            onReview(candidate)
            PolicyCuratorQueueDisposition.QUEUED
        },
    )

    private fun hardFailureCommand() = PolicySafetyGovernorCommand(
        fence = FENCE,
        signal = PolicySafetySignal.DeterministicRuleFailure(safetyRule()),
        exposureReceipt = exposureReceipt(linked = true),
        authoritativeOutcome = authoritativeOutcome(PolicyAuthoritativeTerminalOutcome.FAILURE),
        frozenNowMs = 200L,
    )

    private fun safetyRule() = VersionedFailClosedPolicySafetyRule(
        ruleIdentityDigest = "1".repeat(64),
        ruleVersion = 3,
        ruleContractDigest = "2".repeat(64),
        evaluation = PolicySafetyRuleEvaluation.HIT,
        failClosed = true,
        matchedPolicyArtifactSha256 = FENCE.expectedArtifactHash,
        ruleEvidenceDigest = "3".repeat(64),
    )

    private fun authoritativeOutcome(outcome: PolicyAuthoritativeTerminalOutcome) =
        PolicyAuthoritativeOutcomeEvidence(
            outcome = outcome,
            authorityRevision = 9L,
            authorityEvidenceDigest = "4".repeat(64),
        )

    private fun exposureReceipt(linked: Boolean): PolicyExposureReceipt {
        val bundle = PolicyExposureBundle.create(
            listOf(
                PolicyExposurePolicyRef(
                    policyId = FENCE.policyId,
                    policyRevision = FENCE.expectedContentRevision,
                    artifactSha256 = FENCE.expectedArtifactHash,
                    scope = FENCE.scope,
                    rank = 1,
                    estimatedTokens = 20,
                applicabilityCohortDigest = "a".repeat(64),
                ),
            ),
        )
        val reservation = PolicyExposureReservation(
            key = PolicyExposureReservationKey(
                streamId = Uuid.parse("00000000-0000-0000-0000-000000000301"),
                episodeId = requireNotNull(
                    EpisodeId.parseOrNull("episode-v1:${"5".repeat(64)}"),
                ),
                logicalRunId = Uuid.parse("00000000-0000-0000-0000-000000000302"),
                attemptOrdinal = 1,
                policySetDigest = bundle.policySetDigest,
            ),
            bundle = bundle,
        )
        val states = buildSet {
            add(PolicyExposureState.RETRIEVED)
            add(PolicyExposureState.COMPILED)
            add(PolicyExposureState.INJECTED)
            add(PolicyExposureState.HOST_DISPATCHED)
            if (linked) add(PolicyExposureState.OUTCOME_LINKED)
        }
        return PolicyExposureReceipt.restore(
            reservation = reservation,
            observedStates = states,
            stateVersion = states.size.toLong(),
            terminalOutcome = ProviderAttemptTerminalOutcome.FAILED,
        )
    }

    private companion object {
        val SCOPE = LearningScope.Assistant(
            Uuid.parse("00000000-0000-0000-0000-000000000303"),
        )
        val FENCE = PolicyMutationFence(
            policyId = "policy-safe-one",
            scope = SCOPE,
            expectedRevision = 8L,
            expectedContentRevision = 4L,
            expectedArtifactHash = "a".repeat(64),
        )
    }
}

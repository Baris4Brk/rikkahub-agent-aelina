package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.curation.PolicyCuratorRoutingResult
import me.rerere.rikkahub.learning.curation.PolicyCuratorV0
import me.rerere.rikkahub.learning.curation.PolicyDeltaCandidate
import me.rerere.rikkahub.learning.curation.PolicyDeltaOperation
import me.rerere.rikkahub.learning.curation.PolicyCuratorQueueDisposition
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.model.LearningCanonicalId

private val POLICY_SAFETY_SHA256 = Regex("[0-9a-f]{64}")
private const val POLICY_SAFETY_EVIDENCE_CONTRACT_VERSION = 1

enum class PolicyAuthoritativeTerminalOutcome {
    SUCCESS,
    FAILURE,
    CENSORED,
    UNKNOWN,
}

/** Content-free pointer to the exact committed Conversation/Command terminal authority. */
data class PolicyAuthoritativeOutcomeEvidence(
    val outcome: PolicyAuthoritativeTerminalOutcome,
    val authorityRevision: Long,
    val authorityEvidenceDigest: String,
) {
    init {
        require(authorityRevision > 0L)
        require(authorityEvidenceDigest.matches(POLICY_SAFETY_SHA256))
    }
}

enum class PolicySafetyRuleEvaluation {
    HIT,
    MISS,
    UNKNOWN,
}

/** Only this deterministic, versioned rule type may authorize automatic risk isolation. */
data class VersionedFailClosedPolicySafetyRule(
    val ruleIdentityDigest: String,
    val ruleVersion: Int,
    val ruleContractDigest: String,
    val evaluation: PolicySafetyRuleEvaluation,
    val failClosed: Boolean,
    /** Exact Policy artifact for which the rule matched, even in a co-exposed bundle. */
    val matchedPolicyArtifactSha256: String,
    val ruleEvidenceDigest: String,
) {
    init {
        listOf(
            ruleIdentityDigest,
            ruleContractDigest,
            matchedPolicyArtifactSha256,
            ruleEvidenceDigest,
        ).forEach { require(it.matches(POLICY_SAFETY_SHA256)) }
        require(ruleVersion > 0)
    }
}

enum class PolicyAdvisoryHarmSource {
    HELPFUL_HARMFUL_LABEL,
    LLM_JUDGE,
    MATCHED_COHORT_OBSERVED_UTILITY,
}

/** Advisory signals can create a review candidate but can never change lifecycle state. */
data class PolicyAdvisoryHarmSignal(
    val source: PolicyAdvisoryHarmSource,
    val evidenceContractVersion: Int,
    val evidenceDigest: String,
) {
    init {
        require(evidenceContractVersion > 0)
        require(evidenceDigest.matches(POLICY_SAFETY_SHA256))
    }
}

sealed interface PolicySafetySignal {
    data class DeterministicRuleFailure(
        val rule: VersionedFailClosedPolicySafetyRule,
    ) : PolicySafetySignal

    data class Advisory(
        val signal: PolicyAdvisoryHarmSignal,
    ) : PolicySafetySignal
}

data class PolicySafetyGovernorCommand(
    val fence: PolicyMutationFence,
    val signal: PolicySafetySignal,
    val exposureReceipt: PolicyExposureReceipt? = null,
    val authoritativeOutcome: PolicyAuthoritativeOutcomeEvidence? = null,
    /** Frozen across replay of this exact safety decision. */
    val frozenNowMs: Long,
) {
    init {
        require(frozenNowMs >= 0L)
    }
}

enum class PolicySafetyAbstainReason {
    RULE_NOT_A_DETERMINISTIC_HIT,
    EXPOSURE_NOT_PROVEN,
    POLICY_EXPOSURE_IDENTITY_MISMATCH,
    AUTHORITATIVE_FAILURE_NOT_PROVEN,
    REVIEW_QUEUE_REJECTED,
    REVIEW_QUEUE_UNAVAILABLE,
    MUTATION_UNAVAILABLE,
}

sealed interface PolicySafetyGovernorResult {
    data class HarmReviewQueued(
        val disposition: PolicyCuratorQueueDisposition,
    ) : PolicySafetyGovernorResult

    data class SuspendedPendingReview(
        val mutation: PolicyMutationResult.Applied,
        val reviewDisposition: PolicyCuratorQueueDisposition,
    ) : PolicySafetyGovernorResult

    data class SuspensionDuplicate(
        val mutation: PolicyMutationResult.Duplicate,
        val reviewDisposition: PolicyCuratorQueueDisposition,
    ) : PolicySafetyGovernorResult

    data class SuspensionConflict(
        val mutation: PolicyMutationResult.Conflict,
        val reviewDisposition: PolicyCuratorQueueDisposition,
    ) : PolicySafetyGovernorResult

    data class Abstained(
        val reason: PolicySafetyAbstainReason,
    ) : PolicySafetyGovernorResult
}

/**
 * Safety isolation is deliberately stronger than observed harmfulness. Advisory signals stop at
 * the Curator review queue. Automatic suspension additionally requires exact durable exposure,
 * authoritative failure and a versioned fail-closed rule hit. The exact rule/outcome evidence is
 * embedded in the canonical mutation and atomically committed with the lifecycle revision.
 */
class PolicySafetyGovernor(
    private val curator: PolicyCuratorV0,
    private val mutationStore: PolicyMutationStore,
) {
    suspend fun evaluate(command: PolicySafetyGovernorCommand): PolicySafetyGovernorResult =
        when (val signal = command.signal) {
            is PolicySafetySignal.Advisory -> queueAdvisory(command.fence, signal.signal)
            is PolicySafetySignal.DeterministicRuleFailure -> suspendForRule(command, signal.rule)
        }

    private suspend fun queueAdvisory(
        fence: PolicyMutationFence,
        signal: PolicyAdvisoryHarmSignal,
    ): PolicySafetyGovernorResult {
        val queued = queueReview(
            fence = fence,
            evidenceDigest = signal.evidenceDigest,
            reasonCode = "P2_${signal.source.name}",
        )
        return when (queued) {
            is ReviewQueueResult.Accepted ->
                PolicySafetyGovernorResult.HarmReviewQueued(queued.disposition)
            is ReviewQueueResult.Abstained -> PolicySafetyGovernorResult.Abstained(queued.reason)
        }
    }

    private suspend fun suspendForRule(
        command: PolicySafetyGovernorCommand,
        rule: VersionedFailClosedPolicySafetyRule,
    ): PolicySafetyGovernorResult {
        if (!rule.failClosed || rule.evaluation != PolicySafetyRuleEvaluation.HIT) {
            return PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.RULE_NOT_A_DETERMINISTIC_HIT,
            )
        }
        val receipt = command.exposureReceipt ?: return PolicySafetyGovernorResult.Abstained(
            PolicySafetyAbstainReason.EXPOSURE_NOT_PROVEN,
        )
        if (!receipt.hasObserved(PolicyExposureState.INJECTED) ||
            !receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED) ||
            !receipt.hasObserved(PolicyExposureState.OUTCOME_LINKED)
        ) {
            return PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.EXPOSURE_NOT_PROVEN,
            )
        }
        val exactPolicy = receipt.reservation.bundle.policies.singleOrNull { policy ->
            policy.policyId == command.fence.policyId &&
                policy.policyRevision == command.fence.expectedContentRevision &&
                policy.artifactSha256 == command.fence.expectedArtifactHash &&
                policy.scope == command.fence.scope
        }
        if (exactPolicy == null ||
            rule.matchedPolicyArtifactSha256 != command.fence.expectedArtifactHash
        ) {
            return PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.POLICY_EXPOSURE_IDENTITY_MISMATCH,
            )
        }
        val outcome = command.authoritativeOutcome
        if (outcome?.outcome != PolicyAuthoritativeTerminalOutcome.FAILURE) {
            return PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.AUTHORITATIVE_FAILURE_NOT_PROVEN,
            )
        }
        val combinedEvidenceDigest = LearningCanonicalId.digest(
            domainVersion = "policy-safety-evidence-v1",
            fields = listOf(
                receipt.reservation.key.reservationId,
                receipt.stateVersion.toString(),
                rule.ruleIdentityDigest,
                rule.ruleVersion.toString(),
                rule.ruleContractDigest,
                rule.ruleEvidenceDigest,
                outcome.authorityRevision.toString(),
                outcome.authorityEvidenceDigest,
                command.fence.expectedContentRevision.toString(),
                command.fence.expectedArtifactHash,
            ),
        )
        val evidenceRecord = PolicyLifecycleEvidenceRecord(
            fence = command.fence,
            target = LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
            reason = PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED,
            evidenceKind = PolicyLifecycleEvidenceKind.SAFETY_RULE_FAILURE,
            evidenceContractVersion = POLICY_SAFETY_EVIDENCE_CONTRACT_VERSION,
            evidenceDigest = combinedEvidenceDigest,
            observedAtMs = command.frozenNowMs,
        )
        val queued = queueReview(
            fence = command.fence,
            evidenceDigest = combinedEvidenceDigest,
            reasonCode = "P2_VERSIONED_FAIL_CLOSED_SAFETY_RULE",
        )
        val reviewDisposition = when (queued) {
            is ReviewQueueResult.Accepted -> queued.disposition
            is ReviewQueueResult.Abstained -> return PolicySafetyGovernorResult.Abstained(
                queued.reason,
            )
        }

        val mutation = try {
            mutationStore.mutate(
                PolicyMutationRequest.Transition(
                    fence = command.fence,
                    target = LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
                    reason = PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED,
                    frozenNowMs = command.frozenNowMs,
                    actor = PolicyMutationActor.SAFETY_GOVERNOR,
                    lifecycleEvidence = evidenceRecord,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return PolicySafetyGovernorResult.Abstained(
                PolicySafetyAbstainReason.MUTATION_UNAVAILABLE,
            )
        }
        return when (mutation) {
            is PolicyMutationResult.Applied ->
                PolicySafetyGovernorResult.SuspendedPendingReview(mutation, reviewDisposition)
            is PolicyMutationResult.Duplicate ->
                PolicySafetyGovernorResult.SuspensionDuplicate(mutation, reviewDisposition)
            is PolicyMutationResult.Conflict ->
                PolicySafetyGovernorResult.SuspensionConflict(mutation, reviewDisposition)
        }
    }

    private suspend fun queueReview(
        fence: PolicyMutationFence,
        evidenceDigest: String,
        reasonCode: String,
    ): ReviewQueueResult {
        val result = try {
            curator.route(
                candidate = PolicyDeltaCandidate(
                    operation = PolicyDeltaOperation.QUEUE_HARM_REVIEW,
                    candidateId = null,
                    inputSetHash = null,
                    producerIdentity = null,
                    modelIdentity = null,
                    promptVersion = null,
                    schemaVersion = null,
                    targetPolicyId = fence.policyId,
                    expectedRevision = fence.expectedRevision,
                    baseArtifactHash = fence.expectedArtifactHash,
                    evidenceIds = listOf(evidenceDigest),
                    reasonCode = reasonCode,
                ),
                evidenceAllowlist = setOf(evidenceDigest),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return ReviewQueueResult.Abstained(
                PolicySafetyAbstainReason.REVIEW_QUEUE_UNAVAILABLE,
            )
        }
        return when (result) {
            is PolicyCuratorRoutingResult.HarmReviewQueued ->
                ReviewQueueResult.Accepted(result.disposition)
            is PolicyCuratorRoutingResult.Rejected -> ReviewQueueResult.Abstained(
                PolicySafetyAbstainReason.REVIEW_QUEUE_REJECTED,
            )
            PolicyCuratorRoutingResult.NoOp,
            is PolicyCuratorRoutingResult.NewDraftQueued,
            -> ReviewQueueResult.Abstained(PolicySafetyAbstainReason.REVIEW_QUEUE_REJECTED)
        }
    }
}

private sealed interface ReviewQueueResult {
    data class Accepted(
        val disposition: PolicyCuratorQueueDisposition,
    ) : ReviewQueueResult

    data class Abstained(
        val reason: PolicySafetyAbstainReason,
    ) : ReviewQueueResult
}

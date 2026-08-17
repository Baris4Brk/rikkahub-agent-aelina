package me.rerere.rikkahub.learning.policy.runtime

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.exposure.PolicyExposureOutcomeAuthority
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyAdvisoryHarmSignal
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeOutcomeEvidence
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeTerminalOutcome
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernor
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernorCommand
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernorResult
import me.rerere.rikkahub.learning.policy.PolicySafetyRuleEvaluation
import me.rerere.rikkahub.learning.policy.PolicySafetySignal
import me.rerere.rikkahub.learning.policy.VersionedFailClosedPolicySafetyRule

private const val SINGLE_POLICY_RESPONSE_FAILURE_RULE_VERSION = 1

data class PolicyOutcomeSafetyTrigger(
    val reservationId: String,
    val expectedExposureStateVersion: Long,
    val outcomeAuthority: PolicyExposureOutcomeAuthority,
    /** Frozen when the exact outcome link commits and reused by retries. */
    val frozenNowMs: Long,
) {
    init {
        require(reservationId.length in 1..256)
        require(expectedExposureStateVersion > 0L)
        require(frozenNowMs >= 0L)
    }
}

/**
 * Same-transaction callback invoked only after an exact durable outcome link exists. Throwing is
 * intentional: the caller owns the transaction and must roll the link back so catch-up can retry.
 */
fun interface PolicyOutcomeLinkedObserver {
    suspend fun onLinked(trigger: PolicyOutcomeSafetyTrigger)
}

object NoOpPolicyOutcomeLinkedObserver : PolicyOutcomeLinkedObserver {
    override suspend fun onLinked(trigger: PolicyOutcomeSafetyTrigger) = Unit
}

data class ExactSafetyPolicyHead(
    val fence: PolicyMutationFence,
    val status: LearningPolicyStatus,
) {
    init {
        require(status != LearningPolicyStatus.CANDIDATE)
    }
}

/** Raw terminal classification from the authority-owned committed Command/Episode projection. */
data class AuthoritativeTerminalSafetyFact(
    val authority: PolicyExposureOutcomeAuthority,
    val outcome: PolicyAuthoritativeTerminalOutcome,
    val terminalContractVersion: Int,
) {
    init {
        require(terminalContractVersion > 0)
    }

    fun toGovernorEvidence(): PolicyAuthoritativeOutcomeEvidence =
        PolicyAuthoritativeOutcomeEvidence(
            outcome = outcome,
            authorityRevision = authority.sourceRevision,
            authorityEvidenceDigest = LearningCanonicalId.digest(
                domainVersion = "policy-authoritative-terminal-evidence-v1",
                fields = listOf(
                    authority.sourceKind.name,
                    authority.sourceId,
                    authority.sourceRevision.toString(),
                    outcome.name,
                    terminalContractVersion.toString(),
                ),
            ),
        )
}

data class DurablePolicyOutcomeSafetyMaterial(
    val receipt: PolicyExposureReceipt,
    val terminal: AuthoritativeTerminalSafetyFact,
    val policyHeads: List<ExactSafetyPolicyHead>,
) {
    init {
        require(receipt.hasObserved(PolicyExposureState.OUTCOME_LINKED))
        require(receipt.terminalOutcome != null)
        require(policyHeads.isNotEmpty() && policyHeads.size <= 20)
        require(policyHeads.map { it.fence.policyId }.distinct().size == policyHeads.size)
        val refs = receipt.reservation.bundle.policies
        require(refs.size == policyHeads.size)
        policyHeads.forEach { head ->
            require(refs.singleOrNull { ref ->
                ref.policyId == head.fence.policyId &&
                    ref.policyRevision == head.fence.expectedContentRevision &&
                    ref.artifactSha256 == head.fence.expectedArtifactHash &&
                    ref.scope == head.fence.scope
            } != null)
        }
    }
}

sealed interface PolicyOutcomeSafetyMaterialResult {
    data class Ready(val material: DurablePolicyOutcomeSafetyMaterial) :
        PolicyOutcomeSafetyMaterialResult

    data object Conflict : PolicyOutcomeSafetyMaterialResult
    data object Unavailable : PolicyOutcomeSafetyMaterialResult
}

fun interface PolicyOutcomeSafetyMaterialSource {
    /** Must re-read exposure, terminal authority and every exact Policy head. */
    suspend fun loadExact(trigger: PolicyOutcomeSafetyTrigger): PolicyOutcomeSafetyMaterialResult
}

/**
 * Closed deterministic rule catalogue. A completed provider response that is the sole exposed
 * Policy and is linked to an authoritative FAILURE is a real fail-closed hit. Co-exposed bundles,
 * provider failures/cancellations and unknown/censored outcomes never get individual attribution.
 */
object VersionedPolicySafetyRules {
    fun singlePolicyCompletedResponseFailure(
        material: DurablePolicyOutcomeSafetyMaterial,
        head: ExactSafetyPolicyHead,
    ): VersionedFailClosedPolicySafetyRule? {
        val receipt = material.receipt
        val ref = receipt.reservation.bundle.policies.singleOrNull() ?: return null
        if (material.policyHeads.singleOrNull() != head ||
            head.status != LearningPolicyStatus.ACTIVE ||
            material.terminal.outcome != PolicyAuthoritativeTerminalOutcome.FAILURE ||
            receipt.terminalOutcome != ProviderAttemptTerminalOutcome.COMPLETED ||
            !receipt.canAttributeObservedUtility ||
            ref.policyId != head.fence.policyId ||
            ref.policyRevision != head.fence.expectedContentRevision ||
            ref.artifactSha256 != head.fence.expectedArtifactHash ||
            ref.scope != head.fence.scope
        ) return null
        val ruleIdentity = LearningCanonicalId.digest(
            domainVersion = "policy-safety-rule-identity-v1",
            fields = listOf("SINGLE_POLICY_COMPLETED_RESPONSE_AUTHORITATIVE_FAILURE"),
        )
        val ruleContract = LearningCanonicalId.digest(
            domainVersion = "policy-safety-rule-contract-v1",
            fields = listOf(
                SINGLE_POLICY_RESPONSE_FAILURE_RULE_VERSION.toString(),
                "BUNDLE_SIZE_ONE",
                "PROVIDER_COMPLETED",
                "AUTHORITATIVE_FAILURE",
                "OUTCOME_LINKED",
                "ACTIVE_EXACT_POLICY",
            ),
        )
        val evidence = LearningCanonicalId.digest(
            domainVersion = "policy-safety-rule-hit-v1",
            fields = listOf(
                receipt.reservation.key.reservationId,
                receipt.stateVersion.toString(),
                checkNotNull(receipt.terminalOutcome).name,
                material.terminal.authority.sourceKind.name,
                material.terminal.authority.sourceId,
                material.terminal.authority.sourceRevision.toString(),
                material.terminal.outcome.name,
                head.fence.policyId,
                head.fence.expectedRevision.toString(),
                head.fence.expectedContentRevision.toString(),
                head.fence.expectedArtifactHash,
            ),
        )
        return VersionedFailClosedPolicySafetyRule(
            ruleIdentityDigest = ruleIdentity,
            ruleVersion = SINGLE_POLICY_RESPONSE_FAILURE_RULE_VERSION,
            ruleContractDigest = ruleContract,
            evaluation = PolicySafetyRuleEvaluation.HIT,
            failClosed = true,
            matchedPolicyArtifactSha256 = head.fence.expectedArtifactHash,
            ruleEvidenceDigest = evidence,
        )
    }
}

sealed interface PolicyOutcomeSafetyRuntimeResult {
    data class Evaluated(
        val deterministicHits: Int,
        val noRule: Int,
        val governorResults: List<PolicySafetyGovernorResult>,
    ) : PolicyOutcomeSafetyRuntimeResult

    data object Conflict : PolicyOutcomeSafetyRuntimeResult
    data object Unavailable : PolicyOutcomeSafetyRuntimeResult
}

class PolicyOutcomeSafetyRuntime(
    private val source: PolicyOutcomeSafetyMaterialSource,
    private val governor: PolicySafetyGovernor,
) {
    suspend fun onOutcomeLinked(
        trigger: PolicyOutcomeSafetyTrigger,
    ): PolicyOutcomeSafetyRuntimeResult {
        val material = try {
            when (val loaded = source.loadExact(trigger)) {
                is PolicyOutcomeSafetyMaterialResult.Ready -> loaded.material
                PolicyOutcomeSafetyMaterialResult.Conflict ->
                    return PolicyOutcomeSafetyRuntimeResult.Conflict
                PolicyOutcomeSafetyMaterialResult.Unavailable ->
                    return PolicyOutcomeSafetyRuntimeResult.Unavailable
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PolicyOutcomeSafetyRuntimeResult.Unavailable
        }
        if (material.receipt.reservation.key.reservationId != trigger.reservationId ||
            material.receipt.stateVersion != trigger.expectedExposureStateVersion ||
            material.terminal.authority != trigger.outcomeAuthority
        ) return PolicyOutcomeSafetyRuntimeResult.Conflict

        val authoritative = material.terminal.toGovernorEvidence()
        val results = mutableListOf<PolicySafetyGovernorResult>()
        var hits = 0
        var noRule = 0
        material.policyHeads.sortedBy { it.fence.policyId }.forEach { head ->
            val rule = VersionedPolicySafetyRules.singlePolicyCompletedResponseFailure(
                material,
                head,
            )
            if (rule == null) {
                noRule += 1
            } else {
                hits += 1
                results += governor.evaluate(
                    PolicySafetyGovernorCommand(
                        fence = head.fence,
                        signal = PolicySafetySignal.DeterministicRuleFailure(rule),
                        exposureReceipt = material.receipt,
                        authoritativeOutcome = authoritative,
                        frozenNowMs = trigger.frozenNowMs,
                    ),
                )
            }
        }
        return PolicyOutcomeSafetyRuntimeResult.Evaluated(hits, noRule, results)
    }
}

/** Advisory input is intentionally routed through the governor's queue-only branch. */
class PolicySafetyAdvisoryRuntime(
    private val governor: PolicySafetyGovernor,
) {
    suspend fun queue(
        fence: PolicyMutationFence,
        signal: PolicyAdvisoryHarmSignal,
        frozenNowMs: Long,
    ): PolicySafetyGovernorResult = governor.evaluate(
        PolicySafetyGovernorCommand(
            fence = fence,
            signal = PolicySafetySignal.Advisory(signal),
            frozenNowMs = frozenNowMs,
        ),
    )
}

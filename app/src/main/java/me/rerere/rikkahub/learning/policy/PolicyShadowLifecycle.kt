package me.rerere.rikkahub.learning.policy

/** Reviewed build-time identity documented by docs/agent-learning/P1-shadow-retrieval-gate-v1.md. */
const val P1_SHADOW_ADMISSION_GATE_ID: String = "p1-shadow-retrieval-gate-v1"

data class PolicyShadowPromotionCommand(
    val fence: PolicyMutationFence,
    /** Frozen by the caller and retained across a retry. */
    val frozenNowMs: Long,
    /** An arbitrary runtime score or threshold can never masquerade as the reviewed gate. */
    val admissionGateIdentity: String = P1_SHADOW_ADMISSION_GATE_ID,
) {
    init {
        require(frozenNowMs >= 0L)
        require(admissionGateIdentity == P1_SHADOW_ADMISSION_GATE_ID)
    }

    override fun toString(): String =
        "PolicyShadowPromotionCommand(scope=${fence.scope.kind}, revision=${fence.expectedRevision}, ids=<redacted>)"
}

/**
 * Local-only P1 lifecycle seam. It cannot activate or inject a policy: its sole promotion is the
 * deterministic, revision/artifact-fenced CANDIDATE -> SHADOW mutation through the canonical
 * [PolicyMutationStore]. The actor is deliberately SHADOW_GATE rather than CURATOR_REVIEW: Stage D
 * observes a validated candidate but does not claim that a person reviewed it.
 */
class PolicyShadowLifecycle(
    private val mutationStore: PolicyMutationStore,
) {
    suspend fun promote(command: PolicyShadowPromotionCommand): PolicyMutationResult =
        mutationStore.mutate(
            PolicyMutationRequest.Transition(
                fence = command.fence,
                target = LearningPolicyStatus.SHADOW,
                reason = PolicyLifecycleReason.SHADOW_ELIGIBLE,
                frozenNowMs = command.frozenNowMs,
                actor = PolicyMutationActor.SHADOW_GATE,
            ),
        )
}

enum class PolicyShadowAdmissionFailure {
    GATE_IDENTITY_MISMATCH,
    STATUS_INELIGIBLE,
    SOURCE_OR_SCHEMA_STALE,
    EMPTY_EVIDENCE,
    EVIDENCE_INVALID,
    SUPPORT_MISMATCH,
    POLARITY_MISMATCH,
    AUTHORITY_POLARITY_MISSING,
    P1_EFFECT_STATE_PRESENT,
}

data class PolicyShadowAdmissionFacts(
    val gateIdentity: String,
    val status: LearningPolicyStatus,
    val policyType: PolicyCandidateType,
    val sourceValid: Boolean,
    val schemaValid: Boolean,
    val distinctEpisodeSupport: Long,
    val positiveEpisodeCount: Long,
    val negativeEpisodeCount: Long,
    val evidenceEpisodeIds: List<String>,
    val validEvidenceEpisodeIds: Set<String>,
    val positiveEvidenceEpisodeIds: Set<String>,
    val negativeEvidenceEpisodeIds: Set<String>,
    val usageCount: Long,
    val observedUtilityDelta: Double?,
    val utilityUncertainty: Double?,
) {
    init {
        require(distinctEpisodeSupport >= 0L)
        require(positiveEpisodeCount >= 0L && negativeEpisodeCount >= 0L)
        require(usageCount >= 0L)
        require(evidenceEpisodeIds.size <= MAX_SHADOW_ADMISSION_EVIDENCE)
        require(evidenceEpisodeIds.all { it.isNotBlank() })
    }
}

sealed interface PolicyShadowAdmissionDecision {
    data object Eligible : PolicyShadowAdmissionDecision
    data class Rejected(val failure: PolicyShadowAdmissionFailure) :
        PolicyShadowAdmissionDecision
}

/**
 * Frozen P1 admission invariant. It intentionally has no confidence, utility, score, or numeric
 * promotion threshold. Candidate creation already owns its versioned evidence minimum; Stage D
 * only proves that the current durable evidence still exactly supports the cached statistics.
 */
object FrozenP1PolicyShadowAdmissionGate {
    fun evaluate(facts: PolicyShadowAdmissionFacts): PolicyShadowAdmissionDecision {
        if (facts.gateIdentity != P1_SHADOW_ADMISSION_GATE_ID) {
            return rejected(PolicyShadowAdmissionFailure.GATE_IDENTITY_MISMATCH)
        }
        if (facts.status !in setOf(LearningPolicyStatus.CANDIDATE, LearningPolicyStatus.SHADOW)) {
            return rejected(PolicyShadowAdmissionFailure.STATUS_INELIGIBLE)
        }
        if (!facts.sourceValid || !facts.schemaValid) {
            return rejected(PolicyShadowAdmissionFailure.SOURCE_OR_SCHEMA_STALE)
        }
        val distinctEvidence = facts.evidenceEpisodeIds.toSet()
        if (distinctEvidence.isEmpty()) {
            return rejected(PolicyShadowAdmissionFailure.EMPTY_EVIDENCE)
        }
        if (distinctEvidence.size != facts.evidenceEpisodeIds.size ||
            distinctEvidence != facts.validEvidenceEpisodeIds
        ) {
            return rejected(PolicyShadowAdmissionFailure.EVIDENCE_INVALID)
        }
        if (facts.distinctEpisodeSupport != distinctEvidence.size.toLong()) {
            return rejected(PolicyShadowAdmissionFailure.SUPPORT_MISMATCH)
        }
        val classifiedEvidence = facts.positiveEvidenceEpisodeIds +
            facts.negativeEvidenceEpisodeIds
        if (facts.positiveEvidenceEpisodeIds.intersect(facts.negativeEvidenceEpisodeIds)
                .isNotEmpty() ||
            facts.positiveEpisodeCount != facts.positiveEvidenceEpisodeIds.size.toLong() ||
            facts.negativeEpisodeCount != facts.negativeEvidenceEpisodeIds.size.toLong() ||
            classifiedEvidence != distinctEvidence
        ) {
            return rejected(PolicyShadowAdmissionFailure.POLARITY_MISMATCH)
        }
        val authorityPolarityPresent = when (facts.policyType) {
            PolicyCandidateType.AVOID,
            PolicyCandidateType.FAILURE_MODE,
            -> facts.negativeEvidenceEpisodeIds.isNotEmpty()
            else -> facts.positiveEvidenceEpisodeIds.isNotEmpty()
        }
        if (!authorityPolarityPresent) {
            return rejected(PolicyShadowAdmissionFailure.AUTHORITY_POLARITY_MISSING)
        }
        if (facts.usageCount != 0L || facts.observedUtilityDelta != null ||
            facts.utilityUncertainty != null
        ) {
            return rejected(PolicyShadowAdmissionFailure.P1_EFFECT_STATE_PRESENT)
        }
        return PolicyShadowAdmissionDecision.Eligible
    }

    private fun rejected(failure: PolicyShadowAdmissionFailure) =
        PolicyShadowAdmissionDecision.Rejected(failure)
}

const val MAX_SHADOW_ADMISSION_EVIDENCE: Int = 256

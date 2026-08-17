package me.rerere.rikkahub.learning.policy

private val POLICY_LIFECYCLE_EVIDENCE_DIGEST = Regex("[0-9a-f]{64}")

/**
 * Content-free evidence classes which may justify a fail-closed lifecycle transition.
 *
 * The evidence body remains in its authority-owned store (source validity, tool/authority
 * catalog, or Policy exposure/outcome records). Only its versioned digest crosses this boundary.
 */
enum class PolicyLifecycleEvidenceKind {
    SOURCE_TOMBSTONE,
    SOURCE_REVISION_DRIFT,
    TOOL_SCHEMA_DRIFT,
    AUTHORITY_DRIFT,
    CAPABILITY_DRIFT,
    SAFETY_RULE_FAILURE,
}

/** Exact, bounded audit record that must be durable before an automatic downgrade is attempted. */
data class PolicyLifecycleEvidenceRecord(
    val fence: PolicyMutationFence,
    val target: LearningPolicyStatus,
    val reason: PolicyLifecycleReason,
    val evidenceKind: PolicyLifecycleEvidenceKind,
    val evidenceContractVersion: Int,
    val evidenceDigest: String,
    val observedAtMs: Long,
) {
    init {
        require(target in AUTOMATIC_POLICY_DOWNGRADE_TARGETS) {
            "Lifecycle evidence can only authorize a fail-closed downgrade"
        }
        require(evidenceMatchesLifecycleTarget()) {
            "Lifecycle evidence kind, target and reason disagree"
        }
        require(evidenceContractVersion > 0) { "Invalid lifecycle evidence version" }
        require(evidenceDigest.matches(POLICY_LIFECYCLE_EVIDENCE_DIGEST)) {
            "Invalid lifecycle evidence digest"
        }
        require(observedAtMs >= 0L) { "Negative lifecycle evidence clock" }
    }

    override fun toString(): String =
        "PolicyLifecycleEvidenceRecord(kind=$evidenceKind, version=$evidenceContractVersion, " +
            "target=$target, scope=${fence.scope.kind}, ids=<redacted>)"

    internal fun exactlyAuthorizes(request: PolicyMutationRequest.Transition): Boolean =
        fence == request.fence &&
            target == request.target &&
            reason == request.reason &&
            observedAtMs == request.frozenNowMs
}

private fun PolicyLifecycleEvidenceRecord.evidenceMatchesLifecycleTarget(): Boolean = when (target) {
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW ->
        reason == PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED &&
            evidenceKind == PolicyLifecycleEvidenceKind.SAFETY_RULE_FAILURE
    LearningPolicyStatus.STALE_SOURCE ->
        reason == PolicyLifecycleReason.SOURCE_INVALIDATED &&
            evidenceKind in setOf(
                PolicyLifecycleEvidenceKind.SOURCE_TOMBSTONE,
                PolicyLifecycleEvidenceKind.SOURCE_REVISION_DRIFT,
            )
    LearningPolicyStatus.STALE_SCHEMA -> when (evidenceKind) {
        PolicyLifecycleEvidenceKind.TOOL_SCHEMA_DRIFT ->
            reason == PolicyLifecycleReason.TOOL_SCHEMA_CHANGED
        PolicyLifecycleEvidenceKind.CAPABILITY_DRIFT ->
            reason == PolicyLifecycleReason.CAPABILITY_CHANGED
        else -> false
    }
    LearningPolicyStatus.STALE_AUTHORITY ->
        reason == PolicyLifecycleReason.AUTHORITY_CHANGED &&
            evidenceKind == PolicyLifecycleEvidenceKind.AUTHORITY_DRIFT
    else -> false
}

private val AUTOMATIC_POLICY_DOWNGRADE_TARGETS = setOf(
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
    LearningPolicyStatus.STALE_SOURCE,
    LearningPolicyStatus.STALE_SCHEMA,
    LearningPolicyStatus.STALE_AUTHORITY,
)

package me.rerere.rikkahub.learning.curation

enum class PolicyDeltaOperation {
    NO_OP,
    QUEUE_NEW_DRAFT,
    QUEUE_HARM_REVIEW,
}

data class PolicyDeltaCandidate(
    val operation: PolicyDeltaOperation,
    val candidateId: String?,
    val inputSetHash: String?,
    val producerIdentity: String?,
    val modelIdentity: String?,
    val promptVersion: String?,
    val schemaVersion: Int?,
    val targetPolicyId: String?,
    val expectedRevision: Long?,
    val baseArtifactHash: String?,
    val evidenceIds: List<String>,
    val reasonCode: String?,
) {
    override fun toString(): String =
        "PolicyDeltaCandidate(operation=$operation, evidence=${evidenceIds.size}, ids=<redacted>)"
}

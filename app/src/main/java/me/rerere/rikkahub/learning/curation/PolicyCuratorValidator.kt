package me.rerere.rikkahub.learning.curation

enum class PolicyCuratorValidationFailure {
    INVALID_NO_OP,
    NEW_DRAFT_IDENTITY_MISSING,
    NEW_DRAFT_TARGET_FORBIDDEN,
    HARM_REVIEW_TARGET_MISSING,
    HARM_REVIEW_BASE_MISSING,
    EVIDENCE_OUTSIDE_ALLOWLIST,
    INVALID_VERSION,
}

sealed interface PolicyCuratorValidationResult {
    data class Valid(val candidate: PolicyDeltaCandidate) : PolicyCuratorValidationResult
    data class Rejected(val failure: PolicyCuratorValidationFailure) : PolicyCuratorValidationResult
}

object PolicyCuratorValidator {
    fun validate(
        candidate: PolicyDeltaCandidate,
        evidenceAllowlist: Set<String>,
    ): PolicyCuratorValidationResult {
        if (candidate.evidenceIds.distinct().size != candidate.evidenceIds.size ||
            candidate.evidenceIds.any { it !in evidenceAllowlist }
        ) {
            return rejected(PolicyCuratorValidationFailure.EVIDENCE_OUTSIDE_ALLOWLIST)
        }
        return when (candidate.operation) {
            PolicyDeltaOperation.NO_OP -> if (candidate == EMPTY_NO_OP) {
                PolicyCuratorValidationResult.Valid(candidate)
            } else {
                rejected(PolicyCuratorValidationFailure.INVALID_NO_OP)
            }

            PolicyDeltaOperation.QUEUE_NEW_DRAFT -> when {
                listOf(
                    candidate.candidateId,
                    candidate.inputSetHash,
                    candidate.producerIdentity,
                    candidate.modelIdentity,
                    candidate.promptVersion,
                ).any { it == null } || candidate.schemaVersion == null ->
                    rejected(PolicyCuratorValidationFailure.NEW_DRAFT_IDENTITY_MISSING)
                candidate.schemaVersion <= 0 -> rejected(PolicyCuratorValidationFailure.INVALID_VERSION)
                candidate.targetPolicyId != null || candidate.expectedRevision != null ||
                    candidate.baseArtifactHash != null ->
                    rejected(PolicyCuratorValidationFailure.NEW_DRAFT_TARGET_FORBIDDEN)
                else -> PolicyCuratorValidationResult.Valid(candidate)
            }

            PolicyDeltaOperation.QUEUE_HARM_REVIEW -> when {
                candidate.targetPolicyId == null || candidate.expectedRevision == null ->
                    rejected(PolicyCuratorValidationFailure.HARM_REVIEW_TARGET_MISSING)
                candidate.expectedRevision <= 0L || candidate.baseArtifactHash == null ||
                    candidate.reasonCode == null ->
                    rejected(PolicyCuratorValidationFailure.HARM_REVIEW_BASE_MISSING)
                else -> PolicyCuratorValidationResult.Valid(candidate)
            }
        }
    }

    private val EMPTY_NO_OP = PolicyDeltaCandidate(
        operation = PolicyDeltaOperation.NO_OP,
        candidateId = null,
        inputSetHash = null,
        producerIdentity = null,
        modelIdentity = null,
        promptVersion = null,
        schemaVersion = null,
        targetPolicyId = null,
        expectedRevision = null,
        baseArtifactHash = null,
        evidenceIds = emptyList(),
        reasonCode = null,
    )

    private fun rejected(failure: PolicyCuratorValidationFailure) =
        PolicyCuratorValidationResult.Rejected(failure)
}

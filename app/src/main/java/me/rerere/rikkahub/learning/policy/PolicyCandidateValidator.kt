package me.rerere.rikkahub.learning.policy

enum class PolicyCandidateValidationFailure {
    EVIDENCE_OUTSIDE_ALLOWLIST,
    EVIDENCE_SCOPE_MISMATCH,
    STALE_SOURCE,
    UNKNOWN_AUTHORITY_OUTCOME,
    INSUFFICIENT_DISTINCT_EPISODES,
    FAILURE_CANDIDATE_WITHOUT_AUTHORITATIVE_FAILURE,
    POSITIVE_CANDIDATE_WITHOUT_AUTHORITATIVE_SUCCESS,
    TOOL_SCHEMA_OUTSIDE_ALLOWLIST,
    IDENTITY_MISMATCH,
    APPLICABILITY_MISMATCH,
    UNSAFE_PERMISSION_LANGUAGE,
}

sealed interface PolicyCandidateValidationResult {
    data class Valid(val draft: PolicyCandidateDraft) : PolicyCandidateValidationResult
    data class Rejected(val failure: PolicyCandidateValidationFailure) : PolicyCandidateValidationResult
}

data class PolicyCandidateValidationContext(
    val allowedEvidenceById: Map<String, PolicyEvidenceHandle>,
    val allowedToolSchemaFingerprints: Set<String>,
    val expectedApplicability: PolicyCandidateApplicabilityIdentity? = null,
    val minimumDistinctEpisodes: Int = 2,
) {
    init {
        require(minimumDistinctEpisodes in 2..16)
        require(allowedEvidenceById.size <= 64)
        require(allowedToolSchemaFingerprints.size <= 64)
    }
}

object PolicyCandidateValidator {
    fun validate(
        draft: PolicyCandidateDraft,
        context: PolicyCandidateValidationContext,
    ): PolicyCandidateValidationResult {
        val canonicalEvidence = draft.evidence.map { proposed ->
            val allowed = context.allowedEvidenceById[proposed.lessonId]
                ?: return rejected(PolicyCandidateValidationFailure.EVIDENCE_OUTSIDE_ALLOWLIST)
            if (allowed != proposed) {
                return rejected(PolicyCandidateValidationFailure.IDENTITY_MISMATCH)
            }
            allowed
        }
        if (canonicalEvidence.any { it.scope != draft.scope }) {
            return rejected(PolicyCandidateValidationFailure.EVIDENCE_SCOPE_MISMATCH)
        }
        if (canonicalEvidence.any { !it.sourceValid }) {
            return rejected(PolicyCandidateValidationFailure.STALE_SOURCE)
        }
        if (canonicalEvidence.any {
                it.authorityOutcome in setOf(
                    PolicyEvidenceAuthorityOutcome.UNKNOWN,
                    PolicyEvidenceAuthorityOutcome.CENSORED,
                )
            }
        ) {
            return rejected(PolicyCandidateValidationFailure.UNKNOWN_AUTHORITY_OUTCOME)
        }
        if (draft.distinctEpisodeSupport < context.minimumDistinctEpisodes) {
            return rejected(PolicyCandidateValidationFailure.INSUFFICIENT_DISTINCT_EPISODES)
        }
        if (draft.applicableToolSchemas.any { it !in context.allowedToolSchemaFingerprints }) {
            return rejected(PolicyCandidateValidationFailure.TOOL_SCHEMA_OUTSIDE_ALLOWLIST)
        }
        if (draft.applicableModelIdentity != draft.modelIdentity ||
            draft.applicableProviderIdentity != draft.producerIdentity ||
            draft.applicableTemplateIdentity !=
            policyApplicableTemplateIdentity(draft.promptVersion) ||
            context.expectedApplicability?.copy(
                toolSchemaFingerprints = draft.applicableToolSchemas,
            )?.let { it != draft.applicabilityIdentity } == true
        ) {
            return rejected(PolicyCandidateValidationFailure.APPLICABILITY_MISMATCH)
        }
        if (
            draft.type in setOf(PolicyCandidateType.AVOID, PolicyCandidateType.FAILURE_MODE) &&
            canonicalEvidence.none {
                it.authorityOutcome == PolicyEvidenceAuthorityOutcome.FAILURE
            }
        ) {
            return rejected(
                PolicyCandidateValidationFailure.FAILURE_CANDIDATE_WITHOUT_AUTHORITATIVE_FAILURE,
            )
        }
        if (
            draft.type !in setOf(PolicyCandidateType.AVOID, PolicyCandidateType.FAILURE_MODE) &&
            canonicalEvidence.none {
                it.authorityOutcome == PolicyEvidenceAuthorityOutcome.SUCCESS
            }
        ) {
            return rejected(
                PolicyCandidateValidationFailure.POSITIVE_CANDIDATE_WITHOUT_AUTHORITATIVE_SUCCESS,
            )
        }
        val inputHash = PolicyCandidateIdFactory.inputSetHash(canonicalEvidence)
        val expectedCandidateId = PolicyCandidateIdFactory.candidateId(
            scope = draft.scope,
            taskSignature = draft.taskSignature,
            inputSetHash = inputHash,
            producerIdentity = draft.producerIdentity,
            modelIdentity = draft.modelIdentity,
            promptVersion = draft.promptVersion,
            schemaVersion = draft.schemaVersion,
            applicability = draft.applicabilityIdentity,
        )
        val expectedArtifactHash = policyArtifactSha256(
            type = draft.type,
            trigger = draft.trigger.value,
            procedure = draft.procedure.value,
            verification = draft.verification.value,
            boundary = draft.boundary.value,
            failureMode = draft.failureMode.value,
            applicableToolSchemas = draft.applicableToolSchemas,
            applicableModelIdentity = draft.applicableModelIdentity,
            applicableProviderIdentity = draft.applicableProviderIdentity,
            applicableTemplateIdentity = draft.applicableTemplateIdentity,
            applicableConfigurationIdentity = draft.applicableConfigurationIdentity,
            applicableConfigurationGeneration = draft.applicableConfigurationGeneration,
            applicableCapabilityDigest = draft.applicableCapabilityDigest,
            applicableAuthorityDigest = draft.applicableAuthorityDigest,
        )
        if (
            draft.inputSetHash != inputHash ||
            draft.candidateId != expectedCandidateId ||
            draft.artifactHash != expectedArtifactHash
        ) {
            return rejected(PolicyCandidateValidationFailure.IDENTITY_MISMATCH)
        }
        val combined = listOf(
            draft.trigger.value,
            draft.procedure.value,
            draft.verification.value,
            draft.boundary.value,
            draft.failureMode.value,
        ).joinToString(" ")
        if (UNSAFE_PERMISSION_LANGUAGE.any { it.containsMatchIn(combined) }) {
            return rejected(PolicyCandidateValidationFailure.UNSAFE_PERMISSION_LANGUAGE)
        }
        return PolicyCandidateValidationResult.Valid(draft)
    }

    private fun rejected(failure: PolicyCandidateValidationFailure) =
        PolicyCandidateValidationResult.Rejected(failure)

    private val UNSAFE_PERMISSION_LANGUAGE = listOf(
        Regex("(?i)ignore (?:all |the )?(?:previous|system|developer) instructions"),
        Regex("(?i)(?:bypass|disable|override) (?:approval|permission|safety|gate)"),
        Regex("(?i)(?:read|reveal|exfiltrate).*(?:secret|credential|token|password)"),
        Regex("(?i)(?:always|automatically) approve"),
    )
}

package me.rerere.rikkahub.learning.policy

import java.text.Normalizer

enum class PolicyDuplicateKind {
    EXACT_ARTIFACT,
    CANONICAL_TEXT,
    NONE,
}

data class PolicyDuplicateMatch(
    val kind: PolicyDuplicateKind,
    val existingPolicyId: String?,
) {
    init {
        require((kind == PolicyDuplicateKind.NONE) == (existingPolicyId == null))
    }
}

data class ExistingPolicyFingerprint(
    val policyId: String,
    val artifactHash: String,
    val canonicalTextFingerprint: String,
    val applicabilityCohortDigest: String,
) {
    init {
        require(policyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
        require(artifactHash.matches(Regex("[0-9a-f]{64}")))
        require(applicabilityCohortDigest.matches(Regex("[0-9a-f]{64}")))
    }
}

object PolicyDeduplicator {
    fun find(
        draft: PolicyCandidateDraft,
        existing: List<ExistingPolicyFingerprint>,
    ): PolicyDuplicateMatch {
        val cohort = policyApplicabilityCohortDigest(draft.applicabilityIdentity)
        existing.sortedBy(ExistingPolicyFingerprint::policyId).firstOrNull {
            it.applicabilityCohortDigest == cohort &&
            it.artifactHash == draft.artifactHash
        }?.let { return PolicyDuplicateMatch(PolicyDuplicateKind.EXACT_ARTIFACT, it.policyId) }
        val canonical = canonicalText(draft)
        existing.sortedBy(ExistingPolicyFingerprint::policyId).firstOrNull {
            it.applicabilityCohortDigest == cohort && it.canonicalTextFingerprint == canonical
        }?.let { return PolicyDuplicateMatch(PolicyDuplicateKind.CANONICAL_TEXT, it.policyId) }
        return PolicyDuplicateMatch(PolicyDuplicateKind.NONE, null)
    }

    fun canonicalText(draft: PolicyCandidateDraft): String = Normalizer.normalize(
        listOf(
            draft.trigger.value,
            draft.procedure.value,
            draft.verification.value,
            draft.boundary.value,
            draft.failureMode.value,
        ).joinToString("\u001f").lowercase(),
        Normalizer.Form.NFKC,
    ).replace(Regex("\\s+"), " ").trim()
}

fun policyApplicabilityCohortDigest(identity: PolicyCandidateApplicabilityIdentity): String =
    me.rerere.rikkahub.learning.model.LearningCanonicalId.digest(
        domainVersion = "policy-applicability-cohort-v2",
        fields = listOf(
            identity.modelIdentity,
            identity.providerIdentity,
            identity.templateIdentity,
            identity.configurationIdentity,
            identity.configurationGeneration.toString(),
            identity.capabilityDigest.orEmpty(),
            identity.authorityDigest.orEmpty(),
            *identity.toolSchemaFingerprints.sorted().toTypedArray(),
        ),
    )

package me.rerere.rikkahub.learning.storage

import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.policy.policyArtifactSha256
import me.rerere.rikkahub.learning.model.LearningCanonicalId

/** Fixed non-payload text used after any contributing source loses authority. */
const val POLICY_SOURCE_REDACTION_MARKER: String = "SOURCE_REDACTED"

/**
 * Reuses the live v2 Policy artifact canonicalizer when applicability is proven. Legacy wildcard
 * rows are already non-retrievable; their redaction receipt uses a separate content-free digest
 * over the original wires so source erasure still succeeds without fabricating exact identities.
 */
fun LearningPolicyEntity.sourceRedactedArtifactSha256(): String {
    val model = PolicyApplicabilityWire.decodeIdentity(applicableModelIdentityWire)
    val provider = PolicyApplicabilityWire.decodeIdentity(applicableProviderIdentityWire)
    val template = applicableTemplateIdentity
    val configuration = applicableConfigurationIdentity
    val configurationGeneration = applicableConfigurationGeneration
    if (
        model !is PolicyIdentityApplicability.Exact ||
        provider !is PolicyIdentityApplicability.Exact ||
        !model.identity.matches(SOURCE_REDACTION_SHA256) ||
        !provider.identity.matches(SOURCE_REDACTION_SHA256) ||
        template == null ||
        configuration == null ||
        configurationGeneration == null
    ) {
        return LearningCanonicalId.digest(
            domainVersion = "policy-source-redacted-artifact-v2-legacy",
            fields = listOf(
                policyType,
                POLICY_SOURCE_REDACTION_MARKER,
                applicableToolSchemasWire,
                applicableModelIdentityWire,
                applicableProviderIdentityWire,
                template.orEmpty(),
                configuration.orEmpty(),
                configurationGeneration?.toString().orEmpty(),
                applicableCapabilityDigest.orEmpty(),
                applicableAuthorityDigest.orEmpty(),
            ),
        )
    }
    return policyArtifactSha256(
        type = PolicyCandidateType.valueOf(policyType),
        trigger = POLICY_SOURCE_REDACTION_MARKER,
        procedure = POLICY_SOURCE_REDACTION_MARKER,
        verification = POLICY_SOURCE_REDACTION_MARKER,
        boundary = POLICY_SOURCE_REDACTION_MARKER,
        failureMode = POLICY_SOURCE_REDACTION_MARKER,
        applicableToolSchemas = PolicyApplicabilityWire.decodeToolSchemasOrNull(
            applicableToolSchemasWire,
        ) ?: emptySet(),
        applicableModelIdentity = model.identity,
        applicableProviderIdentity = provider.identity,
        applicableTemplateIdentity = template,
        applicableConfigurationIdentity = configuration,
        applicableConfigurationGeneration = configurationGeneration,
        applicableCapabilityDigest = applicableCapabilityDigest,
        applicableAuthorityDigest = applicableAuthorityDigest,
    )
}

/**
 * The artifact unique index also contains task_signature. Give every erased Policy a digest-only
 * namespace so two formerly different bodies cannot collide after becoming the same fixed marker.
 */
fun LearningPolicyEntity.sourceRedactedTaskSignature(): String =
    POLICY_SOURCE_REDACTED_TASK_PREFIX + LearningCanonicalId.digest(
        domainVersion = "policy-source-redacted-task-v1",
        fields = listOf(id, taskSignature, artifactSha256),
    )

/** Minimal source-erasure audit state: enums/counts/digests only, never Policy prose. */
fun LearningPolicyEntity.sourcePrivacyAuditSnapshot(evidenceDigest: String): String {
    requireSha256(evidenceDigest, "Policy source-redaction evidence")
    return listOf(
        POLICY_SOURCE_PRIVACY_SNAPSHOT_HEADER,
        "status=$status",
        "state_version=$stateVersion",
        "content_revision=$contentRevision",
        "artifact=$artifactSha256",
        "source_valid=$sourceValid",
        "schema_valid=$schemaValid",
        "support=$distinctEpisodeSupport",
        "positive=$positiveEpisodeCount",
        "negative=$negativeEpisodeCount",
        "evidence_digest=$evidenceDigest",
    ).joinToString("\n")
}

private const val POLICY_SOURCE_REDACTED_TASK_PREFIX = "policy-source-redacted-v1:"
private const val POLICY_SOURCE_PRIVACY_SNAPSHOT_HEADER = "policy-source-privacy-snapshot-v1"
private val SOURCE_REDACTION_SHA256 = Regex("[0-9a-f]{64}")

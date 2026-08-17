package me.rerere.rikkahub.learning.policy

import me.rerere.rikkahub.learning.model.LearningCanonicalId

/**
 * Single canonical identity for every Policy artifact writer and verifier.
 *
 * Field order is part of the persisted v2 contract. Source privacy redaction uses this exact
 * function with the fixed redaction marker in all five text slots; it does not invent a parallel
 * digest format.
 */
fun policyArtifactSha256(
    type: PolicyCandidateType,
    trigger: String,
    procedure: String,
    verification: String,
    boundary: String,
    failureMode: String,
    applicableToolSchemas: Set<String>,
    applicableModelIdentity: String,
    applicableProviderIdentity: String,
    applicableTemplateIdentity: String,
    applicableConfigurationIdentity: String,
    applicableConfigurationGeneration: Long,
    applicableCapabilityDigest: String?,
    applicableAuthorityDigest: String?,
): String {
    require(applicableToolSchemas.size <= MAX_POLICY_ARTIFACT_TOOL_SCHEMAS)
    require(applicableToolSchemas.all(LOWER_POLICY_ARTIFACT_SHA256::matches))
    listOf(
        applicableModelIdentity,
        applicableProviderIdentity,
        applicableTemplateIdentity,
        applicableConfigurationIdentity,
    ).forEach { require(it.matches(LOWER_POLICY_ARTIFACT_SHA256)) }
    require(applicableConfigurationGeneration > 0L)
    applicableCapabilityDigest?.let { require(it.matches(LOWER_POLICY_ARTIFACT_SHA256)) }
    applicableAuthorityDigest?.let { require(it.matches(LOWER_POLICY_ARTIFACT_SHA256)) }
    return LearningCanonicalId.digest(
        domainVersion = POLICY_ARTIFACT_DOMAIN_VERSION,
        fields = listOf(
            type.name,
            trigger,
            procedure,
            verification,
            boundary,
            failureMode,
            applicableModelIdentity,
            applicableProviderIdentity,
            applicableTemplateIdentity,
            applicableConfigurationIdentity,
            applicableConfigurationGeneration.toString(),
            applicableCapabilityDigest.orEmpty(),
            applicableAuthorityDigest.orEmpty(),
            *applicableToolSchemas.sorted().toTypedArray(),
        ),
    )
}

fun policyArtifactSha256(entity: me.rerere.rikkahub.learning.storage.LearningPolicyEntity): String {
    val model = me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
        .decodeIdentity(entity.applicableModelIdentityWire) as?
        me.rerere.rikkahub.learning.storage.PolicyIdentityApplicability.Exact
        ?: throw IllegalArgumentException("Wildcard Policy applicability has no live artifact")
    val provider = me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
        .decodeIdentity(entity.applicableProviderIdentityWire) as?
        me.rerere.rikkahub.learning.storage.PolicyIdentityApplicability.Exact
        ?: throw IllegalArgumentException("Wildcard Policy applicability has no live artifact")
    return policyArtifactSha256(
        type = PolicyCandidateType.valueOf(entity.policyType),
        trigger = entity.triggerSummary,
        procedure = entity.procedureSummary,
        verification = entity.verificationSummary,
        boundary = entity.boundarySummary,
        failureMode = entity.failureModeSummary,
        applicableToolSchemas = requireNotNull(
            me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
                .decodeToolSchemasOrNull(entity.applicableToolSchemasWire),
        ),
        applicableModelIdentity = model.identity,
        applicableProviderIdentity = provider.identity,
        applicableTemplateIdentity = requireNotNull(entity.applicableTemplateIdentity),
        applicableConfigurationIdentity = requireNotNull(entity.applicableConfigurationIdentity),
        applicableConfigurationGeneration =
            requireNotNull(entity.applicableConfigurationGeneration),
        applicableCapabilityDigest = entity.applicableCapabilityDigest,
        applicableAuthorityDigest = entity.applicableAuthorityDigest,
    )
}

private const val POLICY_ARTIFACT_DOMAIN_VERSION = "policy-artifact-v2"
private const val MAX_POLICY_ARTIFACT_TOOL_SCHEMAS = 16
private val LOWER_POLICY_ARTIFACT_SHA256 = Regex("[0-9a-f]{64}")

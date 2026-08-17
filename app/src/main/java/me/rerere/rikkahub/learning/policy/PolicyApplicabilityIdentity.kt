package me.rerere.rikkahub.learning.policy

import me.rerere.rikkahub.learning.model.LearningCanonicalId

/** Public, non-secret configuration cohort used by final Policy dispatch applicability. */
fun policyApplicableConfigurationIdentity(
    providerIdentity: String,
    modelIdentity: String,
): String {
    require(providerIdentity.matches(APPLICABILITY_SHA256))
    require(modelIdentity.matches(APPLICABILITY_SHA256))
    return LearningCanonicalId.digest(
        domainVersion = "policy-public-provider-configuration-v1",
        fields = listOf(providerIdentity, modelIdentity),
    )
}

/** Public digest of the Policy distillation/template ABI under which applicability was proven. */
fun policyApplicableTemplateIdentity(templateIdentity: String): String {
    require(templateIdentity.matches(APPLICABILITY_SAFE_IDENTITY))
    return LearningCanonicalId.digest(
        domainVersion = "policy-applicable-template-v1",
        fields = listOf(templateIdentity),
    )
}

/** Stable positive generation for the exact public configuration identity. */
fun policyApplicableConfigurationGeneration(configurationIdentity: String): Long {
    require(configurationIdentity.matches(APPLICABILITY_SHA256))
    val raw = configurationIdentity.take(16).toULong(16).toLong() and Long.MAX_VALUE
    return raw.coerceAtLeast(1L)
}

/**
 * A Policy that requires no tool schema has an exact, empty capability surface. Policies that
 * name tools remain UNKNOWN until a complete provider-visible ToolCatalog snapshot can be frozen;
 * UNKNOWN is never dispatch-eligible.
 */
fun policyApplicableCapabilityDigest(toolSchemaFingerprints: Set<String>): String? {
    require(toolSchemaFingerprints.size <= 16)
    require(toolSchemaFingerprints.all(APPLICABILITY_SHA256::matches))
    if (toolSchemaFingerprints.isNotEmpty()) return null
    return LearningCanonicalId.digest(
        domainVersion = "policy-tool-capability-snapshot-v1",
        fields = emptyList(),
    )
}

private val APPLICABILITY_SHA256 = Regex("[0-9a-f]{64}")
private val APPLICABILITY_SAFE_IDENTITY = Regex("[a-z0-9][a-z0-9._-]{0,95}")

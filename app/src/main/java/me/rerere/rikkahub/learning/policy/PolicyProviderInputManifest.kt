package me.rerere.rikkahub.learning.policy

import java.nio.charset.StandardCharsets
import me.rerere.rikkahub.learning.model.LearningCanonicalId

internal const val POLICY_PROVIDER_INPUT_IDENTITY_PREFIX = "policy-input-v2:"
private const val MAX_POLICY_PROVIDER_PAYLOAD_UTF8_BYTES = 96 * 1_024

/**
 * Content-free durable identity for the exact ordered bytes offered to Policy Distillation.
 *
 * The job persists only this digest. Execution must rebuild the bounded payload from exact
 * source/lesson/reward revisions and compare the identity before a provider dispatch.
 */
internal object PolicyProviderInputManifest {
    fun identity(
        input: PolicyDistillationInput,
        payloadJson: String,
    ): String {
        require(payloadJson.toByteArray(StandardCharsets.UTF_8).size in
            1..MAX_POLICY_PROVIDER_PAYLOAD_UTF8_BYTES)
        require(input.evidenceAllowlist.keys.toList() ==
            (0 until input.evidenceAllowlist.size).map { "E${it + 1}" })
        return LearningCanonicalId.digest(
            domainVersion = "policy-provider-input-v2",
            fields = listOf(
                PolicyCandidateIdFactory.inputSetHash(
                    input.evidenceAllowlist.values.toList(),
                ),
                input.producerIdentity,
                input.modelIdentity,
                input.applicableTemplateIdentity,
                input.applicableConfigurationIdentity,
                input.applicableConfigurationGeneration.toString(),
                input.applicableCapabilityDigest.orEmpty(),
                input.applicableAuthorityDigest.orEmpty(),
                *input.toolSchemaAllowlist.sorted().toTypedArray(),
                payloadJson,
            ),
        )
    }
}

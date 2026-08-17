package me.rerere.rikkahub.learning.policy

import me.rerere.rikkahub.learning.model.LearningCanonicalId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PolicyArtifactIdentityTest {
    @Test
    fun sharedCanonicalizerPreservesPersistedV2FieldOrder() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val actual = policyArtifactSha256(
            type = PolicyCandidateType.PROCEDURE,
            trigger = "trigger",
            procedure = "procedure",
            verification = "verification",
            boundary = "boundary",
            failureMode = "failure",
            applicableToolSchemas = linkedSetOf(b, a),
            applicableModelIdentity = "d".repeat(64),
            applicableProviderIdentity = "e".repeat(64),
            applicableTemplateIdentity = "8".repeat(64),
            applicableConfigurationIdentity = "f".repeat(64),
            applicableConfigurationGeneration = 7L,
            applicableCapabilityDigest = null,
            applicableAuthorityDigest = "9".repeat(64),
        )

        assertEquals(
            LearningCanonicalId.digest(
                domainVersion = "policy-artifact-v2",
                fields = listOf(
                    "PROCEDURE", "trigger", "procedure", "verification", "boundary", "failure",
                    "d".repeat(64), "e".repeat(64), "8".repeat(64),
                    "f".repeat(64), "7", "", "9".repeat(64),
                    a, b,
                ),
            ),
            actual,
        )
    }

    @Test
    fun sourceRedactionChangesTheCanonicalArtifactWithoutChangingItsDomain() {
        val schemas = setOf("c".repeat(64))
        val original = policyArtifactSha256(
            PolicyCandidateType.AVOID,
            "old trigger",
            "old procedure",
            "old verification",
            "old boundary",
            "old failure",
            schemas, "d".repeat(64), "e".repeat(64), "8".repeat(64),
            "f".repeat(64), 1L, null, null,
        )
        val redacted = policyArtifactSha256(
            PolicyCandidateType.AVOID,
            "SOURCE_REDACTED",
            "SOURCE_REDACTED",
            "SOURCE_REDACTED",
            "SOURCE_REDACTED",
            "SOURCE_REDACTED",
            schemas, "d".repeat(64), "e".repeat(64), "8".repeat(64),
            "f".repeat(64), 1L, null, null,
        )

        assertNotEquals(original, redacted)
    }
}

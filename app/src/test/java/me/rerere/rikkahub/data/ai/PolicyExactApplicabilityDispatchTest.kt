package me.rerere.rikkahub.data.ai

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.policyApplicableCapabilityDigest
import me.rerere.rikkahub.learning.retrieval.LearnedPolicyCandidatePacket
import me.rerere.rikkahub.learning.retrieval.LearnedPolicyContextItem
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyExactApplicabilityDispatchTest {
    @Test
    fun modelProviderTemplateConfigurationGenerationAndCapabilityDriftProduceZeroCandidates() {
        val model = "a".repeat(64)
        val provider = "b".repeat(64)
        val template = "c".repeat(64)
        val configuration = "d".repeat(64)
        val tool = "e".repeat(64)
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000001"),
        )
        val packet = LearnedPolicyCandidatePacket(
            scope = scope,
            taskSignature = TaskSignatureV1.create(
                LearningTaskClass.INFORMATION,
                LearningLanguageClass.CHINESE,
                LearningModalityClass.TEXT_ONLY,
                emptySet(),
            ),
            candidates = listOf(
                LearnedPolicyContextItem(
                    policyId = "policy-candidate-v2:${"f".repeat(64)}",
                    policyRevision = 1L,
                    scope = scope,
                    artifactSha256 = "9".repeat(64),
                    renderedFragment = "bounded advice",
                    estimatedTokens = 4,
                    priority = 1,
                    rank = 1,
                    policyCompilerRevision = "active-policy-context-v1",
                    applicableToolSchemaFingerprints = emptySet(),
                    applicableModelIdentity = "EXACT_V1:$model",
                    applicableProviderIdentity = "EXACT_V1:$provider",
                    applicableTemplateIdentity = template,
                    applicableConfigurationIdentity = configuration,
                    applicableConfigurationGeneration = 7L,
                    applicableCapabilityDigest = policyApplicableCapabilityDigest(emptySet()),
                    applicableAuthorityDigest = null,
                ),
            ),
            retrievalRevision = "active-policy-exact-v1",
            truncated = false,
        )
        fun retained(
            m: String = model,
            p: String = provider,
            t: String = template,
            c: String = configuration,
            generation: Long = 7L,
            tools: Set<String> = setOf(tool),
            capability: String? = policyApplicableCapabilityDigest(emptySet()),
        ) = packet.filterFinalApplicability(
            p, m, t, c, generation, tools, capability,
        ).candidates.size

        assertEquals(1, retained())
        assertEquals(0, retained(m = "1".repeat(64)))
        assertEquals(0, retained(p = "2".repeat(64)))
        assertEquals(0, retained(t = "3".repeat(64)))
        assertEquals(0, retained(c = "4".repeat(64)))
        assertEquals(0, retained(generation = 8L))
        assertEquals(1, retained(tools = emptySet()))
        assertEquals(0, retained(capability = null))
    }

    @Test
    fun `tool capability baseline unknown is never dispatch eligible`() {
        assertEquals(null, policyApplicableCapabilityDigest(setOf("e".repeat(64))))
    }
}

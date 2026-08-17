package me.rerere.rikkahub.learning.retrieval

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedPolicySourceContractTest {
    @Test
    fun `packet cannot cross scope or contain two revisions of one Policy`() {
        val otherScope = LearningScope.AuthoritySubject("other-subject")
        assertThrows(IllegalArgumentException::class.java) {
            packet(listOf(item(scope = otherScope)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            packet(
                listOf(
                    item(revision = 1),
                    item(revision = 2, rank = 2),
                ),
            )
        }
    }

    @Test
    fun `candidate contract is bounded and diagnostic strings redact content and identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            item(text = "x".repeat(MAX_LEARNED_POLICY_CONTEXT_ITEM_CHARS + 1))
        }
        val candidate = item(text = "private-fragment")
        val packet = packet(listOf(candidate))

        assertFalse(candidate.toString().contains("private-fragment"))
        assertFalse(candidate.toString().contains(candidate.policyId))
        assertFalse(packet.toString().contains(candidate.policyId))
        assertTrue(candidate.toString().contains("text=<redacted>"))
    }

    @Test
    fun `query accepts only a bounded candidate and token request`() {
        assertThrows(IllegalArgumentException::class.java) {
            LearnedPolicyQuery(
                scope = SCOPE,
                consumingAssistantId = CONSUMER,
                taskSignature = SIGNATURE,
                query = "query",
                maxCandidates = MAX_LEARNED_POLICY_CONTEXT_CANDIDATES + 1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LearnedPolicyQuery(
                scope = SCOPE,
                consumingAssistantId = CONSUMER,
                taskSignature = SIGNATURE,
                query = "query",
                maxEstimatedTokens = MAX_LEARNED_POLICY_CONTEXT_PACKET_ESTIMATED_TOKENS + 1,
            )
        }
    }

    private fun packet(items: List<LearnedPolicyContextItem>) = LearnedPolicyCandidatePacket(
        scope = SCOPE,
        taskSignature = SIGNATURE,
        candidates = items,
        retrievalRevision = "policy-retrieval-v1",
        truncated = false,
    )

    private fun item(
        scope: LearningScope = SCOPE,
        revision: Long = 1,
        rank: Int = 1,
        text: String = "bounded advice",
    ) = LearnedPolicyContextItem(
        policyId = "policy-one",
        policyRevision = revision,
        scope = scope,
        artifactSha256 = "a".repeat(64),
        renderedFragment = text,
        estimatedTokens = 4,
        priority = 10,
        rank = rank,
        policyCompilerRevision = "policy-artifact-v1",
        applicableModelIdentity = "EXACT_V1:${"b".repeat(64)}",
        applicableProviderIdentity = "EXACT_V1:${"c".repeat(64)}",
        applicableTemplateIdentity = "e".repeat(64),
        applicableConfigurationIdentity = "d".repeat(64),
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = null,
        applicableAuthorityDigest = null,
    )

    private companion object {
        val SCOPE: LearningScope = LearningScope.AuthoritySubject("owner-subject")
        val CONSUMER = kotlin.uuid.Uuid.parse("a0000000-0000-0000-0000-00000000000a")
        val SIGNATURE: TaskSignatureV1 = TaskSignatureV1.create(
            taskClass = LearningTaskClass.INFORMATION,
            languageClass = LearningLanguageClass.CHINESE,
            modalityClass = LearningModalityClass.TEXT_ONLY,
            tools = emptySet(),
        )
    }
}

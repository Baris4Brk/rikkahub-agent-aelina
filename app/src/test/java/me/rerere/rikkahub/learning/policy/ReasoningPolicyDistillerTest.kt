package me.rerere.rikkahub.learning.policy

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeIdFactory
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningPolicyDistillerTest {
    @Test
    fun policyPromptPublishesTheCompleteStrictCandidateContract() {
        val prompt = PolicyDistillationPrompt.create("{}")
        val system = prompt.providerTexts().first
        prompt.close()

        assertEquals("policy-distillation-v3", PolicyDistillationPrompt.TEMPLATE_VERSION)
        listOf(
            "schema_version", "op", "type", "trigger", "procedure", "verification",
            "boundary", "failure_mode", "evidence_ids", "tool_schema_fingerprints",
        ).forEach { field -> assertTrue(system.contains(field)) }
    }

    @Test
    fun modelAliasesResolveToCanonicalLessonEvidenceBeforeValidation() {
        val stream = Uuid.parse("00000000-0000-4000-8000-000000000001")
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-4000-8000-000000000002"),
        )
        val evidence = (1..2).associate { index ->
            "E$index" to PolicyEvidenceHandle(
                lessonId = "episode-lesson-v1:lesson-$index",
                episodeId = EpisodeIdFactory.create(
                    stream,
                    Uuid.parse("00000000-0000-4000-8000-00000000000${index + 2}"),
                    Uuid.parse("00000000-0000-4000-8000-00000000000${index + 4}"),
                ),
                scope = scope,
                lessonRevision = 1,
                sourceValid = true,
                authorityOutcome = PolicyEvidenceAuthorityOutcome.SUCCESS,
            )
        }
        val input = PolicyDistillationInput(
            scope = scope,
            taskSignature = TaskSignatureV1.create(
                LearningTaskClass.INFORMATION,
                LearningLanguageClass.CHINESE,
                LearningModalityClass.TEXT_ONLY,
                emptySet(),
            ),
            evidenceAllowlist = evidence,
            toolSchemaAllowlist = emptySet(),
            producerIdentity = "a".repeat(64),
            modelIdentity = "b".repeat(64),
            promptVersion = PolicyDistillationPrompt.TEMPLATE_VERSION,
            applicableTemplateIdentity = policyApplicableTemplateIdentity(
                PolicyDistillationPrompt.TEMPLATE_VERSION,
            ),
            applicableConfigurationIdentity = "c".repeat(64),
            applicableConfigurationGeneration = 1L,
        )
        val raw = """
            {
              "schema_version": 2,
              "op": "CANDIDATE",
              "type": "PROCEDURE",
              "trigger": "Use this for the matching bounded task.",
              "procedure": "Check the prerequisites, then perform the bounded steps.",
              "verification": "Verify the structured outcome before finishing.",
              "boundary": "Apply only inside the current assistant scope.",
              "failure_mode": "Abstain when the evidence is incomplete.",
              "evidence_ids": ["E1", "E2"],
              "tool_schema_fingerprints": []
            }
        """.trimIndent()

        val result = ReasoningPolicyDistiller.distill(raw, input) as
            PolicyDistillationResult.Candidate
        assertTrue(result.draft.candidateId.startsWith("policy-candidate-v2:"))
        assertTrue(
            ReasoningPolicyDistiller.distill("```json\n$raw\n```", input) is
                PolicyDistillationResult.Candidate,
        )
        assertEquals(
            PolicyDistillationFailure.INVALID_JSON,
            (ReasoningPolicyDistiller.distill("candidate:\n$raw", input) as
                PolicyDistillationResult.Rejected).failure,
        )
        val crossCohort = ExistingPolicyFingerprint(
            policyId = "policy-v1:${"f".repeat(64)}",
            artifactHash = result.draft.artifactHash,
            canonicalTextFingerprint = PolicyDeduplicator.canonicalText(result.draft),
            applicabilityCohortDigest = policyApplicabilityCohortDigest(
                result.draft.applicabilityIdentity.copy(configurationGeneration = 2L),
            ),
        )
        assertEquals(
            PolicyDuplicateKind.NONE,
            PolicyDeduplicator.find(result.draft, listOf(crossCohort)).kind,
        )
    }
}

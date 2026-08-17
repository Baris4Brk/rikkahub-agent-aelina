package me.rerere.rikkahub.learning.policy

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyProviderInputManifestTest {
    private val scope = LearningScope.Assistant(
        Uuid.parse("00000000-0000-0000-0000-000000000002"),
    )
    private val first = handle("a", PolicyEvidenceAuthorityOutcome.SUCCESS)
    private val second = handle("b", PolicyEvidenceAuthorityOutcome.FAILURE)

    @Test
    fun exactPayloadAndEvidenceSetProduceStableIdentity() {
        val input = input(first, second)
        val payload = """{"schema_version":1,"evidence":["E1","E2"]}"""

        val left = PolicyProviderInputManifest.identity(input, payload)
        val right = PolicyProviderInputManifest.identity(input.copy(), payload)

        assertEquals(left, right)
        assertTrue(left.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun providerVisiblePayloadOrRewardPolarityMutationChangesIdentity() {
        val payload = """{"schema_version":1,"reward_goal":1.0}"""
        val base = PolicyProviderInputManifest.identity(input(first, second), payload)
        val payloadChanged = PolicyProviderInputManifest.identity(
            input(first, second),
            """{"schema_version":1,"reward_goal":-1.0}""",
        )
        val evidenceChanged = PolicyProviderInputManifest.identity(
            input(first.copy(authorityOutcome = PolicyEvidenceAuthorityOutcome.FAILURE), second),
            payload,
        )

        assertNotEquals(base, payloadChanged)
        assertNotEquals(base, evidenceChanged)
    }

    private fun input(
        first: PolicyEvidenceHandle,
        second: PolicyEvidenceHandle,
    ) = PolicyDistillationInput(
        scope = scope,
        taskSignature = TaskSignatureV1.create(
            LearningTaskClass.OTHER,
            LearningLanguageClass.CHINESE,
            LearningModalityClass.TEXT_ONLY,
            emptySet(),
        ),
        evidenceAllowlist = linkedMapOf("E1" to first, "E2" to second),
        toolSchemaAllowlist = emptySet(),
        producerIdentity = "a".repeat(64),
        modelIdentity = "b".repeat(64),
        promptVersion = "distill-v1",
        applicableTemplateIdentity = policyApplicableTemplateIdentity("distill-v1"),
        applicableConfigurationIdentity = "c".repeat(64),
        applicableConfigurationGeneration = 1L,
    )

    private fun handle(
        marker: String,
        outcome: PolicyEvidenceAuthorityOutcome,
    ) = PolicyEvidenceHandle(
        lessonId = "lesson-$marker",
        episodeId = requireNotNull(
            EpisodeId.parseOrNull("episode-v1:${marker.repeat(64)}"),
        ),
        scope = scope,
        lessonRevision = 1,
        sourceValid = true,
        authorityOutcome = outcome,
    )
}

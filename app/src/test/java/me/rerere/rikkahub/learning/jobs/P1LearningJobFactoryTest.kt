package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.LearningJobType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class P1LearningJobFactoryTest {
    @Test
    fun p1TypesRequireAndPersistCompleteFrozenExecutionIdentity() {
        val job = P1LearningJobFactory.create(source(), spec(), createdAtMs = 2)
        assertEquals(LearningJobType.REFLECT_EPISODE_V1.name, job.jobType)
        assertEquals("reflection-v1", job.algorithmIdentity)
        assertEquals("b".repeat(64), job.providerIdentity)
        assertEquals(LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode, job.providerKindIdentity)
        assertEquals("c".repeat(64), job.providerConfigurationIdentity)
        assertEquals(4L, job.providerConfigGeneration)
        assertEquals("episode-lesson-v1", job.outputSchemaIdentity)
        assertThrows(IllegalArgumentException::class.java) {
            job.copy(providerIdentity = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            job.copy(jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name)
        }
    }

    @Test
    fun everyFrozenIdentityAndReplayGenerationParticipatesInDedupe() {
        val baseline = P1LearningJobFactory.create(source(), spec(), createdAtMs = 2)
        val variants = listOf(
            spec().copy(algorithmIdentity = "reflection-v2"),
            spec().copy(promptIdentity = "reflection-prompt-v2"),
            spec().copy(
                providerKindIdentity = LearningJobProviderKindIdentity.REMOTE.wireCode,
            ),
            spec().copy(modelIdentity = "b".repeat(64)),
            spec().copy(providerIdentity = "c".repeat(64)),
            spec().copy(providerConfigurationIdentity = "d".repeat(64)),
            spec().copy(providerConfigGeneration = 5),
            spec().copy(sourceSchemaIdentity = "episode-input-v2"),
            spec().copy(toolsetIdentity = "toolset-v2"),
            spec().copy(outputSchemaIdentity = "episode-lesson-v2"),
        )
        variants.forEach { changed ->
            val other = P1LearningJobFactory.create(source(), changed, createdAtMs = 2)
            assertNotEquals(baseline.dedupeKey, other.dedupeKey)
            assertNotEquals(baseline.id, other.id)
        }
        val replay = P1LearningJobFactory.create(
            source().copy(replayGeneration = 1),
            spec(),
            createdAtMs = 2,
        )
        assertNotEquals(baseline.dedupeKey, replay.dedupeKey)
    }

    @Test
    fun frozenSpecNeverContainsCredentialOrFreeFormPayloadField() {
        val fields = P1LearningJobFrozenSpec::class.java.declaredFields.map { it.name.lowercase() }
        listOf("credential", "secret", "payload", "prompttext", "toolargs", "tooloutput")
            .forEach { forbidden -> assertTrue(fields.none { forbidden in it }) }
    }

    @Test
    fun providerKindAndExactConfigurationAreFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            spec().copy(providerKindIdentity = "aicore")
        }
        assertThrows(IllegalArgumentException::class.java) {
            spec().copy(providerConfigurationIdentity = "configuration-label")
        }
        assertThrows(IllegalArgumentException::class.java) {
            spec().copy(
                providerKindIdentity = LearningJobProviderKindIdentity.NONE.wireCode,
                providerConfigurationIdentity = "c".repeat(64),
            )
        }
    }

    private fun spec() = P1LearningJobFrozenSpec(
        jobType = LearningJobType.REFLECT_EPISODE_V1,
        algorithmIdentity = "reflection-v1",
        promptIdentity = "reflection-prompt-v1",
        providerKindIdentity = LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode,
        modelIdentity = "a".repeat(64),
        providerIdentity = "b".repeat(64),
        providerConfigurationIdentity = "c".repeat(64),
        providerConfigGeneration = 4,
        sourceSchemaIdentity = "episode-input-v1",
        toolsetIdentity = "toolset-v1",
        outputSchemaIdentity = "episode-lesson-v1",
    )

    private fun source() = LearningInboxEventEntity(
        streamId = "00000000-0000-0000-0000-000000000001",
        eventId = "learning-event-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        outboxSeq = 2,
        eventTypeCode = "COMMAND_TERMINAL",
        eventSchemaVersion = 2,
        terminalState = "COMPLETED",
        decodeState = "KNOWN",
        interpretationVersion = 2,
        sourceType = "COMMAND",
        sourceId = "command-v1",
        sourceRevision = 2,
        missingRevisionReason = null,
        scopeKind = "ASSISTANT",
        scopeId = "00000000-0000-0000-0000-000000000002",
        conversationId = "conversation-v1",
        conversationSourceRevision = 2,
        commandId = "command-v1",
        lineageId = "lineage-v1",
        parentCommandId = null,
        branchAnchorMessageId = "message-v1",
        branchAnchorMessageRevision = 1,
        completionKind = "GENERATION_FINAL_SAVED",
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        messageId = "message-v2",
        messageRevision = 1,
        occurredAtMs = 1,
        createdAtMs = 1,
        ingestedAtMs = 1,
        replayGeneration = 0,
    )
}

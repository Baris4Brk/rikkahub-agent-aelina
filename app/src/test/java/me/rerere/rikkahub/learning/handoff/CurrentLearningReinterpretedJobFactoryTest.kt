package me.rerere.rikkahub.learning.handoff

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScopeKind
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CurrentLearningReinterpretedJobFactoryTest {
    @Test
    fun identityBindsScopeSourceSchemaInterpretationAndReplayGeneration() {
        val baseline = create(event())
        val otherScope = create(event().copy(scopeId = ASSISTANT_B))
        val otherSourceSchema = create(
            event().copy(eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 2)),
        )
        val otherInterpretation = create(event(), interpretationVersion = 2)
        val otherReplay = create(event().copy(replayGeneration = 8))

        listOf(otherScope, otherSourceSchema, otherInterpretation, otherReplay).forEach { changed ->
            assertNotEquals(baseline.id, changed.id)
            assertNotEquals(baseline.dedupeKey, changed.dedupeKey)
        }
        assertEquals(LearningScopeKind.ASSISTANT.name, baseline.scopeKind)
        assertEquals(ASSISTANT_A, baseline.scopeId)
    }

    @Test
    fun nonJobEventNeverCreatesWork() {
        val feedback = event().copy(
            eventCode = LearningEventCode(LearningEventType.USER_FEEDBACK_RECORDED.name, 1),
        )

        val job = CurrentLearningReinterpretedJobFactory.createEligibleJob(
            event = feedback,
            targetInterpretationVersion = 1,
            reinterpretedAtMs = 20,
        )

        assertEquals(null, job)
    }

    private fun create(
        event: LearningInboxAuthoritativeEvent,
        interpretationVersion: Int = 1,
    ): LearningJobEntity {
        val result = CurrentLearningReinterpretedJobFactory.createEligibleJob(
            event = event,
            targetInterpretationVersion = interpretationVersion,
            reinterpretedAtMs = 20,
        )
        assertNotNull(result)
        return requireNotNull(result)
    }

    private fun event() = LearningInboxAuthoritativeEvent(
        streamId = Uuid.parse(STREAM),
        eventId = "learning-event-v1:${"a".repeat(64)}",
        outboxSeq = 3,
        eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
        terminalStateCode = "COMPLETED",
        sourceTypeCode = LearningSourceKind.COMMAND.name,
        sourceId = "command-source",
        sourceRevision = 1,
        missingRevisionReasonCode = null,
        scopeKindCode = LearningScopeKind.ASSISTANT.name,
        scopeId = ASSISTANT_A,
        correlation = LearningCorrelation(
            conversationId = "conversation",
            commandId = "command-source",
            lineageId = "lineage",
            branchAnchorMessageId = "anchor",
        ),
        occurredAtMs = 10,
        createdAtMs = 11,
        replayGeneration = 7,
    )

    private companion object {
        const val STREAM = "00000000-0000-0000-0000-000000000001"
        const val ASSISTANT_A = "00000000-0000-0000-0000-000000000010"
        const val ASSISTANT_B = "00000000-0000-0000-0000-000000000011"
    }
}

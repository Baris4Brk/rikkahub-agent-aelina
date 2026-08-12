package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.data.db.projection.LearningCommandTerminalAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningExecutionTerminalAuthorityProjection
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class LearningTerminalAuthorityProjectorTest {
    @Test
    fun commandProjection_matchesDirectTerminalWriterCanonicalIdentity() {
        val streamId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val row = commandProjection()

        val projected = projectCommandTerminalDraft(row, streamId)

        assertEquals(
            LearningOutboxDraft(
                streamId = streamId,
                eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
                source = LearningSourceRef(
                    sourceKind = LearningSourceKind.COMMAND,
                    sourceId = COMMAND_ID,
                    sourceRevision = 4L,
                    missingRevisionReason = null,
                    databaseStreamId = streamId,
                    scope = LearningScope.Assistant(Uuid.parse(ASSISTANT_ID)),
                    occurredAtMs = 900L,
                ),
                correlation = LearningCorrelation(
                    conversationId = CONVERSATION_ID,
                    commandId = COMMAND_ID,
                    lineageId = LINEAGE_ID,
                    parentCommandId = PARENT_COMMAND_ID,
                    branchAnchorMessageId = BRANCH_ANCHOR_ID,
                ),
                terminalStateCode = "COMPLETED",
                createdAtMs = 900L,
            ),
            projected,
        )
    }

    @Test
    fun commandProjection_prefersFrozenAuthoritySubjectAndSkipsLegacyOrMalformedRows() {
        val streamId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val protected = requireNotNull(
            projectCommandTerminalDraft(
                commandProjection().copy(authoritySubjectId = "second-user:epoch-7"),
                streamId,
            ),
        )
        assertEquals(
            LearningScope.AuthoritySubject("second-user:epoch-7"),
            protected.source?.scope,
        )

        assertNull(
            projectCommandTerminalDraft(
                commandProjection().copy(assistantIdSnapshot = null),
                streamId,
            ),
        )
        assertNull(
            projectCommandTerminalDraft(
                commandProjection().copy(lineageId = null),
                streamId,
            ),
        )
        assertNull(
            projectCommandTerminalDraft(
                commandProjection().copy(stateVersion = 0L),
                streamId,
            ),
        )
        assertNull(
            projectCommandTerminalDraft(
                commandProjection().copy(state = "RUNNING"),
                streamId,
            ),
        )
    }

    @Test
    fun authorityProjections_exposeNoContentPayloadOrRuntimeOutputFields() {
        val fieldNames = listOf(
            LearningCommandTerminalAuthorityProjection::class.java,
            LearningExecutionTerminalAuthorityProjection::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }
        listOf(
            "payload",
            "content",
            "argument",
            "output",
            "error",
            "reason",
            "resource",
            "capability",
            "runtimehandle",
        ).forEach { forbidden ->
            assertFalse(fieldNames.any { forbidden in it })
        }
    }

    @Test
    fun executionProjection_matchesDirectTerminalWriterCanonicalIdentity() {
        val streamId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val row = executionProjection()

        val projected = projectExecutionTerminalDraft(row, streamId)

        assertEquals(
            LearningOutboxDraft(
                streamId = streamId,
                eventCode = LearningEventCode(LearningEventType.EXECUTION_TERMINAL.name, 1),
                source = LearningSourceRef(
                    sourceKind = LearningSourceKind.EXECUTION_EVENT,
                    sourceId = LearningCanonicalId.executionEventSourceId(EVENT_ID),
                    sourceRevision = 8L,
                    missingRevisionReason = null,
                    databaseStreamId = streamId,
                    scope = LearningScope.Assistant(Uuid.parse(ASSISTANT_ID)),
                    occurredAtMs = 1_200L,
                ),
                correlation = LearningCorrelation(
                    conversationId = CONVERSATION_ID,
                    commandId = COMMAND_ID,
                    generationRunId = TRACE_ID,
                    executionId = EXECUTION_ID,
                ),
                terminalStateCode = "SUCCEEDED",
                createdAtMs = 1_200L,
            ),
            projected,
        )
    }

    @Test
    fun executionProjection_requiresExactFinalJournalProofAndFrozenScope() {
        val streamId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val valid = executionProjection()

        listOf(
            valid.copy(eventSequence = 7L),
            valid.copy(eventExecutionId = "different-execution"),
            valid.copy(eventPreviousStatus = "failed"),
            valid.copy(eventNextStatus = "failed"),
            valid.copy(eventNextVerification = "STALE"),
            valid.copy(eventCreatedAtMs = 1_199L),
            valid.copy(updatedAtMs = 1_199L),
            valid.copy(learningScopeKind = null),
            valid.copy(learningScopeId = "not-a-uuid"),
        ).forEach { malformed ->
            assertNull(projectExecutionTerminalDraft(malformed, streamId))
        }
    }

    @Test
    fun executionProjection_requiresExactOwningMessageBeforeSchema2Repair() {
        val streamId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val p1 = executionProjection().copy(
            toolCallId = "call-1",
            toolName = "memory_search",
            toolSchemaFingerprint = "a".repeat(64),
        )

        assertNull(projectExecutionTerminalDraft(p1, streamId))
        assertNull(
            projectExecutionTerminalDraft(
                p1.copy(owningAssistantMessageId = "assistant-message-1"),
                streamId,
            ),
        )

        val projected = projectExecutionTerminalDraft(
            p1.copy(
                owningAssistantMessageId = "assistant-message-1",
                owningAssistantMessageRevision = 3L,
            ),
            streamId,
        )
        assertEquals(2, projected?.eventCode?.schemaVersion)
        assertEquals("assistant-message-1", projected?.correlation?.messageId)
        assertEquals(3L, projected?.correlation?.messageRevision)
    }

    private fun commandProjection() = LearningCommandTerminalAuthorityProjection(
        commandId = COMMAND_ID,
        state = "COMPLETED",
        stateVersion = 4L,
        conversationId = CONVERSATION_ID,
        authoritySubjectId = null,
        assistantIdSnapshot = ASSISTANT_ID,
        lineageId = LINEAGE_ID,
        parentCommandId = PARENT_COMMAND_ID,
        branchAnchorMessageId = BRANCH_ANCHOR_ID,
        finishedAtMs = 900L,
    )

    private fun executionProjection() = LearningExecutionTerminalAuthorityProjection(
        executionId = EXECUTION_ID,
        traceId = TRACE_ID,
        commandId = COMMAND_ID,
        conversationId = CONVERSATION_ID,
        learningScopeKind = "ASSISTANT",
        learningScopeId = ASSISTANT_ID,
        status = "succeeded",
        stateVersion = 8L,
        verificationState = "LIVE_CONFIRMED",
        finishedAtMs = 1_200L,
        updatedAtMs = 1_200L,
        eventId = EVENT_ID,
        eventExecutionId = EXECUTION_ID,
        eventSequence = 8L,
        eventPreviousStatus = "running",
        eventNextStatus = "succeeded",
        eventNextVerification = "LIVE_CONFIRMED",
        eventCreatedAtMs = 1_200L,
    )

    private companion object {
        const val COMMAND_ID = "20000000-0000-0000-0000-000000000001"
        const val CONVERSATION_ID = "20000000-0000-0000-0000-000000000002"
        const val ASSISTANT_ID = "20000000-0000-0000-0000-000000000003"
        const val LINEAGE_ID = "20000000-0000-0000-0000-000000000004"
        const val PARENT_COMMAND_ID = "20000000-0000-0000-0000-000000000005"
        const val BRANCH_ANCHOR_ID = "20000000-0000-0000-0000-000000000006"
        const val EXECUTION_ID = "execution:tool:7"
        const val TRACE_ID = "20000000-0000-0000-0000-000000000007"
        const val EVENT_ID = "tool-event:terminal:7"
    }
}

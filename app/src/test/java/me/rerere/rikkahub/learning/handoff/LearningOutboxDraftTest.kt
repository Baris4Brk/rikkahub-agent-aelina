package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearningOutboxDraftTest {
    @Test
    fun sentinelHasFixedIdentityAndNoBusinessScope() {
        val row = LearningOutboxDraft(
            streamId = STREAM,
            eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 1),
            source = null,
            correlation = LearningCorrelation(),
            terminalStateCode = null,
            createdAtMs = 1,
        ).toEntity()
        assertEquals(LEARNING_STREAM_INIT_EVENT_ID, row.eventId)
        assertNull(row.scopeKind)
        assertNull(row.scopeId)
        assertNull(row.sourceId)
    }

    @Test
    fun terminalIdentityChangesWhenAuthoritativeRevisionChanges() {
        val first = terminalDraft(revision = 2).toEntity()
        val second = terminalDraft(revision = 3).toEntity()
        assertFalse(first.eventId == second.eventId)
        assertEquals("COMMAND_TERMINAL", first.eventType)
        assertEquals("ASSISTANT", first.scopeKind)
    }

    @Test
    fun retryWithSameAuthorityClockProducesAnExactlyEqualDuplicateRow() {
        val first = terminalDraft(revision = 2).toEntity().copy(seq = 1)
        val exactRetry = terminalDraft(revision = 2).toEntity().copy(seq = 99)
        val clockDrift = exactRetry.copy(createdAtMs = 11)

        assertTrue(first.hasSameOutboxIdentityAs(exactRetry))
        assertEquals(first.eventId, clockDrift.eventId)
        assertFalse(first.hasSameOutboxIdentityAs(clockDrift))
    }

    @Test
    fun knownEventsEnforceTerminalSourceAndCorrelationMatrix() {
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(terminalStateCode = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(terminalStateCode = "INTERRUPTED")
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(
                eventCode = LearningEventCode(LearningEventType.COMMAND_ADMITTED.name, 1),
                terminalStateCode = "COMPLETED",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(
                sourceTypeCode = LearningSourceKind.EXECUTION_EVENT.name,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(
                source = terminalDraft(revision = 2).source?.copy(
                    sourceKind = LearningSourceKind.EXECUTION_EVENT,
                ),
                sourceTypeCode = LearningSourceKind.EXECUTION_EVENT.name,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(
                correlation = LearningCorrelation(commandId = "command-1"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(
                correlation = LearningCorrelation(
                    conversationId = "conversation-1",
                    commandId = "command-2",
                    lineageId = "lineage-1",
                    branchAnchorMessageId = "message-root-1",
                ),
            )
        }
    }

    @Test
    fun producerRejectsAnEventThatOccursAfterItsAuthorityCommit() {
        assertThrows(IllegalArgumentException::class.java) {
            terminalDraft(revision = 2).copy(
                source = terminalDraft(revision = 2).source?.copy(occurredAtMs = 11),
            )
        }
    }

    @Test
    fun executionAndWorkflowTerminalMatricesUseTheirOwnBoundedStates() {
        val execution = LearningOutboxDraft(
            streamId = STREAM,
            eventCode = LearningEventCode(LearningEventType.EXECUTION_TERMINAL.name, 1),
            source = source(LearningSourceKind.EXECUTION_EVENT, "execution-event-1"),
            correlation = LearningCorrelation(executionId = "execution-1"),
            terminalStateCode = "SUCCEEDED",
            createdAtMs = 10,
        )
        assertEquals("SUCCEEDED", execution.toEntity().terminalState)
        assertThrows(IllegalArgumentException::class.java) {
            execution.copy(correlation = LearningCorrelation())
        }
        assertThrows(IllegalArgumentException::class.java) {
            execution.copy(terminalStateCode = "SUCCESS")
        }

        val workflow = LearningOutboxDraft(
            streamId = STREAM,
            eventCode = LearningEventCode(LearningEventType.WORKFLOW_TRIAL_TERMINAL.name, 1),
            source = source(LearningSourceKind.WORKFLOW_TRIAL, "workflow-trial-1"),
            correlation = LearningCorrelation(),
            terminalStateCode = "SKIPPED_DAILY_CAP",
            createdAtMs = 10,
        )
        assertEquals("SKIPPED_DAILY_CAP", workflow.toEntity().terminalState)
        assertThrows(IllegalArgumentException::class.java) {
            workflow.copy(terminalStateCode = "SUCCEEDED")
        }
    }

    private fun terminalDraft(revision: Long) = LearningOutboxDraft(
        streamId = STREAM,
        eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
        source = LearningSourceRef(
            sourceKind = LearningSourceKind.COMMAND,
            sourceId = "command-1",
            sourceRevision = revision,
            missingRevisionReason = null,
            databaseStreamId = STREAM,
            scope = LearningScope.Assistant(ASSISTANT),
            occurredAtMs = 10,
        ),
        correlation = LearningCorrelation(
            conversationId = "conversation-1",
            commandId = "command-1",
            lineageId = "lineage-1",
            branchAnchorMessageId = "message-root-1",
        ),
        terminalStateCode = "COMPLETED",
        createdAtMs = 10,
    )

    private fun source(kind: LearningSourceKind, id: String) = LearningSourceRef(
        sourceKind = kind,
        sourceId = id,
        sourceRevision = 1,
        missingRevisionReason = null,
        databaseStreamId = STREAM,
        scope = LearningScope.Assistant(ASSISTANT),
        occurredAtMs = 10,
    )

    private companion object {
        val STREAM = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val ASSISTANT = Uuid.parse("00000000-0000-0000-0000-000000000002")
    }
}

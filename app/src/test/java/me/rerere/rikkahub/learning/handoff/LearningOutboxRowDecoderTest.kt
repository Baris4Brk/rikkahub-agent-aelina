package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearningOutboxRowDecoderTest {
    @Test
    fun validProducerRowRoundTripsWithoutContentPayload() {
        val row = draft().toEntity().copy(seq = 2)
        val decoded = LearningOutboxRowDecoder.decode(row)
        assertTrue(decoded is LearningOutboxDecodeResult.Valid)
        val event = (decoded as LearningOutboxDecodeResult.Valid).event
        assertEquals(row.eventId, event.eventId)
        assertEquals("COMPLETED", event.terminalStateCode)
        assertEquals(10L, event.source?.occurredAtMs)
        assertEquals(10L, event.toInboxEntity(10, 0).occurredAtMs)
    }

    @Test
    fun alteredAuthoritativeFieldCannotReuseEventId() {
        val row = draft().toEntity().copy(seq = 2, sourceRevision = 99)
        assertEquals(
            LearningOutboxDecodeResult.Invalid(LearningOutboxDecodeError.EVENT_ID_MISMATCH),
            LearningOutboxRowDecoder.decode(row),
        )
    }

    @Test
    fun malformedScopeFailsClosedWithoutReturningItsValue() {
        val row = draft().toEntity().copy(seq = 2, scopeKind = "GLOBAL")
        assertEquals(
            LearningOutboxDecodeResult.Invalid(LearningOutboxDecodeError.INVALID_SCOPE),
            LearningOutboxRowDecoder.decode(row),
        )
    }

    @Test
    fun futureMissingRevisionReasonIsRetainedVerbatim() {
        val eventId = LearningCanonicalId.eventId(
            streamId = STREAM,
            eventType = LearningEventType.COMMAND_TERMINAL,
            eventSchemaVersion = 1,
            sourceKindCode = LearningSourceKind.COMMAND.name,
            sourceId = "command-1",
            sourceRevision = null,
            terminalState = "COMPLETED",
        )
        val row = draft().toEntity().copy(
            seq = 2,
            eventId = eventId,
            sourceRevision = null,
            missingRevisionReason = "FUTURE_REASON",
        )

        val decoded = LearningOutboxRowDecoder.decode(row)
        assertTrue(decoded is LearningOutboxDecodeResult.Valid)
        val event = (decoded as LearningOutboxDecodeResult.Valid).event
        assertEquals("FUTURE_REASON", event.missingRevisionReasonCode)
        assertEquals("FUTURE_REASON", event.toInboxEntity(10, 0).missingRevisionReason)
    }

    @Test
    fun futureSchemaCannotMasqueradeAsTheStreamSentinel() {
        val row = LearningOutboxDraft(
            streamId = STREAM,
            eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 1),
            source = null,
            correlation = LearningCorrelation(),
            terminalStateCode = null,
            createdAtMs = 10,
        ).toEntity().copy(seq = 1, eventSchemaVersion = 2)

        assertEquals(
            LearningOutboxDecodeResult.Invalid(LearningOutboxDecodeError.INVALID_SENTINEL),
            LearningOutboxRowDecoder.decode(row),
        )
    }

    @Test
    fun knownEventMatrixAndAuthorityClockFailClosed() {
        val row = draft().toEntity().copy(seq = 2)
        listOf(
            row.copy(terminalState = null),
            row.copy(sourceType = LearningSourceKind.EXECUTION_EVENT.name),
            row.copy(conversationId = null),
        ).forEach { invalidRow ->
            assertEquals(
                LearningOutboxDecodeResult.Invalid(
                    LearningOutboxDecodeError.INVALID_EVENT_CONTRACT,
                ),
                LearningOutboxRowDecoder.decode(invalidRow),
            )
        }
        assertEquals(
            LearningOutboxDecodeResult.Invalid(LearningOutboxDecodeError.INVALID_TIME),
            LearningOutboxRowDecoder.decode(row.copy(occurredAtMs = 11)),
        )
    }

    private fun draft() = LearningOutboxDraft(
        streamId = STREAM,
        eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
        source = LearningSourceRef(
            sourceKind = LearningSourceKind.COMMAND,
            sourceId = "command-1",
            sourceRevision = 2,
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

    private companion object {
        val STREAM = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val ASSISTANT = Uuid.parse("00000000-0000-0000-0000-000000000002")
    }
}

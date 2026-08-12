package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.MissingSourceRevisionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearningHandoffEventTest {
    @Test
    fun streamSentinel_hasNoFakeScopeOrSource() {
        val stream = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val event = LearningHandoffEvent(
            streamId = stream,
            eventId = LEARNING_STREAM_INIT_EVENT_ID,
            outboxSeq = 1,
            eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 1),
            source = null,
            correlation = LearningCorrelation(),
            createdAtMs = 10,
        ).toInboxEntity(ingestedAtMs = 10, replayGeneration = 0)

        assertNull(event.scopeKind)
        assertNull(event.scopeId)
        assertNull(event.sourceType)
        assertNull(event.occurredAtMs)
        assertFalse(event.isSafeToCreateJob())
    }

    @Test
    fun businessEvent_requiresSameStreamAndTypedScope() {
        val stream = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val assistant = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val event = LearningHandoffEvent(
            streamId = stream,
            eventId = commandTerminalEventId(stream, sourceRevision = 3),
            outboxSeq = 2,
            eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
            source = LearningSourceRef(
                sourceKind = LearningSourceKind.COMMAND,
                sourceId = "command-1",
                sourceRevision = 3,
                missingRevisionReason = null,
                databaseStreamId = stream,
                scope = LearningScope.Assistant(assistant),
                occurredAtMs = 8,
            ),
            terminalStateCode = "COMPLETED",
            correlation = commandCorrelation(),
            createdAtMs = 10,
        ).toInboxEntity(ingestedAtMs = 10, replayGeneration = 4)

        assertEquals("ASSISTANT", event.scopeKind)
        assertEquals(assistant.toString(), event.scopeId)
        assertEquals(LearningEventDecodeState.KNOWN.name, event.decodeState)
        assertEquals(8L, event.occurredAtMs)
        assertTrue(event.isSafeToCreateJob())
    }

    @Test
    fun unknownEvent_isPreservedButNeverCreatesAJob() {
        val stream = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val assistant = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val handoff = LearningHandoffEvent(
            streamId = stream,
            eventId = "future-event-1",
            outboxSeq = 3,
            eventCode = LearningEventCode("FUTURE_EVENT", 1),
            source = LearningSourceRef(
                sourceKind = LearningSourceKind.COMMAND,
                sourceId = "command-1",
                sourceRevision = 1,
                missingRevisionReason = null,
                databaseStreamId = stream,
                scope = LearningScope.Assistant(assistant),
                occurredAtMs = 8,
            ),
            correlation = commandCorrelation(),
            createdAtMs = 10,
        )
        val event = handoff.toInboxEntity(ingestedAtMs = 10, replayGeneration = 0)

        assertEquals(LearningEventDecodeState.UNKNOWN_NO_JOB.name, event.decodeState)
        assertEquals("FUTURE_EVENT", event.eventTypeCode)
        assertFalse("FUTURE_EVENT" in handoff.toString())
        assertFalse(event.isSafeToCreateJob())
    }

    @Test
    fun unknownSourceCode_isPreservedButNeverCreatesAJob() {
        val stream = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val assistant = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val event = LearningHandoffEvent(
            streamId = stream,
            eventId = LearningCanonicalId.eventId(
                streamId = stream,
                eventType = LearningEventType.COMMAND_TERMINAL,
                eventSchemaVersion = 1,
                sourceKindCode = "FUTURE_SOURCE_TYPE",
                sourceId = "future-source-id",
                sourceRevision = 1,
                terminalState = "COMPLETED",
            ),
            outboxSeq = 4,
            eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
            source = LearningSourceRef(
                sourceKind = LearningSourceKind.UNKNOWN,
                sourceId = "future-source-id",
                sourceRevision = 1,
                missingRevisionReason = null,
                databaseStreamId = stream,
                scope = LearningScope.Assistant(assistant),
                occurredAtMs = 8,
            ),
            sourceTypeCode = "FUTURE_SOURCE_TYPE",
            terminalStateCode = "COMPLETED",
            correlation = commandCorrelation(commandId = "future-source-id"),
            createdAtMs = 10,
        ).toInboxEntity(ingestedAtMs = 10, replayGeneration = 0)

        assertEquals("FUTURE_SOURCE_TYPE", event.sourceType)
        assertFalse(event.isSafeToCreateJob())
    }

    @Test
    fun streamSentinel_rejectsBusinessCorrelation() {
        val stream = Uuid.parse("00000000-0000-0000-0000-000000000001")
        assertThrows(IllegalArgumentException::class.java) {
            LearningHandoffEvent(
                streamId = stream,
                eventId = LEARNING_STREAM_INIT_EVENT_ID,
                outboxSeq = 1,
                eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 1),
                source = null,
                correlation = LearningCorrelation(commandId = "command-1"),
                createdAtMs = 10,
            )
        }
    }

    @Test
    fun knownBusinessEvent_rejectsNonCanonicalIdentity() {
        val source = LearningSourceRef(
            sourceKind = LearningSourceKind.COMMAND,
            sourceId = "command-1",
            sourceRevision = 3,
            missingRevisionReason = null,
            databaseStreamId = STREAM,
            scope = LearningScope.Assistant(ASSISTANT),
            occurredAtMs = 8,
        )

        assertThrows(IllegalArgumentException::class.java) {
            LearningHandoffEvent(
                streamId = STREAM,
                eventId = "learning-event-v1:${"0".repeat(64)}",
                outboxSeq = 2,
                eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
                source = source,
                terminalStateCode = "COMPLETED",
                correlation = commandCorrelation(),
                createdAtMs = 10,
            )
        }
    }

    @Test
    fun incompatibleStreamSentinelSchema_failsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LearningHandoffEvent(
                streamId = STREAM,
                eventId = LEARNING_STREAM_INIT_EVENT_ID,
                outboxSeq = 1,
                eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 2),
                source = null,
                correlation = LearningCorrelation(),
                createdAtMs = 10,
            )
        }
    }

    @Test
    fun futureMissingRevisionReason_roundTripsWithoutBeingCollapsed() {
        val event = LearningHandoffEvent(
            streamId = STREAM,
            eventId = commandTerminalEventId(STREAM, sourceRevision = null),
            outboxSeq = 2,
            eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
            source = LearningSourceRef(
                sourceKind = LearningSourceKind.COMMAND,
                sourceId = "command-1",
                sourceRevision = null,
                missingRevisionReason = MissingSourceRevisionReason.UNKNOWN,
                databaseStreamId = STREAM,
                scope = LearningScope.Assistant(ASSISTANT),
                occurredAtMs = 8,
            ),
            missingRevisionReasonCode = "FUTURE_REASON",
            terminalStateCode = "COMPLETED",
            correlation = commandCorrelation(),
            createdAtMs = 10,
        ).toInboxEntity(ingestedAtMs = 11, replayGeneration = 0)

        assertEquals("FUTURE_REASON", event.missingRevisionReason)
        assertEquals(8L, event.occurredAtMs)
    }

    @Test
    fun inboxDuplicateComparisonIgnoresOnlyLocalIngestionTime() {
        val handoff = LearningHandoffEvent(
            streamId = STREAM,
            eventId = commandTerminalEventId(STREAM, sourceRevision = 3),
            outboxSeq = 2,
            eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
            source = LearningSourceRef(
                sourceKind = LearningSourceKind.COMMAND,
                sourceId = "command-1",
                sourceRevision = 3,
                missingRevisionReason = null,
                databaseStreamId = STREAM,
                scope = LearningScope.Assistant(ASSISTANT),
                occurredAtMs = 8,
            ),
            terminalStateCode = "COMPLETED",
            correlation = commandCorrelation(),
            createdAtMs = 10,
        )
        val first = handoff.toInboxEntity(ingestedAtMs = 10, replayGeneration = 0)
        val replay = handoff.toInboxEntity(ingestedAtMs = 20, replayGeneration = 0)
        val reinterpreted = replay.copy(
            decodeState = LearningEventDecodeState.UNKNOWN_NO_JOB.name,
            interpretationVersion = 2,
        )

        assertTrue(first.hasSameAuthoritativeIdentityAs(replay))
        assertTrue(first.hasSameAuthoritativeIdentityAs(reinterpreted))
        assertFalse(reinterpreted.isSafeToCreateJob())
        assertFalse(first.hasSameAuthoritativeIdentityAs(replay.copy(occurredAtMs = 9)))
    }

    private fun commandCorrelation(commandId: String = "command-1") = LearningCorrelation(
        conversationId = "conversation-1",
        commandId = commandId,
        lineageId = "lineage-1",
        branchAnchorMessageId = "message-root-1",
    )

    private fun commandTerminalEventId(
        streamId: Uuid,
        sourceRevision: Long?,
    ): String = LearningCanonicalId.eventId(
        streamId = streamId,
        eventType = LearningEventType.COMMAND_TERMINAL,
        eventSchemaVersion = 1,
        sourceKindCode = LearningSourceKind.COMMAND.name,
        sourceId = "command-1",
        sourceRevision = sourceRevision,
        terminalState = "COMPLETED",
    )

    private companion object {
        val STREAM = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val ASSISTANT = Uuid.parse("00000000-0000-0000-0000-000000000002")
    }
}

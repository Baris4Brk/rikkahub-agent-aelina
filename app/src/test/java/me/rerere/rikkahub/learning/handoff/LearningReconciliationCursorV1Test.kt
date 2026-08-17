package me.rerere.rikkahub.learning.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningReconciliationCursorV1Test {
    @Test
    fun `initial cursor has fixed canonical key order`() {
        val encoded = LearningReconciliationCursorV1Codec.encode(initial())

        assertEquals(
            """{"schema_version":1,"state":"RUNNING","phase":"COMMAND","stream_id":"$STREAM","frozen_head_sequence":42,"window_start_ms":100,"window_end_ms":1000,"command":{"after":null,"coverage_floor_ms":null},"execution":{"after":null,"coverage_floor_ms":null},"conversation_source":{"after":null,"coverage_floor_ms":null},"message_source":{"after":null,"coverage_floor_ms":null},"feedback_revision":{"after":null,"coverage_floor_ms":null}}""",
            encoded.substringBefore(",\"integrity_sha256\"") + "}",
        )
        assertTrue(encoded.matches(Regex(".*\\\"integrity_sha256\\\":\\\"[0-9a-f]{64}\\\"}")))
    }

    @Test
    fun `all five key shapes round trip after completion`() {
        val completed = initial()
            .advance(
                LearningReconciliationAfterKeyV1.Command(150, "command-a"),
                observedCoverageFloorMs = 140,
            )
            .nextPhase()
            .advance(
                LearningReconciliationAfterKeyV1.Execution(250, "execution-a"),
                observedCoverageFloorMs = 240,
            )
            .nextPhase()
            .advance(
                LearningReconciliationAfterKeyV1.ConversationSource(
                    updatedAtMs = 350,
                    conversationId = "conversation-a",
                    scopeKind = "ASSISTANT",
                    scopeId = "scope-a",
                ),
                observedCoverageFloorMs = 340,
            )
            .nextPhase()
            .advance(
                LearningReconciliationAfterKeyV1.MessageSource(
                    updatedAtMs = 450,
                    conversationId = "conversation-a",
                    messageId = "message-a",
                    scopeKind = "ASSISTANT",
                    scopeId = "scope-a",
                ),
                observedCoverageFloorMs = 440,
            )
            .nextPhase()
            .advance(
                LearningReconciliationAfterKeyV1.FeedbackRevision(
                    updatedAtMs = 550,
                    feedbackId = "feedback-v1:a",
                    sourceRevision = 3,
                ),
                observedCoverageFloorMs = 540,
            )
            .complete()

        val encoded = LearningReconciliationCursorV1Codec.encode(completed)

        assertEquals(completed, LearningReconciliationCursorV1Codec.decode(encoded))
        assertEquals(LearningReconciliationCursorStateV1.COMPLETE, completed.state)
    }

    @Test
    fun `strict codec rejects reordered unknown duplicate partial and old documents`() {
        val initial = LearningReconciliationCursorV1Codec.encode(initial())
        val reordered = initial.replaceFirst(
            "{\"schema_version\":1,\"state\":\"RUNNING\"",
            "{\"state\":\"RUNNING\",\"schema_version\":1",
        )
        val unknown = initial.replace(
            ",\"integrity_sha256\"",
            ",\"unknown\":0,\"integrity_sha256\"",
        )
        val duplicate = initial.replace(
            "\"state\":\"RUNNING\"",
            "\"state\":\"RUNNING\",\"state\":\"RUNNING\"",
        )
        val oldVersion = initial.replace("\"schema_version\":1", "\"schema_version\":0")
        val withCommand = LearningReconciliationCursorV1Codec.encode(
            initial().advance(LearningReconciliationAfterKeyV1.Command(150, "command-a")),
        )
        val partial = withCommand.replace(",\"id\":\"command-a\"", "")

        listOf(reordered, unknown, duplicate, oldVersion, partial).forEach { malformed ->
            assertNull(LearningReconciliationCursorV1Codec.decode(malformed))
        }
    }

    @Test
    fun `stream identity must be canonical lower-case non-nil uuid`() {
        listOf(
            "123E4567-E89B-12D3-A456-426614174000",
            "00000000-0000-0000-0000-000000000000",
            "not-a-uuid",
        ).forEach { streamId ->
            assertThrows(IllegalArgumentException::class.java) {
                LearningReconciliationCursorV1.initialize(
                    streamId = streamId,
                    frozenHeadSequence = 42,
                    windowStartMs = 100,
                    windowEndMs = 1_000,
                )
            }
        }
    }

    @Test
    fun `integrity rejects semantically valid field tampering`() {
        val encoded = LearningReconciliationCursorV1Codec.encode(initial())
        val changedHead = encoded.replace(
            "\"frozen_head_sequence\":42",
            "\"frozen_head_sequence\":43",
        )

        assertNull(LearningReconciliationCursorV1Codec.decode(changedHead))
    }

    @Test
    fun `oversize and numeric overflow fail closed`() {
        val encoded = LearningReconciliationCursorV1Codec.encode(initial())
        val numericOverflow = encoded.replace(
            "\"frozen_head_sequence\":42",
            "\"frozen_head_sequence\":9223372036854775808",
        )

        assertNull(LearningReconciliationCursorV1Codec.decode(numericOverflow))
        assertNull(
            LearningReconciliationCursorV1Codec.decode(
                "x".repeat(MAX_LEARNING_RECONCILIATION_CURSOR_JSON_CHARS + 1),
            ),
        )
        assertNull(
            LearningReconciliationCursorV1Codec.decode(
                "汉".repeat(MAX_LEARNING_RECONCILIATION_CURSOR_JSON_CHARS / 2),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LearningReconciliationCursorV1.initialize(
                streamId = STREAM,
                frozenHeadSequence = 42,
                windowStartMs = 100,
                windowEndMs = Long.MAX_VALUE,
            )
        }
    }

    @Test
    fun `advance is strictly monotonic and preserves the minimum coverage floor`() {
        val first = initial().advance(
            LearningReconciliationAfterKeyV1.Command(150, "command-a"),
            observedCoverageFloorMs = 145,
        )
        val second = first.advance(
            LearningReconciliationAfterKeyV1.Command(150, "command-b"),
            observedCoverageFloorMs = 130,
        )

        assertEquals(130L, second.command.coverageFloorMs)
        assertEquals("command-b", (second.command.after as LearningReconciliationAfterKeyV1.Command).id)
        assertThrows(IllegalArgumentException::class.java) {
            second.advance(LearningReconciliationAfterKeyV1.Command(150, "command-b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            second.advance(LearningReconciliationAfterKeyV1.Command(149, "command-z"))
        }
        assertEquals(second.command, second.nextPhase().command)
    }

    @Test
    fun `phase transitions accept empty pages but reject future and mismatched positions`() {
        val execution = initial().nextPhase()

        assertEquals(LearningReconciliationPhaseV1.EXECUTION, execution.phase)
        assertTrue(execution.command.isEmpty)
        assertThrows(IllegalArgumentException::class.java) {
            initial().advance(LearningReconciliationAfterKeyV1.Execution(150, "execution-a"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            initial().copy(
                execution = LearningReconciliationPhasePositionV1(
                    LearningReconciliationAfterKeyV1.Execution(150, "execution-a"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) { initial().complete() }

        val feedback = execution.nextPhase().nextPhase().nextPhase()
        assertEquals(LearningReconciliationPhaseV1.FEEDBACK_REVISION, feedback.phase)
        assertThrows(IllegalArgumentException::class.java) { feedback.nextPhase() }
        val complete = feedback.complete()
        assertThrows(IllegalArgumentException::class.java) {
            complete.advance(
                LearningReconciliationAfterKeyV1.FeedbackRevision(500, "feedback-v1:a", 1),
            )
        }
    }

    private fun initial(): LearningReconciliationCursorV1 = LearningReconciliationCursorV1.initialize(
        streamId = STREAM,
        frozenHeadSequence = 42,
        windowStartMs = 100,
        windowEndMs = 1_000,
    )

    private companion object {
        const val STREAM = "123e4567-e89b-12d3-a456-426614174000"
    }
}

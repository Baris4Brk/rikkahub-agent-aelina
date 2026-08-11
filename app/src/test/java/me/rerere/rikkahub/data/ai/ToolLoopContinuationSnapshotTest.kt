package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopContinuationSnapshotTest {
    @Test
    fun `65 tool continuation preserves a fixed prefix and every live tool part`() {
        val history = List(100) { index -> UIMessage.user("history-$index") }
        val currentUser = UIMessage.user("current long task")
        val initial = history + currentUser + assistantTurn(toolCount = 1)
        val snapshot = assertNotNullSnapshot(ToolLoopContinuationSnapshot.capture(initial))
        val expectedPrefix = snapshot.frozenPrefix
        assertEquals(history, expectedPrefix)

        for (toolCount in 1..65) {
            val live = history + currentUser + assistantTurn(toolCount)
            val projection = snapshot.project(live) as ToolLoopSnapshotProjection.Valid
            assertEquals(expectedPrefix, projection.messages.take(expectedPrefix.size))
            assertEquals(currentUser, projection.messages[expectedPrefix.size])

            val projectedAssistant = projection.messages.last()
            val projectedReasoning = projectedAssistant.parts.filterIsInstance<UIMessagePart.Reasoning>()
            val projectedTools = projectedAssistant.parts.filterIsInstance<UIMessagePart.Tool>()
            assertEquals(toolCount, projectedReasoning.size)
            assertEquals(toolCount, projectedTools.size)
            assertEquals("reasoning-${toolCount - 1}", projectedReasoning.last().reasoning)
            assertEquals("{\"value\":${toolCount - 1}}", projectedTools.last().input)
            assertEquals(
                "output-${toolCount - 1}",
                (projectedTools.last().output.single() as UIMessagePart.Text).text,
            )
            assertFalse(projectedTools.any { it.input.contains("_archived_tool_input") })
        }
    }

    @Test
    fun `continuation at message limit freezes the exact call one history boundary`() {
        val history = List(511) { index -> UIMessage.user("history-$index") }
        val currentUser = UIMessage.user("current task")
        val callOneMessages = history + currentUser
        val liveCallTwo = callOneMessages + assistantTurn(toolCount = 1)

        val snapshot = assertNotNullSnapshot(
            ToolLoopContinuationSnapshot.capture(
                liveMessages = liveCallTwo,
                ordinaryMessageLimit = 512,
            ),
        )
        val projection = snapshot.project(liveCallTwo) as ToolLoopSnapshotProjection.Valid

        assertEquals(callOneMessages, projection.messages.dropLast(1))
        assertEquals(history, snapshot.frozenPrefix)
    }

    @Test
    fun `manual summary is frozen exactly once`() {
        val summary = UIMessage.user("summary").copy(
            annotations = listOf(UIMessageAnnotation.ManualCompressionSummary(0, 1)),
        )
        val history = List(40) { UIMessage.user("history-$it") }
        val currentUser = UIMessage.user("task")
        val live = listOf(summary) + history + currentUser + assistantTurn(1)
        val snapshot = assertNotNullSnapshot(ToolLoopContinuationSnapshot.capture(live))

        val projected = (snapshot.project(live) as ToolLoopSnapshotProjection.Valid).messages

        assertEquals(1, projected.count { it.id == summary.id })
        assertEquals(summary, projected.first())
    }

    @Test
    fun `steering message remains in live tail without moving the task boundary`() {
        val currentUser = UIMessage.user("task")
        val initial = listOf(UIMessage.user("history"), currentUser, assistantTurn(1))
        val snapshot = assertNotNullSnapshot(ToolLoopContinuationSnapshot.capture(initial))
        val steering = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("focus on tests")),
            annotations = listOf(UIMessageAnnotation.Steering("command", persistent = true)),
        )
        val live = initial + steering + assistantTurn(2)

        val projected = snapshot.project(live) as ToolLoopSnapshotProjection.Valid

        assertTrue(projected.messages.any { it.id == steering.id })
        assertEquals(snapshot.frozenPrefix, projected.messages.take(snapshot.frozenPrefix.size))
    }

    @Test
    fun `new ordinary user invalidates instead of dropping the newer turn`() {
        val initial = listOf(UIMessage.user("task"), assistantTurn(1))
        val snapshot = assertNotNullSnapshot(ToolLoopContinuationSnapshot.capture(initial))
        val newerUser = UIMessage.user("new task")

        val result = snapshot.project(initial + newerUser + assistantTurn(1))

        assertEquals(
            ToolLoopSnapshotProjection.Invalid(ToolLoopSnapshotInvalidation.NEWER_USER_TURN),
            result,
        )
    }

    @Test
    fun `missing or duplicated boundary fails closed`() {
        val currentUser = UIMessage.user("task")
        val initial = listOf(currentUser, assistantTurn(1))
        val snapshot = assertNotNullSnapshot(ToolLoopContinuationSnapshot.capture(initial))

        assertEquals(
            ToolLoopSnapshotProjection.Invalid(ToolLoopSnapshotInvalidation.TURN_BOUNDARY_MISSING),
            snapshot.project(listOf(UIMessage.user("replacement"), assistantTurn(1))),
        )
        assertEquals(
            ToolLoopSnapshotProjection.Invalid(ToolLoopSnapshotInvalidation.TURN_BOUNDARY_DUPLICATED),
            snapshot.project(listOf(currentUser, currentUser, assistantTurn(1))),
        )
    }

    @Test
    fun `historical mutation insertion reorder and deletion invalidate the frozen prefix`() {
        val history = List(4) { UIMessage.user("history-$it") }
        val currentUser = UIMessage.user("task")
        val assistant = assistantTurn(1)
        val initial = history + currentUser + assistant
        val snapshot = assertNotNullSnapshot(ToolLoopContinuationSnapshot.capture(initial))
        val expected = ToolLoopSnapshotProjection.Invalid(
            ToolLoopSnapshotInvalidation.HISTORICAL_PREFIX_CHANGED,
        )

        val sameIdMutation = history.toMutableList().also { changed ->
            changed[1] = changed[1].copy(parts = listOf(UIMessagePart.Text("mutated")))
        }
        val inserted = history.toMutableList().also { changed ->
            changed.add(2, UIMessage.user("inserted"))
        }
        val prepended = listOf(UIMessage.user("prepended")) + history
        val reordered = history.toMutableList().also { changed ->
            val first = changed[0]
            changed[0] = changed[1]
            changed[1] = first
        }
        val deleted = history.drop(1)

        listOf(sameIdMutation, inserted, prepended, reordered, deleted).forEach { changedHistory ->
            assertEquals(expected, snapshot.project(changedHistory + currentUser + assistant))
        }
    }

    @Test
    fun `empty prefix growth and same-id user mutation start a new epoch`() {
        val currentUser = UIMessage.user("task")
        val assistant = assistantTurn(1)
        val snapshot = assertNotNullSnapshot(
            ToolLoopContinuationSnapshot.capture(listOf(currentUser, assistant)),
        )

        assertEquals(
            ToolLoopSnapshotProjection.Invalid(
                ToolLoopSnapshotInvalidation.HISTORICAL_PREFIX_CHANGED,
            ),
            snapshot.project(listOf(UIMessage.user("new history"), currentUser, assistant)),
        )

        val changedUser = currentUser.copy(parts = listOf(UIMessagePart.Text("edited task")))
        assertEquals(
            ToolLoopSnapshotProjection.Invalid(
                ToolLoopSnapshotInvalidation.TURN_BOUNDARY_CHANGED,
            ),
            snapshot.project(listOf(changedUser, assistant)),
        )
    }

    @Test
    fun `changes outside the call-one selected limit do not invalidate visible prefix`() {
        val history = List(20) { UIMessage.user("history-$it") }
        val currentUser = UIMessage.user("task")
        val assistant = assistantTurn(1)
        val initial = history + currentUser + assistant
        val snapshot = assertNotNullSnapshot(
            ToolLoopContinuationSnapshot.capture(
                liveMessages = initial,
                ordinaryMessageLimit = 8,
            ),
        )
        val selectedIds = snapshot.frozenPrefix.mapTo(hashSetOf()) { it.id }
        val omittedIndex = history.indexOfFirst { it.id !in selectedIds }
        assertTrue(omittedIndex >= 0)
        val changedHistory = history.toMutableList().also { changed ->
            changed[omittedIndex] = changed[omittedIndex].copy(
                parts = listOf(UIMessagePart.Text("changed but never sent")),
            )
        }

        assertTrue(snapshot.project(changedHistory + currentUser + assistant) is ToolLoopSnapshotProjection.Valid)
    }

    private fun assistantTurn(toolCount: Int): UIMessage = UIMessage.assistant("").copy(
        parts = buildList {
            repeat(toolCount) { index ->
                add(UIMessagePart.Reasoning("reasoning-$index"))
                add(UIMessagePart.Tool(
                    toolCallId = "call-$index",
                    toolName = "tool_$index",
                    input = "{\"value\":$index}",
                    output = listOf(UIMessagePart.Text("output-$index")),
                    approvalState = if (index % 2 == 0) {
                        ToolApprovalState.Auto
                    } else {
                        ToolApprovalState.Approved
                    },
                ))
            }
        },
    )

    private fun assertNotNullSnapshot(
        snapshot: ToolLoopContinuationSnapshot?,
    ): ToolLoopContinuationSnapshot {
        assertNotNull(snapshot)
        return requireNotNull(snapshot)
    }
}

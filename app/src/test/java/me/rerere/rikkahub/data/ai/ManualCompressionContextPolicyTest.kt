package me.rerere.rikkahub.data.ai

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCompressionContextPolicyTest {
    @Test
    fun `ordinary finite context still limits an uncompressed conversation`() {
        val messages = List(520) { index -> UIMessage.user("message-$index") }

        val selected = messages.selectOrdinaryChatContext(messageLimit = 512)

        assertEquals(264, selected.size)
        assertSame(messages[256], selected.first())
    }

    @Test
    fun `marked manual compression boundary prevents a sliding 512 window`() {
        val summary = UIMessage.user("summary").copy(
            annotations = listOf(UIMessageAnnotation.ManualCompressionSummary(0, 1)),
        )
        val messages = listOf(summary) + List(512) { index -> UIMessage.user("kept-$index") }

        val firstRequest = messages.selectOrdinaryChatContext(messageLimit = 512)
        val secondRequest = (messages + UIMessage.user("next"))
            .selectOrdinaryChatContext(messageLimit = 512)

        assertEquals(513, firstRequest.size)
        assertEquals(514, secondRequest.size)
        assertSame(summary, firstRequest.first())
        assertSame(summary, secondRequest.first())
    }

    @Test
    fun `legacy compressed conversation is recognized by its newer summary prefix`() {
        val summaryTime = LocalDateTime(2026, 7, 23, 0, 56, 42)
        val oldTailTime = LocalDateTime(2026, 7, 18, 6, 1, 54)
        val summaries = List(10) { index ->
            UIMessage.user("summary-$index").copy(createdAt = summaryTime)
        }
        val oldTail = List(512) { index ->
            UIMessage.user("old-$index").copy(createdAt = oldTailTime)
        }
        // Mirrors the inspected device: 10 summaries + 512 retained messages + seven
        // subsequent user/assistant turns (14 messages) = 536 active messages.
        val appendedTurns = List(14) { index -> UIMessage.user("new-$index") }
        val messages = summaries + oldTail + appendedTurns

        val selected = messages.selectOrdinaryChatContext(messageLimit = 512)

        assertEquals(536, selected.size)
        assertSame(summaries.first(), selected.first())
    }

    @Test
    fun `tool loop keeps current turn and a stable recent user boundary`() {
        val history = List(100) { index -> UIMessage.user("history-$index") }
        val currentUser = UIMessage.user("current task")
        val toolReply = UIMessage.assistant("tool result")
        val messages = history + currentUser + toolReply

        val selected = messages.selectToolLoopContinuationContext(recentHistoryMessageLimit = 8)

        assertEquals(10, selected.size)
        assertSame(history[92], selected.first())
        assertSame(currentUser, selected[selected.lastIndex - 1])
        assertSame(toolReply, selected.last())
    }

    @Test
    fun `tool loop preserves explicit manual compression summaries without duplicating them`() {
        val summary = UIMessage.user("summary").copy(
            annotations = listOf(UIMessageAnnotation.ManualCompressionSummary(0, 1)),
        )
        val history = List(50) { index -> UIMessage.user("history-$index") }
        val currentUser = UIMessage.user("current task")
        val messages = listOf(summary) + history + currentUser + UIMessage.assistant("tool")

        val selected = messages.selectToolLoopContinuationContext(recentHistoryMessageLimit = 4)

        assertSame(summary, selected.first())
        assertEquals(1, selected.count { it.id == summary.id })
        assertTrue(selected.any { it.id == currentUser.id })
        assertEquals(7, selected.size)
    }
}

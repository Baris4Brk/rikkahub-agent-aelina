package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ReasoningSource
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkTagTransformerTest {
    @Test
    fun `finishing a generation preserves old assistant history and transforms only the latest output`() {
        val oldAssistant = UIMessage.assistant("<think>old reasoning</think>old answer")
        val messages = listOf(
            UIMessage.user("old request"),
            oldAssistant,
            UIMessage.user("current request"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("<think>new reasoning</think>new answer")),
            ),
        )

        val transformed = transformThinkTagsForLatestAssistant(messages, isFinal = true)

        assertSame(oldAssistant, transformed[1])
        val currentParts = transformed.last().parts
        assertEquals(2, currentParts.size)
        assertTrue(currentParts[0] is UIMessagePart.Reasoning)
        val reasoning = currentParts[0] as UIMessagePart.Reasoning
        assertEquals("new reasoning", reasoning.reasoning)
        assertEquals(ReasoningSource.THINK_TAG, reasoning.source)
        assertEquals("new answer", (currentParts[1] as UIMessagePart.Text).text)
    }
}

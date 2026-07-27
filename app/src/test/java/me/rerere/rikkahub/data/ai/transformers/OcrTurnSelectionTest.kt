package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTurnSelectionTest {
    @Test
    fun `older image is outside the latest user turn`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image("file:///old.png")),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("description")),
            ),
            UIMessage.user("hello"),
        )

        val latestUserIndex = latestUserTurnIndex(messages)

        assertEquals(2, latestUserIndex)
        assertFalse(messages[latestUserIndex].parts.any { it is UIMessagePart.Image })
        assertFalse(shouldRunOcrForMessage(messageIndex = 0, latestUserMessageIndex = latestUserIndex))
    }

    @Test
    fun `image in latest user turn remains eligible`() {
        val messages = listOf(
            UIMessage.user("earlier text"),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("describe this"),
                    UIMessagePart.Image("file:///new.png"),
                ),
            ),
        )

        val latestUserIndex = latestUserTurnIndex(messages)

        assertEquals(1, latestUserIndex)
        assertTrue(messages[latestUserIndex].parts.any { it is UIMessagePart.Image })
        assertTrue(shouldRunOcrForMessage(messageIndex = 1, latestUserMessageIndex = latestUserIndex))
    }
}

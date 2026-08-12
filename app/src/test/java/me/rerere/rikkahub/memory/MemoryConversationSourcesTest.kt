package me.rerere.rikkahub.memory

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryConversationSourcesTest {
    private val assistantMessageId = Uuid.parse("00000000-0000-0000-0000-000000000010")

    @Test
    fun `assistant answer and executed tool output receive independent source identities`() {
        val message = UIMessage(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("final answer"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "weather",
                    input = "private input is not a source",
                    output = listOf(UIMessagePart.Text("sunny")),
                ),
                UIMessagePart.Image("https://example.invalid/not-a-text-source"),
                UIMessagePart.Tool(
                    toolCallId = "call-pending",
                    toolName = "pending",
                    input = "{}",
                ),
            ),
        )

        val sources = memoryCaptureSourcesForMessage(message)

        assertEquals(listOf(MemorySourceRole.ASSISTANT, MemorySourceRole.TOOL), sources.map { it.role })
        assertEquals("final answer", sources[0].text)
        assertEquals("sunny", sources[1].text)
        assertEquals(
            memoryToolSourceId(assistantMessageId.toString(), 1, "call-1"),
            sources[1].messageId,
        )
        assertFalse(sources.any { it.text.contains("private input") })
        assertFalse(sources.any { it.text.contains("example.invalid") })
    }

    @Test
    fun `tool output edit keeps source id but changes version digest`() {
        fun source(output: String) = memoryCaptureSourcesForMessage(
            UIMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "weather",
                        input = "{}",
                        output = listOf(UIMessagePart.Text(output)),
                    ),
                ),
            ),
        ).single()

        val before = source("sunny")
        val after = source("rainy")
        assertEquals(before.messageId, after.messageId)
        assertNotEquals(memorySourceTextDigest(before.text), memorySourceTextDigest(after.text))
        assertTrue(before.role == MemorySourceRole.TOOL)
    }
}

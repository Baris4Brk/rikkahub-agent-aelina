package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientConversationToolSanitizerTest {
    @Test
    fun `raw cross conversation output and query are removed while pairing survives`() {
        val rawSecret = "UNIQUE_OTHER_CONVERSATION_SECRET"
        val tool = UIMessagePart.Tool(
            toolCallId = "call-42",
            toolName = "conversation_read_recent",
            input = """{"conversation_id":"abc","query":"$rawSecret"}""",
            output = listOf(
                UIMessagePart.Text(
                    """{"ok":true,"code":"OK","operation":"read","source_conversation_id":"abc","source_title":"Source","count":1,"character_count":32,"truncated":false,"data":[{"text":"$rawSecret"}]}"""
                )
            ),
        )
        val sanitized = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .sanitizeTransientConversationToolResults()
        val result = sanitized.single().parts.single() as UIMessagePart.Tool
        val serialized = result.toString()

        assertEquals("call-42", result.toolCallId)
        assertEquals("conversation_read_recent", result.toolName)
        assertFalse(serialized.contains(rawSecret))
        assertTrue(result.input.contains("[redacted after current task]"))
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("raw_content_saved\":false"))
        assertTrue((result.output.single() as UIMessagePart.Text).text.contains("Source"))
    }

    @Test
    fun `ordinary tool output is untouched`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "ordinary",
            toolName = "get_time_info",
            input = "{}",
            output = listOf(UIMessagePart.Text("keep me")),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        assertEquals(listOf(message), listOf(message).sanitizeTransientConversationToolResults())
    }

    @Test
    fun `query is redacted even in execution started breadcrumb`() {
        val pending = UIMessagePart.Tool(
            toolCallId = "pending",
            toolName = "conversation_search",
            input = """{"conversation_id":"abc","query":"private phrase"}""",
            executionStartedAt = 123L,
        )
        val sanitized = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending)))
            .sanitizeTransientConversationToolResults()
            .single().parts.single() as UIMessagePart.Tool
        assertFalse(sanitized.input.contains("private phrase"))
        assertTrue(sanitized.output.isEmpty())
        assertEquals(123L, sanitized.executionStartedAt)
    }

    @Test
    fun `oversized transient conversation output is never spilled to disk`() {
        assertFalse(shouldSpillToolOutputToFile("conversation_read_recent", 100_000, true))
        assertFalse(shouldSpillToolOutputToFile("conversation_search", 100_000, true))
        assertTrue(shouldSpillToolOutputToFile("workspace_shell", 100_000, true))
    }
}

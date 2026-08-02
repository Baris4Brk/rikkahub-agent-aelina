package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalAnswerContextPolicyTest {
    @Test
    fun `final answer context keeps current transaction but compacts large payloads`() {
        val oldTurn = UIMessage.user("old turn")
        val user = UIMessage.user("current task")
        val input = "x".repeat(2_000)
        val output = "first:" + "y".repeat(8_000) + ":last"
        val assistant = UIMessage.assistant("").copy(
            parts = listOf(
                UIMessagePart.Reasoning("private reasoning"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "example_tool",
                    input = input,
                    output = listOf(UIMessagePart.Text(output)),
                ),
            ),
        )
        val source = listOf(oldTurn, user, assistant)

        val compact = source.compactCurrentTurnForFinalAnswer()

        assertEquals(2, compact.size)
        assertEquals("current task", compact.first().toText())
        val compactTool = compact.last().getTools().single()
        assertTrue(compactTool.isExecuted)
        assertTrue(compactTool.output.single().let { it as UIMessagePart.Text }.text.length <= 2_048)
        assertTrue((compactTool.output.single() as UIMessagePart.Text).text.startsWith("first:"))
        assertTrue((compactTool.output.single() as UIMessagePart.Text).text.endsWith(":last"))
        assertFalse(compact.last().parts.any { it is UIMessagePart.Reasoning })
        val marker = Json.parseToJsonElement(compactTool.input).jsonObject
        assertTrue(marker["_truncated_for_final_answer"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2000", marker["original_characters"]!!.jsonPrimitive.content)

        // Provider-only compaction must never rewrite the conversation stored in Room.
        assertEquals(input, source.last().getTools().single().input)
        assertEquals(output, (source.last().getTools().single().output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `pending tools stay pending during final answer compaction`() {
        val source = listOf(
            UIMessage.user("task"),
            UIMessage.assistant("").copy(
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "pending",
                        toolName = "example_tool",
                        input = "{}",
                    ),
                ),
            ),
        )

        val compactTool = source.compactCurrentTurnForFinalAnswer().last().getTools().single()

        assertFalse(compactTool.isExecuted)
        assertTrue(compactTool.output.isEmpty())
    }
}

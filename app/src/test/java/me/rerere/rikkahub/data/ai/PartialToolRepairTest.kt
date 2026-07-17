package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.ai.ui.finishPendingTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialToolRepairTest {
    @Test
    fun `parallel pending tools each receive exactly one cancellation result`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            state = UIMessageState.STREAMING,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "first",
                    input = "{}",
                    approvalState = ToolApprovalState.Approved,
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "second",
                    input = "{}",
                    approvalState = ToolApprovalState.Approved,
                ),
            ),
        )

        val repaired = message.finishPendingTools { tool ->
            tool.copy(
                output = listOf(UIMessagePart.Text("{\"status\":\"cancelled\"}")),
                approvalState = ToolApprovalState.Denied("cancelled"),
            )
        }
        val tools = repaired.parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(2, tools.size)
        assertTrue(tools.all { it.output.size == 1 })
        assertTrue(tools.all { it.output.single() is UIMessagePart.Text })
        assertTrue(tools.all { it.approvalState is ToolApprovalState.Denied })
    }

    @Test
    fun `started approved tool remains termination unknown when no cancellation proof exists`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-unknown",
                    toolName = "side_effect",
                    input = "{}",
                    approvalState = ToolApprovalState.Approved,
                    executionStartedAt = 123L,
                ),
            ),
        )

        val repaired = message.finishPendingTools { tool ->
            assertTrue(tool.isInterruptedAttempt)
            tool.copy(output = listOf(UIMessagePart.Text("{\"status\":\"termination_unknown\"}")))
        }

        val tool = repaired.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("{\"status\":\"termination_unknown\"}", (tool.output.single() as UIMessagePart.Text).text)
    }
}

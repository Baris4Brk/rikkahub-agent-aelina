package me.rerere.rikkahub.subagent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class SubAgentFinalAnswerPolicyTest {
    @Test
    fun `only the latest assistant message is harvested as the child result`() {
        val earlier = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("intermediate reasoning must not leak")),
        )
        val emptyFinal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("   ")),
        )
        val realFinal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Conclusion"), UIMessagePart.Text("Sources")),
        )

        assertEquals("", selectSubAgentFinalText(listOf(earlier, emptyFinal)))
        assertEquals("Conclusion\nSources", selectSubAgentFinalText(listOf(earlier, realFinal)))
    }
}

package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class FinalAnswerRecoveryReasoningTest {
    @Test
    fun `reasoning generated during final answer recovery has a distinct source`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("original reasoning")),
            annotations = listOf(
                UIMessageAnnotation.FinalAnswerRecovery(
                    commandId = "command-1",
                    reason = "missing final answer",
                    status = FinalAnswerRecoveryStatus.STARTED,
                ),
            ),
        )

        val merged = listOf(UIMessage.user("request"), assistant).handleMessageChunk(
            MessageChunk(
                id = "recovery-1",
                model = "same-model",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Reasoning("recovery reasoning")),
                        ),
                        message = null,
                        finishReason = null,
                    ),
                ),
            ),
        )

        val reasoning = merged.last().parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasoning.size)
        assertEquals(ReasoningSource.PROVIDER_NATIVE, reasoning[0].source)
        assertEquals(ReasoningSource.FINAL_ANSWER_RECOVERY, reasoning[1].source)
        assertEquals("recovery reasoning", reasoning[1].reasoning)
    }
}

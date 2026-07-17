package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationCompletionPolicyTest {
    @Test
    fun `message chunk exposes provider length finish as terminal`() {
        val chunk = MessageChunk(
            id = "chunk-1",
            model = "test-model",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = emptyList(),
                    ),
                    message = null,
                    finishReason = "length",
                ),
            ),
        )

        assertEquals(FinishCategory.LENGTH, chunk.resolvedTerminal()?.category)
    }

    @Test
    fun `unknown provider finish cannot complete a partial answer`() {
        val message = UIMessage.assistant("partial answer")

        val outcome = GenerationCompletionPolicy.evaluate(
            message = message,
            terminal = GenerationTerminal.fromProviderReason("unknown"),
        )

        assertTrue(outcome is GenerationOutcome.Interrupted)
    }

    @Test
    fun `unknown provider finish with reasoning only still requests final answer recovery`() {
        val outcome = GenerationCompletionPolicy.evaluate(
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning("unfinished reasoning")),
            ),
            terminal = GenerationTerminal.fromProviderReason("provider_policy_changed"),
        )

        assertTrue(outcome is GenerationOutcome.NeedsFinalAnswer)

        val literalUnknown = GenerationCompletionPolicy.evaluate(
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning("still no answer")),
            ),
            terminal = GenerationTerminal.fromProviderReason("unknown"),
        )
        assertTrue(literalUnknown is GenerationOutcome.NeedsFinalAnswer)
    }

    @Test
    fun `failed or safety terminal with reasoning only still requests final answer recovery`() {
        val reasoningOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("analysis without a visible answer")),
        )

        listOf("failed", "content_filter").forEach { finishReason ->
            assertTrue(
                GenerationCompletionPolicy.evaluate(
                    message = reasoningOnly,
                    terminal = GenerationTerminal.fromProviderReason(finishReason),
                ) is GenerationOutcome.NeedsFinalAnswer,
            )
        }
    }

    @Test
    fun `failed terminal without reasoning or answer remains a hard failure`() {
        val outcome = GenerationCompletionPolicy.evaluate(
            message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
            terminal = GenerationTerminal.fromProviderReason("failed"),
        )

        assertTrue(outcome is GenerationOutcome.Failed)
    }

    @Test
    fun `provider safety and tool protocol finish reasons map conservatively`() {
        val safetyReasons = listOf(
            "content_filter",
            "recitation",
            "blocklist",
            "prohibited_content",
            "spii",
            "image_safety",
            "image_prohibited_content",
        )
        safetyReasons.forEach { reason ->
            assertEquals(FinishCategory.SAFETY, GenerationTerminal.fromProviderReason(reason).category)
        }

        assertEquals(
            FinishCategory.LENGTH,
            GenerationTerminal.fromProviderReason("model_context_window_exceeded").category,
        )
        assertEquals(
            FinishCategory.INCOMPLETE,
            GenerationTerminal.fromProviderReason("pause_turn").category,
        )
        listOf("malformed_function_call", "unexpected_tool_call", "too_many_tool_calls")
            .forEach { reason ->
                assertEquals(FinishCategory.FAILED, GenerationTerminal.fromProviderReason(reason).category)
            }

        listOf("stop", "end_turn", "stop_sequence").forEach { reason ->
            assertEquals(FinishCategory.STOP, GenerationTerminal.fromProviderReason(reason).category)
        }
        listOf("tool_calls", "tool_use", "function_call").forEach { reason ->
            assertEquals(FinishCategory.TOOL_CALLS, GenerationTerminal.fromProviderReason(reason).category)
        }
        listOf("max_tokens", "max_output_tokens").forEach { reason ->
            assertEquals(FinishCategory.LENGTH, GenerationTerminal.fromProviderReason(reason).category)
        }
    }

    @Test
    fun `stream closed without terminal becomes missing transport terminal`() {
        val tracker = GenerationTerminalTracker()

        val terminal = tracker.finish()

        assertEquals(FinishCategory.EOF, terminal.category)
        assertTrue(!terminal.terminalSeen)
    }

    @Test
    fun `missing final answer may recover until ten attempts are exhausted`() {
        val terminal = GenerationTerminal.fromProviderReason("stop")
        val ninthAttempt = FinalAnswerRecoveryPolicy.decide(
            outcome = GenerationOutcome.NeedsFinalAnswer(terminal),
            attempts = 9,
            maxAttempts = 10,
            cancelled = false,
            emergencyStopActive = false,
        )
        val exhausted = FinalAnswerRecoveryPolicy.decide(
            outcome = GenerationOutcome.NeedsFinalAnswer(terminal),
            attempts = 10,
            maxAttempts = 10,
            cancelled = false,
            emergencyStopActive = false,
        )

        assertEquals(FinalAnswerRecoveryDecision.Attempt, ninthAttempt)
        assertEquals(FinalAnswerRecoveryDecision.Skip, exhausted)
    }

    @Test
    fun `recovery policy stops after exactly ten reasoning only attempts`() {
        val outcome = GenerationOutcome.NeedsFinalAnswer(
            GenerationTerminal.fromProviderReason("stop"),
        )
        var attempts = 0
        while (FinalAnswerRecoveryPolicy.decide(
                outcome = outcome,
                attempts = attempts,
                maxAttempts = 10,
                cancelled = false,
                emergencyStopActive = false,
            ) == FinalAnswerRecoveryDecision.Attempt
        ) {
            attempts++
        }

        assertEquals(10, attempts)
    }

    @Test
    fun `reasoning after executed tool without visible answer needs final answer`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("checking running processes"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_process_list",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("[]")),
                ),
                UIMessagePart.Reasoning("The task is complete and the processes are healthy."),
            ),
        )

        val outcome = GenerationCompletionPolicy.evaluate(
            message = message,
            terminal = GenerationTerminal.fromProviderReason("stop"),
        )

        assertTrue(outcome is GenerationOutcome.NeedsFinalAnswer)
    }

    @Test
    fun `answer in the middle followed by reasoning still needs a final answer`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("first analysis"),
                UIMessagePart.Text("an intermediate answer"),
                UIMessagePart.Reasoning("continued thinking without a final answer"),
            ),
        )

        val outcome = GenerationCompletionPolicy.evaluate(
            message = message,
            terminal = GenerationTerminal.fromProviderReason("stop"),
        )

        assertTrue(outcome is GenerationOutcome.NeedsFinalAnswer)
    }

    @Test
    fun `final answer recovery appends to the same assistant message in the same turn`() {
        val user = UIMessage.user("current request")
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("unfinished")),
        )
        val recovered = listOf(user, assistant).handleMessageChunk(
            MessageChunk(
                id = "recovery",
                model = "same-model",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = UIMessage.assistant("final answer"),
                        message = null,
                        finishReason = "stop",
                    ),
                ),
                terminal = GenerationTerminal.fromProviderReason("stop"),
            ),
        )

        assertEquals(2, recovered.size)
        assertEquals(assistant.id, recovered.last().id)
        assertTrue(recovered.last().parts.last() is UIMessagePart.Text)
        assertEquals(
            GenerationOutcome.Completed,
            GenerationCompletionPolicy.evaluate(
                recovered.last(),
                GenerationTerminal.fromProviderReason("stop"),
            ),
        )
    }
}

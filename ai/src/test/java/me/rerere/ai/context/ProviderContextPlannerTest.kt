package me.rerere.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderContextPlannerTest {
    private val estimator = ContextTokenEstimator { message ->
        message.parts.sumOf { part -> part.testTokenSize() }.coerceAtLeast(1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing model metadata requires an explicit trusted context window`() {
        ProviderContextPlanner(estimator).plan(
            messages = listOf(UIMessage.user("hello")),
            declaredContextTokens = null,
        )
    }

    @Test
    fun `one million token window does not summarize before its 750k trigger`() {
        val plan = ProviderContextPlanner(estimator).plan(
            messages = listOf(UIMessage.user("x".repeat(749_999))),
            declaredContextTokens = 1_000_000,
        )

        assertEquals(1_000_000, plan.budget.contextWindowTokens)
        assertEquals(750_000, plan.budget.compressionTriggerTokens)
        assertFalse(plan.compressed)
        assertFalse(plan.requiresSummary)
    }

    @Test
    fun `fallback estimator does not undercount cjk text`() {
        val message = UIMessage.user("思".repeat(4_000))

        assertTrue(ApproximateContextTokenEstimator.estimate(message) >= 4_000)
    }

    @Test
    fun `fallback estimator treats base64 like payload conservatively`() {
        val payload = "A".repeat(8_000) + "=="
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "payload",
                    toolName = "upload",
                    input = "{\"data\":\"$payload\"}",
                ),
            ),
        )

        assertTrue(ApproximateContextTokenEstimator.estimate(message) >= 4_000)
    }

    @Test
    fun `final provider payload validation reserves output budget`() {
        val validation = validateProviderContextPayload(
            messages = listOf(UIMessage.user("长".repeat(9_500))),
            contextWindowTokens = 10_000,
            requestedOutputTokens = 2_000,
        )

        assertFalse(validation.fits)
        assertEquals(8_000, validation.maximumInputTokens)
    }

    @Test
    fun `old completed reasoning is stripped while current tool chain remains lossless`() {
        val oldReasoning = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("private-old-reasoning"),
                UIMessagePart.Text("old visible conclusion"),
                UIMessagePart.Tool(
                    toolCallId = "old-tool",
                    toolName = "old_read",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Reasoning("private-old-tool-reasoning"),
                        UIMessagePart.Text("old tool conclusion"),
                    ),
                ),
            ),
        )
        val currentUser = UIMessage.user("inspect processes")
        val currentAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("current-chain-reasoning"),
                UIMessagePart.Tool(
                    toolCallId = "tool-1",
                    toolName = "process_list",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("processes")),
                ),
            ),
        )
        val original = listOf(UIMessage.system("system"), UIMessage.user("old"), oldReasoning, currentUser, currentAssistant)

        val plan = ProviderContextPlanner(estimator).plan(original, declaredContextTokens = 128_000)
        val providerMessages = plan.assemble()

        assertFalse(providerMessages.any { message ->
            message.parts.filterIsInstance<UIMessagePart.Reasoning>()
                .any { it.reasoning == "private-old-reasoning" }
        })
        assertFalse(providerMessages.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .flatMap { it.output }
            .any { it is UIMessagePart.Reasoning })
        assertTrue(plan.activeToolChain.any { message ->
            message.parts.filterIsInstance<UIMessagePart.Reasoning>()
                .any { it.reasoning == "current-chain-reasoning" }
        })
        assertTrue(plan.activeToolChain.any { it.parts.any { part -> part is UIMessagePart.Tool } })
        assertEquals("private-old-reasoning", (original[2].parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals(2, plan.strippedHistoricalReasoningParts)
    }

    @Test
    fun `oversized history becomes transient summary input while protected messages survive`() {
        val system = UIMessage.system("s".repeat(500))
        val oldTurns = (0 until 18).flatMap { turn ->
            listOf(
                UIMessage.user("u$turn:" + "u".repeat(6_000)),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Reasoning("secret-$turn:" + "r".repeat(3_000)),
                        UIMessagePart.Text("a$turn:" + "a".repeat(6_000)),
                    ),
                ),
            )
        }
        val latestUser = UIMessage.user("latest request")
        val activeTool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("required active reasoning"),
                UIMessagePart.Tool(
                    toolCallId = "active",
                    toolName = "read_status",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("ok")),
                ),
            ),
        )
        val plan = ProviderContextPlanner(
            tokenEstimator = estimator,
            summaryTokenReserve = 2_000,
        ).plan(
            messages = listOf(system) + oldTurns + latestUser + activeTool,
            declaredContextTokens = 128_000,
        )

        assertTrue(plan.compressed)
        assertTrue(plan.oldHistoryForSummary.isNotEmpty())
        assertTrue(plan.requiresSummary)
        assertTrue(plan.systemMessages.contains(system))
        assertEquals(latestUser.id, plan.activeToolChain.first().id)
        assertEquals(activeTool.id, plan.activeToolChain.last().id)
        assertTrue(plan.budget.plannedTokens <= plan.budget.compressionTargetTokens)
        assertEquals(2_000, plan.budget.summaryReservedTokens)
        assertFalse(plan.oldHistoryForSummary.any { message ->
            message.parts.any { it is UIMessagePart.Reasoning }
        })

        val summary = UIMessage.user("temporary summary")
        val assembled = plan.assemble(summary)
        assertEquals(system.id, assembled.first().id)
        assertTrue(assembled.contains(summary))
        assertEquals(latestUser.id, assembled[assembled.lastIndex - 1].id)
        assertEquals(activeTool.id, assembled.last().id)

        val generatedSummary = plan.buildTransientSummary()
        assertEquals(MessageRole.SYSTEM, generatedSummary.role)
        assertFalse(generatedSummary.toText().contains("secret-"))
        assertTrue(generatedSummary.toText().contains("压缩后的较早对话上下文"))
        assertTrue(generatedSummary.toText().contains("不得称呼用户为 USER、user 或 urse"))
        assertTrue(
            generatedSummary.toText().contains("用户：") ||
                generatedSummary.toText().contains("助手："),
        )
        assertFalse(generatedSummary.toText().contains("USER:"))
        assertFalse(generatedSummary.toText().contains("ASSISTANT:"))
        assertTrue(generatedSummary.toText().length <= 8_192)
    }

    @Test
    fun `oversized single summary entry keeps complete chinese role label`() {
        val plan = ProviderContextPlan(
            budget = ProviderContextBudget(
                contextWindowTokens = 1_000,
                compressionTriggerTokens = 750,
                compressionTargetTokens = 600,
                originalTokens = 10_000,
                protectedTokens = 0,
                summaryReservedTokens = 128,
                plannedTokens = 128,
            ),
            compressed = true,
            systemMessages = emptyList(),
            oldHistoryForSummary = listOf(UIMessage.user("x".repeat(10_000))),
            recentOriginalMessages = emptyList(),
            activeToolChain = emptyList(),
            strippedHistoricalReasoningParts = 0,
        )

        val text = plan.buildTransientSummary().toText()

        assertTrue(text.contains("用户：…"))
        assertFalse(text.contains("USER:"))
        assertTrue(ApproximateContextTokenEstimator.estimate(UIMessage.system(text)) <= 128)
    }

    @Test
    fun `protected current turn is never dropped even when it alone exceeds target`() {
        val system = UIMessage.system("system")
        val latestUser = UIMessage.user("x".repeat(90_000))

        val plan = ProviderContextPlanner(estimator).plan(
            messages = listOf(system, UIMessage.user("old"), UIMessage.assistant("old answer"), latestUser),
            declaredContextTokens = 100_000,
        )

        assertTrue(plan.compressed)
        assertFalse(plan.budget.fitsCompressionTarget)
        assertEquals(listOf(latestUser.id), plan.activeToolChain.map { it.id })
        assertTrue(plan.oldHistoryForSummary.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `summary is required before assembling a compressed plan`() {
        val messages = listOf(
            UIMessage.system("system"),
            UIMessage.user("old" + "x".repeat(100)),
            UIMessage.assistant("answer" + "y".repeat(100)),
            UIMessage.user("latest"),
        )
        val plan = ProviderContextPlanner(estimator, summaryTokenReserve = 5).plan(
            messages = messages,
            declaredContextTokens = 100,
        )

        check(plan.requiresSummary)
        plan.assemble()
    }
}

private fun UIMessagePart.testTokenSize(): Int = when (this) {
    is UIMessagePart.Text -> text.length
    is UIMessagePart.Reasoning -> reasoning.length
    is UIMessagePart.Tool -> input.length + output.sumOf { it.testTokenSize() } + 8
    is UIMessagePart.Image -> url.length
    is UIMessagePart.Video -> url.length
    is UIMessagePart.Audio -> url.length
    is UIMessagePart.Document -> url.length + fileName.length
    is UIMessagePart.ToolCall -> arguments.length
    is UIMessagePart.ToolResult -> content.toString().length + arguments.toString().length
    UIMessagePart.Search -> 1
}

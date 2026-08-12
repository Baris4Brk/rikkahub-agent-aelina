package me.rerere.rikkahub.data.ai

import me.rerere.ai.context.ContextTokenEstimator
import me.rerere.ai.context.ProviderContextOverflowException
import me.rerere.ai.context.ProviderContextOverflowKind
import me.rerere.ai.context.ResolvedContextWindowSource
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationProviderContextPreparerTest {
    @Test
    fun `ordinary chat keeps 600k history when stale metadata reports 100k`() {
        val oldUser = UIMessage.user("earlier request")
        val oldAssistant = UIMessage.assistant("earlier answer")
        val currentUser = UIMessage.user("continue")
        val messages = listOf(oldUser, oldAssistant, currentUser)
        val tokenCounts = mapOf(
            oldUser to 300_000,
            oldAssistant to 299_000,
            currentUser to 1_000,
        )

        val prepared = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { message -> tokenCounts.getValue(message) },
        ).prepareOrdinaryChat(
            messages = messages,
            configuredContextWindowTokens = 1_000_000,
            advertisedContextWindowTokens = 100_000,
        )

        assertEquals(messages, prepared.messages)
        assertEquals(600_000, prepared.estimatedRequestTokens)
        assertEquals(1_000_000, prepared.configuredContextWindowTokens)
        assertEquals(1_000_000, prepared.enforcedWindowTokens)
        assertEquals(4_096, prepared.effectiveMaxOutputTokens)
        assertFalse(prepared.summaryUsed)
    }

    @Test
    fun `invalid configured window falls back to one million and enforces it`() {
        val messages = listOf(UIMessage.user("keep manual context"))

        val prepared = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { 600_000 },
        ).prepareOrdinaryChat(
            messages = messages,
            configuredContextWindowTokens = 0,
            advertisedContextWindowTokens = 100_000,
        )

        assertEquals(messages, prepared.messages)
        assertEquals(1_000_000, prepared.configuredContextWindowTokens)
        assertEquals(1_000_000, prepared.enforcedWindowTokens)
        assertFalse(prepared.summaryUsed)
    }

    @Test
    fun `absolute cap clamps a larger user policy while advertised remains advisory`() {
        val preparer = GenerationProviderContextPreparer()

        val resolved = preparer.resolveWindow(
            configuredContextWindowTokens = 10_000_000,
            advertisedContextWindowTokens = 128_000,
        )

        assertEquals(1_000_000, resolved.effectiveTokens)
        assertEquals(128_000, resolved.advertisedTokens)
        assertEquals(ResolvedContextWindowSource.ABSOLUTE_APP_CAP, resolved.source)
    }

    @Test
    fun `trusted local capability may lower but advertised metadata cannot`() {
        val preparer = GenerationProviderContextPreparer()

        val resolved = preparer.resolveWindow(
            configuredContextWindowTokens = 1_000_000,
            trustedContextWindowTokens = 32_000,
            advertisedContextWindowTokens = 8_000,
        )

        assertEquals(32_000, resolved.effectiveTokens)
        assertEquals(ResolvedContextWindowSource.TRUSTED_CAPABILITY, resolved.source)
    }

    @Test
    fun `observable policy drops an old completed turn atomically and preserves current user`() {
        val oldUser = UIMessage.user("old-user")
        val oldAssistant = UIMessage.assistant("old-assistant")
        val currentUser = UIMessage.user("current")
        val sizes = mapOf(oldUser to 3_000, oldAssistant to 3_000, currentUser to 2_000)
        val preparer = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { sizes.getValue(it) },
        )

        val prepared = preparer.prepareOrdinaryChat(
            messages = listOf(oldUser, oldAssistant, currentUser),
            configuredContextWindowTokens = 10_000,
            advertisedContextWindowTokens = null,
            requestedOutputTokens = 2_000,
        ).applyProviderContextProjectionPolicy(
            policy = ORDINARY_GENERATION_CONTEXT_PROJECTION_POLICY,
            stage = "test",
        )

        assertEquals(
            ProviderContextProjectionPolicy.OBSERVABLE_PRUNING,
            ORDINARY_GENERATION_CONTEXT_PROJECTION_POLICY,
        )
        assertEquals(listOf(currentUser), prepared.messages)
        assertEquals(1, prepared.trace.droppedCompletedTurns)
        assertEquals(2, prepared.trace.droppedMessages)
        assertEquals(2_000, prepared.effectiveMaxOutputTokens)
    }

    @Test
    fun `observable projection strips reasoning and whole old turns without mutating stored messages`() {
        val droppedUser = UIMessage.user("drop-user")
        val droppedAssistant = UIMessage.assistant("drop-assistant")
        val retainedUser = UIMessage.user("retained-user")
        val retainedAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("provider-private-reasoning"),
                UIMessagePart.Text("retained-answer"),
            ),
        )
        val currentUser = UIMessage.user("current")
        val storedMessages = listOf(
            droppedUser,
            droppedAssistant,
            retainedUser,
            retainedAssistant,
            currentUser,
        )
        val storedSnapshot = storedMessages.map { message -> message.copy(parts = message.parts.toList()) }
        val prepared = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { message ->
                when (message.id) {
                    droppedUser.id, droppedAssistant.id -> 2_500
                    retainedUser.id, currentUser.id -> 1_000
                    retainedAssistant.id -> if (
                        message.parts.any { it is UIMessagePart.Reasoning }
                    ) 4_000 else 1_000
                    else -> error("unexpected message")
                }
            },
        ).prepareOrdinaryChat(
            messages = storedMessages,
            configuredContextWindowTokens = 10_000,
            advertisedContextWindowTokens = null,
            requestedOutputTokens = 2_000,
        ).applyProviderContextProjectionPolicy(
            policy = ProviderContextProjectionPolicy.OBSERVABLE_PRUNING,
            stage = "test",
        )

        assertEquals(storedSnapshot, storedMessages)
        assertTrue(retainedAssistant.parts.first() is UIMessagePart.Reasoning)
        assertFalse(prepared.messages.contains(droppedUser))
        assertFalse(prepared.messages.contains(droppedAssistant))
        assertTrue(prepared.messages.contains(retainedUser))
        assertTrue(prepared.messages.contains(currentUser))
        val projectedAssistant = prepared.messages.single { it.id == retainedAssistant.id }
        assertFalse(projectedAssistant.parts.any { it is UIMessagePart.Reasoning })
        assertEquals(1, prepared.trace.strippedHistoricalReasoningParts)
        assertEquals(1, prepared.trace.droppedCompletedTurns)
        assertEquals(2, prepared.trace.droppedMessages)
    }

    @Test
    fun `strict lossless policy rejects implicit history loss`() {
        val oldUser = UIMessage.user("old-user")
        val oldAssistant = UIMessage.assistant("old-assistant")
        val currentUser = UIMessage.user("current")
        val sizes = mapOf(oldUser to 3_000, oldAssistant to 3_000, currentUser to 2_000)
        val prepared = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { sizes.getValue(it) },
        ).prepareOrdinaryChat(
            messages = listOf(oldUser, oldAssistant, currentUser),
            configuredContextWindowTokens = 10_000,
            advertisedContextWindowTokens = null,
            requestedOutputTokens = 2_000,
        )

        val failure = runCatching {
            prepared.applyProviderContextProjectionPolicy(
                policy = ProviderContextProjectionPolicy.STRICT_LOSSLESS,
                stage = "test",
            )
        }.exceptionOrNull()

        assertTrue(failure is ProviderContextRequiresExplicitAdjustmentException)
        assertTrue(failure?.message?.contains("dropped_messages=2") == true)
    }

    @Test
    fun `protected current turn overflow fails locally`() {
        val currentUser = UIMessage.user("oversized current request")
        val preparer = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { 9_500 },
        )
        var providerCalls = 0
        var lastAccessWrites = 0

        val failure = runCatching {
            preparer.prepareOrdinaryChat(
                messages = listOf(currentUser),
                configuredContextWindowTokens = 10_000,
                advertisedContextWindowTokens = null,
                requestedOutputTokens = 2_000,
            ).applyProviderContextProjectionPolicy(
                policy = ORDINARY_GENERATION_CONTEXT_PROJECTION_POLICY,
                stage = "test",
            )
            lastAccessWrites++
            providerCalls++
        }.exceptionOrNull()

        assertTrue(failure is ProviderContextOverflowException)
        assertEquals(
            ProviderContextOverflowKind.CURRENT_TURN_TOO_LARGE,
            (failure as ProviderContextOverflowException).overflow.kind,
        )
        assertEquals(0, providerCalls)
        assertEquals(0, lastAccessWrites)
    }

    @Test
    fun `fixed prefix overflow fails before provider call or last access`() {
        val fixedSystem = UIMessage.system("oversized fixed prefix")
        val preparer = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { 9_500 },
        )
        var providerCalls = 0
        var lastAccessWrites = 0

        val failure = runCatching {
            preparer.prepareOrdinaryChat(
                messages = listOf(fixedSystem),
                configuredContextWindowTokens = 10_000,
                advertisedContextWindowTokens = null,
                requestedOutputTokens = 2_000,
            ).applyProviderContextProjectionPolicy(
                policy = ORDINARY_GENERATION_CONTEXT_PROJECTION_POLICY,
                stage = "test",
            )
            lastAccessWrites++
            providerCalls++
        }.exceptionOrNull()

        assertTrue(failure is ProviderContextOverflowException)
        assertEquals(
            ProviderContextOverflowKind.FIXED_PREFIX_TOO_LARGE,
            (failure as ProviderContextOverflowException).overflow.kind,
        )
        assertEquals(0, providerCalls)
        assertEquals(0, lastAccessWrites)
    }

    @Test
    fun `tool schema reserve can clamp output and returned value is the wire value`() {
        val tool = Tool(
            name = "large_tool",
            description = "d".repeat(12_000),
            execute = { emptyList() },
        )
        val prepared = GenerationProviderContextPreparer().prepareOrdinaryChat(
            messages = listOf(UIMessage.user("x".repeat(4_000))),
            configuredContextWindowTokens = 10_000,
            advertisedContextWindowTokens = null,
            requestedOutputTokens = 7_000,
            tools = listOf(tool),
        )

        assertTrue(prepared.trace.toolSchemaTokens > 0)
        assertTrue(prepared.trace.outputClamped)
        assertTrue(prepared.effectiveMaxOutputTokens < 7_000)
        assertTrue(
            prepared.trace.finalMessageTokens + prepared.trace.toolSchemaTokens +
                prepared.trace.safetyMarginTokens + prepared.effectiveMaxOutputTokens <= 10_000,
        )
        assertTrue(
            runCatching {
                prepared.applyProviderContextProjectionPolicy(
                    policy = ProviderContextProjectionPolicy.STRICT_LOSSLESS,
                    stage = "test",
                )
            }.exceptionOrNull() is ProviderContextRequiresExplicitAdjustmentException,
        )
    }

    @Test
    fun `memory compiler allocation is explicitly capped at 1024 tokens`() {
        val preparer = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { 100 },
        )
        val resolved = preparer.resolveWindow(
            configuredContextWindowTokens = 100_000,
            advertisedContextWindowTokens = null,
        )

        val budget = preparer.conservativeMemoryBudget(
            resolvedWindow = resolved,
            requestedOutputTokens = 4_096,
            tools = emptyList(),
            baseMessages = listOf(UIMessage.user("current")),
        )

        assertEquals(1_024, budget)
    }
}

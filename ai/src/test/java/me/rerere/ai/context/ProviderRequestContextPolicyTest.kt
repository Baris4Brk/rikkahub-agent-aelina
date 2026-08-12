package me.rerere.ai.context

import kotlin.coroutines.cancellation.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderCacheIdentity
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestContextPolicyTest {
    @Test
    fun `advertised metadata never changes enforcement`() {
        val resolved = ProviderContextWindowResolver.resolve(
            configuredPolicyTokens = 600_000,
            trustedCapabilityTokens = null,
            advertisedTokens = 100_000,
        )

        assertEquals(600_000, resolved.effectiveTokens)
        assertEquals(100_000, resolved.advertisedTokens)
        assertEquals(ResolvedContextWindowSource.USER_POLICY, resolved.source)
    }

    @Test
    fun `absolute cap and trusted lower capability are deterministic`() {
        val capped = ProviderContextWindowResolver.resolve(10_000_000, null, null)
        val local = ProviderContextWindowResolver.resolve(10_000_000, 32_000, 2_000_000)

        assertEquals(ABSOLUTE_CONTEXT_WINDOW_TOKENS, capped.effectiveTokens)
        assertEquals(ResolvedContextWindowSource.ABSOLUTE_APP_CAP, capped.source)
        assertEquals(32_000, local.effectiveTokens)
        assertEquals(ResolvedContextWindowSource.TRUSTED_CAPABILITY, local.source)
    }

    @Test
    fun `hard gate independently enforces the process absolute cap`() {
        val current = UIMessage.user("current")
        val result = ProviderRequestContextGate(
            ProviderRequestTokenEstimator(ContextTokenEstimator { 800_000 }),
        ).enforce(
            messages = listOf(current),
            contextWindowTokens = 2_000_000,
            requestedOutputTokens = 50_000,
        ) as ProviderContextGateResult.Success

        assertEquals(ABSOLUTE_CONTEXT_WINDOW_TOKENS, result.trace.contextWindowTokens)
        assertEquals(listOf(current), result.messages)
    }

    @Test
    fun `historical reasoning removal is provider-only`() {
        val old = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("private"),
                UIMessagePart.Text("visible"),
            ),
        )
        val current = UIMessage.user("current")
        val estimator = ContextTokenEstimator { message ->
            when (message.id) {
                old.id -> if (message.parts.any { it is UIMessagePart.Reasoning }) 8_000 else 1_000
                else -> 1_000
            }
        }
        val result = ProviderRequestContextGate(
            ProviderRequestTokenEstimator(estimator),
        ).enforce(
            messages = listOf(old, current),
            contextWindowTokens = 10_000,
            requestedOutputTokens = 1_000,
        ) as ProviderContextGateResult.Success

        assertFalse(result.messages.first().parts.any { it is UIMessagePart.Reasoning })
        assertTrue(old.parts.first() is UIMessagePart.Reasoning)
        assertEquals(1, result.trace.strippedHistoricalReasoningParts)
    }

    @Test
    fun `cache identity accepts only opaque sha256 and compiler revision participates in equality`() {
        val digest = "ab".repeat(32)
        val first = ProviderCacheIdentity.fromOpaqueDigest(digest, "memory-v1")
        val same = ProviderCacheIdentity.fromOpaqueDigest(digest.uppercase(), "memory-v1")
        val revised = ProviderCacheIdentity.fromOpaqueDigest(digest, "memory-v2")

        assertEquals(first, same)
        assertNotEquals(first, revised)
        assertEquals("abababababab", first.redactedPrefix())
        assertFalse(first.toString().contains(digest))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cache identity rejects a raw uuid`() {
        ProviderCacheIdentity.fromOpaqueDigest(
            opaqueSha256 = "123e4567-e89b-12d3-a456-426614174000",
            compilerRevision = "memory-v1",
        )
    }

    @Test(expected = CancellationException::class)
    fun `schema token estimator propagates cancellation`() {
        ProviderRequestTokenEstimator().estimateToolSchemaTokens(
            listOf(
                Tool(
                    name = "cancelled_schema",
                    description = "",
                    parameters = { throw CancellationException("cancel schema") },
                    execute = { emptyList() },
                ),
            ),
        )
    }

    @Test
    fun `schema token estimator keeps conservative fallback for ordinary failures`() {
        val tokens = ProviderRequestTokenEstimator().estimateToolSchemaTokens(
            listOf(
                Tool(
                    name = "broken_schema",
                    description = "",
                    parameters = { error("schema unavailable") },
                    execute = { emptyList() },
                ),
            ),
        )

        assertEquals(256, tokens)
    }
}

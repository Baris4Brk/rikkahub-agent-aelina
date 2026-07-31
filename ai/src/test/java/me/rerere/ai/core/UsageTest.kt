package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    @Test
    fun `merge carries cost from the incoming chunk`() {
        val merged = (null as TokenUsage?).merge(
            TokenUsage(promptTokens = 10, completionTokens = 5, cost = 0.0012)
        )
        assertEquals(0.0012, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge keeps prior cost when the incoming chunk has none`() {
        // Streaming: an early chunk reported cost; a later token-only delta must not wipe it.
        val merged = TokenUsage(cost = 0.0034).merge(TokenUsage(completionTokens = 3))
        assertEquals(0.0034, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge prefers the newest cost`() {
        val merged = TokenUsage(cost = 0.001).merge(TokenUsage(cost = 0.002))
        assertEquals(0.002, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge leaves cost null when neither side reports it`() {
        val merged = TokenUsage(promptTokens = 1).merge(TokenUsage(completionTokens = 1))
        assertNull(merged.cost)
    }

    @Test
    fun `merge resets stale cache when a new prompt snapshot reports no cache`() {
        val merged = TokenUsage(
            promptTokens = 336_512,
            completionTokens = 100,
            cachedTokens = 336_128,
        ).merge(
            TokenUsage(promptTokens = 25_399, completionTokens = 225, cachedTokens = 0)
        )

        assertEquals(25_399, merged.promptTokens)
        assertEquals(0, merged.cachedTokens)
        assertEquals(25_624, merged.totalTokens)
    }

    @Test
    fun `accumulate sums complete provider calls without violating cache invariant`() {
        val accumulated = TokenUsage(
            promptTokens = 336_512,
            completionTokens = 100,
            cachedTokens = 336_128,
            cost = 0.01,
        ).accumulate(
            TokenUsage(
                promptTokens = 25_399,
                completionTokens = 225,
                cachedTokens = 0,
                cost = 0.02,
            )
        )

        assertEquals(361_911, accumulated.promptTokens)
        assertEquals(325, accumulated.completionTokens)
        assertEquals(336_128, accumulated.cachedTokens)
        assertEquals(362_236, accumulated.totalTokens)
        assertEquals(0.03, accumulated.cost!!, 1e-9)
    }

    @Test
    fun `normalized clamps provider cache to prompt`() {
        val normalized = TokenUsage(
            promptTokens = 10,
            completionTokens = 2,
            cachedTokens = 99,
        ).normalized()

        assertEquals(10, normalized.cachedTokens)
        assertEquals(12, normalized.totalTokens)
    }
}

package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [formatCost] — the pure helper that renders a USD generation cost.
 * Guards the regression where a positive sub-1e-6 cost rounded to "$0" and read as free.
 */
class FormatCostTest {

    @Test
    fun `tiny positive cost never renders as exactly zero`() {
        // 1e-7 rounds to 0 at 6dp; it must show the floor form, not "$0".
        assertEquals("<$0.000001", formatCost(0.0000001))
    }

    @Test
    fun `normal cost keeps its decimals and trims trailing zeros`() {
        assertEquals("$0.0234", formatCost(0.0234))
    }

    @Test
    fun `cost at the 1e-6 boundary still shows a value`() {
        assertEquals("$0.000001", formatCost(0.000001))
    }

    @Test
    fun `cache rate explains the inspected near complete cache hit`() {
        assertEquals("99.87%", formatCacheRate(promptTokens = 150_978, cachedTokens = 150_784))
    }

    @Test
    fun `cache rate clamps invalid provider counts`() {
        assertEquals("100.00%", formatCacheRate(promptTokens = 100, cachedTokens = 120))
        assertEquals("0%", formatCacheRate(promptTokens = 0, cachedTokens = 0))
    }
}

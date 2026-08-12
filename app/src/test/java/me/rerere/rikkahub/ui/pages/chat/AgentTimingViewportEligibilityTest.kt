package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTimingViewportEligibilityTest {

    @Test
    fun `content-ready viewport eligibility is fail-closed`() {
        assertTrue(
            freezeAgentTimingViewportEligibility(
                existing = null,
                isForeground = true,
                isTargetSelected = true,
                isTargetNodeVisible = true,
            )
        )
        assertFalse(
            freezeAgentTimingViewportEligibility(
                existing = null,
                isForeground = false,
                isTargetSelected = true,
                isTargetNodeVisible = true,
            )
        )
        assertFalse(
            freezeAgentTimingViewportEligibility(
                existing = null,
                isForeground = true,
                isTargetSelected = false,
                isTargetNodeVisible = true,
            )
        )
        assertFalse(
            freezeAgentTimingViewportEligibility(
                existing = null,
                isForeground = true,
                isTargetSelected = true,
                isTargetNodeVisible = false,
            )
        )
    }

    @Test
    fun `later auto-scroll cannot upgrade a frozen offscreen verdict`() {
        assertFalse(
            freezeAgentTimingViewportEligibility(
                existing = false,
                isForeground = true,
                isTargetSelected = true,
                isTargetNodeVisible = true,
            )
        )
    }

    @Test
    fun `later layout changes do not rewrite an eligible ready-time verdict`() {
        assertTrue(
            freezeAgentTimingViewportEligibility(
                existing = true,
                isForeground = false,
                isTargetSelected = false,
                isTargetNodeVisible = false,
            )
        )
    }
}

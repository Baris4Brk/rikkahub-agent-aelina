package me.rerere.rikkahub.ui.pages.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryObserverDiagnosticsPresentationTest {
    @Test
    fun `observer diagnostics tab is developer only and does not reorder normal tabs`() {
        val normal = memoryCenterTabs(developerMode = false)
        val developer = memoryCenterTabs(developerMode = true)

        assertEquals(
            listOf(
                MemoryCenterTab.LIBRARY,
                MemoryCenterTab.DREAM,
                MemoryCenterTab.REVIEW,
                MemoryCenterTab.SETTINGS,
            ),
            normal,
        )
        assertTrue(MemoryCenterTab.DREAM in normal)
        assertFalse(MemoryCenterTab.OBSERVER in normal)
        assertTrue(MemoryCenterTab.OBSERVER in developer)
        assertEquals(MemoryCenterTab.OBSERVER, developer.last())
    }
}

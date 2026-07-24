package me.rerere.rikkahub.quickcapture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCaptureFileCleanerTest {
    @Test
    fun `only stale unreferenced quick capture uploads are eligible for deletion`() {
        val now = 1_000_000L

        assertTrue(
            shouldDeleteQuickCaptureFile(
                displayName = "quick-capture-a.png",
                createdAtMs = now - QUICK_CAPTURE_ORPHAN_AGE_MS,
                nowMs = now,
                referenced = false,
            ),
        )
        assertFalse(shouldDeleteQuickCaptureFile("quick-capture-a.png", now - 1, now, false))
        assertFalse(shouldDeleteQuickCaptureFile("quick-capture-a.png", 0, now, true))
        assertFalse(shouldDeleteQuickCaptureFile("ordinary-image.png", 0, now, false))
    }
}

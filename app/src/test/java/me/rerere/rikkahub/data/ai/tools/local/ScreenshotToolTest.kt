package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotToolTest {

    @Test
    fun `take_screenshot returns service-not-active when offline`() {
        val tool = takeScreenshotTool(NULL_CONTEXT)
        val result = execTool(tool, """{}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    @Test
    fun `take_screenshot rejects bare nonprimary display id`() {
        val tool = takeScreenshotTool(NULL_CONTEXT)
        val result = execTool(tool, """{"display_id":1}""")
        // A non-primary display needs an owned display_session_id and must never fall back.
        assertTrue(result.contains("display_session_required"))
    }
}

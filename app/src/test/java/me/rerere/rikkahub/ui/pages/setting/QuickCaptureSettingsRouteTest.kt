package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickCaptureSettingsRouteTest {
    @Test
    fun `settings home item and notification destination use the quick capture screen`() {
        val item = QuickCaptureSettingsRoute.settingsHomeItem

        assertEquals(Screen.SettingQuickCapture, QuickCaptureSettingsRoute.screen)
        assertEquals(QuickCaptureSettingsRoute.screen, item.destination)
        assertEquals(R.string.quick_capture_title, item.titleRes)
        assertEquals(R.string.quick_capture_summary, item.summaryRes)
    }
}

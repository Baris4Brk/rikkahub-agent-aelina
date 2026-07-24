package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.runtime.Composable
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/** Shared navigation contract for the settings-home item, RouteActivity, and notification link. */
internal object QuickCaptureSettingsRoute {
    val screen: Screen = Screen.SettingQuickCapture

    val settingsHomeItem = SettingsHomeNavigationItem(
        destination = screen,
        titleRes = R.string.quick_capture_title,
        summaryRes = R.string.quick_capture_summary,
    )

    @Composable
    fun Content() {
        SettingQuickCapturePage()
    }
}

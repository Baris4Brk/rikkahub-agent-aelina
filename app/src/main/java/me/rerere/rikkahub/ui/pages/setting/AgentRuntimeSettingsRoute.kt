package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

internal data class SettingsHomeNavigationItem(
    val destination: Screen,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
)

/** Shared contract for the settings-home action and its Navigation 3 destination. */
internal object AgentRuntimeSettingsRoute {
    val screen: Screen = Screen.SettingAgentRuntime

    val settingsHomeItem = SettingsHomeNavigationItem(
        destination = screen,
        titleRes = R.string.agent_runtime_title,
        summaryRes = R.string.agent_runtime_summary,
    )

    @Composable
    fun Content() {
        SettingAgentRuntimePage()
    }
}

package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRuntimeSettingsRouteTest {
    @Test
    fun `settings home item targets the registered agent runtime screen`() {
        val homeItem = AgentRuntimeSettingsRoute.settingsHomeItem

        assertEquals(Screen.SettingAgentRuntime, AgentRuntimeSettingsRoute.screen)
        assertEquals(AgentRuntimeSettingsRoute.screen, homeItem.destination)
        assertEquals(R.string.agent_runtime_title, homeItem.titleRes)
        assertEquals(R.string.agent_runtime_summary, homeItem.summaryRes)
    }
}

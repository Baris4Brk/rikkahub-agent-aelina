package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginToolApprovalPolicyTest {
    private val pluginToolName = "plugin__0123456789ab__read_status"

    @Test
    fun `third party plugin tools always require a fresh approval`() {
        assertTrue(ToolApprovalDefaults.requiresApproval(pluginToolName))
        assertFalse(ToolApprovalDefaults.allowsAlwaysAllow(pluginToolName))
    }
}

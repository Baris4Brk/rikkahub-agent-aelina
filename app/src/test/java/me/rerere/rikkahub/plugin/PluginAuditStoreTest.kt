package me.rerere.rikkahub.plugin

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginAuditStoreTest {
    @Test
    fun `audit is bounded and stores no invocation payload`() {
        val store = PluginAuditStore(maxEntries = 2, nowMs = { 123L })
        repeat(3) { index ->
            store.record(
                PluginInvocation(
                    pluginId = "sample-plugin",
                    handler = "readState",
                    kind = PluginInvocationKind.TOOL,
                    inputJson = "{\"password\":\"secret-$index\"}",
                    assistantEnabledPluginIds = setOf("sample-plugin"),
                    assistantId = "assistant-1",
                    conversationId = "conversation-1",
                    runId = "run-1",
                    origin = ToolCallOrigin.LocalChat,
                ),
                PluginRuntimeResponse(
                    ok = index != 1,
                    invocationId = "call-$index",
                    errorCode = if (index == 1) "plugin_failed" else null,
                ),
            )
        }

        assertEquals(2, store.events.value.size)
        val rendered = store.events.value.toString()
        assertFalse(rendered.contains("password"))
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("sample-plugin"))
    }
}

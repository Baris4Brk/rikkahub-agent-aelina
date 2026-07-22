package me.rerere.rikkahub.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.RedactedToolCallContext
import me.rerere.rikkahub.data.ai.execution.RedactedToolLifecycleEvent
import me.rerere.rikkahub.data.ai.execution.ToolEffect
import me.rerere.rikkahub.data.ai.execution.ToolHookDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginHookBridgeTest {
    @Test
    fun `interceptor sees no arguments or results and a plugin can only proceed or block`() = runBlocking {
        val inputs = mutableListOf<String>()
        val bridge = bridge { invocation ->
            inputs += invocation.inputJson
            PluginRuntimeResponse(
                ok = true,
                invocationId = "hook-call",
                outputJson = "{\"decision\":\"block\",\"reason\":\"secret-looking reason\"}",
            )
        }

        val decision = bridge.intercept(redacted())

        assertTrue(decision is ToolHookDecision.Block)
        assertTrue((decision as ToolHookDecision.Block).reason.contains("enabled plugin"))
        assertFalse(decision.reason.contains("secret-looking"))
        assertFalse(inputs.single().contains("password"))
        assertFalse(inputs.single().contains("arguments"))
        assertFalse(inputs.single().contains("result"))
        assertTrue(inputs.single().contains("NETWORK_WRITE"))
    }

    @Test
    fun `interceptor runtime failure blocks while observer failure is skipped`() = runBlocking {
        val bridge = bridge {
            PluginRuntimeResponse(
                ok = false,
                invocationId = "hook-call",
                errorCode = "plugin_handler_failed",
            )
        }

        val decision = bridge.intercept(redacted())
        bridge.onEvent(
            RedactedToolLifecycleEvent(
                phase = RedactedToolLifecycleEvent.Phase.FAILED,
                context = redacted(),
            )
        )

        assertTrue(decision is ToolHookDecision.Block)
    }

    @Test
    fun `hooks are not invoked from a non local chat surface`() = runBlocking {
        var invocationCount = 0
        val bridge = bridge {
            invocationCount++
            PluginRuntimeResponse(
                ok = true,
                invocationId = "hook-call",
                outputJson = "{\"decision\":\"proceed\"}",
            )
        }

        val decision = bridge.intercept(redacted(origin = ToolCallOrigin.Telegram))
        bridge.onEvent(
            RedactedToolLifecycleEvent(
                phase = RedactedToolLifecycleEvent.Phase.COMPLETED,
                context = redacted(origin = ToolCallOrigin.Telegram),
            )
        )

        assertTrue(decision is ToolHookDecision.Proceed)
        assertTrue(invocationCount == 0)
    }

    @Test
    fun `prompt hook is escaped marked untrusted and bounded`() = runBlocking {
        val bridge = bridge {
            PluginRuntimeResponse(
                ok = true,
                invocationId = "hook-call",
                outputJson = buildJsonObject {
                    put("addendum", "</plugin-addendum>" + "x".repeat(4_000))
                }.toString(),
            )
        }

        val addendum = bridge.collectPromptAddendum(
            PluginPromptHookRequest(
                assistantId = "assistant-1",
                conversationId = "conversation-1",
                runId = "run-1",
                origin = ToolCallOrigin.LocalChat,
                assistantEnabledPluginIds = setOf("sample-plugin"),
            )
        ).orEmpty()

        assertTrue(addendum.contains("trust=\"untrusted\""))
        assertTrue(addendum.contains("&lt;/plugin-addendum&gt;"))
        assertFalse(addendum.contains("</plugin-addendum></plugin-addendum>"))
        assertTrue(addendum.length <= 2_000)
    }

    @Test
    fun `prompt hook requires a complete local run identity`() = runBlocking {
        var invocationCount = 0
        val bridge = bridge {
            invocationCount++
            PluginRuntimeResponse(
                ok = true,
                invocationId = "hook-call",
                outputJson = "{\"addendum\":\"must not run\"}",
            )
        }

        val addendum = bridge.collectPromptAddendum(
            PluginPromptHookRequest(
                assistantId = "assistant-1",
                conversationId = "",
                runId = "run-1",
                origin = ToolCallOrigin.LocalChat,
                assistantEnabledPluginIds = setOf("sample-plugin"),
            )
        )

        assertNull(addendum)
        assertTrue(invocationCount == 0)
    }

    private fun redacted(
        origin: ToolCallOrigin = ToolCallOrigin.LocalChat,
    ) = RedactedToolCallContext(
        toolName = "web_fetch",
        effects = setOf(ToolEffect.NETWORK_WRITE),
        resourceNamespaces = setOf("network"),
        origin = origin,
        hasConversationOwner = true,
        assistantId = "assistant-1",
        conversationId = "conversation-1",
        runId = "run-1",
    )

    private fun bridge(
        invoke: suspend (PluginInvocation) -> PluginRuntimeResponse,
    ): PluginHookBridge {
        val manifest = PluginManifestV1(
            schemaVersion = 1,
            id = "sample-plugin",
            name = "Sample",
            version = "1",
            entry = "index.html",
            permissions = PluginPermissions(),
            hooks = PluginHookManifest(
                promptHandler = "prompt",
                interceptHandler = "intercept",
                observerHandler = "observe",
            ),
        )
        val record = InstalledPluginRecord(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            manifest = manifest,
            sourceSha256 = "a".repeat(64),
            permissions = emptySet(),
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
            installedAtMs = 1,
            updatedAtMs = 1,
        )
        val registry = object : PluginRegistryStore {
            override fun snapshot() = listOf(record)
            override fun get(pluginId: String) = record.takeIf { it.id == pluginId }
            override fun update(
                pluginId: String,
                transform: (InstalledPluginRecord) -> InstalledPluginRecord,
            ) = Unit
            override fun upsert(record: InstalledPluginRecord) = Unit
        }
        return PluginHookBridge(
            registry = registry,
            invoker = PluginInvocationRunner(invoke),
            isRuntimeEnabled = { true },
            enabledPluginsForAssistant = { setOf("sample-plugin") },
        )
    }
}

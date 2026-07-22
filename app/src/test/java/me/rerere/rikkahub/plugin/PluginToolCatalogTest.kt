package me.rerere.rikkahub.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginToolCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    @After
    fun tearDown() {
        job.cancel()
    }

    @Test
    fun `only reviewed globally enabled assistant-scoped local plugins are exposed`() {
        val registry = registry(reviewStatus = PluginReviewStatus.APPROVED, enabled = true)
        val catalog = PluginToolCatalog(
            registry = registry,
            invoker = PluginInvocationRunner { error("execution_not_expected") },
            isRuntimeEnabled = { true },
            executionScope = scope,
        )

        val exposed = catalog.registrations(request(enabledIds = setOf("sample-plugin")))
        val wrongAssistant = catalog.registrations(request(enabledIds = emptySet()))
        val remote = catalog.registrations(
            request(enabledIds = setOf("sample-plugin"), origin = ToolCallOrigin.Telegram)
        )

        assertEquals(1, exposed.size)
        assertTrue(exposed.single().definition.name.matches(
            Regex("plugin__[0-9a-f]{12}__read_state")
        ))
        assertTrue(exposed.single().definition.needsApproval(buildJsonObject {}))
        val schema = exposed.single().definition.parameters() as InputSchema.Obj
        assertTrue("query" in schema.properties)
        assertEquals(listOf("query"), schema.required)
        assertTrue(wrongAssistant.isEmpty())
        assertTrue(remote.isEmpty())
    }

    @Test
    fun `plugin output is explicitly wrapped as untrusted content`() = runBlocking {
        val registry = registry(reviewStatus = PluginReviewStatus.APPROVED, enabled = true)
        val catalog = PluginToolCatalog(
            registry = registry,
            invoker = PluginInvocationRunner { invocation ->
                assertFalse(invocation.stateProjection.contains("chat_history"))
                PluginRuntimeResponse(
                    ok = true,
                    invocationId = "plugin-call",
                    outputJson = "{\"value\":7}",
                )
            },
            isRuntimeEnabled = { true },
            executionScope = scope,
        )
        val tool = catalog.registrations(
            request(enabledIds = setOf("sample-plugin"))
        ).single().definition

        val text = tool.execute(buildJsonObject { put("query", "safe") })
            .single().let { it as me.rerere.ai.ui.UIMessagePart.Text }.text

        assertTrue(text.contains("untrusted_plugin_output"))
        assertTrue(text.contains("\"value\":7"))
        assertFalse(text.contains("sample-plugin"))
    }

    private fun request(
        enabledIds: Set<String>,
        origin: ToolCallOrigin = ToolCallOrigin.LocalChat,
    ) = PluginToolSurfaceRequest(
        assistantId = "assistant-1",
        conversationId = "conversation-1",
        runId = "run-1",
        origin = origin,
        assistantEnabledPluginIds = enabledIds,
    )

    private fun registry(
        reviewStatus: PluginReviewStatus,
        enabled: Boolean,
    ): PluginRegistryStore {
        val root = temporaryFolder.newFolder("registry-${System.nanoTime()}")
        val marker = temporaryFolder.newFolder("marker-${System.nanoTime()}")
        return FilePluginRegistryStore(root, marker).also { registry ->
            val manifest = PluginManifestV1(
                schemaVersion = 1,
                id = "sample-plugin",
                name = "Sample",
                version = "1",
                entry = "index.html",
                permissions = PluginPermissions(stateRead = true),
                tools = listOf(
                    PluginToolManifest(
                        slug = "read_state",
                        description = "Read bounded state",
                        handler = "readState",
                        inputSchema = buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("query", buildJsonObject { put("type", "string") })
                            })
                            put("required", kotlinx.serialization.json.buildJsonArray {
                                add(kotlinx.serialization.json.JsonPrimitive("query"))
                            })
                        },
                    )
                ),
            )
            registry.upsert(
                InstalledPluginRecord(
                    id = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    manifest = manifest,
                    sourceSha256 = "a".repeat(64),
                    permissions = setOf("state:read"),
                    enabled = enabled,
                    reviewStatus = reviewStatus,
                    installedAtMs = 1,
                    updatedAtMs = 1,
                )
            )
        }
    }
}

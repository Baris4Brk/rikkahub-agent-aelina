package me.rerere.rikkahub.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginRuntimeCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `runtime and assistant enablement are both required`() = runBlocking {
        val registry = installedRegistry()
        registry.update("sample-plugin") { it.copy(
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
        ) }
        val transport = FakeTransport()
        val disabledRuntime = coordinator(registry, transport, runtimeEnabled = false)

        val globalOff = disabledRuntime.invoke(invocation(enabledPluginIds = setOf("sample-plugin")))
        val assistantOff = coordinator(registry, transport, runtimeEnabled = true)
            .invoke(invocation(enabledPluginIds = emptySet()))

        assertEquals("plugin_runtime_disabled", globalOff.errorCode)
        assertEquals("plugin_not_enabled_for_assistant", assistantOff.errorCode)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `plugin invocations fail closed outside a complete local chat run`() = runBlocking {
        val registry = installedRegistry()
        registry.update("sample-plugin") { it.copy(
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
        ) }
        val transport = FakeTransport(outputJson = "{\"value\":1}")
        val coordinator = coordinator(registry, transport, runtimeEnabled = true)

        val remote = coordinator.invoke(
            invocation(enabledPluginIds = setOf("sample-plugin")).copy(
                origin = ToolCallOrigin.Telegram,
            ),
        )
        val missingRunId = coordinator.invoke(
            invocation(enabledPluginIds = setOf("sample-plugin")).copy(runId = ""),
        )

        assertEquals("plugin_invocation_surface_not_allowed", remote.errorCode)
        assertEquals("plugin_invocation_surface_not_allowed", missingRunId.errorCode)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `three consecutive failures within ten minutes quarantine plugin`() = runBlocking {
        var now = 1_000L
        val registry = installedRegistry()
        registry.update("sample-plugin") { it.copy(
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
        ) }
        val transport = FakeTransport(errorCode = "plugin_handler_failed")
        val coordinator = PluginRuntimeCoordinator(
            registry = registry,
            transport = transport,
            hostRpcGateway = gateway(),
            isRuntimeEnabled = { true },
            nowMs = { now },
        )

        repeat(3) {
            coordinator.invoke(invocation(enabledPluginIds = setOf("sample-plugin")))
            now += 1_000
        }

        val record = registry.get("sample-plugin")!!
        assertFalse(record.enabled)
        assertEquals(PluginReviewStatus.QUARANTINED, record.reviewStatus)
        assertEquals(3, record.failureTimestampsMs.size)
    }

    @Test
    fun `successful invocation clears consecutive failure streak`() = runBlocking {
        val registry = installedRegistry()
        registry.update("sample-plugin") { it.copy(
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
            failureTimestampsMs = listOf(1, 2),
        ) }
        val transport = FakeTransport(outputJson = "{\"value\":1}")
        val coordinator = coordinator(registry, transport, runtimeEnabled = true)

        val result = coordinator.invoke(invocation(enabledPluginIds = setOf("sample-plugin")))

        assertTrue(result.ok)
        assertEquals("{\"value\":1}", result.outputJson)
        assertTrue(registry.get("sample-plugin")!!.failureTimestampsMs.isEmpty())
    }

    @Test
    fun `cancelling a coordinator invocation requests transport cancellation`() = runBlocking {
        val registry = installedRegistry()
        registry.update("sample-plugin") { it.copy(
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
        ) }
        var startedId: String? = null
        var cancelledId: String? = null
        val transport = object : PluginRuntimeTransport {
            override suspend fun invoke(request: PluginRuntimeRequest): PluginRuntimeResponse {
                startedId = request.invocationId
                awaitCancellation()
            }

            override suspend fun cancel(invocationId: String) {
                cancelledId = invocationId
            }
        }
        val coordinator = coordinator(registry, transport, runtimeEnabled = true)
        val call = async {
            coordinator.invoke(invocation(enabledPluginIds = setOf("sample-plugin")))
        }
        while (startedId == null) yield()

        call.cancel(CancellationException("test stop"))
        runCatching { call.await() }

        assertEquals(startedId, cancelledId)
    }

    private fun installedRegistry(): FilePluginRegistryStore {
        val root = temporaryFolder.newFolder("plugins-${System.nanoTime()}")
        val marker = temporaryFolder.newFolder("marker-${System.nanoTime()}")
        val registry = FilePluginRegistryStore(root, marker)
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
                    description = "Read state",
                    handler = "readState",
                    inputSchema = buildJsonObject { put("type", "object") },
                )
            ),
        )
        registry.upsert(
            InstalledPluginRecord(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                manifest = manifest,
                sourceSha256 = "d".repeat(64),
                permissions = setOf("state:read"),
                installedAtMs = 1,
                updatedAtMs = 1,
            )
        )
        return registry
    }

    private fun coordinator(
        registry: PluginRegistryStore,
        transport: PluginRuntimeTransport,
        runtimeEnabled: Boolean,
    ) = PluginRuntimeCoordinator(
        registry = registry,
        transport = transport,
        hostRpcGateway = gateway(),
        isRuntimeEnabled = { runtimeEnabled },
    )

    private fun gateway() = PluginHostRpcGateway(
        storageRoot = temporaryFolder.root,
        networkGateway = PluginNetworkGateway {
            Result.failure(IllegalStateException("network_not_expected"))
        },
    )

    private fun invocation(enabledPluginIds: Set<String>) = PluginInvocation(
        pluginId = "sample-plugin",
        handler = "readState",
        kind = PluginInvocationKind.TOOL,
        inputJson = "{}",
        assistantEnabledPluginIds = enabledPluginIds,
        stateProjection = "{\"foreground_app\":\"Browser\"}",
        assistantId = "assistant-1",
        conversationId = "conversation-1",
        runId = "run-1",
        origin = ToolCallOrigin.LocalChat,
    )

    private class FakeTransport(
        private val errorCode: String? = null,
        private val outputJson: String? = null,
    ) : PluginRuntimeTransport {
        var calls = 0

        override suspend fun invoke(request: PluginRuntimeRequest): PluginRuntimeResponse {
            calls++
            return PluginRuntimeResponse(
                ok = errorCode == null,
                invocationId = request.invocationId,
                outputJson = outputJson,
                errorCode = errorCode,
            )
        }

        override suspend fun cancel(invocationId: String) = Unit
    }
}

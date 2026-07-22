package me.rerere.rikkahub.plugin

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginHostRpcGatewayTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `state read returns only the bounded projection registered for this invocation`() {
        val gateway = gateway()
        gateway.register(context(permissions = setOf("state:read")))

        val response = gateway.handleRpc(request("state.read"))

        assertTrue(response.contains("foreground_app"))
        assertFalse(response.contains("raw chat"))
        assertFalse(response.contains("api-key"))
    }

    @Test
    fun `wrong token and undeclared permission both fail closed`() {
        val gateway = gateway()
        gateway.register(context(permissions = emptySet()))

        val wrongToken = gateway.handleRpc(request("state.read", token = "f".repeat(64)))
        val undeclared = gateway.handleRpc(request("state.read"))

        assertTrue(wrongToken.contains("plugin_rpc_context_mismatch"))
        assertTrue(undeclared.contains("plugin_permission_denied"))
    }

    @Test
    fun `scoped storage rejects traversal and enforces plugin directory`() {
        val gateway = gateway()
        gateway.register(context(permissions = setOf("storage:write", "storage:read")))

        val traversal = gateway.handleRpc(
            request(
                "storage.write",
                buildJsonObject { put("path", "../escape"); put("content", "bad") },
            )
        )
        val write = gateway.handleRpc(
            request(
                "storage.write",
                buildJsonObject { put("path", "notes/value.txt"); put("content", "safe") },
            )
        )
        val read = gateway.handleRpc(
            request("storage.read", buildJsonObject { put("path", "notes/value.txt") })
        )

        assertTrue(traversal.contains("plugin_storage_path_invalid"))
        assertTrue(write.contains("\"ok\":true"))
        assertTrue(read.contains("safe"))
        assertFalse(File(temporaryFolder.root, "escape").exists())
    }

    @Test
    fun `network RPC receives only exact allowlisted https hosts`() {
        val seen = mutableListOf<String>()
        val gateway = gateway(
            network = PluginNetworkGateway { url ->
                seen += url
                Result.success(PluginNetworkResponse(200, "text/plain", "ok"))
            }
        )
        gateway.register(context(permissions = setOf("network:https://api.example.com")))

        val http = gateway.handleRpc(
            request("network.fetch", buildJsonObject { put("url", "http://api.example.com/x") })
        )
        val sibling = gateway.handleRpc(
            request("network.fetch", buildJsonObject { put("url", "https://evil.example.com/x") })
        )
        val allowed = gateway.handleRpc(
            request("network.fetch", buildJsonObject { put("url", "https://api.example.com/x") })
        )

        assertTrue(http.contains("plugin_network_url_blocked"))
        assertTrue(sibling.contains("plugin_network_host_blocked"))
        assertTrue(allowed.contains("\"status\":200"))
        assertEquals(listOf("https://api.example.com/x"), seen)
    }

    @Test
    fun `scoped storage refuses the 257th file but permits replacing an existing file`() {
        val pluginRoot = File(temporaryFolder.root, "sample-plugin").apply { mkdirs() }
        repeat(256) { index -> File(pluginRoot, "f$index.txt").writeText("x") }
        val gateway = gateway()
        gateway.register(context(permissions = setOf("storage:write")))

        val overflow = gateway.handleRpc(
            request(
                "storage.write",
                buildJsonObject { put("path", "overflow.txt"); put("content", "x") },
            )
        )
        val replace = gateway.handleRpc(
            request(
                "storage.write",
                buildJsonObject { put("path", "f0.txt"); put("content", "updated") },
            )
        )

        assertTrue(overflow.contains("plugin_storage_file_limit_exceeded"))
        assertTrue(replace.contains("\"ok\":true"))
    }

    @Test
    fun `oversized RPC response remains valid JSON and fails closed`() {
        val gateway = gateway(
            network = PluginNetworkGateway {
                Result.success(
                    PluginNetworkResponse(
                        status = 200,
                        contentType = "text/plain",
                        body = "x".repeat(256 * 1024),
                    ),
                )
            },
        )
        gateway.register(context(permissions = setOf("network:https://api.example.com")))

        val response = gateway.handleRpc(
            request("network.fetch", buildJsonObject { put("url", "https://api.example.com/x") }),
        )

        val parsed = Json.parseToJsonElement(response).jsonObject
        assertFalse(parsed["ok"]!!.jsonPrimitive.boolean)
        assertEquals("plugin_rpc_response_too_large", parsed["error"]!!.jsonPrimitive.content)
    }

    private fun gateway(
        network: PluginNetworkGateway = PluginNetworkGateway {
            Result.failure(IllegalStateException("network_not_expected"))
        },
    ) = PluginHostRpcGateway(
        storageRoot = temporaryFolder.root,
        networkGateway = network,
    )

    private fun context(permissions: Set<String>) = PluginHostInvocationContext(
        invocationId = INVOCATION_ID,
        rpcToken = TOKEN,
        pluginId = "sample-plugin",
        permissions = permissions,
        stateProjection = "{\"foreground_app\":\"Browser\"}",
    )

    private fun request(
        method: String,
        params: kotlinx.serialization.json.JsonObject = buildJsonObject {},
        token: String = TOKEN,
    ) = buildJsonObject {
        put("protocolVersion", 1)
        put("rpcToken", token)
        put("invocationId", INVOCATION_ID)
        put("pluginId", "sample-plugin")
        put("requestId", "req_12345678")
        put("method", method)
        put("params", params)
    }.toString()

    private companion object {
        const val INVOCATION_ID = "invocation_12345678"
        val TOKEN = "a".repeat(64)
    }
}

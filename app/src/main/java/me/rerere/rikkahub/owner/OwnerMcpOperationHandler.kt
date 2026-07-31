package me.rerere.rikkahub.owner

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.McpVaultSecretReference
import me.rerere.rikkahub.data.ai.mcp.control.McpControlValidation
import me.rerere.rikkahub.data.ai.mcp.control.McpUrlGuard
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecondUserSecretVault
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Owner adapter over the existing MCP Settings store and live manager. */
class OwnerMcpOperationHandler(
    private val settingsStore: SettingsStore,
    private val manager: McpManager,
    private val httpClient: OkHttpClient,
    private val vault: SecondUserSecretVault,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.MCP && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported MCP action.")
        if ((action.arguments.keys - allowed).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "MCP action contains an unsupported field.")
        }
        if (action.arguments.keys.any { it.lowercase() in SECRET_KEYS }) {
            return invalid("OWNER_SECRET_ARGUMENT_FORBIDDEN", "MCP credentials must use Vault slot references.")
        }
        val dangerous = action.arguments.values.any { value ->
            value.toString().contains(Regex("(?i)curl\\s+[^|]{0,2048}\\|\\s*(?:sh|bash)"))
        }
        if (dangerous) return invalid("OWNER_INSTALL_PIPE_BLOCKED", "Piped remote shell installers are not allowed.")

        if (action.type in setOf("mcp_install", "mcp_update", "mcp_discover")) {
            val pin = action.arguments.string("pin")?.trim().orEmpty()
            if (!OwnerPinnedSourcePolicy.isPinned(pin)) {
                return invalid("OWNER_SOURCE_NOT_PINNED", "A fixed version, commit, or SHA-256 pin is required; latest is not accepted.")
            }
        }
        if (action.type in setOf("mcp_install", "mcp_update")) {
            val transport = action.arguments.string("transport")?.lowercase()
            if (transport !in setOf("sse", "streamable_http")) {
                return invalid("MCP_TRANSPORT_INVALID", "transport must be sse or streamable_http.")
            }
            val url = action.arguments.string("url").orEmpty()
            val urlCheck = McpUrlGuard.check(url, headless = false)
            if (urlCheck is McpUrlGuard.Result.Reject) return invalid(urlCheck.error, urlCheck.detail)
            val current = settingsStore.settingsFlow.value.mcpServers
            val excluding = action.arguments.string("mcp_id")
            when (val name = McpControlValidation.validateName(action.arguments.string("name").orEmpty(), current, excluding)) {
                is McpControlValidation.Result.Reject -> return invalid(name.error, name.detail)
                else -> Unit
            }
            when (val headers = McpControlValidation.validateHeaders(action.arguments.headers())) {
                is McpControlValidation.Result.Reject -> return invalid(headers.error, headers.detail)
                else -> Unit
            }
            if (action.type == "mcp_install") {
                val rawId = action.arguments.string("mcp_id")
                val requestedId = rawId?.let { action.arguments.uuid("mcp_id") }
                if (rawId != null && requestedId == null) {
                    return invalid("MCP_ID_INVALID", "mcp_id must be a UUID when supplied.")
                }
                if (requestedId != null && current.any { it.id == requestedId }) {
                    return invalid("MCP_ALREADY_EXISTS", "mcp_id already exists.")
                }
            }
            val slots = vault.listMetadata(request.authoritySubjectId).associateBy { it.slotId }
            action.arguments.headers().forEach { (_, value) ->
                val slotId = McpVaultSecretReference.slotIdOrNull(value) ?: return@forEach
                if (slots[slotId] == null) return invalid("MCP_SECRET_SLOT_MISSING", "Referenced MCP Vault slot does not exist.")
            }
            val pin = action.arguments.string("pin")!!.trim()
            val sourceUrl = action.arguments.string("source_url")?.trim()?.takeIf { it.isNotBlank() }
            if (sourceUrl != null) {
                val manifest = loadPinnedManifest(sourceUrl, pin)
                    ?: return invalid("MCP_PIN_MISMATCH", "Pinned MCP source manifest could not be verified.")
                if (manifest.string("name") != action.arguments.string("name")?.trim() ||
                    manifest.string("transport")?.lowercase() != transport ||
                    manifest.string("url")?.trim() != url.trim()
                ) {
                    return invalid("MCP_MANIFEST_MISMATCH", "Typed MCP configuration does not match its pinned source manifest.")
                }
            } else {
                val hashCandidate = if (pin.startsWith("sha256:", ignoreCase = true)) {
                    pin.substringAfter(':')
                } else pin
                val isContentHash = hashCandidate.matches(Regex("(?i)^[0-9a-f]{64}$"))
                if (isContentHash || !url.contains(pin, ignoreCase = true)) {
                    return invalid(
                        "MCP_IMMUTABLE_SOURCE_REQUIRED",
                        "Use source_url for a content hash, or include the fixed version/commit in the endpoint URL.",
                    )
                }
            }
        }
        if (action.type in ID_ACTIONS) {
            val id = action.arguments.uuid("mcp_id")
                ?: return invalid("MCP_ID_REQUIRED", "mcp_id is required.")
            if (settingsStore.settingsFlow.value.mcpServers.none { it.id == id }) {
                return invalid("MCP_NOT_FOUND", "MCP server does not exist.")
            }
        }
        if (action.type in setOf("mcp_bind", "mcp_unbind")) {
            val assistantId = action.arguments.uuid("assistant_id")
                ?: return invalid("ASSISTANT_ID_REQUIRED", "assistant_id is required.")
            if (settingsStore.settingsFlow.value.assistants.none { it.id == assistantId }) {
                return invalid("ASSISTANT_NOT_FOUND", "Assistant does not exist.")
            }
        }
        return OwnerActionValidation(true, "MCP_ACTION_VALID", "MCP action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "mcp_list" -> list(index)
            "mcp_discover" -> discover(index, action)
            "mcp_install" -> install(index, request, action)
            "mcp_update" -> update(index, request, action)
            "mcp_delete" -> delete(index, request, action)
            "mcp_bind" -> bind(index, request, action, true)
            "mcp_unbind" -> bind(index, request, action, false)
            "mcp_test" -> test(index, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported MCP action.")
        }
    }.getOrElse {
        failure(index, action.type, "MCP_OPERATION_FAILED", "MCP operation failed inside the host runtime.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val id = action.arguments.uuid("mcp_id")
            ?: applied.result.data?.get("mcp_id")?.jsonPrimitive?.contentOrNull?.let {
                runCatching { Uuid.parse(it) }.getOrNull()
            }
        return when (action.type) {
            "mcp_install", "mcp_update", "mcp_bind", "mcp_unbind" -> if (id != null &&
                settingsStore.settingsFlow.value.mcpServers.any { it.id == id }
            ) OwnerActionValidation(true, "MCP_ACTION_VERIFIED", "MCP state verified.")
            else invalid("MCP_VERIFY_FAILED", "MCP state could not be confirmed.")
            "mcp_delete" -> if (id != null && settingsStore.settingsFlow.value.mcpServers.none { it.id == id }) {
                OwnerActionValidation(true, "MCP_DELETE_VERIFIED", "MCP deletion verified.")
            } else invalid("MCP_VERIFY_FAILED", "MCP deletion could not be confirmed.")
            else -> OwnerActionValidation(true, "MCP_ACTION_VERIFIED", "MCP action completed.")
        }
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? SnapshotReceipt
            ?: return OwnerCompensationResult(true, "MCP_NO_COMPENSATION_REQUIRED")
        return runCatching {
            restore(receipt)
            OwnerCompensationResult(true, "MCP_STATE_RESTORED")
        }.getOrElse { OwnerCompensationResult(false, "MCP_COMPENSATION_FAILED") }
    }

    private fun list(index: Int): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        val statuses = manager.syncingStatus.value
        return success(index, "mcp_list", "MCP_LIST", "MCP metadata returned.", buildJsonObject {
            put("servers", buildJsonArray {
                settings.mcpServers.forEach { server ->
                    add(buildJsonObject {
                        put("mcp_id", server.id.toString())
                        put("name", server.commonOptions.name.take(60))
                        put("transport", transport(server))
                        put("enabled", server.commonOptions.enable)
                        put("status", statusCode(statuses[server.id]))
                        put("tool_count", server.commonOptions.tools.size)
                    })
                }
            })
        })
    }

    private fun discover(index: Int, action: OwnerAction): OwnerAppliedAction {
        val source = action.arguments.string("source_url")?.trim()
            ?: return failure(index, action.type, "MCP_SOURCE_REQUIRED", "source_url is required.")
        val uri = runCatching { URI(source) }.getOrNull()
            ?: return failure(index, action.type, "MCP_SOURCE_INVALID", "Source URL is invalid.")
        if (uri.scheme?.lowercase() != "https") {
            return failure(index, action.type, "MCP_SOURCE_HTTPS_REQUIRED", "Registry discovery requires HTTPS.")
        }
        val pin = action.arguments.string("pin")!!
        val manifest = loadPinnedManifest(source, pin)
        if (manifest == null) {
            return failure(index, action.type, "MCP_PIN_MISMATCH", "Registry content does not match the requested immutable pin.")
        }
        val name = manifest.string("name")?.take(60).orEmpty()
        val transport = manifest.string("transport")?.lowercase().orEmpty()
        return if (name.isBlank() || transport !in setOf("sse", "streamable_http")) {
            failure(index, action.type, "MCP_MANIFEST_INVALID", "Manifest must declare name and a supported transport.")
        } else success(index, action.type, "MCP_DISCOVERED", "Pinned MCP manifest validated.", buildJsonObject {
            put("name", name)
            put("transport", transport)
            manifest.string("url")?.take(2048)?.let { put("url", it) }
            put("pin", pin.take(128))
        })
    }

    private fun loadPinnedManifest(source: String, pin: String): JsonObject? = runCatching {
        val uri = URI(source)
        require(uri.scheme?.lowercase() == "https" && uri.userInfo == null)
        val request = Request.Builder().url(source).header("Accept", "application/json").build()
        val bytes = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body.byteStream().use { input -> input.readOwnerBytesAtMost(MAX_MANIFEST_BYTES + 1) }
        }
        if (bytes.size > MAX_MANIFEST_BYTES) return@runCatching null
        try {
            val manifest = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: return@runCatching null
            manifest.takeIf { OwnerPinnedSourcePolicy.verifyManifest(pin, bytes, it) }
        } finally {
            bytes.fill(0)
        }
    }.getOrNull()

    private suspend fun install(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val snapshot = snapshot(request.authoritySubjectId)
        val id = action.arguments.uuid("mcp_id") ?: Uuid.random()
        val config = action.arguments.toConfig(id, emptyList())
        settingsStore.update { it.copy(mcpServers = it.mcpServers + config) }
        syncVaultBindings(id, config.commonOptions.headers, request.authoritySubjectId)
        if (config.commonOptions.enable) {
            manager.addClient(config)
            awaitTerminal(id, action.arguments.int("wait_seconds")?.coerceIn(1, 60) ?: 15)
            val error = manager.syncingStatus.value[id] as? McpStatus.Error
            if (error != null) {
                restore(snapshot)
                return failure(index, action.type, "MCP_CONNECT_FAILED", "MCP endpoint did not pass its initial connection test.")
            }
        }
        return success(index, action.type, "MCP_INSTALLED", "Pinned MCP server installed.", buildJsonObject {
            put("mcp_id", id.toString())
            put("status", statusCode(manager.syncingStatus.value[id]))
        }, snapshot)
    }

    private suspend fun update(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("mcp_id"))
        val snapshot = snapshot(request.authoritySubjectId)
        val old = snapshot.servers.first { it.id == id }
        val updated = action.arguments.toConfig(id, old.commonOptions.tools)
        manager.removeClient(old)
        settingsStore.update { current -> current.copy(mcpServers = current.mcpServers.map { if (it.id == id) updated else it }) }
        syncVaultBindings(id, updated.commonOptions.headers, request.authoritySubjectId)
        if (updated.commonOptions.enable) {
            manager.addClient(updated)
            awaitTerminal(id, action.arguments.int("wait_seconds")?.coerceIn(1, 60) ?: 15)
            if (manager.syncingStatus.value[id] is McpStatus.Error) {
                restore(snapshot)
                return failure(index, action.type, "MCP_CONNECT_FAILED", "Updated MCP endpoint failed verification; previous configuration restored.")
            }
        }
        return success(index, action.type, "MCP_UPDATED", "Pinned MCP configuration updated.", buildJsonObject {
            put("mcp_id", id.toString())
        }, snapshot)
    }

    private suspend fun delete(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("mcp_id"))
        val snapshot = snapshot(request.authoritySubjectId)
        snapshot.servers.firstOrNull { it.id == id }?.let { manager.removeClient(it) }
        settingsStore.update { current -> current.copy(
            mcpServers = current.mcpServers.filterNot { it.id == id },
            assistants = current.assistants.map { it.copy(mcpServers = it.mcpServers - id) },
        ) }
        syncVaultBindings(id, emptyList(), request.authoritySubjectId)
        return success(index, action.type, "MCP_DELETED", "MCP server deleted and bindings removed.", buildJsonObject {
            put("mcp_id", id.toString())
        }, snapshot)
    }

    private suspend fun bind(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        enabled: Boolean,
    ): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("mcp_id"))
        val assistantId = requireNotNull(action.arguments.uuid("assistant_id"))
        val snapshot = snapshot(request.authoritySubjectId)
        settingsStore.update { current -> current.copy(assistants = current.assistants.map { assistant ->
            if (assistant.id != assistantId) assistant else assistant.copy(
                mcpServers = if (enabled) assistant.mcpServers + id else assistant.mcpServers - id,
            )
        }) }
        return success(index, action.type, if (enabled) "MCP_BOUND" else "MCP_UNBOUND", if (enabled) "MCP bound to assistant." else "MCP unbound from assistant.", buildJsonObject {
            put("mcp_id", id.toString())
            put("assistant_id", assistantId.toString())
        }, snapshot)
    }

    private suspend fun test(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("mcp_id"))
        val config = settingsStore.settingsFlow.value.mcpServers.first { it.id == id }
        if (!config.commonOptions.enable) return failure(index, action.type, "MCP_DISABLED", "Enable the MCP server before testing it.")
        manager.forceResync(id)
        awaitTerminal(id, action.arguments.int("wait_seconds")?.coerceIn(1, 60) ?: 15)
        val status = manager.syncingStatus.value[id]
        return if (status == McpStatus.Connected) success(index, action.type, "MCP_TEST_OK", "MCP connection and tool sync verified.", buildJsonObject {
            put("mcp_id", id.toString())
            put("tool_count", settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == id }?.commonOptions?.tools?.size ?: 0)
        }) else failure(index, action.type, "MCP_TEST_FAILED", "MCP connection could not be confirmed.")
    }

    private suspend fun awaitTerminal(id: Uuid, seconds: Int) {
        repeat(seconds * 5) {
            when (manager.syncingStatus.value[id]) {
                McpStatus.Connected, is McpStatus.Error -> return
                else -> delay(200)
            }
        }
    }

    private data class SnapshotReceipt(
        val servers: List<McpServerConfig>,
        val bindings: Map<Uuid, Set<Uuid>>,
        val authoritySubjectId: String,
        val vaultBindings: Map<String, List<SecretBinding>>,
    )

    private suspend fun snapshot(authoritySubjectId: String): SnapshotReceipt {
        val settings = settingsStore.settingsFlow.value
        return SnapshotReceipt(
            servers = settings.mcpServers,
            bindings = settings.assistants.associate { it.id to it.mcpServers },
            authoritySubjectId = authoritySubjectId,
            vaultBindings = vault.listMetadata(authoritySubjectId).associate { it.slotId to it.bindings },
        )
    }

    private suspend fun restore(snapshot: SnapshotReceipt) {
        val current = settingsStore.settingsFlow.value.mcpServers
        current.forEach { manager.removeClient(it) }
        settingsStore.update { settings -> settings.copy(
            mcpServers = snapshot.servers,
            assistants = settings.assistants.map { assistant ->
                snapshot.bindings[assistant.id]?.let { assistant.copy(mcpServers = it) } ?: assistant
            },
        ) }
        vault.listMetadata(snapshot.authoritySubjectId).forEach { slot ->
            snapshot.vaultBindings[slot.slotId]?.let { bindings ->
                vault.updateBindings(slot.slotId, snapshot.authoritySubjectId, bindings)
            }
        }
        snapshot.servers.filter { it.commonOptions.enable }.forEach { manager.addClient(it) }
    }

    private suspend fun syncVaultBindings(
        serverId: Uuid,
        headers: List<Pair<String, String>>,
        authoritySubjectId: String,
    ) {
        val slots = vault.listMetadata(authoritySubjectId).associateBy { it.slotId }
        val desired = headers.mapIndexedNotNull { index, (name, value) ->
            val slotId = McpVaultSecretReference.slotIdOrNull(value) ?: return@mapIndexedNotNull null
            val binding = SecretBinding(
                kind = SecretBindingKind.MCP,
                targetId = McpVaultSecretReference.bindingTarget(serverId, name, index),
            )
            slotId to binding
        }.groupBy({ it.first }, { it.second })
        slots.values.forEach { slot ->
            val retained = slot.bindings.filterNot { binding ->
                binding.kind == SecretBindingKind.MCP && binding.targetId.startsWith("$serverId:")
            }
            val updated = retained + desired[slot.slotId].orEmpty()
            if (updated != slot.bindings) {
                check(vault.updateBindings(slot.slotId, authoritySubjectId, updated)) {
                    "mcp_vault_binding_update_failed"
                }
            }
        }
    }

    private fun JsonObject.toConfig(id: Uuid, tools: List<McpTool>): McpServerConfig {
        val common = McpCommonOptions(
            enable = boolean("enabled") ?: true,
            name = string("name")!!.trim().take(60),
            headers = headers(),
            tools = tools,
            ownerSource = (string("source_url") ?: string("url")!!).trim().take(2048),
            ownerPin = string("pin")!!.trim().take(128),
        )
        val url = string("url")!!.trim()
        return if (string("transport")!!.equals("sse", true)) {
            McpServerConfig.SseTransportServer(id, common, url)
        } else McpServerConfig.StreamableHTTPServer(id, common, url)
    }

    private fun JsonObject.headers(): List<Pair<String, String>> = (this["headers"] as? JsonArray)
        ?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val value = obj.string("value") ?: return@mapNotNull null
            name to value
        }?.take(32).orEmpty()

    private fun transport(config: McpServerConfig) = when (config) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    }
    private fun statusCode(status: McpStatus?) = when (status) {
        McpStatus.Connected -> "CONNECTED"
        is McpStatus.Error -> "ERROR"
        is McpStatus.Reconnecting -> "RECONNECTING"
        McpStatus.Connecting -> "CONNECTING"
        else -> "IDLE"
    }
    private fun success(index: Int, type: String, code: String, message: String, data: JsonObject? = null, receipt: Any? = null) =
        OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data), receipt)
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.uuid(key: String) = string(key)?.trim()?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    private fun JsonObject.int(key: String) = string(key)?.toIntOrNull()
    private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()

    private companion object {
        const val MAX_MANIFEST_BYTES = 512 * 1024
        val SECRET_KEYS = setOf("secret", "token", "password", "api_key", "authorization")
        val COMMON = setOf("mcp_id", "name", "transport", "url", "source_url", "enabled", "headers", "pin", "wait_seconds")
        val FIELDS = mapOf(
            "mcp_list" to emptySet(),
            "mcp_discover" to setOf("source_url", "pin"),
            "mcp_install" to COMMON,
            "mcp_update" to COMMON + "mcp_id",
            "mcp_delete" to setOf("mcp_id"),
            "mcp_bind" to setOf("mcp_id", "assistant_id"),
            "mcp_unbind" to setOf("mcp_id", "assistant_id"),
            "mcp_test" to setOf("mcp_id", "wait_seconds"),
        )
        val ID_ACTIONS = setOf("mcp_update", "mcp_delete", "mcp_bind", "mcp_unbind", "mcp_test")
    }
}

internal object OwnerPinnedSourcePolicy {
    private val SHA256 = Regex("(?i)^(?:sha256:)?[0-9a-f]{64}$")
    private val COMMIT = Regex("(?i)^[0-9a-f]{7,40}$")
    private val VERSION = Regex("^[vV]?\\d+(?:\\.\\d+){1,3}(?:[-+][0-9A-Za-z.-]+)?$")

    fun isPinned(value: String): Boolean {
        val pin = value.trim()
        if (pin.isBlank() || pin.equals("latest", true) || pin.contains("*")) return false
        return SHA256.matches(pin) || COMMIT.matches(pin) || VERSION.matches(pin)
    }

    fun verifyManifest(pinValue: String, bytes: ByteArray, manifest: JsonObject): Boolean {
        val pin = pinValue.trim()
        val hash = if (pin.startsWith("sha256:", ignoreCase = true)) pin.substringAfter(':') else pin
        if (hash.length == 64 && hash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return actual.equals(hash, ignoreCase = true)
        }
        if (COMMIT.matches(pin)) {
            val commit = manifest["commit"]?.jsonPrimitive?.contentOrNull?.trim()
            return commit?.equals(pin, ignoreCase = true) == true
        }
        if (VERSION.matches(pin)) {
            val expected = pin.removePrefix("v").removePrefix("V")
            val version = manifest["version"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.removePrefix("v")?.removePrefix("V")
            return version == expected
        }
        return false
    }
}

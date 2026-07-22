package me.rerere.rikkahub.plugin

import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PluginHostInvocationContext(
    val invocationId: String,
    val rpcToken: String,
    val pluginId: String,
    val permissions: Set<String>,
    val stateProjection: String,
)

data class PluginNetworkResponse(
    val status: Int,
    val contentType: String?,
    val body: String,
)

fun interface PluginNetworkGateway {
    suspend fun fetch(url: String): Result<PluginNetworkResponse>
}

class PluginHostRpcGateway(
    private val storageRoot: File,
    private val networkGateway: PluginNetworkGateway,
) {
    private val contexts = ConcurrentHashMap<String, PluginHostInvocationContext>()

    init {
        storageRoot.mkdirs()
    }

    fun register(context: PluginHostInvocationContext) {
        require(context.invocationId.matches(ID_PATTERN)) { "plugin_invocation_id_invalid" }
        require(context.rpcToken.matches(TOKEN_PATTERN)) { "plugin_rpc_token_invalid" }
        require(context.stateProjection.length <= MAX_STATE_CHARS) { "plugin_state_too_large" }
        check(contexts.putIfAbsent(context.invocationId, context) == null) {
            "plugin_invocation_duplicate"
        }
    }

    fun unregister(invocationId: String) {
        contexts.remove(invocationId)
    }

    fun handleRpc(raw: String): String {
        val response = runCatching {
            require(raw.length <= MAX_REQUEST_CHARS) { "plugin_rpc_request_too_large" }
            val request = JSON.parseToJsonElement(raw).jsonObject
            require(request["protocolVersion"]?.jsonPrimitive?.intOrNull == 1) {
                "plugin_rpc_protocol_invalid"
            }
            val invocationId = request.string("invocationId")
                ?: error("plugin_rpc_context_mismatch")
            val context = contexts[invocationId] ?: error("plugin_rpc_context_mismatch")
            require(request.string("rpcToken") == context.rpcToken &&
                request.string("pluginId") == context.pluginId
            ) { "plugin_rpc_context_mismatch" }
            val requestId = request.string("requestId").orEmpty()
            require(requestId.matches(REQUEST_ID_PATTERN)) { "plugin_rpc_request_id_invalid" }
            val method = request.string("method") ?: error("plugin_rpc_method_invalid")
            val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
            when (method) {
                "state.read" -> stateRead(context)
                "storage.read" -> storageRead(context, params)
                "storage.write" -> storageWrite(context, params)
                "network.fetch" -> networkFetch(context, params)
                else -> error("plugin_rpc_method_invalid")
            }
        }.getOrElse { failure ->
            errorResponse(failure.message.toRpcError())
        }
        // Never truncate a serialized response: a partial JSON document makes a plugin mistake
        // look like a transport failure and can leave its JavaScript promise unresolved.
        return response.takeIf { it.length <= MAX_RESPONSE_CHARS }
            ?: errorResponse("plugin_rpc_response_too_large")
    }

    private fun stateRead(context: PluginHostInvocationContext): String {
        requirePermission(context, "state:read")
        val projection = runCatching { JSON.parseToJsonElement(context.stateProjection) }
            .getOrElse { JsonPrimitive(context.stateProjection.take(MAX_STATE_CHARS)) }
        return successResponse { put("state", projection) }
    }

    private fun storageRead(
        context: PluginHostInvocationContext,
        params: JsonObject,
    ): String {
        requirePermission(context, "storage:read")
        val file = scopedFile(context.pluginId, params.string("path"))
        require(file.isFile) { "plugin_storage_not_found" }
        require(file.length() <= MAX_STORAGE_FILE_BYTES) { "plugin_storage_file_too_large" }
        return successResponse { put("content", file.readText(Charsets.UTF_8)) }
    }

    private fun storageWrite(
        context: PluginHostInvocationContext,
        params: JsonObject,
    ): String {
        requirePermission(context, "storage:write")
        val content = params.string("content") ?: error("plugin_storage_content_invalid")
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_STORAGE_FILE_BYTES) {
            "plugin_storage_file_too_large"
        }
        val file = scopedFile(context.pluginId, params.string("path"))
        val pluginRoot = File(storageRoot, context.pluginId).canonicalFile
        val existingFiles = pluginRoot.walkTopDown()
            .filter(File::isFile)
            .take(MAX_STORAGE_FILES + 1)
            .toList()
        val existingBytes = existingFiles.sumOf(File::length)
        require(existingBytes - file.takeIf(File::isFile)?.length().orZero() +
            content.toByteArray(Charsets.UTF_8).size <= MAX_STORAGE_TOTAL_BYTES
        ) { "plugin_storage_quota_exceeded" }
        require(file.isFile || existingFiles.size < MAX_STORAGE_FILES) {
            "plugin_storage_file_limit_exceeded"
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            temp.delete()
            error("plugin_storage_write_failed")
        }
        check(temp.renameTo(file)) { "plugin_storage_write_failed" }
        return successResponse { put("bytes", content.toByteArray(Charsets.UTF_8).size) }
    }

    private fun networkFetch(
        context: PluginHostInvocationContext,
        params: JsonObject,
    ): String {
        val url = params.string("url") ?: error("plugin_network_url_invalid")
        val parsed = runCatching { URI(url) }.getOrElse { error("plugin_network_url_invalid") }
        require(parsed.scheme == "https" && parsed.userInfo == null && parsed.host != null) {
            "plugin_network_url_blocked"
        }
        val host = parsed.host.lowercase()
        require("network:https://$host" in context.permissions) {
            "plugin_network_host_blocked"
        }
        val response = runBlocking { networkGateway.fetch(url) }.getOrElse { failure ->
            error(failure.message.toRpcError("plugin_network_failed"))
        }
        require(response.body.length <= MAX_NETWORK_BODY_CHARS) {
            "plugin_network_response_too_large"
        }
        return successResponse {
            put("status", response.status)
            response.contentType?.let { put("contentType", it.take(120)) }
            put("body", response.body)
        }
    }

    private fun scopedFile(pluginId: String, rawPath: String?): File {
        val path = rawPath ?: error("plugin_storage_path_invalid")
        try {
            PluginManifestValidator.requireSafeRelativePath(path)
        } catch (_: Throwable) {
            error("plugin_storage_path_invalid")
        }
        val pluginRoot = File(storageRoot, pluginId).canonicalFile
        pluginRoot.mkdirs()
        val target = File(pluginRoot, path).canonicalFile
        require(target.toPath().startsWith(pluginRoot.toPath()) && target != pluginRoot) {
            "plugin_storage_path_invalid"
        }
        return target
    }

    private fun requirePermission(context: PluginHostInvocationContext, permission: String) {
        require(permission in context.permissions) { "plugin_permission_denied" }
    }

    private fun successResponse(content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("ok", true)
            content()
        }.toString()

    private fun errorResponse(code: String) = buildJsonObject {
        put("ok", false)
        put("error", code)
    }.toString()

    private fun JsonObject.string(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull

    private fun Long?.orZero(): Long = this ?: 0L

    private fun String?.toRpcError(fallback: String = "plugin_rpc_failed"): String =
        this?.takeIf { it.matches(ERROR_CODE_PATTERN) } ?: fallback

    private companion object {
        const val MAX_REQUEST_CHARS = 16 * 1024
        const val MAX_RESPONSE_CHARS = 256 * 1024
        const val MAX_STATE_CHARS = 8 * 1024
        const val MAX_STORAGE_FILE_BYTES = 64 * 1024L
        const val MAX_STORAGE_TOTAL_BYTES = 1024 * 1024L
        const val MAX_STORAGE_FILES = 256
        const val MAX_NETWORK_BODY_CHARS = 256 * 1024
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{8,96}")
        val TOKEN_PATTERN = Regex("[a-f0-9]{64}")
        val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,96}")
        val ERROR_CODE_PATTERN = Regex("[a-z0-9_]{3,80}")
        val JSON = Json { ignoreUnknownKeys = false }
    }
}

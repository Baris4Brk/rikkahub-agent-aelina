package me.rerere.rikkahub.plugin

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val PLUGIN_MODEL_TOOL_PREFIX = "plugin__"

private val PLUGIN_MODEL_TOOL_NAME = Regex("plugin__[0-9a-f]{12}__[a-z][a-z0-9_]{0,62}")

fun isPluginModelToolName(toolName: String): Boolean =
    PLUGIN_MODEL_TOOL_NAME.matches(toolName)

fun PluginRegistryStore.containsApprovedModelTool(toolName: String): Boolean {
    if (!isPluginModelToolName(toolName)) return false
    return snapshot().any { record ->
        record.enabled && record.reviewStatus == PluginReviewStatus.APPROVED &&
            record.manifest.tools.any { tool ->
                PluginManifestValidator.modelToolName(record.id, tool.slug) == toolName
            }
    }
}

@Serializable
data class PluginManifestV1(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val entry: String,
    val permissions: PluginPermissions,
    val tools: List<PluginToolManifest> = emptyList(),
    val hooks: PluginHookManifest = PluginHookManifest(),
)

@Serializable
data class PluginPermissions(
    val stateRead: Boolean = false,
    val storageRead: Boolean = false,
    val storageWrite: Boolean = false,
    val networkHosts: List<String> = emptyList(),
)

@Serializable
data class PluginToolManifest(
    val slug: String,
    val description: String,
    val handler: String,
    val inputSchema: JsonObject,
)

@Serializable
data class PluginHookManifest(
    val promptHandler: String? = null,
    val interceptHandler: String? = null,
    val observerHandler: String? = null,
)

internal object PluginManifestValidator {
    fun validate(manifest: PluginManifestV1) {
        require(manifest.schemaVersion == 1) { "plugin_manifest_version_unsupported" }
        require(manifest.id.matches(PLUGIN_ID)) { "plugin_id_invalid" }
        require(manifest.name.isNotBlank() && manifest.name.length <= 80) { "plugin_name_invalid" }
        require(manifest.version.isNotBlank() && manifest.version.length <= 64) { "plugin_version_invalid" }
        requireSafeRelativePath(manifest.entry)
        require(manifest.entry.endsWith(".html", ignoreCase = true)) { "plugin_entry_invalid" }
        require(manifest.tools.size <= MAX_TOOLS) { "plugin_tool_limit_exceeded" }
        require(manifest.tools.isNotEmpty() || manifest.hooks.hasAny()) {
            "plugin_manifest_has_no_handlers"
        }
        val slugs = hashSetOf<String>()
        manifest.tools.forEach { tool ->
            require(tool.slug.matches(TOOL_SLUG) && slugs.add(tool.slug)) {
                "plugin_tool_slug_invalid"
            }
            require(tool.description.isNotBlank() && tool.description.length <= 500) {
                "plugin_tool_description_invalid"
            }
            requireHandler(tool.handler)
            validateInputSchema(tool.inputSchema)
        }
        listOfNotNull(
            manifest.hooks.promptHandler,
            manifest.hooks.interceptHandler,
            manifest.hooks.observerHandler,
        ).forEach(::requireHandler)
        require(manifest.permissions.networkHosts.size <= MAX_NETWORK_HOSTS) {
            "plugin_network_host_limit_exceeded"
        }
        require(manifest.permissions.networkHosts.distinct().size ==
            manifest.permissions.networkHosts.size
        ) { "plugin_network_host_duplicate" }
        manifest.permissions.networkHosts.forEach { host ->
            require(host == host.lowercase() && host.matches(HOSTNAME)) {
                "plugin_network_host_invalid"
            }
        }
    }

    fun permissionSet(permissions: PluginPermissions): Set<String> = buildSet {
        if (permissions.stateRead) add("state:read")
        if (permissions.storageRead) add("storage:read")
        if (permissions.storageWrite) add("storage:write")
        permissions.networkHosts.forEach { add("network:https://$it") }
    }

    fun modelToolName(pluginId: String, slug: String): String =
        "$PLUGIN_MODEL_TOOL_PREFIX${pluginIdHash(pluginId)}__$slug"

    fun pluginIdHash(pluginId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(pluginId.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(12)

    fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank() && path.length <= 240 && '\u0000' !in path) {
            "plugin_path_invalid"
        }
        require(!path.startsWith('/') && !path.startsWith('\\') && ':' !in path) {
            "plugin_path_invalid"
        }
        require('\\' !in path) { "plugin_path_invalid" }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "plugin_path_invalid"
        }
    }

    private fun PluginHookManifest.hasAny(): Boolean =
        promptHandler != null || interceptHandler != null || observerHandler != null

    private fun requireHandler(handler: String) {
        require(handler.matches(HANDLER)) { "plugin_handler_invalid" }
    }

    private fun validateInputSchema(schema: JsonObject) {
        require(schema.toString().length <= MAX_SCHEMA_CHARS) { "plugin_input_schema_too_large" }
        require(schema["type"]?.jsonPrimitive?.content == "object") {
            "plugin_input_schema_must_be_object"
        }
        require(jsonDepth(schema) <= MAX_SCHEMA_DEPTH) { "plugin_input_schema_too_deep" }
    }

    private fun jsonDepth(element: JsonElement): Int = when (element) {
        is JsonObject -> 1 + (element.values.maxOfOrNull(::jsonDepth) ?: 0)
        is kotlinx.serialization.json.JsonArray -> 1 +
            (element.maxOfOrNull(::jsonDepth) ?: 0)
        else -> 1
    }

    private const val MAX_TOOLS = 16
    private const val MAX_NETWORK_HOSTS = 16
    private const val MAX_SCHEMA_CHARS = 16_000
    private const val MAX_SCHEMA_DEPTH = 8
    private val PLUGIN_ID = Regex("[a-z0-9][a-z0-9_-]{1,62}")
    private val TOOL_SLUG = Regex("[a-z][a-z0-9_]{0,62}")
    private val HANDLER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")
    private val HOSTNAME = Regex(
        "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    )
}

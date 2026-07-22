package me.rerere.rikkahub.plugin

import kotlinx.serialization.Serializable

@Serializable
enum class PluginInvocationKind {
    TOOL,
    PROMPT_HOOK,
    INTERCEPT_HOOK,
    OBSERVER_HOOK,
}

@Serializable
data class PluginRuntimeRequest(
    val protocolVersion: Int = 1,
    val invocationId: String,
    val rpcToken: String,
    val pluginId: String,
    val pluginIdHash: String,
    val entry: String,
    val handler: String,
    val inputJson: String,
    val kind: PluginInvocationKind,
    val timeoutMs: Long,
)

@Serializable
data class PluginRuntimeResponse(
    val ok: Boolean,
    val invocationId: String,
    val outputJson: String? = null,
    val errorCode: String? = null,
    val durationMs: Long = 0,
)

internal object PluginRuntimeRequestValidator {
    private val INVOCATION_ID = Regex("[A-Za-z0-9_-]{8,96}")
    private val TOKEN = Regex("[a-f0-9]{64}")
    private val HANDLER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")
    private val HASH = Regex("[a-f0-9]{12}")

    fun validate(request: PluginRuntimeRequest) {
        require(request.protocolVersion == 1) { "plugin_protocol_unsupported" }
        require(request.invocationId.matches(INVOCATION_ID)) { "plugin_invocation_id_invalid" }
        require(request.rpcToken.matches(TOKEN)) { "plugin_rpc_token_invalid" }
        PluginManifestValidator.requireSafeRelativePath(request.entry)
        require(request.entry.endsWith(".html", ignoreCase = true)) { "plugin_entry_invalid" }
        require(request.handler.matches(HANDLER)) { "plugin_handler_invalid" }
        require(request.pluginIdHash.matches(HASH) &&
            request.pluginIdHash == PluginManifestValidator.pluginIdHash(request.pluginId)
        ) { "plugin_origin_invalid" }
        require(request.inputJson.length <= MAX_INPUT_CHARS) { "plugin_input_too_large" }
        require(request.timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "plugin_timeout_invalid"
        }
    }

    fun originFor(pluginIdHash: String): String {
        require(pluginIdHash.matches(HASH)) { "plugin_origin_invalid" }
        return "https://p-$pluginIdHash.plugin.rikkahub.invalid"
    }

    const val MAX_INPUT_CHARS = 64 * 1024
    const val MAX_OUTPUT_CHARS = 64 * 1024
    const val MIN_TIMEOUT_MS = 500L
    const val MAX_TIMEOUT_MS = 30_000L
}

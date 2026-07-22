package me.rerere.rikkahub.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PluginAuditEvent(
    val pluginIdHash: String,
    val kind: PluginInvocationKind,
    val ok: Boolean,
    val errorCode: String?,
    val durationMs: Long,
    val recordedAtMs: Long,
)

/** Bounded redacted audit log: no plugin input, output, chat, path, URL, or RPC data. */
class PluginAuditStore(
    private val maxEntries: Int = 100,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutableEvents = MutableStateFlow<List<PluginAuditEvent>>(emptyList())
    val events: StateFlow<List<PluginAuditEvent>> = mutableEvents.asStateFlow()

    @Synchronized
    fun record(invocation: PluginInvocation, response: PluginRuntimeResponse) {
        val event = PluginAuditEvent(
            pluginIdHash = PluginManifestValidator.pluginIdHash(invocation.pluginId),
            kind = invocation.kind,
            ok = response.ok,
            errorCode = response.errorCode?.takeIf { it.matches(ERROR_CODE) },
            durationMs = response.durationMs.coerceAtLeast(0L),
            recordedAtMs = nowMs(),
        )
        mutableEvents.value = (listOf(event) + mutableEvents.value)
            .take(maxEntries.coerceAtLeast(1))
    }

    private companion object {
        val ERROR_CODE = Regex("[a-z0-9_]{3,80}")
    }
}

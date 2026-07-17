package me.rerere.rikkahub.diagnostics

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RuntimeDiagnosticStatus {
    READY,
    SERVICE_OFFLINE,
    IMPLEMENTED_BUT_NOT_AUTHORIZED,
    OEM_RESTRICTED,
    NOT_SUPPORTED,
}

enum class RuntimeDiagnosticFix {
    ACCESSIBILITY_SETTINGS,
    NOTIFICATION_LISTENER_SETTINGS,
    NOTIFICATION_SETTINGS,
    BATTERY_OPTIMIZATION_SETTINGS,
    APPLICATION_DETAILS,
    INPUT_METHOD_SETTINGS,
    SHIZUKU_APP,
    TERMUX_APP,
}

data class RuntimeDiagnosticItem(
    val id: String,
    val title: String,
    val status: RuntimeDiagnosticStatus,
    val detail: String,
    val fix: RuntimeDiagnosticFix? = null,
)

data class RuntimeDiagnosticsSnapshot(
    val conversationId: String?,
    val collectedAtEpochMs: Long,
    val items: List<RuntimeDiagnosticItem>,
) {
    /**
     * Export is deliberately allow-listed instead of serializing provider internals. It can
     * therefore never pick up credentials, command payloads, notifications or communication
     * content when the runtime provider grows new fields.
     */
    fun toRedactedJson(): String = buildJsonObject {
        put("schemaVersion", 1)
        conversationId?.let { put("conversationId", it) }
        put("collectedAtEpochMs", collectedAtEpochMs)
        put("items", buildJsonArray {
            items.forEach { item ->
                add(buildJsonObject {
                    put("id", item.id)
                    put("title", item.title)
                    put("status", item.status.name)
                    put("detail", item.detail.redactedDiagnosticDetail())
                    item.fix?.let { put("fix", it.name) }
                })
            }
        })
    }.toString()
}

internal fun bridgeDiagnosticStatus(
    installed: Boolean,
    binderAvailable: Boolean,
    permissionGranted: Boolean,
    userServiceAvailable: Boolean,
): RuntimeDiagnosticStatus = when {
    !installed -> RuntimeDiagnosticStatus.NOT_SUPPORTED
    !binderAvailable -> RuntimeDiagnosticStatus.SERVICE_OFFLINE
    !permissionGranted -> RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED
    !userServiceAvailable -> RuntimeDiagnosticStatus.NOT_SUPPORTED
    else -> RuntimeDiagnosticStatus.READY
}

internal fun enabledServiceDiagnosticStatus(
    enabled: Boolean,
    running: Boolean,
): RuntimeDiagnosticStatus = when {
    !enabled -> RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED
    !running -> RuntimeDiagnosticStatus.SERVICE_OFFLINE
    else -> RuntimeDiagnosticStatus.READY
}

private fun String.redactedDiagnosticDetail(): String =
    if (SENSITIVE_DIAGNOSTIC_DETAIL.containsMatchIn(this)) {
        "Sensitive diagnostic detail redacted."
    } else {
        this
    }

private val SENSITIVE_DIAGNOSTIC_DETAIL = Regex(
    pattern = """(?ix)
        \b(token|password|secret|api[_ -]?key)\b |
        \b(command|arguments|stdin|stdout|stderr)\s*[:=] |
        \b(notification|sms)\s+(body|text|content)\b |
        \bcontact\s+(name|number|data)\b
    """.trimIndent(),
)

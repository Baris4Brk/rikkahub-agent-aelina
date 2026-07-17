package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.privilege.PrivilegedCommandInput
import me.rerere.rikkahub.privilege.PrivilegedCommandResult

enum class ExternalPrivilegeBridgePrivilege {
    None,
    Shell,
    Root,
}

data class ExternalPrivilegeBridgeStatus(
    val installed: Boolean,
    val binderAvailable: Boolean,
    val permissionGranted: Boolean,
    val permissionPermanentlyDenied: Boolean,
    val apiVersion: Int?,
    val serverVersion: String?,
    val serverUid: Int?,
    val privilege: ExternalPrivilegeBridgePrivilege,
    val userServiceAvailable: Boolean,
)

data class ExternalPrivilegePackage(
    val packageName: String,
    val label: String?,
    val systemApp: Boolean,
    val enabled: Boolean,
    val versionName: String?,
    val versionCode: Long,
)

data class ExternalPrivilegePackageList(
    val packages: List<ExternalPrivilegePackage>,
    val truncated: Boolean,
    val ok: Boolean = true,
    val code: String = "OK",
    val message: String = "Packages listed.",
)

data class ExternalPrivilegeActionResult(
    val ok: Boolean,
    val code: String,
    val message: String,
)

/** Validates package identifiers and rejects code-owned protected packages before Binder IPC. */
internal class ProtectedPackagePolicy(
    protectedPackages: Set<String>,
) {
    private val protected = protectedPackages.mapTo(mutableSetOf()) { it.lowercase() }

    fun validateMutationTarget(packageName: String?): ExternalPrivilegeActionResult? {
        val candidate = packageName?.trim().orEmpty()
        if (candidate.lowercase() in protected) {
            return ExternalPrivilegeActionResult(
                ok = false,
                code = "PROTECTED_PACKAGE",
                message = "$candidate is protected and cannot be modified by the assistant.",
            )
        }
        if (candidate.isEmpty() || candidate.length > MAX_PACKAGE_NAME_LENGTH ||
            !PACKAGE_NAME.matches(candidate)) {
            return ExternalPrivilegeActionResult(
                ok = false,
                code = "INVALID_PACKAGE_NAME",
                message = "package_name must be a plain Android package identifier.",
            )
        }
        return null
    }

    companion object {
        private const val MAX_PACKAGE_NAME_LENGTH = 255
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*$")
    }
}

/**
 * Deep seam used by tools and settings. Android/Shizuku lifecycle details stay behind it;
 * JVM tests use a small fake adapter through the same interface.
 */
interface ExternalPrivilegeBridge {
    fun status(): ExternalPrivilegeBridgeStatus

    suspend fun listPackages(): ExternalPrivilegePackageList

    suspend fun forceStopApp(packageName: String): ExternalPrivilegeActionResult

    suspend fun clearAppCache(packageName: String): ExternalPrivilegeActionResult

    suspend fun startCommand(input: PrivilegedCommandInput): ToolExecutionHandle

    suspend fun cancelAllCommands(): PrivilegedCommandResult

    /** User-initiated settings action only. AI tools never call this method. */
    fun requestPermission()
}

private fun statusResponse(status: ExternalPrivilegeBridgeStatus): String {
    val code = when {
        !status.installed -> "SHIZUKU_NOT_INSTALLED"
        !status.binderAvailable -> "SHIZUKU_NOT_RUNNING"
        !status.permissionGranted -> "SHIZUKU_PERMISSION_REQUIRED"
        !status.userServiceAvailable -> "SHIZUKU_USER_SERVICE_UNAVAILABLE"
        else -> "OK"
    }
    val ok = code == "OK"
    return buildJsonObject {
        put("ok", ok)
        put("code", code)
        put("message", if (ok) "Shizuku bridge is ready." else code.lowercase().replace('_', ' '))
        put("data", buildJsonObject {
            put("installed", status.installed)
            put("binder_available", status.binderAvailable)
            put("permission_granted", status.permissionGranted)
            put("permission_permanently_denied", status.permissionPermanentlyDenied)
            status.apiVersion?.let { put("api_version", it) }
            status.serverVersion?.let { put("server_version", it) }
            status.serverUid?.let { put("server_uid", it) }
            put("privilege", status.privilege.name.lowercase())
            put("user_service_available", status.userServiceAvailable)
        })
    }.toString()
}

internal fun shizukuStatusTool(bridge: ExternalPrivilegeBridge): Tool = Tool(
    name = "shizuku_status",
    description = "Report whether Shizuku or Sui is installed, running, authorized, and ready.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        listOf(UIMessagePart.Text(statusResponse(bridge.status())))
    },
)

internal fun listPackagesTool(bridge: ExternalPrivilegeBridge): Tool = Tool(
    name = "list_packages",
    description = "List packages installed for the current Android user through Shizuku.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val result = bridge.listPackages()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", result.ok)
            put("code", result.code)
            put("message", result.message)
            put("data", buildJsonObject {
                put("count", result.packages.size)
                put("truncated", result.truncated)
                put("packages", buildJsonArray {
                    result.packages.forEach { item ->
                        addJsonObject {
                            put("package_name", item.packageName)
                            item.label?.let { put("label", it) }
                            put("system_app", item.systemApp)
                            put("enabled", item.enabled)
                            item.versionName?.let { put("version_name", it) }
                            put("version_code", item.versionCode)
                        }
                    }
                })
            })
        }.toString()))
    },
)

private fun actionResponse(result: ExternalPrivilegeActionResult): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", result.ok)
        put("code", result.code)
        put("message", result.message)
        put("data", buildJsonObject { })
    }.toString()))

private fun packageMutationTool(
    name: String,
    description: String,
    bridge: ExternalPrivilegeBridge,
    policy: ProtectedPackagePolicy,
    operation: suspend ExternalPrivilegeBridge.(String) -> ExternalPrivilegeActionResult,
): Tool = Tool(
    name = name,
    description = description,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact Android package name; shell syntax is not accepted.")
                })
            },
            required = listOf("package_name"),
        )
    },
    execute = { input ->
        val packageName = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull?.trim()
        policy.validateMutationTarget(packageName)?.let { return@Tool actionResponse(it) }
        actionResponse(bridge.operation(packageName!!))
    },
)

internal fun forceStopAppTool(
    bridge: ExternalPrivilegeBridge,
    policy: ProtectedPackagePolicy,
): Tool = packageMutationTool(
    name = "force_stop_app",
    description = "Force-stop one non-critical app for the current Android user through Shizuku.",
    bridge = bridge,
    policy = policy,
    operation = ExternalPrivilegeBridge::forceStopApp,
)

internal fun clearAppCacheTool(
    bridge: ExternalPrivilegeBridge,
    policy: ProtectedPackagePolicy,
): Tool = packageMutationTool(
    name = "clear_app_cache",
    description = "Clear cache only (never user data) for one non-critical app through Shizuku.",
    bridge = bridge,
    policy = policy,
    operation = ExternalPrivilegeBridge::clearAppCache,
)

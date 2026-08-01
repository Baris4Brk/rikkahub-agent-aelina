package me.rerere.rikkahub.owner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.ai.EmergencyStopOwnerBridge
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

/** Android-owned permission surfaces plus one-way, full-backend Emergency Stop activation. */
class OwnerAndroidControlHandler(
    context: Context,
    private val safety: AgentSafetySettings,
) : OwnerOperationHandler {
    private val appContext = context.applicationContext

    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean = when (request.family) {
        OwnerToolFamily.RUNTIME -> action.type == "runtime_permissions_open"
        OwnerToolFamily.SAFETY -> action.type == "safety_emergency_stop_activate"
        else -> false
    }

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = when (action.type) {
        "runtime_permissions_open" -> {
            val unknown = action.arguments.keys - setOf("permission")
            val permission = action.arguments["permission"]?.jsonPrimitive?.contentOrNull?.lowercase()
            when {
                unknown.isNotEmpty() -> invalid("OWNER_UNSUPPORTED_FIELD", "Unsupported runtime permission fields.")
                permission !in PERMISSIONS -> invalid("RUNTIME_PERMISSION_INVALID", "Supported permission surfaces: ${PERMISSIONS.sorted().joinToString()}.")
                else -> OwnerActionValidation(true, "RUNTIME_PERMISSION_SURFACE_VALID", "Android permission surface validated.")
            }
        }
        "safety_emergency_stop_activate" -> if (action.arguments.isEmpty() && request.actions.size == 1) {
            OwnerActionValidation(true, "EMERGENCY_STOP_VALID", "One-way Emergency Stop activation validated.")
        } else invalid("OWNER_EMERGENCY_STOP_MUST_BE_SINGLE", "Emergency Stop activation accepts no fields and must be the only action.")
        else -> invalid("OWNER_ACTION_UNSUPPORTED", "Android control action is unsupported.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "runtime_permissions_open" -> {
                val permission = requireNotNull(action.arguments["permission"]?.jsonPrimitive?.contentOrNull).lowercase()
                val intent = permissionIntent(permission).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                check(intent.resolveActivity(appContext.packageManager) != null) { "permission_surface_unavailable" }
                appContext.startActivity(intent)
                OwnerAppliedAction(
                    OwnerActionResult(index, action.type, true, "NEEDS_USER_ACTION", "Android permission surface opened; the operating system requires user input."),
                    PermissionSurfaceOpened,
                )
            }
            "safety_emergency_stop_activate" -> {
                val result = EmergencyStopOwnerBridge.activate()
                OwnerAppliedAction(
                    OwnerActionResult(
                        index, action.type, true,
                        if (result.ok) "EMERGENCY_STOP_ACTIVATED" else "EMERGENCY_STOP_PARTIAL",
                        if (result.ok) "Emergency Stop activated across all registered runtimes." else "Emergency Stop gate is active; one or more runtimes reported uncertain termination.",
                    ),
                    EmergencyActivated,
                )
            }
            else -> OwnerAppliedAction(OwnerActionResult(index, action.type, false, "OWNER_ACTION_UNSUPPORTED", "Android control action is unsupported."))
        }
    }.getOrElse {
        OwnerAppliedAction(OwnerActionResult(index, action.type, false, it.safeAndroidCode(), "Android control action failed."))
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        return when (applied.compensationReceipt) {
            PermissionSurfaceOpened -> OwnerActionValidation(true, "NEEDS_USER_ACTION", "Android permission UI was dispatched; only the user can grant it.")
            EmergencyActivated -> if (safety.emergencyStopFlow.first()) {
                OwnerActionValidation(true, "EMERGENCY_STOP_VERIFIED", "Persisted Emergency Stop gate is active.")
            } else invalid("EMERGENCY_STOP_VERIFY_FAILED", "Emergency Stop gate was not persisted.")
            else -> invalid("OWNER_ANDROID_RECEIPT_MISSING", "Android control receipt is missing.")
        }
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = OwnerCompensationResult(false, "ANDROID_USER_OR_SAFETY_ACTION_NOT_REVERSIBLE")

    private fun permissionIntent(permission: String): Intent = when (permission) {
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${appContext.packageName}"))
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "all_files" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${appContext.packageName}"))
        } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))
    }

    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private data object PermissionSurfaceOpened
    private data object EmergencyActivated

    private companion object {
        val PERMISSIONS = setOf("overlay", "accessibility", "all_files", "notifications", "battery", "app_settings")
    }
}

private fun Throwable.safeAndroidCode(): String = message?.takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }?.uppercase()
    ?: "OWNER_ANDROID_CONTROL_FAILED"

package me.rerere.rikkahub.data.ai

import android.app.KeyguardManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.assistant.SystemAssistantInvocationRegistry
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.CapabilityDescriptor
import me.rerere.rikkahub.data.capability.CapabilityId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.ai.tools.local.ContentUriSafetyGuard
import me.rerere.rikkahub.data.ai.tools.local.PathSafetyGuard
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_WRITE_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_WRITE_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_WRITE_TOOL_NAMES
import kotlin.uuid.Uuid

internal fun phoneCallHardBlockReason(
    origin: ToolCallOrigin,
    deviceLocked: Boolean,
): String? = phoneActionHardBlockReason("call_phone", origin, deviceLocked)

internal fun phoneActionHardBlockReason(
    toolName: String,
    origin: ToolCallOrigin,
    deviceLocked: Boolean,
): String? = when {
    origin != ToolCallOrigin.LocalChat -> "$toolName is only allowed from an unlocked local chat."
    deviceLocked -> "$toolName is blocked while the device is locked."
    else -> null
}

internal fun canAutoApproveUnrestrictedCall(
    origin: ToolCallOrigin,
    deviceLocked: Boolean,
    emergencyStop: Boolean,
): Boolean = !emergencyStop && phoneCallHardBlockReason(origin, deviceLocked) == null

internal fun unrestrictedMayBypassCapability(capabilityId: CapabilityId?): Boolean =
    capabilityId != CapabilityId.ExternalPrivilegeBridge &&
        capabilityId != CapabilityId.PrivilegedShell &&
        capabilityId != CapabilityId.StructuredPrivilegedSystemTools &&
        capabilityId != CapabilityId.StructuredPrivilegedSystemToolsV2 &&
        capabilityId != CapabilityId.VerifiedAccessibility

/** Catalog origin restrictions are immutable invocation-surface boundaries. */
internal fun catalogOriginHardBlockReason(
    toolName: String,
    origin: ToolCallOrigin,
    capability: CapabilityDescriptor?,
): String? = when {
    capability == null && origin == ToolCallOrigin.SystemAssistant ->
        "$toolName is not classified for the system-assistant surface."
    capability != null && origin !in capability.allowedOrigins ->
        "$toolName is not allowed from $origin according to the capability catalog. " +
            "This tool can be called from: ${capability.allowedOrigins.joinToString(", ")}."
    origin == ToolCallOrigin.SystemAssistant &&
        !CapabilityCatalog.isAvailableFromSystemAssistant(toolName) ->
        "$toolName requires an Activity or system consent and is unavailable from the " +
            "system-assistant overlay."
    else -> null
}

/** Unrestricted changes approval behavior; it never expands a capability's origins. */
internal fun canApplyUnrestrictedOverride(
    toolName: String,
    origin: ToolCallOrigin,
    capability: CapabilityDescriptor?,
): Boolean = catalogOriginHardBlockReason(toolName, origin, capability) == null

internal fun capabilityRequiresHardUnlock(capabilityId: CapabilityId?): Boolean =
    capabilityId == CapabilityId.StructuredPrivilegedSystemTools ||
        capabilityId == CapabilityId.StructuredPrivilegedSystemToolsV2 ||
        capabilityId == CapabilityId.VerifiedAccessibility

internal fun foregroundRequirementBlockReason(
    toolName: String,
    requiresForegroundApp: Boolean,
    appInForeground: Boolean,
    origin: ToolCallOrigin = ToolCallOrigin.LocalChat,
): String? = InvocationSurfacePolicy.foregroundRequirementBlockReason(
    toolName = toolName,
    origin = origin,
    requiresForegroundApp = requiresForegroundApp,
    appInForeground = appInForeground,
)

private val SYSTEM_ASSISTANT_SENSITIVE_PATH_ARGUMENTS: Map<String, String> = mapOf(
    "list_files" to "path",
    "read_file" to "path",
    "file_info" to "path",
    "find_files" to "root",
    "list_zip_contents" to "source",
)

internal fun systemAssistantSensitivePathBlockReason(
    toolName: String,
    origin: ToolCallOrigin,
    arguments: JsonObject?,
): String? {
    if (origin != ToolCallOrigin.SystemAssistant) return null
    val argumentName = SYSTEM_ASSISTANT_SENSITIVE_PATH_ARGUMENTS[toolName] ?: return null
    val rawPath = (arguments?.get(argumentName) as? JsonPrimitive)
        ?.contentOrNull
        ?: return null
    val violation = if (ContentUriSafetyGuard.isContentUri(rawPath)) {
        ContentUriSafetyGuard.checkSensitiveRead(rawPath)
    } else {
        PathSafetyGuard.checkSensitiveRead(rawPath.removePrefix("file://"))
    }
    return violation?.let {
        "$toolName cannot read protected RikkaHub conversation or settings data " +
            "from the system-assistant surface."
    }
}

/**
 * Unified execution gate for all tool calls regardless of origin.
 *
 * Every tool invocation — from local chat, workflow, Telegram, WebServer, MCP, or external
 * intent — MUST pass through [evaluate] before executing. The gate checks:
 *
 * 1. [AgentSafetySettings.emergencyStop] — hard block when set
 * 2. [AgentSafetySettings.highRiskToolsEnabled] — blocks tools in the [HIGH_RISK_TOOLS] set
 * 3. [AgentSafetySettings.remoteToolCallsEnabled] — blocks non-local origins
 * 4. [AgentSafetySettings.allowWhileDeviceLocked] — blocks when the device is locked
 * 5. Origin-specific restrictions for Telegram / WebServer / MCP / ExternalIntent
 * 6. Approval requirements from [ToolApprovalDefaults]
 *
 * This is NOT a replacement for [ToolApprovalDefaults.requiresApproval] — that layer
 * handles the user-facing approval prompt flow. This gate enforces policy regardless
 * of approval: even if the user has granted "Always Allow", a blocked-by-gate tool
 * never runs.
 */
class ToolExecutionGate(
    private val context: Context,
    private val safetySettings: AgentSafetySettings,
) {
    /**
     * Tools that are considered high-risk and require [AgentSafetySettings.highRiskToolsEnabled]
     * to be true. When the high-risk toggle is off, these tools are blocked at the gate.
     */
    companion object {
        val HIGH_RISK_TOOLS: Set<String> = setOf(
            "install_apk",
            "request_uninstall_package",
            "device_admin_enable",
            "device_lock_now",
            "vpn_start",
            "vpn_stop",
            "vpn_update_config",
            "media_projection_start",
            "media_projection_stop",
            "external_bridge_run_command",
            "external_bridge_grant_appop",
            "termux_run_command",
            "eval_javascript",
            "ssh_exec",
            "ssh_exec_saved",
            "force_stop_app",
            "clear_app_cache",
        ) + STRUCTURED_PRIVILEGED_WRITE_TOOL_NAMES +
            STRUCTURED_PRIVILEGED_V2_WRITE_TOOL_NAMES +
            VERIFIED_ACCESSIBILITY_WRITE_TOOL_NAMES

        /**
         * Tools that are NEVER allowed from remote origins (Telegram / WebServer / MCP /
         * ExternalIntent). LocalChat and TrustedWorkflow are exempt.
         */
        val NEVER_REMOTE: Set<String> = setOf(
            "install_apk",
            "request_uninstall_package",
            "device_admin_enable",
            "device_lock_now",
            "vpn_start",
            "media_projection_start",
            "call_phone",
            "answer_phone_call",
            // Sending SMS from a remote origin is too risky
            "send_sms",
            // Termux and SSH from a remote origin with no local UI to see the prompt
            "termux_run_command",
            "ssh_exec",
            "ssh_exec_saved",
            "shizuku_status",
            "list_packages",
            "force_stop_app",
            "clear_app_cache",
        ) + STRUCTURED_PRIVILEGED_TOOL_NAMES +
            STRUCTURED_PRIVILEGED_V2_TOOL_NAMES +
            VERIFIED_ACCESSIBILITY_TOOL_NAMES

        /**
         * Tools that are blocked when the device is locked, regardless of origin.
         */
        val BLOCKED_WHILE_LOCKED: Set<String> = setOf(
            "install_apk",
            "request_uninstall_package",
            "device_admin_enable",
            "device_lock_now",
            "vpn_start",
            "vpn_update_config",
            "media_projection_start",
            "call_phone",
            "answer_phone_call",
            "send_sms",
            "external_bridge_run_command",
            "external_bridge_grant_appop",
            "take_photo",
            "record_audio",
            "verify_fingerprint",
            "shizuku_status",
            "list_packages",
            "force_stop_app",
            "clear_app_cache",
        ) + STRUCTURED_PRIVILEGED_TOOL_NAMES +
            STRUCTURED_PRIVILEGED_V2_TOOL_NAMES +
            VERIFIED_ACCESSIBILITY_TOOL_NAMES
    }

    /**
     * Result of a gate evaluation.
     */
    sealed class GateResult {
        /** The tool call is allowed. Proceed. */
        data object Allowed : GateResult()

        /** The tool call is denied for the given [reason]. */
        data class Denied(val reason: String) : GateResult()
    }

    private fun isDeviceLocked(): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isDeviceLocked == true || keyguard?.isKeyguardLocked == true
    }

    private fun isAppInForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState
        .isAtLeast(Lifecycle.State.STARTED)

    internal fun canAutoApproveUnrestrictedCallNow(
        origin: ToolCallOrigin,
    ): Boolean = canAutoApproveUnrestrictedCall(
        origin = origin,
        deviceLocked = isDeviceLocked(),
        emergencyStop = safetySettings.isEmergencyStop(),
    )

    /**
     * Evaluate whether [toolName] can execute from [origin] with the given [arguments].
     *
     * @param toolName The name of the tool being called.
     * @param origin The origin of the call.
     * @param arguments The raw JSON arguments (used for parameter-specific gating in the future).
     * @return [GateResult.Allowed] or [GateResult.Denied] with a user-facing reason.
     */
    suspend fun evaluate(
        toolName: String,
        origin: ToolCallOrigin,
        conversationId: Uuid? = null,
        commandId: Uuid? = null,
        arguments: JsonObject? = null,
        /** When true, ALL security gates are bypassed (only emergency stop still applies).
         *  Used by assistants with `unrestricted = true`. */
        unrestrictedOverride: Boolean = false,
    ): GateResult {
        // ── Level 1: Emergency stop ────────────────────────────────────────────────
        if (safetySettings.isEmergencyStop()) {
            return GateResult.Denied("Emergency stop is active — all tool execution is paused. " +
                    "Go to Settings > Safety > Emergency Stop to resume.")
        }

        // A keyguard invocation remains untrusted for its entire lifetime, even if the user
        // unlocks before model generation reaches a tool call. This hard stop intentionally
        // precedes unrestricted and the user-configurable allow-while-locked setting.
        InvocationSurfacePolicy.toolExecutionBlockReason(toolName, origin)?.let { reason ->
            return GateResult.Denied(reason)
        }

        InvocationSurfacePolicy.systemAssistantVisibilityBlockReason(
            origin = origin,
            deviceLocked = isDeviceLocked(),
            hasAuthorizedInvocation = conversationId?.let { id ->
                SystemAssistantInvocationRegistry.hasAuthorizedUnlockedInvocation(id, commandId)
            } == true,
        )?.let { reason ->
            return GateResult.Denied(reason)
        }

        // Direct calls are hard-local and hard-unlocked. These checks intentionally run
        // before unrestrictedOverride so a high-autonomy assistant can skip the approval
        // card locally but can never turn a remote or locked-device request into a call.
        if (toolName == "call_phone" || toolName == "answer_phone_call") {
            phoneActionHardBlockReason(toolName, origin, isDeviceLocked())?.let { reason ->
                return GateResult.Denied(reason)
            }
        }

        // ── Level 0: Unrestricted override ─────────────────────────────────────────
        val catalogEntry = CapabilityCatalog.byToolName(toolName)
        // This check intentionally precedes unrestrictedOverride. A selected high-autonomy
        // assistant can skip approval, but cannot acquire an invocation origin or UI surface
        // that the Catalog never granted.
        catalogOriginHardBlockReason(
            toolName = toolName,
            origin = origin,
            capability = catalogEntry,
        )?.let { reason ->
            return GateResult.Denied(reason)
        }
        systemAssistantSensitivePathBlockReason(
            toolName = toolName,
            origin = origin,
            arguments = arguments,
        )?.let { reason ->
            return GateResult.Denied(reason)
        }
        foregroundRequirementBlockReason(
            toolName = toolName,
            requiresForegroundApp = catalogEntry?.requiresForegroundApp == true,
            appInForeground = isAppInForeground(),
            origin = origin,
        )?.let { reason ->
            return GateResult.Denied(reason)
        }
        if (capabilityRequiresHardUnlock(catalogEntry?.id) && isDeviceLocked()) {
            return GateResult.Denied("Device must be unlocked to use $toolName.")
        }
        // Unrestricted mode never bypasses either fixed bridge operations or the
        // privileged-session shell's high-risk, origin, and lock-screen invariants.
        if (unrestrictedOverride &&
            canApplyUnrestrictedOverride(toolName, origin, catalogEntry) &&
            unrestrictedMayBypassCapability(catalogEntry?.id)
        ) {
            return GateResult.Allowed
        }

        // ── Level 2: High-risk tools gate ──────────────────────────────────────────
        if (toolName in HIGH_RISK_TOOLS && !safetySettings.isHighRiskToolsEnabled()) {
            return GateResult.Denied("High-risk tools are disabled. " +
                    "Enable them in Settings > Safety > High-Risk Tools.")
        }

        // ── Level 3: Remote origin restrictions ────────────────────────────────────
        val isRemote = origin in InvocationSurfacePolicy.REMOTE

        if (isRemote) {
            if (!safetySettings.isRemoteToolCallsEnabled()) {
                return GateResult.Denied("Remote tool calls are disabled. " +
                        "Enable them in Settings > Safety > Remote Tool Calls.")
            }

            if (toolName in NEVER_REMOTE) {
                return GateResult.Denied("$toolName is not allowed from $origin.")
            }
        }

        // ── Level 4: Background automation gate ────────────────────────────────────
        val isBackground = origin == ToolCallOrigin.TrustedWorkflow
        if (isBackground && !safetySettings.isBackgroundAutomationEnabled() &&
            toolName in setOf(
                "schedule_job", "delete_job", "resume_job", "trigger_job_now",
                "workflow_create", "workflow_update", "workflow_delete", "workflow_run"
            )
        ) {
            return GateResult.Denied("Background automation is disabled. " +
                    "Enable it in Settings > Safety > Background Automation.")
        }

        // ── Level 5: Lock screen gate ──────────────────────────────────────────────
        if (toolName in BLOCKED_WHILE_LOCKED || catalogEntry?.requiresUnlockedDevice == true) {
            if (!safetySettings.isAllowWhileDeviceLocked()) {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isLocked = km?.isDeviceLocked == true || km?.isKeyguardLocked == true
                if (isLocked) {
                    return GateResult.Denied("Device is locked and $toolName is blocked while locked. " +
                            "Unlock the device or enable 'Allow while locked' in Settings > Safety.")
                }
            }
        }

        // ── Level 6: CapabilityCatalog origin restriction ─────────────────────────
        return GateResult.Allowed
    }

}

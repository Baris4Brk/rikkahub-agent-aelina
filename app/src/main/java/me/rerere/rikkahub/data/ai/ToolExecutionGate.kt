package me.rerere.rikkahub.data.ai

import android.app.KeyguardManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.assistant.SystemAssistantInvocationRegistry
import me.rerere.rikkahub.quickcapture.QuickCaptureInvocationRegistry
import me.rerere.rikkahub.quickcapture.InvocationSurfaceContexts
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.CapabilityDescriptor
import me.rerere.rikkahub.data.capability.CapabilityId
import me.rerere.rikkahub.data.capability.CapabilityKey
import me.rerere.rikkahub.data.capability.CapabilityPolicyEngine
import me.rerere.rikkahub.data.capability.CapabilityRequest
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.DefaultCapabilityPolicyEngine
import me.rerere.rikkahub.data.capability.PolicyDecision
import me.rerere.rikkahub.data.capability.ToolCapabilityResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.ai.tools.local.ContentUriSafetyGuard
import me.rerere.rikkahub.data.ai.tools.local.PathSafetyGuard
import me.rerere.rikkahub.data.ai.tools.SelfPreservationPolicy
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_WRITE_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_WRITE_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES
import me.rerere.rikkahub.privilege.DefaultHardDenyPolicy
import me.rerere.rikkahub.privilege.HardDenyDecision
import me.rerere.rikkahub.privilege.HardDenyPolicy
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
    capability == null && origin in setOf(ToolCallOrigin.SystemAssistant, ToolCallOrigin.QuickCapture) ->
        "$toolName is not classified for the assistant-overlay surface."
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

private val SELF_PACKAGE_MUTATION_TOOLS = setOf(
    "force_stop_app",
    "clear_app_cache",
    "privileged_package_disable",
    "privileged_package_suspend",
    "privileged_package_uninstall",
    "privileged_appop_set",
    "privileged_appop_reset",
    "privileged_permission_revoke",
)

internal fun selfPreservationBlockReason(
    toolName: String,
    arguments: JsonObject?,
    policy: SelfPreservationPolicy,
): String? {
    if (arguments == null) return null
    if (toolName in SELF_PACKAGE_MUTATION_TOOLS) {
        val targetPackage = sequenceOf("package_name", "package", "pkg")
            .mapNotNull { key -> (arguments[key] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull()
        policy.checkPackageMutation(targetPackage)?.let { return it.reason }
    }
    val pathMutation = toolName in setOf(
        "download_file",
        "write_text_file",
        "write_binary_file",
        "delete_file",
        "move_file",
        "copy_file",
        "create_directory",
        "batch_copy",
        "batch_move",
        "batch_delete",
        "zip_files",
        "unzip_file",
        "workspace_write_file",
        "workspace_edit_file",
    )
    if (pathMutation) {
        fun strings(element: JsonElement): Sequence<String> = when (element) {
            is JsonPrimitive -> element.contentOrNull?.let(::sequenceOf) ?: emptySequence()
            is JsonArray -> element.asSequence().flatMap(::strings)
            is JsonObject -> element.values.asSequence().flatMap(::strings)
        }
        strings(arguments).forEach { path ->
            policy.checkAppPrivateMutation(path)?.let { return it.reason }
        }
    }
    return null
}

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
    if (origin != ToolCallOrigin.SystemAssistant && origin != ToolCallOrigin.QuickCapture) return null
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
            "from the assistant-overlay surface."
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
    private val hardDenyPolicy: HardDenyPolicy = DefaultHardDenyPolicy(context.packageName),
    private val capabilityPolicyEngine: CapabilityPolicyEngine = DefaultCapabilityPolicyEngine(),
) {
    private val selfPreservationPolicy by lazy {
        SelfPreservationPolicy.forApplication(context.packageName)
    }
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
            "linux_run",
            "linux_session_create",
            "linux_session_exec",
            "linux_session_close",
            "linux_grant_request",
            "linux_grant_revoke",
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

    private fun isProtectedSelfSettingsSurface(toolName: String): Boolean {
        val legacyMutation = toolName in setOf(
                "tap",
                "click_node",
                "long_press",
                "set_text",
                "global_action",
            )
        if (!legacyMutation && toolName !in VERIFIED_ACCESSIBILITY_WRITE_TOOL_NAMES) return false
        val root = RikkaAccessibilityService.instance?.rootInActiveWindow ?: return false
        val windowPackage = root.packageName?.toString().orEmpty()
        if (windowPackage !in setOf(
                "com.android.settings",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
            )
        ) return false
        val appLabel = context.applicationInfo.loadLabel(context.packageManager)
            .toString().lowercase()
        val text = buildString {
            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 160) {
                val node = queue.removeFirst()
                append(' ')
                append(node.text?.toString().orEmpty().lowercase())
                append(' ')
                append(node.contentDescription?.toString().orEmpty().lowercase())
                repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
            }
        }
        val identifiesSelf = text.contains(context.packageName.lowercase()) ||
            (appLabel.isNotBlank() && text.contains(appLabel))
        val destructiveSurface = listOf(
            "uninstall",
            "clear data",
            "clear storage",
            "force stop",
            "disable",
            "卸载",
            "清除数据",
            "清除存储",
            "强行停止",
            "停用",
        ).any(text::contains)
        return identifiesSelf && destructiveSurface
    }

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
        /** Explicit principal for the new scoped-policy path; null preserves legacy callers. */
        capabilitySubject: CapabilitySubject? = null,
        /** Bound second-user conversation proof captured at command admission time. */
        selectedPrivilegedConversation: Boolean = false,
        /** Immutable workflow capability snapshot, empty for ordinary requests. */
        frozenCapabilities: Set<CapabilityKey> = emptySet(),
        /**
         * Compatibility approval override. Permanent denial, origin, lock-screen, Android
         * permission and protected-runtime checks still run first. Assistant.unrestricted is
         * never permitted to populate this value.
         */
        unrestrictedOverride: Boolean = false,
    ): GateResult {
        // ── Level 1: Emergency stop ────────────────────────────────────────────────
        if (safetySettings.isEmergencyStop()) {
            return GateResult.Denied("Emergency stop is active — all tool execution is paused. " +
                    "Go to Settings > Safety > Emergency Stop to resume.")
        }
        if (origin == ToolCallOrigin.PetInteraction) {
            return GateResult.Denied("pet_interaction_tools_forbidden")
        }

        // A keyguard invocation remains untrusted for its entire lifetime, even if the user
        // unlocks before model generation reaches a tool call. This hard stop intentionally
        // precedes unrestricted and the user-configurable allow-while-locked setting.
        InvocationSurfacePolicy.toolExecutionBlockReason(toolName, origin)?.let { reason ->
            return GateResult.Denied(reason)
        }

        // The shared permanent floor intentionally runs before every approval or unrestricted
        // decision. GenerationHandler and workflow still invoke it defensively, but putting it
        // here gives every ToolRuntime caller the same outcome.
        when (val hardDeny = hardDenyPolicy.checkTool(toolName, arguments)) {
            HardDenyDecision.Allowed -> Unit
            is HardDenyDecision.Denied -> {
                return GateResult.Denied("${hardDeny.code}: ${hardDeny.message}")
            }
        }

        val deviceLocked = isDeviceLocked()
        capabilitySubject?.let { subject ->
            val resolved = ToolCapabilityResolver.resolve(
                toolName = toolName,
                args = arguments ?: JsonObject(emptyMap()),
            )
            when (
                val decision = capabilityPolicyEngine.evaluate(
                    CapabilityRequest(
                        subject = subject,
                        origin = origin,
                        capabilities = resolved.capabilities,
                        resource = resolved.resource,
                        catalogCapability = resolved.catalogCapability,
                        conversationId = conversationId?.toString(),
                        executionId = commandId?.toString(),
                        deviceUnlocked = !deviceLocked,
                        selectedPrivilegedConversation = selectedPrivilegedConversation,
                        frozenCapabilities = frozenCapabilities,
                    ),
                )
            ) {
                is PolicyDecision.Denied -> {
                    return GateResult.Denied("${decision.code}: ${decision.message}")
                }

                PolicyDecision.Abstain,
                is PolicyDecision.Allowed,
                -> Unit
            }
        }
        val hasAuthorizedInvocation = conversationId?.let { id ->
            when (origin) {
                ToolCallOrigin.QuickCapture -> QuickCaptureInvocationRegistry.hasAuthorizedRun(id, commandId)
                else -> SystemAssistantInvocationRegistry.hasAuthorizedUnlockedInvocation(id, commandId)
            }
        } == true
        val surfaceContext = conversationId?.let { id ->
            InvocationSurfaceContexts.currentContext(origin, id, commandId)
        }
        val toolExposurePlan = ToolExposurePlan.create(
            origin = origin,
            deviceLocked = deviceLocked,
            hasAuthorizedInvocation = hasAuthorizedInvocation,
            surfaceContext = surfaceContext,
        )
        InvocationSurfacePolicy.systemAssistantVisibilityBlockReason(
            origin = origin,
            deviceLocked = deviceLocked,
            hasAuthorizedInvocation = hasAuthorizedInvocation,
        )?.let { reason ->
            return GateResult.Denied(reason)
        }

        // Direct calls are hard-local and hard-unlocked. These checks intentionally run
        // before unrestrictedOverride so a high-autonomy assistant can skip the approval
        // card locally but can never turn a remote or locked-device request into a call.
        if (toolName == "call_phone" || toolName == "answer_phone_call") {
            phoneActionHardBlockReason(toolName, origin, deviceLocked)?.let { reason ->
                return GateResult.Denied(reason)
            }
        }

        selfPreservationBlockReason(
            toolName = toolName,
            arguments = arguments,
            policy = selfPreservationPolicy,
        )?.let { reason ->
            return GateResult.Denied(reason)
        }
        if (isProtectedSelfSettingsSurface(toolName)) {
            return GateResult.Denied(
                "Accessibility actions are blocked on RikkaHub's uninstall, app-info, or data-clear surface.",
            )
        }

        // ── Level 0: Unrestricted override ─────────────────────────────────────────
        val catalogEntry = CapabilityCatalog.byToolName(toolName)
        // This check intentionally precedes unrestrictedOverride. A selected high-autonomy
        // assistant can skip approval, but cannot acquire an invocation origin or UI surface
        // that the Catalog never granted.
        val catalogBlockReason = if (origin == ToolCallOrigin.SystemAssistant || origin == ToolCallOrigin.QuickCapture) {
            toolExposurePlan.blockReason(toolName)
        } else {
            catalogOriginHardBlockReason(
                toolName = toolName,
                origin = origin,
                capability = catalogEntry,
            )
        }
        catalogBlockReason?.let { reason ->
            return GateResult.Denied(reason)
        }
        systemAssistantSensitivePathBlockReason(
            toolName = toolName,
            origin = origin,
            arguments = arguments,
        )?.let { reason ->
            return GateResult.Denied(reason)
        }
        if (!toolExposurePlan.activityOverlayAuthorized) {
            foregroundRequirementBlockReason(
                toolName = toolName,
                requiresForegroundApp = catalogEntry?.requiresForegroundApp == true,
                appInForeground = isAppInForeground(),
                origin = origin,
            )?.let { reason ->
                return GateResult.Denied(reason)
            }
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

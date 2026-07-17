package me.rerere.rikkahub.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.keyboard.KeyboardApiClient
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver
import me.rerere.rikkahub.privilege.ShizukuBridgeManager
import me.rerere.rikkahub.service.WorkspaceProcessService
import me.rerere.workspace.WorkspaceProcessManager
import kotlin.uuid.Uuid

class RuntimeDiagnosticsProvider(
    context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val safetySettings: AgentSafetySettings,
    private val shizukuBridgeManager: ShizukuBridgeManager,
    private val workspaceProcessManager: WorkspaceProcessManager,
    private val keyboardApiClient: KeyboardApiClient,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    suspend fun refresh(conversationId: String?): RuntimeDiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val privilege = resolvePrivilege(conversationId)
        val bridge = shizukuBridgeManager.status()
        val workspace = workspaceProcessManager.summary.value
        val accessibilityEnabled = AccessibilityServiceHandle.isEnabledInSettings(appContext)
        val notificationListenerEnabled = NotificationListenerHandle.isEnabledInSettings(appContext)
        val defaultInputMethod = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty()
        val keyboardInstalled = keyboardApiClient.isKeyboardInstalled()
        val keyboardSelected = defaultInputMethod.substringBefore('/') == KeyboardApiClient.KEYBOARD_PACKAGE
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        val batteryExempt = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val outputRegexSummary = resolveOutputRegexSummary(conversationId)

        val snapshot = buildRuntimeDiagnosticsSnapshot(
            RuntimeDiagnosticsRawState(
                conversationId = conversationId,
                privilege = privilege,
                bridgeInstalled = bridge.installed,
                bridgeBinderAvailable = bridge.binderAvailable,
                bridgePermissionGranted = bridge.permissionGranted,
                bridgeUserServiceAvailable = bridge.userServiceAvailable,
                bridgeUserServiceConnected = shizukuBridgeManager.isUserServiceConnected,
                bridgePrivilege = bridge.privilege.name.lowercase(),
                activeBridgeCommands = shizukuBridgeManager.activeCommandCount,
                workspaceActiveCount = workspace.activeCount,
                workspaceRecoveringCount = workspace.recoveringCount,
                workspaceDesiredRunningCount = workspace.desiredRunningCount,
                workspaceKeepAwakeCount = workspace.keepAwakeCount,
                workspaceWakeLockHeld = WorkspaceProcessService.isWakeLockHeld,
                accessibilityEnabled = accessibilityEnabled,
                accessibilityRunning = AccessibilityServiceHandle.isRunning(),
                notificationListenerEnabled = notificationListenerEnabled,
                notificationListenerRunning = NotificationListenerHandle.isBound(),
                appNotificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
                keyboardInstalled = keyboardInstalled,
                keyboardSelected = keyboardSelected,
                termuxState = when (TermuxIntegration.state(appContext)) {
                    TermuxIntegration.State.NOT_INSTALLED -> RuntimeTermuxState.NOT_INSTALLED
                    TermuxIntegration.State.NO_PERMISSION -> RuntimeTermuxState.NOT_AUTHORIZED
                    TermuxIntegration.State.READY -> RuntimeTermuxState.READY
                },
                emergencyStopActive = safetySettings.isEmergencyStop(),
                batteryOptimizationExempt = batteryExempt,
                honorOrHuaweiDevice = manufacturer.contains("honor", ignoreCase = true) ||
                    manufacturer.contains("huawei", ignoreCase = true),
                manufacturer = manufacturer.ifBlank { "unknown" },
            ),
            clockMillis(),
        )
        val generation = RecentGenerationDiagnostics.snapshot()
        val extraItems = buildList {
            generation?.let {
                add(RuntimeDiagnosticItem(
                    id = "recent_generation",
                    title = "Recent generation outcome",
                    status = if (it.completionOutcome == "Completed" ||
                        it.recoveryStatus == "SUCCEEDED"
                    ) {
                        RuntimeDiagnosticStatus.READY
                    } else {
                        RuntimeDiagnosticStatus.SERVICE_OFFLINE
                    },
                    detail = it.redactedDetail(),
                ))
            }
            add(RuntimeDiagnosticItem(
                id = "assistant_output_regex",
                title = "Assistant output regex rules",
                status = if (outputRegexSummary != null) {
                    RuntimeDiagnosticStatus.READY
                } else {
                    RuntimeDiagnosticStatus.NOT_SUPPORTED
                },
                detail = outputRegexSummary ?: "No conversation or fixed assistant was resolved.",
            ))
        }
        snapshot.copy(items = snapshot.items + extraItems)
    }

    private suspend fun resolveOutputRegexSummary(conversationId: String?): String? {
        val settings = settingsStore.settingsFlow.value
        val conversationAssistantId = conversationId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { conversationRepository.getConversationById(it) }
            ?.assistantId
        val assistantId = conversationAssistantId ?: settings.systemAssistantTargetAssistantId
        val assistant = settings.assistants.firstOrNull { it.id == assistantId } ?: return null
        val enabled = assistant.regexes.filter { it.enabled }
        val assistantRules = enabled.filter { AssistantAffectScope.ASSISTANT in it.affectingScope }
        return "enabled=${enabled.size}; assistantOutput=${assistantRules.size}; " +
            "canonical=${assistantRules.count { !it.visualOnly }}; " +
            "visualOnly=${assistantRules.count { it.visualOnly }}"
    }

    private suspend fun resolvePrivilege(conversationId: String?): RuntimePrivilegeDiagnostic {
        if (conversationId == null) {
            return RuntimePrivilegeDiagnostic(
                selected = false,
                privileged = false,
                autoApprove = false,
                unrestricted = false,
                detail = "未选择会话。请从聊天页打开诊断以检查该会话的真实权限。",
            )
        }
        val id = runCatching { Uuid.parse(conversationId) }.getOrNull()
            ?: return RuntimePrivilegeDiagnostic.invalid("会话标识无效。")
        val conversation = conversationRepository.getConversationById(id)
            ?: return RuntimePrivilegeDiagnostic.invalid("会话不存在。")
        val assistant = settingsStore.settingsFlow.value.assistants
            .firstOrNull { it.id == conversation.assistantId }
            ?: return RuntimePrivilegeDiagnostic.invalid("会话所属助手不存在。")
        val resolved = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation,
            origin = ToolCallOrigin.LocalChat,
        )
        return RuntimePrivilegeDiagnostic(
            selected = true,
            privileged = resolved.isPrivileged,
            autoApprove = resolved.autoApproveTools,
            unrestricted = resolved.unrestrictedOverride,
            detail = if (resolved.isPrivileged) {
                "${resolved.identityName}；来源 LocalChat；仅此会话获得扩展工具面。"
            } else {
                "当前会话不是该助手指定的特权会话。"
            },
        )
    }
}

internal data class RuntimePrivilegeDiagnostic(
    val selected: Boolean,
    val privileged: Boolean,
    val autoApprove: Boolean,
    val unrestricted: Boolean,
    val detail: String,
) {
    companion object {
        fun invalid(detail: String) = RuntimePrivilegeDiagnostic(
            selected = true,
            privileged = false,
            autoApprove = false,
            unrestricted = false,
            detail = detail,
        )
    }
}

internal enum class RuntimeTermuxState { NOT_INSTALLED, NOT_AUTHORIZED, READY }

internal data class RuntimeDiagnosticsRawState(
    val conversationId: String?,
    val privilege: RuntimePrivilegeDiagnostic,
    val bridgeInstalled: Boolean,
    val bridgeBinderAvailable: Boolean,
    val bridgePermissionGranted: Boolean,
    val bridgeUserServiceAvailable: Boolean,
    val bridgeUserServiceConnected: Boolean,
    val bridgePrivilege: String,
    val activeBridgeCommands: Int,
    val workspaceActiveCount: Int,
    val workspaceRecoveringCount: Int,
    val workspaceDesiredRunningCount: Int,
    val workspaceKeepAwakeCount: Int,
    val workspaceWakeLockHeld: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityRunning: Boolean,
    val notificationListenerEnabled: Boolean,
    val notificationListenerRunning: Boolean,
    val appNotificationsEnabled: Boolean,
    val keyboardInstalled: Boolean,
    val keyboardSelected: Boolean,
    val termuxState: RuntimeTermuxState,
    val emergencyStopActive: Boolean,
    val batteryOptimizationExempt: Boolean,
    val honorOrHuaweiDevice: Boolean,
    val manufacturer: String,
)

internal fun buildRuntimeDiagnosticsSnapshot(
    state: RuntimeDiagnosticsRawState,
    collectedAtEpochMs: Long,
): RuntimeDiagnosticsSnapshot {
    val bridgeStatus = bridgeDiagnosticStatus(
        installed = state.bridgeInstalled,
        binderAvailable = state.bridgeBinderAvailable,
        permissionGranted = state.bridgePermissionGranted,
        userServiceAvailable = state.bridgeUserServiceAvailable,
    )
    val privilegeStatus = if (state.privilege.privileged) {
        RuntimeDiagnosticStatus.READY
    } else {
        RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED
    }
    val workspaceStatus = when {
        state.workspaceDesiredRunningCount > 0 &&
            state.workspaceActiveCount == 0 && state.workspaceRecoveringCount == 0 -> {
            RuntimeDiagnosticStatus.SERVICE_OFFLINE
        }
        else -> RuntimeDiagnosticStatus.READY
    }

    return RuntimeDiagnosticsSnapshot(
        conversationId = state.conversationId,
        collectedAtEpochMs = collectedAtEpochMs,
        items = listOf(
            RuntimeDiagnosticItem(
                id = "privileged_session",
                title = "特权会话",
                status = privilegeStatus,
                detail = state.privilege.detail,
            ),
            RuntimeDiagnosticItem(
                id = "approval_policy",
                title = "本轮审批与 unrestricted",
                status = privilegeStatus,
                detail = "自动审批=${state.privilege.autoApprove}；effectiveUnrestricted=${state.privilege.unrestricted}",
            ),
            RuntimeDiagnosticItem(
                id = "shizuku_bridge",
                title = "Shizuku / Sui Bridge",
                status = bridgeStatus,
                detail = "权限=${state.bridgePermissionGranted}；活动命令=${state.activeBridgeCommands}",
                fix = RuntimeDiagnosticFix.SHIZUKU_APP,
            ),
            RuntimeDiagnosticItem(
                id = "user_service",
                title = "UserService v2",
                status = bridgeStatus,
                detail = if (state.bridgeUserServiceConnected) "已连接" else "可用时按需连接；当前未绑定",
                fix = RuntimeDiagnosticFix.SHIZUKU_APP,
            ),
            RuntimeDiagnosticItem(
                id = "shell_privilege",
                title = "Shell / Root 权限级别",
                status = bridgeStatus,
                detail = "当前级别=${state.bridgePrivilege}",
            ),
            RuntimeDiagnosticItem(
                id = "workspace_processes",
                title = "Workspace 长期进程",
                status = workspaceStatus,
                detail = "运行=${state.workspaceActiveCount}；恢复中=${state.workspaceRecoveringCount}；期望运行=${state.workspaceDesiredRunningCount}",
            ),
            RuntimeDiagnosticItem(
                id = "workspace_wake_lock",
                title = "Workspace 保持唤醒",
                status = if (state.workspaceKeepAwakeCount > 0 && !state.workspaceWakeLockHeld) {
                    RuntimeDiagnosticStatus.SERVICE_OFFLINE
                } else {
                    RuntimeDiagnosticStatus.READY
                },
                detail = "keepAwake 进程=${state.workspaceKeepAwakeCount}；实际 WakeLock=${state.workspaceWakeLockHeld}",
            ),
            RuntimeDiagnosticItem(
                id = "accessibility",
                title = "无障碍服务",
                status = enabledServiceDiagnosticStatus(state.accessibilityEnabled, state.accessibilityRunning),
                detail = "系统已授权=${state.accessibilityEnabled}；服务已连接=${state.accessibilityRunning}",
                fix = RuntimeDiagnosticFix.ACCESSIBILITY_SETTINGS,
            ),
            RuntimeDiagnosticItem(
                id = "notification_listener",
                title = "通知监听服务",
                status = enabledServiceDiagnosticStatus(
                    state.notificationListenerEnabled,
                    state.notificationListenerRunning,
                ),
                detail = "系统已授权=${state.notificationListenerEnabled}；服务已连接=${state.notificationListenerRunning}",
                fix = RuntimeDiagnosticFix.NOTIFICATION_LISTENER_SETTINGS,
            ),
            RuntimeDiagnosticItem(
                id = "app_notifications",
                title = "App 通知权限",
                status = if (state.appNotificationsEnabled) RuntimeDiagnosticStatus.READY
                    else RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED,
                detail = "通知已允许=${state.appNotificationsEnabled}",
                fix = RuntimeDiagnosticFix.NOTIFICATION_SETTINGS,
            ),
            RuntimeDiagnosticItem(
                id = "agent_keyboard",
                title = "Agent Keyboard",
                status = when {
                    !state.keyboardInstalled -> RuntimeDiagnosticStatus.NOT_SUPPORTED
                    !state.keyboardSelected -> RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED
                    else -> RuntimeDiagnosticStatus.READY
                },
                detail = "已安装=${state.keyboardInstalled}；当前输入法=${state.keyboardSelected}",
                fix = RuntimeDiagnosticFix.INPUT_METHOD_SETTINGS,
            ),
            RuntimeDiagnosticItem(
                id = "termux",
                title = "Termux",
                status = when (state.termuxState) {
                    RuntimeTermuxState.NOT_INSTALLED -> RuntimeDiagnosticStatus.NOT_SUPPORTED
                    RuntimeTermuxState.NOT_AUTHORIZED -> RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED
                    RuntimeTermuxState.READY -> RuntimeDiagnosticStatus.READY
                },
                detail = "状态=${state.termuxState.name}",
                fix = RuntimeDiagnosticFix.TERMUX_APP,
            ),
            RuntimeDiagnosticItem(
                id = "emergency_stop",
                title = "Emergency Stop",
                status = if (state.emergencyStopActive) {
                    RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED
                } else {
                    RuntimeDiagnosticStatus.READY
                },
                detail = if (state.emergencyStopActive) "已开启；新的高权限任务会被拒绝" else "未开启",
            ),
            RuntimeDiagnosticItem(
                id = "battery_optimization",
                title = "电池优化",
                status = if (state.batteryOptimizationExempt) RuntimeDiagnosticStatus.READY
                    else RuntimeDiagnosticStatus.OEM_RESTRICTED,
                detail = "已忽略电池优化=${state.batteryOptimizationExempt}",
                fix = RuntimeDiagnosticFix.BATTERY_OPTIMIZATION_SETTINGS,
            ),
            RuntimeDiagnosticItem(
                id = "oem_background",
                title = "荣耀 / 华为后台限制",
                status = if (state.honorOrHuaweiDevice) RuntimeDiagnosticStatus.OEM_RESTRICTED
                    else RuntimeDiagnosticStatus.NOT_SUPPORTED,
                detail = if (state.honorOrHuaweiDevice) {
                    "设备厂商=${state.manufacturer}；系统没有公开 API 验证自启动、后台运行和最近任务锁定，请人工确认"
                } else {
                    "当前设备不使用荣耀 / 华为专项检查"
                },
                fix = RuntimeDiagnosticFix.APPLICATION_DETAILS,
            ),
        ),
    )
}

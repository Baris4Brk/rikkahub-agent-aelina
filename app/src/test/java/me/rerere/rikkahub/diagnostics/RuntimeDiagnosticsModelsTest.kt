package me.rerere.rikkahub.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticsModelsTest {
    @Test
    fun `bridge status distinguishes unavailable unauthorized offline and ready`() {
        assertEquals(
            RuntimeDiagnosticStatus.NOT_SUPPORTED,
            bridgeDiagnosticStatus(installed = false, binderAvailable = false, permissionGranted = false, userServiceAvailable = false),
        )
        assertEquals(
            RuntimeDiagnosticStatus.SERVICE_OFFLINE,
            bridgeDiagnosticStatus(installed = true, binderAvailable = false, permissionGranted = false, userServiceAvailable = false),
        )
        assertEquals(
            RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED,
            bridgeDiagnosticStatus(installed = true, binderAvailable = true, permissionGranted = false, userServiceAvailable = false),
        )
        assertEquals(
            RuntimeDiagnosticStatus.NOT_SUPPORTED,
            bridgeDiagnosticStatus(installed = true, binderAvailable = true, permissionGranted = true, userServiceAvailable = false),
        )
        assertEquals(
            RuntimeDiagnosticStatus.READY,
            bridgeDiagnosticStatus(installed = true, binderAvailable = true, permissionGranted = true, userServiceAvailable = true),
        )
    }

    @Test
    fun `service status distinguishes missing authorization from a disconnected service`() {
        assertEquals(
            RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED,
            enabledServiceDiagnosticStatus(enabled = false, running = false),
        )
        assertEquals(
            RuntimeDiagnosticStatus.SERVICE_OFFLINE,
            enabledServiceDiagnosticStatus(enabled = true, running = false),
        )
        assertEquals(
            RuntimeDiagnosticStatus.READY,
            enabledServiceDiagnosticStatus(enabled = true, running = true),
        )
    }

    @Test
    fun `redacted export keeps diagnostic states but removes sensitive detail`() {
        val snapshot = RuntimeDiagnosticsSnapshot(
            conversationId = "conversation-1",
            collectedAtEpochMs = 123L,
            items = listOf(
                RuntimeDiagnosticItem(
                    id = "safe",
                    title = "Safe row",
                    status = RuntimeDiagnosticStatus.READY,
                    detail = "active commands: 2",
                ),
                RuntimeDiagnosticItem(
                    id = "sensitive",
                    title = "Sensitive row",
                    status = RuntimeDiagnosticStatus.SERVICE_OFFLINE,
                    detail = "token=secret-value command=rm -rf /data",
                ),
            ),
        )

        val exported = snapshot.toRedactedJson()

        assertTrue(exported.contains("conversation-1"))
        assertTrue(exported.contains("active commands: 2"))
        assertTrue(exported.contains("Sensitive diagnostic detail redacted"))
        assertFalse(exported.contains("secret-value"))
        assertFalse(exported.contains("rm -rf"))
        assertTrue(exported.contains("SERVICE_OFFLINE"))
    }

    @Test
    fun `snapshot reports missing session and degraded runtime services honestly`() {
        val snapshot = buildRuntimeDiagnosticsSnapshot(
            state = RuntimeDiagnosticsRawState(
                conversationId = null,
                privilege = RuntimePrivilegeDiagnostic(
                    selected = false,
                    privileged = false,
                    autoApprove = false,
                    unrestricted = false,
                    detail = "未选择会话。",
                ),
                bridgeInstalled = true,
                bridgeBinderAvailable = false,
                bridgePermissionGranted = false,
                bridgeUserServiceAvailable = false,
                bridgeUserServiceConnected = false,
                bridgePrivilege = "unavailable",
                activeBridgeCommands = 0,
                workspaceActiveCount = 0,
                workspaceRecoveringCount = 0,
                workspaceDesiredRunningCount = 2,
                workspaceKeepAwakeCount = 1,
                workspaceWakeLockHeld = false,
                accessibilityEnabled = true,
                accessibilityRunning = false,
                notificationListenerEnabled = false,
                notificationListenerRunning = false,
                appNotificationsEnabled = false,
                keyboardInstalled = true,
                keyboardSelected = false,
                termuxState = RuntimeTermuxState.NOT_AUTHORIZED,
                emergencyStopActive = true,
                batteryOptimizationExempt = false,
                honorOrHuaweiDevice = true,
                manufacturer = "HONOR",
            ),
            collectedAtEpochMs = 456L,
        )

        fun status(id: String) = snapshot.items.single { it.id == id }.status
        assertEquals(RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED, status("privileged_session"))
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, status("shizuku_bridge"))
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, status("workspace_processes"))
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, status("workspace_wake_lock"))
        assertEquals(RuntimeDiagnosticStatus.SERVICE_OFFLINE, status("accessibility"))
        assertEquals(RuntimeDiagnosticStatus.IMPLEMENTED_BUT_NOT_AUTHORIZED, status("notification_listener"))
        assertEquals(RuntimeDiagnosticStatus.OEM_RESTRICTED, status("battery_optimization"))
        assertEquals(RuntimeDiagnosticStatus.OEM_RESTRICTED, status("oem_background"))
    }
}

package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.workspace.WorkspaceStorageMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SecondUserDeviceAccessAddendumTest {
    @Test
    fun `ordinary conversation receives no device routing addendum`() {
        assertNull(
            secondUserDeviceAccessAddendum(
                privilege = context(expanded = false),
                workspaceId = "workspace-1",
                workspaceStorageMode = WorkspaceStorageMode.PRIVATE.name,
                workspaceShellSharedStorage = false,
            ),
        )
    }

    @Test
    fun `confirmed second user receives phone workspace and termux path mapping`() {
        val prompt = requireNotNull(
            secondUserDeviceAccessAddendum(
                privilege = context(expanded = true),
                workspaceId = "workspace-1",
                workspaceStorageMode = WorkspaceStorageMode.SHARED.name,
                workspaceShellSharedStorage = true,
            ),
        )

        assertTrue(prompt.contains("/storage/emulated/0"))
        assertTrue(prompt.contains("/sdcard"))
        assertTrue(prompt.contains("/workspace"))
        assertTrue(prompt.contains("~/storage/shared/RikkaHubExchange"))
        assertTrue(prompt.contains("RikkaHubExchange/workspaces/workspace-1"))
        assertFalse(prompt.contains("command="))
        assertFalse(prompt.contains("password"))
    }

    @Test
    fun `private workspace is explicitly separated from phone file storage`() {
        val prompt = requireNotNull(
            secondUserDeviceAccessAddendum(
                privilege = context(expanded = true),
                workspaceId = "workspace-1",
                workspaceStorageMode = WorkspaceStorageMode.PRIVATE.name,
                workspaceShellSharedStorage = false,
            ),
        )

        assertTrue(prompt.contains("PRIVATE storage"))
        assertTrue(prompt.contains("not directly visible in Android file managers"))
        assertTrue(prompt.contains("does not currently have the shared-storage bind mount"))
    }

    private fun context(expanded: Boolean) = PrivilegedSessionContext(
        assistantId = Uuid.random(),
        conversationId = Uuid.random(),
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = Uuid.random(),
        identityName = "second user",
        isPrivileged = expanded,
        expandLocalTools = expanded,
        autoApproveTools = expanded,
        unrestrictedOverride = false,
    )
}

package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.CapabilityCatalog
import me.rerere.rikkahub.data.capability.CapabilityId
import me.rerere.rikkahub.data.capability.ImplementationState
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceProcessToolsTest {
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()
    private val privileged = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = conversationId,
        identityName = "Second user",
        isPrivileged = true,
        expandLocalTools = true,
        autoApproveTools = true,
        unrestrictedOverride = true,
    )

    @Test
    fun `managed processes are injected only into privileged local foreground sessions`() {
        assertTrue(
            shouldInjectWorkspaceProcessTools(privileged, ToolCallOrigin.LocalChat, false),
        )
        assertTrue(
            shouldInjectWorkspaceProcessTools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistant),
                ToolCallOrigin.SystemAssistant,
                false,
            ),
        )
        assertFalse(
            shouldInjectWorkspaceProcessTools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistantKeyguard),
                ToolCallOrigin.SystemAssistantKeyguard,
                false,
            ),
        )
        assertFalse(
            shouldInjectWorkspaceProcessTools(privileged, ToolCallOrigin.Telegram, false),
        )
        assertFalse(
            shouldInjectWorkspaceProcessTools(privileged, ToolCallOrigin.LocalChat, true),
        )
        assertFalse(
            shouldInjectWorkspaceProcessTools(
                privileged.copy(isPrivileged = false),
                ToolCallOrigin.LocalChat,
                false,
            ),
        )
    }

    @Test
    fun `catalog exposes the exact six managed process tools without an ordinary switch`() {
        val capability = CapabilityCatalog.capabilityOf(CapabilityId.WorkspaceProcessManagement)

        assertEquals(ImplementationState.Implemented, capability?.implementationState)
        assertNull(capability?.localToolOption)
        assertEquals(WORKSPACE_PROCESS_TOOL_NAMES, capability?.toolNames)
        assertEquals(
            InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER,
            capability?.allowedOrigins,
        )
        assertFalse(capability?.requiresUnlockedDevice ?: true)

        WORKSPACE_PROCESS_TOOL_NAMES.forEach { toolName ->
            assertEquals(CapabilityId.WorkspaceProcessManagement, CapabilityCatalog.byToolName(toolName)?.id)
        }
    }

    @Test
    fun `approval defaults remain defensive when a tool is injected incorrectly`() {
        WORKSPACE_PROCESS_TOOL_NAMES.forEach { toolName ->
            assertTrue(ToolApprovalDefaults.requiresApproval(toolName))
            assertFalse(ToolApprovalDefaults.allowsAlwaysAllow(toolName))
        }
    }

    @Test
    fun `omitted workspace target falls back only to the assistant workspace`() {
        assertEquals("explicit", resolveWorkspaceProcessTarget("explicit", "assistant"))
        assertEquals("assistant", resolveWorkspaceProcessTarget(null, "assistant"))
        assertEquals("assistant", resolveWorkspaceProcessTarget("", "assistant"))
        assertNull(resolveWorkspaceProcessTarget(null, null))
    }
}

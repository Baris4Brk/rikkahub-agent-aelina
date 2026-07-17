package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvocationSurfacePolicyTest {
    @Test
    fun `unlocked system assistant is local interactive but cannot host Activity-only tools`() {
        val decision = InvocationSurfacePolicy.forOrigin(ToolCallOrigin.SystemAssistant)

        assertTrue(decision.allowsToolExecution)
        assertTrue(decision.allowsPrivilegedToolInjection)
        assertTrue(decision.allowsAutoApproval)
        assertTrue(decision.allowsSelectedConversationUnrestricted)
        assertTrue(decision.requiresVisibleForegroundSurface)
        assertFalse(decision.allowsForegroundOnlyTools)
        assertNotNull(
            InvocationSurfacePolicy.foregroundRequirementBlockReason(
                toolName = "get_gnss_status",
                origin = ToolCallOrigin.SystemAssistant,
                requiresForegroundApp = true,
                appInForeground = true,
            ),
        )
    }

    @Test
    fun `named origin sets never acquire keyguard by enum growth`() {
        assertTrue(ToolCallOrigin.SystemAssistant in InvocationSurfacePolicy.LOCAL_UNLOCKED)
        assertTrue(ToolCallOrigin.TrustedWorkflow in InvocationSurfacePolicy.LOCAL_OR_WORKFLOW)
        assertTrue(ToolCallOrigin.Telegram in InvocationSurfacePolicy.REMOTE)
        assertFalse(ToolCallOrigin.SystemAssistantKeyguard in InvocationSurfacePolicy.LOCAL_UNLOCKED)
        assertFalse(ToolCallOrigin.SystemAssistantKeyguard in InvocationSurfacePolicy.LOCAL_OR_WORKFLOW)
        assertFalse(ToolCallOrigin.SystemAssistantKeyguard in InvocationSurfacePolicy.REMOTE)
        assertFalse(ToolCallOrigin.SystemAssistantKeyguard in InvocationSurfacePolicy.ALL_NON_KEYGUARD)
    }

    @Test
    fun `existing origins retain foreground capability behavior`() {
        assertNull(
            InvocationSurfacePolicy.foregroundRequirementBlockReason(
                toolName = "legacy_tool",
                origin = ToolCallOrigin.Telegram,
                requiresForegroundApp = true,
                appInForeground = true,
            ),
        )
        assertNotNull(
            InvocationSurfacePolicy.foregroundRequirementBlockReason(
                toolName = "legacy_tool",
                origin = ToolCallOrigin.Telegram,
                requiresForegroundApp = true,
                appInForeground = false,
            ),
        )
    }

    @Test
    fun `system assistant keyguard surface cannot execute or elevate any tool`() {
        val decision = InvocationSurfacePolicy.forOrigin(ToolCallOrigin.SystemAssistantKeyguard)

        assertFalse(decision.allowsToolExecution)
        assertFalse(decision.allowsPrivilegedToolInjection)
        assertFalse(decision.allowsAutoApproval)
        assertFalse(decision.allowsSelectedConversationUnrestricted)
        assertTrue(decision.requiresVisibleForegroundSurface)
        assertNotNull(
            InvocationSurfacePolicy.toolExecutionBlockReason(
                toolName = "battery_status",
                origin = ToolCallOrigin.SystemAssistantKeyguard,
            ),
        )
    }

    @Test
    fun `system assistant elevation requires visible bound overlay and current unlock`() {
        assertNull(
            InvocationSurfacePolicy.systemAssistantVisibilityBlockReason(
                origin = ToolCallOrigin.SystemAssistant,
                deviceLocked = false,
                hasAuthorizedInvocation = true,
            ),
        )
        assertNotNull(
            InvocationSurfacePolicy.systemAssistantVisibilityBlockReason(
                origin = ToolCallOrigin.SystemAssistant,
                deviceLocked = false,
                hasAuthorizedInvocation = false,
            ),
        )
        assertNotNull(
            InvocationSurfacePolicy.systemAssistantVisibilityBlockReason(
                origin = ToolCallOrigin.SystemAssistant,
                deviceLocked = true,
                hasAuthorizedInvocation = true,
            ),
        )
        assertNull(
            InvocationSurfacePolicy.systemAssistantVisibilityBlockReason(
                origin = ToolCallOrigin.LocalChat,
                deviceLocked = true,
                hasAuthorizedInvocation = false,
            ),
        )
        assertTrue(
            InvocationSurfacePolicy.canExposeToolSurface(
                origin = ToolCallOrigin.SystemAssistant,
                deviceLocked = false,
                hasAuthorizedInvocation = true,
            ),
        )
        assertFalse(
            InvocationSurfacePolicy.canExposeToolSurface(
                origin = ToolCallOrigin.SystemAssistantKeyguard,
                deviceLocked = false,
                hasAuthorizedInvocation = true,
            ),
        )
    }

    @Test
    fun `system assistant local tools fail closed without catalog and hide interactive tools`() {
        assertTrue(
            InvocationSurfacePolicy.canExposeSystemAssistantLocalTool(
                toolName = "battery_status",
                catalogAllowsOrigin = true,
                requiresForegroundApp = false,
            ),
        )
        assertFalse(
            InvocationSurfacePolicy.canExposeSystemAssistantLocalTool(
                toolName = "uncatalogued_tool",
                catalogAllowsOrigin = false,
                requiresForegroundApp = false,
            ),
        )
        assertFalse(
            InvocationSurfacePolicy.canExposeSystemAssistantLocalTool(
                toolName = "ask_user",
                catalogAllowsOrigin = true,
                requiresForegroundApp = false,
            ),
        )
        assertFalse(
            InvocationSurfacePolicy.canExposeSystemAssistantLocalTool(
                toolName = "camera_photo",
                catalogAllowsOrigin = true,
                requiresForegroundApp = true,
            ),
        )
    }
}

package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.assistant.InvocationSurfaceContext
import me.rerere.rikkahub.assistant.InvocationSurfacePresence
import me.rerere.rikkahub.assistant.SystemAssistantHostKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class InvocationSurfacePolicyTest {
    @Test
    fun `AI key activity plan exposes advanced tools while voice session stays background only`() {
        val conversationId = Uuid.random()
        val activity = ToolExposurePlan.create(
            origin = ToolCallOrigin.SystemAssistant,
            deviceLocked = false,
            hasAuthorizedInvocation = true,
            surfaceContext = InvocationSurfaceContext(
                origin = ToolCallOrigin.SystemAssistant,
                hostKind = SystemAssistantHostKind.ACTIVITY_OVERLAY,
                presence = InvocationSurfacePresence.OVERLAY_VISIBLE,
                conversationId = conversationId,
                commandId = Uuid.random(),
                unlockedOwner = true,
            ),
        )
        val voice = ToolExposurePlan.create(
            origin = ToolCallOrigin.SystemAssistant,
            deviceLocked = false,
            hasAuthorizedInvocation = true,
            surfaceContext = InvocationSurfaceContext(
                origin = ToolCallOrigin.SystemAssistant,
                hostKind = SystemAssistantHostKind.VOICE_SESSION,
                presence = InvocationSurfacePresence.VOICE_SESSION_VISIBLE,
                conversationId = conversationId,
                commandId = Uuid.random(),
                unlockedOwner = true,
            ),
        )

        listOf("workspace_shell", "browser_open", "take_screenshot", "keyboard_input").forEach {
            assertTrue("activity overlay should expose $it", activity.canExpose(it))
            assertFalse("voice session must not expose $it", voice.canExpose(it))
        }
        assertTrue(activity.canExpose("get_battery_status"))
        assertTrue(voice.canExpose("get_battery_status"))
        assertFalse(activity.canExpose("call_phone"))
        assertFalse(activity.canExpose("answer_phone_call"))
    }

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

    @Test
    fun `quick capture has an exact lease surface but no implicit tool directory or activity access`() {
        val plan = ToolExposurePlan.create(
            origin = ToolCallOrigin.QuickCapture,
            deviceLocked = false,
            hasAuthorizedInvocation = true,
            surfaceContext = InvocationSurfaceContext(
                origin = ToolCallOrigin.QuickCapture,
                hostKind = SystemAssistantHostKind.QUICK_CAPTURE_OVERLAY,
                presence = InvocationSurfacePresence.OVERLAY_VISIBLE,
                conversationId = Uuid.random(),
                commandId = Uuid.random(),
                unlockedOwner = true,
            ),
        )

        assertTrue(plan.surfaceAvailable)
        assertFalse(plan.activityOverlayAuthorized)
        assertFalse(plan.canExpose("ask_user"))
        assertFalse(plan.canExpose("call_phone"))
        // This otherwise background-safe tool remains unavailable until a future per-tool
        // QuickCapture audit explicitly adds the origin to the capability catalog.
        assertFalse(plan.canExpose("get_battery_status"))
    }
}

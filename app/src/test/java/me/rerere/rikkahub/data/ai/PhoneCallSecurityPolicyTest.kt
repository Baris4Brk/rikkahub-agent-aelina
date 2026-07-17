package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallSecurityPolicyTest {
    @Test
    fun `direct calls are a hard local unlocked capability`() {
        assertNull(phoneCallHardBlockReason(ToolCallOrigin.LocalChat, deviceLocked = false))
        assertTrue(phoneCallHardBlockReason(ToolCallOrigin.Telegram, deviceLocked = false) != null)
        assertTrue(phoneCallHardBlockReason(ToolCallOrigin.WebServer, deviceLocked = false) != null)
        assertTrue(phoneCallHardBlockReason(ToolCallOrigin.TrustedWorkflow, deviceLocked = false) != null)
        assertTrue(phoneCallHardBlockReason(ToolCallOrigin.SystemAssistant, deviceLocked = false) != null)
        assertTrue(phoneCallHardBlockReason(ToolCallOrigin.LocalChat, deviceLocked = true) != null)
        assertTrue(
            phoneActionHardBlockReason(
                "answer_phone_call",
                ToolCallOrigin.SystemAssistant,
                deviceLocked = false,
            ) != null,
        )
    }

    @Test
    fun `normal direct calls always ask and cannot be permanently allowed`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("call_phone"))
        assertFalse(ToolApprovalDefaults.allowsAlwaysAllow("call_phone"))
    }

    @Test
    fun `unrestricted call auto approval still respects emergency origin and lock state`() {
        assertTrue(canAutoApproveUnrestrictedCall(
            origin = ToolCallOrigin.LocalChat,
            deviceLocked = false,
            emergencyStop = false,
        ))
        assertFalse(canAutoApproveUnrestrictedCall(
            origin = ToolCallOrigin.Telegram,
            deviceLocked = false,
            emergencyStop = false,
        ))
        assertFalse(canAutoApproveUnrestrictedCall(
            origin = ToolCallOrigin.LocalChat,
            deviceLocked = true,
            emergencyStop = false,
        ))
        assertFalse(canAutoApproveUnrestrictedCall(
            origin = ToolCallOrigin.LocalChat,
            deviceLocked = false,
            emergencyStop = true,
        ))
    }
}

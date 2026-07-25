package me.rerere.rikkahub.privilege

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HardDenyPolicyTest {
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()
    private val context = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = conversationId,
        identityName = "second user",
        isPrivileged = true,
        expandLocalTools = true,
        autoApproveTools = true,
        unrestrictedOverride = false,
    )

    @Test
    fun `seven management zones remain immutable for second user`() {
        val policy = DefaultHardDenyPolicy("me.rerere.rikkahub")
        val denied = policy.checkPrivilegedAction(PrivilegedAction.CloseApplication, context)

        assertTrue(denied is HardDenyDecision.Denied)
        assertEquals(HardDenyZone.SELF_PROTECTION, (denied as HardDenyDecision.Denied).zone)
    }

    @Test
    fun `interactive termux input still hits command safety floor`() {
        val decision = DefaultHardDenyPolicy("me.rerere.rikkahub").checkTool(
            "termux_session_send",
            buildJsonObject { put("input", "rm -rf /") },
        )

        assertTrue(decision is HardDenyDecision.Denied)
        assertEquals(HardDenyZone.COMMAND_SAFETY, (decision as HardDenyDecision.Denied).zone)
    }
}

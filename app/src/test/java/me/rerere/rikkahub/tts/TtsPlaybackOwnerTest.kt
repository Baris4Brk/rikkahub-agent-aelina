package me.rerere.rikkahub.tts

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsPlaybackOwnerTest {
    @Test
    fun `only confirmed local second user gets pet speaking owner`() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val context = ToolInvocationContext(
            callerAssistantId = assistantId.toString(),
            callerConversationId = conversationId.toString(),
            callOrigin = ToolCallOrigin.LocalChat,
            privilege = PrivilegedSessionContext(
                assistantId = assistantId,
                conversationId = conversationId,
                origin = ToolCallOrigin.LocalChat,
                privilegedConversationId = conversationId,
                identityName = "Second user",
                isPrivileged = true,
                expandLocalTools = true,
                autoApproveTools = true,
                unrestrictedOverride = false,
            ),
        )

        assertEquals(
            TtsPlaybackOwner.secondUser(assistantId.toString(), conversationId.toString()),
            secondUserTtsOwnerKey(context),
        )
        assertNull(secondUserTtsOwnerKey(context.copy(callOrigin = ToolCallOrigin.PetHandoffAuto)))
        assertNull(secondUserTtsOwnerKey(context.copy(callerConversationId = Uuid.random().toString())))
    }
}

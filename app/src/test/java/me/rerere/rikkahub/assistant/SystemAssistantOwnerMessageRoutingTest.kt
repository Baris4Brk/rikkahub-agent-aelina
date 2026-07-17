package me.rerere.rikkahub.assistant

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prepareSecondUserProviderMessages
import me.rerere.rikkahub.service.chat.CommandOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SystemAssistantOwnerMessageRoutingTest {
    @Test
    fun `system assistant submission is an owner message without second user annotation`() {
        val submission = SystemAssistantChatSubmission(
            commandId = Uuid.random(),
            assistantId = Uuid.random(),
            conversationId = Uuid.random(),
            text = "hello",
            origin = CommandOrigin.SYSTEM_ASSISTANT,
            dedupeKey = "system-assistant:test",
        )

        val payload = submission.toOwnerMessagePayload()

        assertEquals(listOf(UIMessagePart.Text("hello")), payload.parts)
        assertTrue(payload.annotations.isEmpty())
    }

    @Test
    fun `owner message reaches the provider without a second user identity envelope`() {
        val submission = SystemAssistantChatSubmission(
            commandId = Uuid.random(),
            assistantId = Uuid.random(),
            conversationId = Uuid.random(),
            text = "hello",
            origin = CommandOrigin.SYSTEM_ASSISTANT,
            dedupeKey = "system-assistant:test",
        )
        val payload = submission.toOwnerMessagePayload()
        val ownerMessage = UIMessage(
            role = MessageRole.USER,
            parts = payload.parts,
            annotations = payload.annotations,
        )

        assertEquals(
            listOf(ownerMessage),
            prepareSecondUserProviderMessages(listOf(ownerMessage)),
        )
    }
}

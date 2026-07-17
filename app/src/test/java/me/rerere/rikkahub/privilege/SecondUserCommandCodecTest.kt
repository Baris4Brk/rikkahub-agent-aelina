package me.rerere.rikkahub.privilege

import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.chat.CommandCodec
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.SendMessageCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SecondUserCommandCodecTest {
    @Test
    fun `durable message round trip preserves second user identity`() {
        val annotation = UIMessageAnnotation.SecondUser(
            sourceAssistantId = Uuid.random(),
            sourceConversationId = Uuid.random(),
            displayName = "第二用户",
        )
        val command = SendMessageCommand(
            RawUserContent(
                parts = listOf(UIMessagePart.Text("继续检查")),
                annotations = listOf(annotation),
            )
        )

        val encoded = CommandCodec.encode(command)
        val decoded = CommandCodec.decode(encoded.first, encoded.second) as SendMessageCommand

        assertEquals(listOf(annotation), decoded.content.annotations)
    }

    @Test
    fun `old durable messages without annotations remain readable`() {
        val legacyPayload = """{"content":"{\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}],\"answer\":true}"}"""
        val decoded = CommandCodec.decode("send_message", legacyPayload) as SendMessageCommand

        assertTrue(decoded.content.annotations.isEmpty())
    }
}

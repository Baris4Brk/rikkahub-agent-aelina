package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SystemAssistantCommandOriginCodecTest {
    @Test
    fun `durable codec preserves both system assistant invocation identities`() {
        val command = SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("ping"))))

        listOf(
            CommandOrigin.SYSTEM_ASSISTANT,
            CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD,
        ).forEach { origin ->
            val (_, payload) = CommandCodec.encodeDurable(command, origin)
            assertEquals(origin, CommandCodec.decodeDurableOrigin(payload))
        }
    }

    @Test
    fun `legacy durable payload without origin remains internal`() {
        assertEquals(CommandOrigin.INTERNAL, CommandCodec.decodeDurableOrigin("{}"))
    }

    @Test
    fun `durable system assistant command preserves its accepted assistant snapshot`() {
        val assistantId = Uuid.random()
        val command = SendMessageCommand(
            content = RawUserContent(listOf(UIMessagePart.Text("ping"))),
            assistantIdSnapshot = assistantId,
        )

        val (type, payload) = CommandCodec.encodeDurable(
            command,
            CommandOrigin.SYSTEM_ASSISTANT,
        )
        val decoded = CommandCodec.decode(type, payload) as SendMessageCommand

        assertEquals(assistantId, decoded.assistantIdSnapshot)
        assertEquals(CommandOrigin.SYSTEM_ASSISTANT, CommandCodec.decodeDurableOrigin(payload))
    }
}

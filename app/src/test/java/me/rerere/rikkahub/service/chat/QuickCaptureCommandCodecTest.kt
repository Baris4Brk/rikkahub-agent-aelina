package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class QuickCaptureCommandCodecTest {
    @Test
    fun `durable quick capture preserves origin target session and internal annotation`() {
        val assistantId = Uuid.random()
        val sessionId = Uuid.random()
        val commandId = Uuid.random()
        val command = SendMessageCommand(
            content = RawUserContent(
                parts = listOf(UIMessagePart.Text("inspect"), UIMessagePart.Image("file:///capture.png")),
                annotations = listOf(UIMessageAnnotation.QuickCapture(commandId.toString(), sessionId.toString())),
            ),
            assistantIdSnapshot = assistantId,
            quickCaptureSessionId = sessionId,
        )

        val (type, payload) = CommandCodec.encodeDurable(command, CommandOrigin.QUICK_CAPTURE)
        val decoded = CommandCodec.decode(type, payload) as SendMessageCommand

        assertEquals(CommandOrigin.QUICK_CAPTURE, CommandCodec.decodeDurableOrigin(payload))
        assertEquals(assistantId, decoded.assistantIdSnapshot)
        assertEquals(sessionId, decoded.quickCaptureSessionId)
        assertEquals(command.content.annotations, decoded.content.annotations)
    }
}

package me.rerere.rikkahub.service.chat

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.authority.source.ConversationSourceSnapshotFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SendMessageAnchorAuthorityTest {
    @Test
    fun durableRoundTripPreservesTheExactAnchorTimestampAndDigest() {
        val createdAt = LocalDateTime(2026, 8, 14, 13, 38, 0, 123_000_000)
        val messageId = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val command = SendMessageCommand(
            content = RawUserContent(
                parts = listOf(UIMessagePart.Text("stable anchor")),
                createdAt = createdAt,
            ),
        )

        val encoded = CommandCodec.encodeDurable(command, CommandOrigin.APP_UI)
        val restored = CommandCodec.decode(encoded.first, encoded.second) as SendMessageCommand
        val admitted = command.content.toAnchoredUserMessage(messageId)
        val executed = restored.content.toAnchoredUserMessage(messageId)

        assertEquals(createdAt, restored.content.createdAt)
        assertEquals(admitted, executed)
        assertEquals(
            ConversationSourceSnapshotFactory.payloadIntegritySha256(admitted),
            ConversationSourceSnapshotFactory.payloadIntegritySha256(executed),
        )
    }

    @Test
    fun fastPathMayReplacePartsWithoutResamplingAnchorTime() {
        val createdAt = LocalDateTime(2026, 8, 14, 13, 40, 0)
        val content = RawUserContent(
            parts = listOf(UIMessagePart.Text("raw")),
            createdAt = createdAt,
        )

        val anchored = content.toAnchoredUserMessage(
            messageId = Uuid.parse("22222222-2222-4222-8222-222222222222"),
            effectiveParts = listOf(UIMessagePart.Text("processed")),
        )

        assertEquals(createdAt, anchored.createdAt)
        assertEquals(listOf(UIMessagePart.Text("processed")), anchored.parts)
    }
}

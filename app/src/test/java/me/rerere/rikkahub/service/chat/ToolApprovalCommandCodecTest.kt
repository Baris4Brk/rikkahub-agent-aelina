package me.rerere.rikkahub.service.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolApprovalCommandCodecTest {
    @Test
    fun `durable approval preserves CAS and idempotency identity`() {
        val command = ToolApprovalCommand(
            toolCallId = "call-7",
            decision = ToolDecision.Approved,
            toolName = "linux_run",
            scope = "Once",
            expectedStateVersion = 12,
            resolutionRequestId = "request-9",
        )

        val encoded = CommandCodec.encode(command)

        assertEquals(command, CommandCodec.decode(encoded.first, encoded.second))
    }
}

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
            approvalId = "approval:exact-7",
            executionId = "execution-7",
            expectedStateVersion = 12,
            resolutionRequestId = "request-9",
        )

        val encoded = CommandCodec.encode(command)

        assertEquals(command, CommandCodec.decode(encoded.first, encoded.second))
    }

    @Test
    fun `legacy approval payload remains decodable but has no exact positive authority`() {
        val decoded = CommandCodec.decode(
            "tool_approval",
            """{"toolCallId":"call-old","decision":{"kind":"approved"}}""",
        ) as ToolApprovalCommand

        assertEquals(null, decoded.approvalId)
        assertEquals(null, decoded.executionId)
        assertEquals(null, decoded.expectedStateVersion)
    }
}

package me.rerere.rikkahub.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManagedExecutionLedgerCodecTest {
    @Test
    fun `ledger round trip contains identity but has no command or secret fields`() {
        val record = ManagedExecutionLedgerRecord(
            executionId = "termux:run-1",
            runtime = "termux",
            nativeId = "run-1",
            ownerAssistantId = "assistant",
            ownerConversationId = "conversation",
            ownerOrigin = "LocalChat",
            status = "running",
            pid = 12,
            processGroupId = 12,
            processStartTicks = 999,
            tokenHash = "sha256-only",
            createdAtMs = 1,
            updatedAtMs = 2,
        )

        val encoded = ManagedExecutionLedgerCodec.encode(listOf(record))
        val decoded = ManagedExecutionLedgerCodec.decode(encoded).single()

        assertEquals(record, decoded)
        assertFalse(encoded.contains("command", ignoreCase = true))
        assertFalse(encoded.contains("password", ignoreCase = true))
        assertFalse(encoded.contains("privateKey", ignoreCase = true))
    }

    @Test
    fun `corrupt ledger fails closed to an empty list`() {
        assertEquals(emptyList<ManagedExecutionLedgerRecord>(), ManagedExecutionLedgerCodec.decode("not-json"))
    }
}

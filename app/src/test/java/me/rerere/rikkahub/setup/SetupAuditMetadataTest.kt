package me.rerere.rikkahub.setup

import me.rerere.rikkahub.data.agentrun.AgentRunKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SetupAuditMetadataTest {
    @Test
    fun `setup AgentRun kind round trips from its stored wire value`() {
        assertEquals(AgentRunKind.Setup, AgentRunKind.fromWire("setup"))
    }

    @Test
    fun `setup ledger metadata contains only transaction identity types and count`() {
        val metadata = buildSetupAuditMetadata(
            transactionId = "transaction-1",
            changeTypes = listOf("assistant_chat_model", "assistant_skills"),
        )

        assertEquals(
            setOf("transaction_id", "change_types", "change_count"),
            metadata.keys,
        )
        assertEquals(2, metadata.getValue("change_count").toString().toInt())
        assertFalse(metadata.toString().contains("value", ignoreCase = true))
        assertFalse(metadata.toString().contains("secret", ignoreCase = true))
    }
}

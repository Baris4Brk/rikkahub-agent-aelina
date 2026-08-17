package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration46To47RewardAuthorityContractTest {
    @Test
    fun `v47 outbox additions are nullable content-free typed metadata`() {
        assertEquals(
            listOf(
                "reward_dimension",
                "reward_signal_kind",
                "reward_value_milli",
                "execution_verification_state",
            ),
            LEARNING_V47_OUTBOX_COLUMNS.map { it.first },
        )
        assertTrue(LEARNING_V47_OUTBOX_COLUMNS.all { it.second in setOf("TEXT", "INTEGER") })
        assertTrue(LEARNING_V47_SENTINEL_PAYLOAD_COLUMNS.containsAll(
            LEARNING_V47_OUTBOX_COLUMNS.map { it.first },
        ))
    }

    @Test
    fun `reward authority schema contains no user or model payload`() {
        val sql = LEARNING_V47_REWARD_FEEDBACK_AUTHORITY_TABLE_SQL.lowercase()
        listOf("content", "payload", "prompt", "response", "message_text").forEach { forbidden ->
            assertFalse(sql.contains("`$forbidden`"))
        }
        assertTrue(sql.contains("`integrity_sha256` text not null"))
        assertTrue(sql.contains("`target_assistant_message_revision` integer not null"))
        assertTrue(LEARNING_V47_REWARD_FEEDBACK_REVISIONS_TABLE_SQL.contains("ON DELETE CASCADE"))
    }
}

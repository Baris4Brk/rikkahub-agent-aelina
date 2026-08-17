package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration47To48PolicyGrantContractTest {
    @Test
    fun `grant tables are content-free and stream bound`() {
        val sql = (
            LEARNING_V48_POLICY_GRANTS_TABLE_SQL +
                LEARNING_V48_POLICY_GRANT_REVISIONS_TABLE_SQL
            ).lowercase()

        listOf(
            "content", "evidence", "prompt", "response", "model_output", "message_text",
        ).forEach { forbidden -> assertFalse(sql.contains("`$forbidden`")) }
        listOf(
            "`source_stream_id` text not null",
            "`policy_revision` integer not null",
            "`artifact_sha256` text not null",
            "`consuming_assistant_id` text not null",
            "`state_version` integer not null",
        ).forEach { required -> assertTrue(sql.contains(required)) }
        assertTrue(LEARNING_V48_POLICY_GRANT_REVISIONS_TABLE_SQL.contains("ON DELETE CASCADE"))
    }

    @Test
    fun `grant schema has exact authority and audit indexes`() {
        assertEquals(6, LEARNING_V48_POLICY_GRANT_INDEX_SQL.size)
        assertTrue(
            LEARNING_V48_POLICY_GRANT_INDEX_SQL.any {
                it.contains("UNIQUE INDEX") && it.contains("source_stream_id") &&
                    it.contains("scope_kind") && it.contains("policy_id")
            },
        )
        assertTrue(
            LEARNING_V48_POLICY_GRANT_INDEX_SQL.any {
                it.contains("grant_revisions_changed") && it.contains("changed_at_ms")
            },
        )
    }

    @Test
    fun `policy revision column is the content revision and never a lifecycle state version`() {
        assertEquals(
            "LEARNING_POLICY_CONTENT_REVISION",
            LEARNING_V48_GRANT_POLICY_REVISION_SEMANTICS,
        )
        assertFalse(LEARNING_V48_POLICY_GRANTS_TABLE_SQL.contains("policy_state_version"))
        assertTrue(LEARNING_V48_POLICY_GRANTS_TABLE_SQL.contains("`policy_revision` INTEGER"))
    }
}

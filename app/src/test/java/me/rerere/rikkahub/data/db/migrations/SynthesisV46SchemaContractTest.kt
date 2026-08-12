package me.rerere.rikkahub.data.db.migrations

import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisV46SchemaContractTest {
    @Test
    fun `synthesis migration creates exactly the four frozen derived tables`() {
        val tables = MEMORY_V46_SYNTHESIS_TABLE_AND_INDEX_SQL
            .filter { it.startsWith("CREATE TABLE") }
            .mapNotNull { sql -> Regex("`([^`]+)`").find(sql)?.groupValues?.get(1) }

        assertEquals(
            listOf(
                "dream_claims",
                "dream_claim_versions",
                "dream_claim_version_sources",
                "dream_snapshots",
            ),
            tables,
        )
        val allSql = MEMORY_V46_SYNTHESIS_TABLE_AND_INDEX_SQL.joinToString("\n")
        listOf(
            "dream_snapshot_preimages",
            "dream_current_state",
            "dream_snapshot_claims",
            "dream_claims_fts",
        ).forEach { forbidden -> assertFalse(allSql.contains(forbidden)) }
    }

    @Test
    fun `observer state starts synthesis unapplied and active snapshot has no reverse FK`() {
        assertEquals(
            listOf(
                "dream_state_revision" to "INTEGER NOT NULL DEFAULT 0",
                "last_applied_memory_epoch" to "INTEGER NOT NULL DEFAULT 0",
                "active_snapshot_id" to "TEXT",
                "last_full_rebuild_at_ms" to "INTEGER",
            ),
            MEMORY_V46_SCOPE_STATE_COLUMNS,
        )
        assertTrue(MEMORY_V46_ACTIVE_SNAPSHOT_INDEX_SQL.contains("UNIQUE INDEX"))
        assertTrue(MEMORY_V46_SCOPE_STATE_COLUMNS.none { (_, sql) -> sql.contains("REFERENCES") })
    }

    @Test
    fun `run audit preserves not-applicable as null and freezes dream base at claim time`() {
        assertEquals(
            "INTEGER NOT NULL DEFAULT 0",
            MEMORY_V46_DREAM_RUN_COLUMNS.toMap().getValue("base_dream_revision"),
        )
        assertEquals(
            "TEXT",
            MEMORY_V46_DREAM_RUN_COLUMNS.toMap().getValue("source_timezone_id"),
        )
        listOf(
            "source_timezone_id",
            "model_identity_digest",
            "provider_kind",
            "prompt_contract_version",
            "validator_version",
            "input_memory_count",
            "input_tokens",
            "output_claim_count",
            "output_tokens",
            "input_manifest_hash",
            "output_manifest_hash",
        ).forEach { column ->
            assertFalse(MEMORY_V46_DREAM_RUN_COLUMNS.toMap().getValue(column).contains("DEFAULT"))
        }
    }

    @Test
    fun `exact authority revision and memory deletion are restricted`() {
        val sql = MEMORY_V46_DREAM_CLAIM_SOURCES_TABLE_SQL

        assertTrue(
            sql.contains(
                "FOREIGN KEY(`memory_id`) REFERENCES `MemoryEntity`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE RESTRICT",
            ),
        )
        assertTrue(
            sql.contains(
                "FOREIGN KEY(`memory_id`, `memory_revision`) " +
                    "REFERENCES `memory_revisions`(`memory_id`, `revision`) " +
                    "ON UPDATE NO ACTION ON DELETE RESTRICT",
            ),
        )
        assertTrue(
            sql.contains(
                "FOREIGN KEY(`claim_id`, `claim_revision`) " +
                    "REFERENCES `dream_claim_versions`(`claim_id`, `claim_revision`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE",
            ),
        )
    }

    @Test
    fun `privacy tombstone remains a first-class persisted claim state`() {
        assertTrue(MEMORY_V46_DREAM_CLAIMS_TABLE_SQL.contains("`state` TEXT NOT NULL"))
        assertTrue(DreamClaimState.TOMBSTONED in DreamClaimState.entries)
    }
}

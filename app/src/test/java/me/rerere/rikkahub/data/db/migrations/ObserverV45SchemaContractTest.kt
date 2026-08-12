package me.rerere.rikkahub.data.db.migrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverV45SchemaContractTest {
    @Test
    fun `observer migration creates exactly the three dormant M1 tables`() {
        val createdTables = MEMORY_V45_OBSERVER_SCHEMA_SQL
            .filter { it.startsWith("CREATE TABLE") }
            .mapNotNull { sql -> Regex("`([^`]+)`").find(sql)?.groupValues?.get(1) }

        assertEquals(
            listOf("memory_scope_state", "memory_scope_changes", "dream_runs"),
            createdTables,
        )
        val allSql = MEMORY_V45_OBSERVER_SCHEMA_SQL.joinToString("\n")
        listOf(
            "dream_claims",
            "dream_claim_versions",
            "dream_snapshots",
            "dream_state_revision",
            "active_snapshot_id",
            "model_identity_digest",
        ).forEach { synthesisName -> assertFalse(allSql.contains(synthesisName)) }
    }

    @Test
    fun `change receipt identity excludes operation and revision`() {
        val uniqueSql = MEMORY_V45_SCOPE_CHANGES_INDEX_SQL.single { it.contains("UNIQUE") }

        assertTrue(
            uniqueSql.endsWith(
                "(`scope_id`, `memory_epoch`, `entity_kind`, `entity_id`)",
            ),
        )
        assertFalse(uniqueSql.contains("`operation`"))
        assertFalse(uniqueSql.contains("`entity_revision`"))
    }

    @Test
    fun `scope and run checkpoints have distinct frozen column names`() {
        assertTrue(MEMORY_V45_SCOPE_STATE_TABLE_SQL.contains("`observer_checkpoint_epoch`"))
        assertFalse(MEMORY_V45_SCOPE_STATE_TABLE_SQL.contains("`checkpoint_epoch` INTEGER"))
        assertTrue(MEMORY_V45_DREAM_RUNS_TABLE_SQL.contains("`checkpoint_epoch` INTEGER"))
        assertTrue(
            MEMORY_V45_DREAM_RUNS_TABLE_SQL.contains("`status` TEXT NOT NULL DEFAULT 'PENDING'"),
        )
        assertTrue(
            MEMORY_V45_DREAM_RUNS_TABLE_SQL.contains("`base_observer_checkpoint_epoch`"),
        )
    }

    @Test
    fun `scope state not run row is the durable single-run lease authority`() {
        assertTrue(MEMORY_V45_SCOPE_STATE_TABLE_SQL.contains("`active_run_id`"))
        assertTrue(MEMORY_V45_SCOPE_STATE_TABLE_SQL.contains("`active_run_lease_until_ms`"))
        assertTrue(MEMORY_V45_SCOPE_STATE_ACTIVE_RUN_INDEX_SQL.contains("UNIQUE INDEX"))
        assertTrue(MEMORY_V45_SCOPE_STATE_LEASE_INDEX_SQL.contains("active_run_lease_until_ms"))
        assertFalse(MEMORY_V45_DREAM_RUNS_TABLE_SQL.contains("`active_run_id`"))
        assertTrue(
            MEMORY_V45_DREAM_RUNS_INDEX_SQL.any { it.contains("scope_id_created_at_ms") },
        )
    }
}

package me.rerere.rikkahub.learning.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyFtsSchemaContractTest {
    @Test
    fun ftsSqlFiltersAuthorityAndEvidenceBeforeLimit() {
        val normalized = POLICY_FTS_SEARCH_SQL.lowercase()
        val limit = normalized.lastIndexOf("limit ?")
        listOf(
            "p.scope_kind = ?",
            "p.scope_id = ?",
            "p.status in ('candidate', 'shadow')",
            "p.source_valid = 1",
            "p.schema_valid = 1",
            "policy_evidence",
            "learning_source_validity",
            "conversation_message",
        ).forEach { predicate ->
            val at = normalized.indexOf(predicate)
            assertTrue("$predicate missing or after LIMIT", at >= 0 && at < limit)
        }
        assertTrue("delete trigger missing", POLICY_FTS_TRIGGER_SQL.any { "after delete" in it.lowercase() })
        assertTrue("update trigger missing", POLICY_FTS_TRIGGER_SQL.any { "after update" in it.lowercase() })
        val update = POLICY_FTS_TRIGGER_SQL.single { "after update" in it.lowercase() }.lowercase()
        listOf("status", "source_valid", "schema_valid").forEach { field ->
            assertTrue("$field is not part of FTS invalidation", field in update)
        }
        val backfill = POLICY_FTS_BACKFILL_MISSING_SQL.lowercase()
        assertTrue("backfill includes non-shadow rows", "p.status in ('candidate', 'shadow')" in backfill)
        assertTrue("backfill includes stale sources", "p.source_valid = 1" in backfill)
        assertTrue("backfill includes stale schemas", "p.schema_valid = 1" in backfill)
        assertTrue("ineligible projection cleanup missing", "source_valid != 1" in
            POLICY_FTS_DELETE_INELIGIBLE_SQL.lowercase())
        assertFalse(POLICY_FTS_SEARCH_SQL.contains("SELECT *"))
    }
}

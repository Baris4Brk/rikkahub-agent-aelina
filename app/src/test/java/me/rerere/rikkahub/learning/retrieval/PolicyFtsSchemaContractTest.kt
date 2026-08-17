package me.rerere.rikkahub.learning.retrieval

import me.rerere.rikkahub.learning.storage.VALID_POLICY_EVIDENCE_PREDICATE
import org.junit.Assert.assertEquals
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
            "p.updated_at_ms >= ?",
            "p.source_valid = 1",
            "p.schema_valid = 1",
            "p.applicable_template_identity is not null",
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
        listOf("status", "source_valid", "schema_valid", "applicable_template_identity")
            .forEach { field ->
            assertTrue("$field is not part of FTS invalidation", field in update)
        }
        val backfill = POLICY_FTS_BACKFILL_MISSING_SQL.lowercase()
        assertTrue("backfill includes non-shadow rows", "p.status in ('candidate', 'shadow')" in backfill)
        assertTrue("backfill includes stale sources", "p.source_valid = 1" in backfill)
        assertTrue("backfill includes stale schemas", "p.schema_valid = 1" in backfill)
        assertTrue("backfill includes unproven templates",
            "p.applicable_template_identity is not null" in backfill)
        assertTrue("ineligible projection cleanup missing", "source_valid != 1" in
            POLICY_FTS_DELETE_INELIGIBLE_SQL.lowercase())
        assertFalse(POLICY_FTS_SEARCH_SQL.contains("SELECT *"))
    }

    @Test
    fun ftsLiveQuerySharesExactRoomRewardProvenanceGateBeforeLimit() {
        val search = POLICY_FTS_SEARCH_SQL.normalizedSql()
        val sharedGate = VALID_POLICY_EVIDENCE_PREDICATE.normalizedSql()
        val limit = search.lastIndexOf("limit ?")

        assertEquals(
            "FTS positive and all-evidence-negative gates must use the same Room predicate",
            2,
            search.countOccurrences(sharedGate),
        )
        listOf(
            "policy_reward_evidence pre",
            "learning_reward_signals rs",
            "rs.episode_id = pre.episode_id",
            "rs.id = pre.reward_signal_id",
            "rsv.stream_id = ep.stream_id",
            "rsv.replay_generation = ep.replay_generation",
            "rsv.scope_kind = ep.scope_kind",
            "rsv.scope_id = ep.scope_id",
            "rsv.source_type = pre.source_type",
            "rsv.source_id = pre.source_id",
            "rsv.source_revision = pre.source_revision",
            "rsv.integrity_sha256 = pre.source_integrity_sha256",
            "rs.source_type = pre.source_type",
            "rs.source_id = pre.source_id",
            "rs.source_revision = pre.source_revision",
            "rs.source_integrity_sha256 = pre.source_integrity_sha256",
        ).forEach { tupleField ->
            val at = search.indexOf(tupleField)
            assertTrue("$tupleField missing or after LIMIT", at >= 0 && at < limit)
        }
    }

    @Test
    fun feedbackSupersedeOrTombstoneFailsClosedOnLiveValidityState() {
        val search = POLICY_FTS_SEARCH_SQL.normalizedSql()
        val limit = search.lastIndexOf("limit ?")
        listOf(
            "rsv.state = 'valid'",
            "bad_rsv.source_id is null",
            "bad_rsv.state != 'valid'",
            "bad_rsv.integrity_sha256 is null",
            "bad_rsv.integrity_sha256 != bad_pre.source_integrity_sha256",
            "bad_rs.id is null",
        ).forEach { failClosedPredicate ->
            val at = search.indexOf(failClosedPredicate)
            assertTrue("$failClosedPredicate missing or after LIMIT", at >= 0 && at < limit)
        }
        assertTrue(
            "Every invalid reward edge must reject the policy, not merely lose positive support",
            "and not (" in search &&
                Regex("and not exists \\(\\s*select 1 from policy_evidence").containsMatchIn(search),
        )
    }
}

private fun String.normalizedSql(): String = lowercase().replace(Regex("\\s+"), " ").trim()

private fun String.countOccurrences(needle: String): Int {
    require(needle.isNotEmpty())
    var count = 0
    var cursor = 0
    while (true) {
        val match = indexOf(needle, cursor)
        if (match < 0) return count
        count += 1
        cursor = match + needle.length
    }
}

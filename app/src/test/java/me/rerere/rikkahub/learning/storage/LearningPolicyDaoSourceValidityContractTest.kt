package me.rerere.rikkahub.learning.storage

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pure JVM SQL-contract checks; Room execution tests remain emulator-only and are not run here. */
class LearningPolicyDaoSourceValidityContractTest {
    @Test
    fun retrievalAndAuditQueriesUseExactReplayScopeAndEveryTraceGate() {
        val queries = VALID_POLICY_EVIDENCE_PREDICATE +
            VALID_POLICY_EVIDENCE_PREDICATE_FOR_E_ALIAS

        assertTrue("replay_generation" in queries)
        assertTrue("scope_kind" in queries && "scope_id" in queries)
        assertTrue("NOT EXISTS (SELECT 1 FROM learning_trace_features" in queries)
        assertTrue("integrity_sha256" in queries)
        assertTrue("l.state = 'VALID'" in queries)
    }

    @Test
    fun sourceReconciliationIsContentFencedRedactionCasAndNeverReplace() {
        val query = REDACT_POLICY_SOURCE_SQL

        assertTrue("state_version = :expectedStateVersion" in query)
        assertTrue("state_version = state_version + 1" in query)
        assertTrue("content_revision = :expectedContentRevision" in query)
        assertTrue("artifact_sha256 = :expectedArtifactSha256" in query)
        assertTrue("applicable_tool_schemas_wire = :expectedApplicableToolSchemasWire" in query)
        assertTrue("status = 'STALE_SOURCE'" in query)
        assertTrue("source_valid = 0" in query)
        assertTrue("SOURCE_REDACTED" in query)
        assertTrue("REPLACE" !in query.uppercase())
    }

    @Test
    fun retentionListsOnlyUnreviewedCandidatesAndHasNoRawPolicyDelete() {
        val source = java.io.File(
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningPolicyDao.kt",
        ).readText()
        val retentionQuery = source.substringAfter("listExpiredUnreviewedCandidates")

        assertTrue("status IN ('CANDIDATE', 'SHADOW')" in source)
        assertTrue("actor IN ('USER', 'GRANT_BINDER')" in source)
        assertFalse("deleteExpiredPolicies" in source)
        assertFalse("DELETE FROM learning_policies WHERE id IN" in retentionQuery)
    }
}

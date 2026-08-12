package me.rerere.rikkahub.learning.storage

import org.junit.Assert.assertTrue
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
    fun sourceReconciliationIsCasAndNeverReplace() {
        val query = RECONCILE_POLICY_SOURCE_SQL

        assertTrue("state_version = :expectedStateVersion" in query)
        assertTrue("state_version = state_version + 1" in query)
        assertTrue("REPLACE" !in query.uppercase())
    }
}

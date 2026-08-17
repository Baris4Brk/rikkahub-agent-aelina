package me.rerere.rikkahub.learning.storage.curator

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for the Room transaction shape; device execution is covered separately. */
class RoomCuratorAtomicitySourceContractTest {
    @Test
    fun `production apply and rollback each own one LearningDB transaction`() {
        val source = mainSource("storage/curator/RoomCuratorApplyRuntimeStore.kt")

        assertTrue(source.contains("database.withTransaction { applyInOpenTransaction(request) }"))
        assertTrue(source.contains("database.withTransaction { rollbackInOpenTransaction(request) }"))
        assertTrue(source.contains("requireExactApplyHeads(canonicalPlan)"))
        assertTrue(source.contains("requireExactRollbackHeads(plan)"))
        assertTrue(source.contains("updateCuratorPolicyHeadIfExact"))
        assertTrue(source.contains("insertEvidenceIgnore"))
        assertTrue(source.contains("insertPolicyRewardEvidenceIgnore"))
        assertTrue(source.contains("requireExactPolicyEvidence"))
        assertTrue(source.contains("markAppliedWithLineageFenced"))
        assertTrue(source.contains("markRolledBackWithLineageFenced"))
        assertFalse(source.contains("deletePolicy"))
        assertFalse(source.contains("DELETE FROM"))
    }

    @Test
    fun `candidate CAS precedes lineage insertion and update accepts empty lineage`() {
        val source = mainSource("storage/curator/CuratorDeltaDao.kt")
        val method = source.substringAfter("suspend fun markAppliedWithLineageFenced")
            .substringBefore("suspend fun markRolledBackWithLineageFenced")

        assertTrue(method.indexOf("markAppliedFencedRaw(") < method.indexOf("insertLineage(lineage)"))
        assertTrue(method.contains("countActiveLineage(next.id, planId) == lineage.size"))
        val applySql = source.substringAfter("state = 'APPLIED'")
            .substringBefore("suspend fun markAppliedFencedRaw")
        assertFalse(applySql.contains("EXISTS (SELECT"))
    }

    @Test
    fun `state machine invokes immutable candidate fence`() {
        val source = mainSource("storage/curator/CuratorDeltaStateMachine.kt")
        val transition = source.substringAfter("fun requireTransition(")
            .substringBefore("private fun requireImmutableCandidateFields")

        assertTrue(transition.contains("requireImmutableCandidateFields(expected, next)"))
    }

    @Test
    fun `review facade is exact and retention scan excludes reviewed and terminal live states`() {
        val facade = mainSource("storage/curator/RoomCuratorReviewRuntimeStore.kt")
        val dao = mainSource("storage/curator/CuratorDeltaDao.kt")

        listOf("approve", "reject", "archive", "listRetentionArchivable", "archiveRetention")
            .forEach { assertTrue(facade.contains("fun $it(")) }
        val retentionQuery = dao.substringAfter("Only retention-eligible nonterminal/conflict states")
            .substringBefore("suspend fun listRetentionArchivablePage")
        listOf("'PROPOSED'", "'REJECTED'", "'APPLY_CONFLICT'", "'ROLLBACK_CONFLICT'")
            .forEach { assertTrue(retentionQuery.contains(it)) }
        listOf("'APPROVED'", "'APPLYING'", "'APPLIED'", "'ROLLED_BACK'")
            .forEach { assertFalse(retentionQuery.contains(it)) }
    }

    private fun mainSource(relative: String): String =
        File("src/main/java/me/rerere/rikkahub/learning/$relative").readText()
}

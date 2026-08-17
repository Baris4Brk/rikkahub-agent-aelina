package me.rerere.rikkahub.learning.privacy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactScopeLearnedWorkflowEraseCompositionContractTest {
    @Test
    fun exactScopeQueriesAndAppDatabaseFencePrecedeLearningDatabaseDeletion() {
        val candidateDao = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/storage/dao/LearnedWorkflowCandidateDao.kt",
            "src/main/java/me/rerere/rikkahub/learning/storage/dao/LearnedWorkflowCandidateDao.kt",
        ).readText()
        assertTrue(candidateDao.contains("WHERE assistant_id = :assistantId"))
        assertTrue(candidateDao.contains("AND authority_subject_id IS NULL"))
        assertTrue(candidateDao.contains(
            "WHERE authority_subject_id = :authoritySubjectId",
        ))
        assertTrue(candidateDao.contains("LIMIT CASE WHEN :limit BETWEEN 1 AND 128"))

        val eraseStore = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/storage/LearningRetentionPolicyV1.kt",
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningRetentionPolicyV1.kt",
        ).readText().substringAfter("class LearningDerivedDataEraseStore(")
        val mainFence = eraseStore.indexOf("fenceMainDatabaseWorkflows(scope, frozenNowMs)")
        val learningTransaction = eraseStore.indexOf("return database.withTransaction")
        val candidateDelete = eraseStore.indexOf(".deleteAssistantScope(")
        assertTrue(mainFence >= 0)
        assertTrue(learningTransaction > mainFence)
        assertTrue(candidateDelete > learningTransaction)
    }

    @Test
    fun tombstonesArePermanentAndLateRunWritesAreDenied() {
        val workflowDao = projectFile(
            "app/src/main/java/me/rerere/rikkahub/workflow/db/WorkflowDao.kt",
            "src/main/java/me/rerere/rikkahub/workflow/db/WorkflowDao.kt",
        ).readText()
        val runDao = projectFile(
            "app/src/main/java/me/rerere/rikkahub/workflow/db/WorkflowRunDao.kt",
            "src/main/java/me/rerere/rikkahub/workflow/db/WorkflowRunDao.kt",
        ).readText()
        val workflowRepository = projectFile(
            "app/src/main/java/me/rerere/rikkahub/workflow/repository/WorkflowRepository.kt",
            "src/main/java/me/rerere/rikkahub/workflow/repository/WorkflowRepository.kt",
        ).readText()
        listOf(
            "learning_scope_erased_definition_v1",
            "learning_scope_erased_claim_v1",
        ).forEach { marker ->
            assertTrue(workflowDao.contains(marker))
            assertTrue(runDao.contains(marker))
        }
        assertTrue(workflowDao.contains("origin = 'LEARNED'"))
        assertTrue(workflowDao.contains("sourceCandidateId = :candidateId"))
        assertTrue(runDao.contains("@Transaction"))
        assertTrue(runDao.contains("canRecordRun(entity.workflowId) == 1"))
        assertTrue(!workflowRepository.contains("workflowRunDao.insertRaw("))
    }

    @Test
    fun resetFencesEveryCandidateAndRedactsOrphansBeforeDeletingCandidateRoots() {
        val resetter = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/handoff/LearningInboxBatchStore.kt",
            "src/main/java/me/rerere/rikkahub/learning/handoff/LearningInboxBatchStore.kt",
        ).readText().substringAfter("class LearningDerivedStateResetter")
            .substringBefore("private fun LearningInboxEventEntity.toInitialJob")
        val list = resetter.indexOf("listAllIdsForDerivedReset")
        val claim = resetter.indexOf("learnedWorkflowErasePort.redactAndFence")
        val orphan = resetter.indexOf("durableLearnedWorkflowPrivacyPort.redactAllForDerivedReset")
        val delete = resetter.indexOf("learnedWorkflowCandidateDao().deleteAll()")
        assertTrue(orphan >= 0)
        assertTrue(list > orphan)
        assertTrue(claim > list)
        // The delete is textually inside reset(), while the helper definition follows it. The
        // reset call itself must occur before the LearningDatabase transaction/delete.
        val fenceCall = resetter.indexOf("fenceLearnedWorkflowsBeforeCandidateDelete(frozenNowMs)")
        val transaction = resetter.indexOf("return database.withTransaction")
        assertTrue(delete >= 0)
        assertTrue(fenceCall in 0 until transaction)

        val production = projectFile(
            "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText()
        assertTrue("Production reset must inject the candidate fence", production.contains(
            "learnedWorkflowErasePort = get()",
        ))
        assertTrue("Production reset must inject durable AppDB provenance", production.contains(
            "durableLearnedWorkflowPrivacyPort = get()",
        ))
    }

    @Test
    fun coldRestoreRedactsLearnedDefinitionsAndPreservesUserAuthorityBeforeSwap() {
        val reconciler = projectFile(
            "app/src/main/java/me/rerere/rikkahub/data/db/ImportedDatabaseReconciler.kt",
            "src/main/java/me/rerere/rikkahub/data/db/ImportedDatabaseReconciler.kt",
        ).readText()
        val restore = reconciler.substringAfter("fun reconcileStagedFileOrThrow")
            .substringBefore("private fun migrateExactStagedToV49")
        assertTrue(
            restore.indexOf("quarantineStagedLearnedWorkflowsOrThrow") in
                0 until restore.indexOf("normalizeStagedToSingleFileOrThrow"),
        )
        val quarantine = reconciler.substringAfter(
            "private fun quarantineStagedLearnedWorkflowsOrThrow",
        ).substringBefore("private fun invalidCanonicalUuidSql")
        assertTrue(quarantine.contains("WHERE `origin` = 'LEARNED'"))
        assertTrue(!quarantine.contains("WHERE `origin` = 'USER'"))
        assertTrue(quarantine.contains("DELETE FROM `workflow_runs`"))
    }

    private fun projectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }
}

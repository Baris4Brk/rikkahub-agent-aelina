package me.rerere.rikkahub.learning.workflow

import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionActor
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedWorkflowRetentionContractTest {
    @Test
    fun maintenanceArchivesThroughExactLifecycleInsteadOfDeletingHistory() {
        val daoSource = java.io.File(
            "src/main/java/me/rerere/rikkahub/learning/storage/dao/" +
                "LearnedWorkflowCandidateDao.kt",
        ).readText()
        val retentionSource = java.io.File(
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningRetentionPolicyV1.kt",
        ).readText()

        assertTrue("suspend fun listExpiredArchivable" in daoSource)
        assertFalse("deleteExpiredDormant" in daoSource)
        assertTrue("dao.transitionFenced(" in retentionSource)
        assertTrue(
            LearnedWorkflowCandidateRevisionReason.RETENTION_EXPIRED.name in retentionSource,
        )
        assertTrue(LearnedWorkflowCandidateRevisionActor.RETENTION.name in retentionSource)
    }
}

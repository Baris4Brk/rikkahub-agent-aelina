package me.rerere.rikkahub.learning.retention

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowCandidateRetentionContractTest {
    @Test
    fun `verified candidates expire and workflow revision cutoff reaches a bounded dao mutation`() {
        val dao = projectFile(
            "src/main/java/me/rerere/rikkahub/learning/storage/dao/LearnedWorkflowCandidateDao.kt",
        ).readText()
        val store = projectFile(
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningRetentionPolicyV1.kt",
        ).readText()
        assertTrue(dao.contains("'PROPOSED','VERIFIED','REJECTED'"))
        assertTrue(dao.contains("deleteExpiredSupersededMachineRevisions"))
        assertTrue(dao.contains("r.state_version != c.state_version"))
        assertTrue(dao.contains("r.actor != 'USER'"))
        assertTrue(store.contains("cutoffs.workflowRevisionCutoffMs"))
        assertTrue(store.contains("deletedWorkflowRevisions = workflowRevisions"))
    }

    private fun projectFile(relative: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val direct = File(current, relative)
            if (direct.isFile) return direct
            val underApp = File(current, "app/$relative")
            if (underApp.isFile) return underApp
            current = current.parentFile ?: return@repeat
        }
        error("project file not found: $relative")
    }
}

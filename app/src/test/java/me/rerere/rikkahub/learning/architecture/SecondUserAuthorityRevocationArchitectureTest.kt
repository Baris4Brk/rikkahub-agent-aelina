package me.rerere.rikkahub.learning.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondUserAuthorityRevocationArchitectureTest {
    @Test
    fun `reassign coordinator cannot clear durable epoch before learning saga completes`() {
        val source = projectFile(
            "app/src/main/java/me/rerere/rikkahub/assistant/" +
                "SecondUserAuthorityRevocationCoordinator.kt",
        ).readText()
        val saga = source.indexOf("learningAuthorityRevocation.resume(learningFence)")
        val complete = source.indexOf("authority.completeUnassign()")
        assertTrue(saga >= 0)
        assertTrue(complete > saga)
        assertTrue("learningAuthorityRevocationPending = true" in source)
    }

    @Test
    fun `reserved old epochs have no production grant revival path`() {
        val writer = projectFile(
            "app/src/main/java/me/rerere/rikkahub/data/authority/policy/" +
                "RoomPolicyGrantService.kt",
        ).readText()
        assertTrue("hasLiveReservedSecondUserAuthority" in writer)
        assertTrue("SecondUserAuthorityRegistry.current()" in writer)
        assertTrue("active.subjectId == subject" in writer)
        assertTrue("local_second_user:" in writer)
        assertFalse("SECOND_USER_AUTHORITY_REVOKED isAllowedFor" in writer)
    }

    @Test
    fun `learning adapter records policy evidence and workflow audit instead of raw status updates`() {
        val source = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/authority/" +
                "RoomSecondUserDerivedAuthorityInvalidationPort.kt",
        ).readText()
        assertTrue("PolicyLifecycleEvidenceKind.AUTHORITY_DRIFT" in source)
        assertTrue("PolicyMutationActor.AUTHORITY_RECONCILER" in source)
        assertTrue("LearnedWorkflowCandidateRevisionReason.AUTHORITY_DRIFT" in source)
        assertTrue("workflowDao.transitionFenced" in source)
        assertFalse("UPDATE learning_policies SET" in source)
        assertFalse("UPDATE learned_workflow_candidates SET" in source)
    }
}

private fun projectFile(relative: String): File {
    var current = File(System.getProperty("user.dir")).canonicalFile
    repeat(8) {
        val candidate = File(current, relative)
        if (candidate.isFile) return candidate
        current = current.parentFile ?: return@repeat
    }
    error("Project file not found: $relative")
}


package me.rerere.rikkahub.learning.workflow.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level composition contract because Android Room/Koin are unavailable to this JVM test. */
class ReviewedPolicyWorkflowProductionCompositionTest {
    @Test
    fun `production graph keeps reviewed proposal explicit exact and content-free at evidence edge`() {
        val root = locateProjectRoot()
        val di = read(root, "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt")
        val facade = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt",
        )
        val proposal = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/workflow/runtime/" +
                "ReviewedPolicyWorkflowSubmission.kt",
        )

        listOf(
            "single<me.rerere.rikkahub.learning.workflow.runtime." +
                "ReviewedPolicyWorkflowSourceRuntimePort>",
            "single<me.rerere.rikkahub.learning.workflow.runtime." +
                "ReviewedPolicyWorkflowProposalPort>",
            "ProductionReviewedPolicyWorkflowProposalPort(",
            "single<me.rerere.rikkahub.learning.workflow.runtime." +
                "UserReviewedPolicyWorkflowSubmissionService>",
            "UserReviewedPolicyWorkflowSubmissionCoordinator(",
        ).forEach { needle ->
            assertTrue("Missing reviewed workflow DI edge: $needle", needle in di)
        }
        listOf(
            "ReviewedPolicyWorkflowSourceRuntimePort",
            "readExactReviewedPolicyWorkflowSource(",
            "findExactGrantedActivePolicy(",
            "listEvidenceValidity(",
            "PolicyApplicabilityWire.decodeToolSchemasOrNull",
        ).forEach { needle ->
            assertTrue("Missing exact LearningDB source fence: $needle", needle in facade)
        }
        listOf(
            "SAFE_TIME_INFO_TOOL: String = \"get_time_info\"",
            "HostWorkflowFixtureProfile.SAFE_TIME_INFO_V1",
            "typedSlots = emptyList()",
            "args = JsonObject(emptyMap())",
            "workflowAuthority.revalidateExact(exactGrant)",
            "finalSource != initialSource",
        ).forEach { needle ->
            assertTrue("Missing closed proposal fence: $needle", needle in proposal)
        }
        assertFalse("Source DTO must not retain a message body", "messageText:" in proposal)
        assertFalse("Source DTO must not retain a lesson body", "lessonText:" in proposal)
        assertFalse("Source DTO must not retain a trace body", "traceText:" in proposal)
    }

    private fun read(root: Path, relative: String): String =
        Files.readString(root.resolve(relative), StandardCharsets.UTF_8)

    private fun locateProjectRoot(): Path {
        var cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("app/src/main/java"))) return cursor
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate project root")
    }
}

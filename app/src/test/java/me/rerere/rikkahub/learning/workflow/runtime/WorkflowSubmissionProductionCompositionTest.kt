package me.rerere.rikkahub.learning.workflow.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level production composition contract; Android Koin cannot be booted in this JVM suite. */
class WorkflowSubmissionProductionCompositionTest {
    @Test
    fun `production graph connects exact authority Room runtime host fixtures and orchestrator`() {
        val root = locateProjectRoot()
        val di = read(root, "app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt")
        val facade = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt",
        )
        val authority = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/workflow/runtime/" +
                "ProductionWorkflowSubmissionAuthority.kt",
        )
        val host = read(
            root,
            "app/src/main/java/me/rerere/rikkahub/learning/workflow/runtime/" +
                "ProductionHostWorkflowFixtureProvider.kt",
        )

        listOf(
            "single<me.rerere.rikkahub.learning.workflow.runtime.WorkflowCandidateSubmissionRuntime>",
            "single<me.rerere.rikkahub.learning.workflow.runtime.WorkflowCandidateRuntimeStore>",
            "single<me.rerere.rikkahub.learning.workflow.runtime.WorkflowSubmissionAuthorityPort>",
            "ProductionWorkflowSubmissionAuthority(",
            "single<me.rerere.rikkahub.learning.workflow.runtime.HostWorkflowFixtureProvider>",
            "ProductionHostWorkflowFixtureProvider",
            "single<me.rerere.rikkahub.learning.workflow.runtime.LearnedWorkflowSubmissionService>",
            "LearnedWorkflowSubmissionOrchestrator(",
        ).forEach { needle ->
            assertTrue("Missing production DI edge: $needle", needle in di)
        }
        listOf(
            "WorkflowCandidateSubmissionRuntime",
            "dao.insertCompiled(candidate.toEntity())",
            ".transitionFenced(",
            "WorkflowCandidateTransition.VALIDATION_PASSED",
            "WorkflowCandidateTransition.VALIDATION_FAILED",
        ).forEach { needle ->
            assertTrue("Missing Room runtime fence: $needle", needle in facade)
        }
        listOf(
            "grants.revalidateExact(exactGrant)",
            "settings.assistants.singleOrNull",
            "ToolCallOrigin.TrustedWorkflow",
            "ToolCatalogSnapshot.fromDefinitions",
        ).forEach { needle ->
            assertTrue("Missing current authority fence: $needle", needle in authority)
        }
        listOf(
            "SAFE_TIME_INFO_V1",
            "get_time_info",
            "FakeWorkflowToolRegistration(",
            "WorkflowReplayFixture(",
            "entry.schemaFingerprint",
        ).forEach { needle ->
            assertTrue("Missing real host fixture component: $needle", needle in host)
        }
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

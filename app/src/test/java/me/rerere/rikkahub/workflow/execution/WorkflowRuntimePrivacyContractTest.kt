package me.rerere.rikkahub.workflow.execution

import java.io.File
import me.rerere.rikkahub.data.agentrun.AgentRunFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRuntimePrivacyContractTest {
    @Test
    fun `durable workflow and agent run reasons collapse unknown runtime content`() {
        assertEquals(
            WorkflowFailureCode.ACTION_RUNTIME_FAILURE,
            WorkflowFailureCode.durableOrGeneric("secret tool output and exception detail"),
        )
        assertEquals(
            AgentRunFailureCode.RUNTIME_FAILURE,
            AgentRunFailureCode.sanitize("IllegalStateException: token=secret"),
        )
        assertEquals(
            WorkflowFailureCode.ACTION_TIMEOUT,
            WorkflowFailureCode.durableOrGeneric(WorkflowFailureCode.ACTION_TIMEOUT),
        )
    }

    @Test
    fun `workflow runner never builds durable detail or output summaries`() {
        val source = projectFile(
            "src/main/java/me/rerere/rikkahub/workflow/execution/WorkflowEngine.kt",
        ).readText()
        listOf(
            "t.message",
            "runtimeResult.detail",
            "Completed).output",
            "outputs.joinToString",
            "hardlineReason\"",
        ).forEach { forbidden ->
            assertFalse("runtime content must not enter workflow result: $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("WorkflowFailureCode.ACTION_RUNTIME_FAILURE"))
        assertTrue(source.contains("WorkflowFailureCode.ACTIONS_COMPLETED"))
    }

    @Test
    fun `learned execution snapshot carries and attests installed definition`() {
        val engine = projectFile(
            "src/main/java/me/rerere/rikkahub/workflow/execution/WorkflowEngine.kt",
        ).readText()
        val validator = projectFile(
            "src/main/java/me/rerere/rikkahub/learning/promotion/ProductionLearnedWorkflowAuthorityValidator.kt",
        ).readText()
        assertTrue(engine.contains("val installedDefinition: WorkflowDefinition"))
        assertTrue(engine.contains("installedDefinition = def"))
        assertTrue(validator.contains("candidate.matchesInstalled(snapshot)"))
        assertTrue(validator.contains("recomputedArtifact != artifactSha256"))
        assertTrue(validator.contains("snapshot.installedDefinition == expectedInstalled"))
    }

    @Test
    fun `enabled learned row still fails before tool execution when source authority is stale`() {
        val engine = projectFile(
            "src/main/java/me/rerere/rikkahub/workflow/execution/WorkflowEngine.kt",
        ).readText()
        val validator = projectFile(
            "src/main/java/me/rerere/rikkahub/learning/promotion/ProductionLearnedWorkflowAuthorityValidator.kt",
        ).readText()
        val facade = projectFile(
            "src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt",
        ).readText()
        val invalidation = projectFile(
            "src/main/java/me/rerere/rikkahub/learning/jobs/P1LearningJobOutputs.kt",
        ).readText()

        assertTrue(validator.contains("sourceAuthority.isCurrentFailClosed(candidate)"))
        assertTrue(facade.contains("countBlockingSourceInvalidationJobs"))
        assertTrue(facade.contains("reader.inspect() == before"))
        assertTrue(invalidation.contains("LearnedWorkflowCandidateState.STALE_SOURCE.name"))
        assertTrue(invalidation.contains("LearnedWorkflowCandidateRevisionReason.SOURCE_INVALIDATED"))

        val authorityCheck = engine.indexOf("learnedAuthorityValidator?.isActive(authority)")
        val disable = engine.indexOf("repository.disableLearnedAsStale", authorityCheck)
        val toolResolution = engine.indexOf("val settings = settingsStore.settingsFlow.first()", disable)
        assertTrue(authorityCheck >= 0 && disable > authorityCheck && toolResolution > disable)
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

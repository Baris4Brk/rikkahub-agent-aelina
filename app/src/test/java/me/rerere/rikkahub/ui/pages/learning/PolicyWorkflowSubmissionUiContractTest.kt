package me.rerere.rikkahub.ui.pages.learning

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyWorkflowSubmissionUiContractTest {
    @Test
    fun `reviewed Policy exposes one explicit confirmed submission path`() {
        val root = locateProjectRoot()
        val vm = read(root, "app/src/main/java/me/rerere/rikkahub/ui/pages/learning/LearningCenterVM.kt")
        val page = read(root, "app/src/main/java/me/rerere/rikkahub/ui/pages/learning/LearningCenterPage.kt")
        val di = read(root, "app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt")

        listOf(
            "UserReviewedPolicyWorkflowSubmissionService",
            "ReviewedPolicyWorkflowProposalRequest(",
            "explicitUserSubmission = true",
            "expectedGrantStateVersion = detail.grant.stateVersion",
        ).forEach { needle ->
            assertTrue("Missing direct reviewed submission fence: $needle", needle in vm)
        }
        listOf(
            "ReviewConfirmation.CreateWorkflow",
            "learning_policy_create_workflow_confirm",
            "item.status == LearningPolicyStatus.ACTIVE",
            "detail.grant.state == PolicyReviewGrantState.EXACT_GRANTED",
        ).forEach { needle ->
            assertTrue("Missing explicit UI confirmation/fence: $needle", needle in page)
        }
        assertTrue("ViewModel does not receive the production submission service", "workflowSubmission = get()" in di)
    }

    @Test
    fun `assistant scope erase remains reachable with an empty Policy list`() {
        val root = locateProjectRoot()
        val vm = read(root, "app/src/main/java/me/rerere/rikkahub/ui/pages/learning/LearningCenterVM.kt")
        val page = read(root, "app/src/main/java/me/rerere/rikkahub/ui/pages/learning/LearningCenterPage.kt")

        assertTrue("Exact assistant scope is not independently selectable", "requestAssistantScopeErase" in vm)
        assertTrue("Scope erase card is hidden behind a Policy row", "AssistantScopeEraseCard" in page)
        assertTrue("Scope erase lacks a fresh confirmation", "vm.confirmErase()" in page)
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

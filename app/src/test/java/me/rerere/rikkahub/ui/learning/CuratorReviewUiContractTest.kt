package me.rerere.rikkahub.ui.learning

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratorReviewUiContractTest {
    private val appRoot = Paths.get(System.getProperty("user.dir")).let { cwd ->
        if (Files.isDirectory(cwd.resolve("src/main"))) cwd else cwd.resolve("app")
    }

    private fun source(name: String): String = Files.readString(
        appRoot.resolve("src/main/java/me/rerere/rikkahub/ui/pages/learning/curator/$name"),
    )

    @Test
    fun `detail renders reviewed values full fences revision lineage and runtime state`() {
        val page = source("CuratorReviewPage.kt")
        listOf(
            "diff.afterValue",
            "diff.beforeSha256",
            "diff.afterSha256",
            "source.expectedRevision",
            "source.baseHash",
            "evidence.integritySha256",
            "revision.candidateSha256",
            "revision.previousStateVersion",
            "revision.applyPlanId",
            "edge.parentArtifactSha256",
            "edge.childArtifactSha256",
            "summary.conflictCode",
            "plan.rollback.expectedAppliedHeads",
        ).forEach { required -> assertTrue(required, page.contains(required)) }
    }

    @Test
    fun `apply and rollback remain explicit confirmed actions`() {
        val page = source("CuratorReviewPage.kt")
        assertTrue(page.contains("CuratorAction.Apply"))
        assertTrue(page.contains("CuratorAction.Rollback"))
        assertTrue(page.contains("learning_curator_confirm_apply"))
        assertTrue(page.contains("learning_curator_confirm_rollback"))
        assertTrue(page.contains("AlertDialog"))
    }

    @Test
    fun `explicit producer form reaches typed coordinator for all four operations`() {
        val page = source("CuratorReviewPage.kt")
        val vm = source("CuratorReviewVM.kt")
        assertTrue(page.contains("CuratorProposalForm"))
        assertTrue(page.contains("CuratorDeltaOperation.entries"))
        assertTrue(page.contains("learning_curator_confirm_proposal"))
        assertTrue(vm.contains("producer.listExactReviewedSources"))
        assertTrue(vm.contains("producer.propose"))
        assertTrue(vm.contains("CuratorDeltaCandidate.Update"))
        assertTrue(vm.contains("CuratorDeltaCandidate.Merge"))
        assertTrue(vm.contains("CuratorDeltaCandidate.Split"))
        assertTrue(vm.contains("CuratorDeltaCandidate.Supersede"))
        assertTrue(vm.contains("explicitlyUserReviewed = true"))
    }
}

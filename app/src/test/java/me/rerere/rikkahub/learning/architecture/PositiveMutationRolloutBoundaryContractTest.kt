package me.rerere.rikkahub.learning.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositiveMutationRolloutBoundaryContractTest {
    private val root: Path = Path.of(System.getProperty("user.dir")).let { cwd ->
        if (Files.exists(cwd.resolve("src/main"))) cwd else cwd.resolve("app")
    }

    @Test
    fun policyPositiveWritesAreGatedButAuthorityReductionAndEraseRemainAvailable() {
        val source = main("learning/review/ProductionLearningPolicyReviewRepository.kt")
        val coordinator = main("learning/grant/PolicyGrantReviewCoordinator.kt")
        val approve = function(
            source,
            "override suspend fun approve",
            "override suspend fun revoke",
        )
        val revoke = function(
            source,
            "override suspend fun revoke",
            "override suspend fun suspendPolicy",
        )
        val suspend = function(
            source,
            "override suspend fun suspendPolicy",
            "override suspend fun archive",
        )
        val restore = function(
            source,
            "override suspend fun restoreRevision",
            "override suspend fun issueEraseChallenge",
        )
        val erase = function(
            source,
            "override suspend fun erase",
            "override suspend fun exportRedacted",
        )

        assertTrue("POLICY_APPROVE_OR_RESUME" in approve)
        assertTrue("POLICY_RESTORE_ARCHIVED_REVISION" in restore)
        assertFalse("positiveMutationUnavailable" in revoke)
        assertFalse("positiveMutationUnavailable" in suspend)
        assertFalse("positiveMutationUnavailable" in erase)
        assertTrue("command.fence != PolicyGrantFence.REVOKE" in coordinator)
        assertTrue("POLICY_APPROVE_OR_RESUME" in coordinator)
    }

    @Test
    fun everyCuratorPositiveBoundaryChecksTheExactOperationAndReversalDoesNot() {
        val candidate = main("learning/curator/CuratorCandidateProduction.kt")
        val review = main("learning/curator/CuratorReviewRuntimeStore.kt")
        val apply = main("learning/curator/CuratorApplyRuntimeStore.kt")
        val mapping = main("learning/curator/CuratorOperationRolloutGate.kt")

        assertTrue("positiveMutations.allows(request.candidate.operation)" in candidate)
        assertTrue("positiveMutations.allows(request.expectedOperation)" in review)
        assertTrue("positiveMutations.allows(request.expectedOperation)" in apply)
        assertTrue("CURATOR_UPDATE_CANDIDATE" in mapping)
        assertTrue("CURATOR_MERGE_CANDIDATE" in mapping)
        assertTrue("CURATOR_SPLIT_CANDIDATE" in mapping)
        assertTrue("CURATOR_SUPERSEDE_CANDIDATE" in mapping)

        val reviewCoordinator = review.substringAfter("class CuratorReviewRuntimeCoordinator")
        val applyCoordinator = apply.substringAfter("class CuratorApplyRuntimeCoordinator")
        val reject = function(reviewCoordinator, "suspend fun reject", "suspend fun archive")
        val rollback = function(
            applyCoordinator,
            "suspend fun rollback",
            "const val MAX_CURATOR_RUNTIME_MUTATIONS",
        )
        assertFalse("positiveMutations" in reject)
        assertFalse("positiveMutations" in rollback)
    }

    @Test
    fun workflowSubmissionPromotionAndExecutionRetainLastBoundaryRolloutFences() {
        val submission = main("learning/workflow/runtime/LearnedWorkflowSubmissionOrchestrator.kt")
        val submissionGate = main("learning/workflow/runtime/WorkflowRolloutGate.kt")
        val promotion = main("learning/promotion/LearnedWorkflowPromotionSaga.kt")
        val promotionGate = main("learning/promotion/GatedLearnedWorkflowPromotionService.kt")

        assertTrue(submission.split("if (!rolloutFence())").size - 1 >= 2)
        assertFalse("rolloutFence: () -> Boolean = { true }" in submission)
        assertTrue("gate.candidateEnabled()" in submissionGate)
        assertTrue("if (!rolloutFence())" in promotion)
        assertFalse("rolloutFence: () -> Boolean = { true }" in promotion)
        assertTrue("gate.promotionEnabled()" in promotionGate)
    }

    @Test
    fun cleanupRetentionAndInvalidationDoNotDependOnPositiveRolloutConsent() {
        val cleanupSources = listOf(
            main("learning/privacy/LearningDerivedEraseService.kt"),
            main("learning/retention/LearningRetentionMaintenance.kt"),
            main("learning/handoff/LearningSourceInvalidationAuthorityEventWriter.kt"),
            main("learning/handoff/RewardFeedbackAuthorityOutboxAdapter.kt"),
        )
        cleanupSources.forEach { source ->
            assertFalse("LearningPositiveMutationGate" in source)
            assertFalse("positiveMutations" in source)
        }
    }

    @Test
    fun productionDependencyGraphUsesOneResolvedGateSource() {
        val di = main("../di/DataSourceModule.kt")
        val settings = main("ui/pages/setting/SettingAgentRuntimePage.kt")
        val controller = main("learning/model/LearningRolloutController.kt")
        assertTrue("FeatureFlagLearningPositiveMutationGate(get())" in di)
        assertTrue("curatorV1Ready = true" in di)
        assertTrue(di.split("positiveMutations = get()").size - 1 >= 4)
        assertTrue("setCuratorOperationEnabled" in controller)
        assertTrue("LearningCuratorOperation.UPDATE" in settings)
        assertTrue("LearningCuratorOperation.MERGE" in settings)
        assertTrue("LearningCuratorOperation.SPLIT" in settings)
        assertTrue("LearningCuratorOperation.SUPERSEDE" in settings)
    }

    @Test
    fun uiDisablesPositiveControlsButLeavesReductionControlsIndependent() {
        val policyVm = main("ui/pages/learning/LearningCenterVM.kt")
        val policyPage = main("ui/pages/learning/LearningCenterPage.kt")
        val workflowVm = main("ui/pages/learning/workflow/WorkflowReviewVM.kt")
        val workflowPage = main("ui/pages/learning/workflow/WorkflowReviewPage.kt")
        val curatorVm = main("ui/pages/learning/curator/CuratorReviewVM.kt")
        val curatorPage = main("ui/pages/learning/curator/CuratorReviewPage.kt")

        assertTrue("policyPositiveActionsEnabled" in policyVm)
        assertTrue("workflowCandidateActionEnabled" in policyVm)
        assertTrue("return@perform PolicyReviewActionResult.Unavailable" in policyVm)
        assertTrue("\"ROLLOUT_DISABLED\"" in policyVm)
        assertTrue("positiveActionsEnabled && canApprove" in policyPage)
        assertTrue("workflowCandidateEnabled && item.status" in policyPage)
        assertTrue("enabled = canRevoke && !busy" in policyPage)
        assertTrue("enabled = canSuspend && !busy" in policyPage)
        assertTrue("WORKFLOW_PROMOTION_OR_ENABLE" in workflowVm)
        assertTrue("positiveActionsEnabled && detail.canPromoteDisabled" in workflowPage)
        assertTrue("positiveActionsEnabled && detail.canEnable" in workflowPage)
        assertTrue("learning_positive_actions_disabled" in policyPage)
        assertTrue("learning_positive_actions_disabled" in workflowPage)
        assertTrue("positiveMutations.allows(operation)" in curatorVm)
        assertTrue("operationEnabled = vm::operationEnabled" in curatorPage)
        assertTrue("positiveActionsEnabled && !busy" in curatorPage)
        assertTrue("learning_positive_actions_disabled" in curatorPage)
    }

    private fun main(relative: String): String {
        val path = if (relative.startsWith("../di/")) {
            root.resolve("src/main/java/me/rerere/rikkahub/di/${relative.removePrefix("../di/")}")
        } else {
            root.resolve("src/main/java/me/rerere/rikkahub/$relative")
        }
        return Files.readString(path)
    }

    private fun function(source: String, start: String, end: String): String =
        source.substringAfter(start).substringBefore(end)
}

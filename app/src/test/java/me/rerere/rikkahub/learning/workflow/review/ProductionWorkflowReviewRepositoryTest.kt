package me.rerere.rikkahub.learning.workflow.review

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanCursor
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanResult
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.promotion.LearnedWorkflowPromotionService
import me.rerere.rikkahub.learning.promotion.WorkflowPromotionFence
import me.rerere.rikkahub.learning.promotion.WorkflowPromotionResult
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ProductionWorkflowReviewRepositoryTest {
    @Test
    fun `enable without second explicit confirmation is rejected before any authority or saga call`() =
        runBlocking {
            var sagaCalls = 0
            val repository = ProductionWorkflowReviewRepository(
                runtime = object : WorkflowReviewRuntimePort {
                    override suspend fun listWorkflowCandidates(
                        consumingAssistantId: Uuid,
                        limit: Int,
                    ) = WorkflowReviewReadResult.Ready(emptyList<WorkflowReviewListItem>())

                    override suspend fun readWorkflowCandidate(
                        consumingAssistantId: Uuid,
                        candidateId: String,
                    ) = WorkflowReviewReadResult.NotFound
                },
                grantAuthority = object : PolicyGrantAuthoritySource {
                    override suspend fun listExactGranted(
                        scope: LearningScope,
                        consumingAssistantId: Uuid,
                        sourceStreamId: String,
                        limit: Int,
                    ): List<PolicyGrantAuthoritySnapshot> = error("authority read must not run")

                    override suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot) =
                        error("authority revalidation must not run")

                    override suspend fun listCurrentPage(
                        after: PolicyGrantAuthorityScanCursor?,
                        limit: Int,
                    ): PolicyGrantAuthorityScanResult = error("authority scan must not run")
                },
                promotion = object : LearnedWorkflowPromotionService {
                    override suspend fun promoteVerifiedDisabled(
                        fence: WorkflowPromotionFence,
                        exactGrant: PolicyGrantAuthoritySnapshot,
                        nowMs: Long,
                    ): WorkflowPromotionResult {
                        sagaCalls++
                        return WorkflowPromotionResult.PromotedDisabled("learned:id", false)
                    }

                    override suspend fun enableAfterExplicitConfirmation(
                        fence: WorkflowPromotionFence,
                        exactGrant: PolicyGrantAuthoritySnapshot,
                        expectedWorkflowStateVersion: Long,
                        userConfirmed: Boolean,
                        nowMs: Long,
                    ): WorkflowPromotionResult {
                        sagaCalls++
                        return WorkflowPromotionResult.Enabled("learned:id")
                    }
                },
                workflows = nullWorkflowRepository(),
                metadataSource = WorkflowReviewToolMetadataSource { _, _ -> null },
            )

            val result = repository.enable(
                EnablePromotedWorkflowCommand(
                    consumingAssistantId = ASSISTANT_ID,
                    fence = WorkflowReviewFence(
                        candidateId = "workflow-candidate-v1:${"a".repeat(64)}",
                        candidateVersion = 1L,
                        stateVersion = 3L,
                        artifactSha256 = "b".repeat(64),
                    ),
                    expectedWorkflowStateVersion = 1L,
                    explicitUserConfirmation = false,
                ),
            )

            assertEquals(
                WorkflowReviewMutationResult.Rejected("EXPLICIT_CONFIRMATION_REQUIRED"),
                result,
            )
            assertEquals(0, sagaCalls)
        }

    /** This dependency is never touched by the early-confirmation test. */
    private fun nullWorkflowRepository(): WorkflowRepository = WorkflowRepository(
        workflowDao = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(me.rerere.rikkahub.workflow.db.WorkflowDao::class.java),
        ) { _, _, _ -> error("workflow store must not run") } as me.rerere.rikkahub.workflow.db.WorkflowDao,
        workflowRunDao = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(me.rerere.rikkahub.workflow.db.WorkflowRunDao::class.java),
        ) { _, _, _ -> error("workflow run store must not run") } as me.rerere.rikkahub.workflow.db.WorkflowRunDao,
    )

    companion object {
        private val ASSISTANT_ID = Uuid.parse("11111111-1111-4111-8111-111111111111")
    }
}

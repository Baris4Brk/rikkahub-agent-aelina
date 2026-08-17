package me.rerere.rikkahub.learning.promotion

import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.workflow.runtime.WorkflowRolloutGate

/** The only production-facing promotion service; disabling P4 immediately denies both writes. */
class GatedLearnedWorkflowPromotionService(
    private val delegate: LearnedWorkflowPromotionService,
    private val gate: WorkflowRolloutGate,
) : LearnedWorkflowPromotionService {
    override suspend fun promoteVerifiedDisabled(
        fence: WorkflowPromotionFence,
        exactGrant: PolicyGrantAuthoritySnapshot,
        nowMs: Long,
    ): WorkflowPromotionResult {
        if (!gate.promotionEnabled()) return rolloutDisabled()
        return delegate.promoteVerifiedDisabled(fence, exactGrant, nowMs)
    }

    override suspend fun enableAfterExplicitConfirmation(
        fence: WorkflowPromotionFence,
        exactGrant: PolicyGrantAuthoritySnapshot,
        expectedWorkflowStateVersion: Long,
        userConfirmed: Boolean,
        nowMs: Long,
    ): WorkflowPromotionResult {
        if (!gate.promotionEnabled()) return rolloutDisabled()
        return delegate.enableAfterExplicitConfirmation(
            fence,
            exactGrant,
            expectedWorkflowStateVersion,
            userConfirmed,
            nowMs,
        )
    }

    private fun rolloutDisabled(): WorkflowPromotionResult =
        WorkflowPromotionResult.Rejected(WorkflowPromotionResult.Reason.ROLLOUT_DISABLED)
}

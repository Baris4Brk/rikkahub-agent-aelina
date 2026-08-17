package me.rerere.rikkahub.learning.workflow.runtime

import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.FeatureFlagLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningPositiveMutation
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate

/**
 * Content-free P4 emergency gate. Every production mutation and every learned execution checks
 * this projection at the last responsible boundary; schema presence never implies user consent.
 */
interface WorkflowRolloutGate {
    fun candidateEnabled(): Boolean
    fun promotionEnabled(): Boolean
}

class FeatureFlagWorkflowRolloutGate(
    private val positiveMutations: LearningPositiveMutationGate,
) : WorkflowRolloutGate {
    constructor(flags: LearningFeatureFlagSource) : this(
        FeatureFlagLearningPositiveMutationGate(flags),
    )

    override fun candidateEnabled(): Boolean = positiveMutations.allows(
        LearningPositiveMutation.WORKFLOW_CANDIDATE_SUBMISSION,
    )

    override fun promotionEnabled(): Boolean = positiveMutations.allows(
        LearningPositiveMutation.WORKFLOW_PROMOTION_OR_ENABLE,
    )
}

object DisabledWorkflowRolloutGate : WorkflowRolloutGate {
    override fun candidateEnabled(): Boolean = false
    override fun promotionEnabled(): Boolean = false
}

/** Keeps the pure compiler/verifier reusable while making the DI-exposed service fail closed. */
class GatedLearnedWorkflowSubmissionService(
    private val delegate: LearnedWorkflowSubmissionService,
    private val gate: WorkflowRolloutGate,
) : LearnedWorkflowSubmissionService {
    override suspend fun submit(
        request: LearnedWorkflowSubmissionRequest,
        nowMs: Long,
    ): LearnedWorkflowSubmissionResult {
        if (!gate.candidateEnabled()) {
            return LearnedWorkflowSubmissionResult.Unavailable(
                LearnedWorkflowSubmissionFailure.ROLLOUT_DISABLED,
            )
        }
        return delegate.submit(request, nowMs)
    }
}

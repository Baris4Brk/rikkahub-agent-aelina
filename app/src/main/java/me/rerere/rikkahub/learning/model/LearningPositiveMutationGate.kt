package me.rerere.rikkahub.learning.model

/**
 * Positive Learning mutations create/revive user-visible derived behavior. Cleanup and authority
 * reduction deliberately do not appear here: revoke, suspend, archive, rollback, erase,
 * retention, and source invalidation must continue to work after rollout is disabled.
 */
enum class LearningPositiveMutation {
    POLICY_APPROVE_OR_RESUME,
    POLICY_RESTORE_ARCHIVED_REVISION,
    CURATOR_UPDATE_CANDIDATE,
    CURATOR_MERGE_CANDIDATE,
    CURATOR_SPLIT_CANDIDATE,
    CURATOR_SUPERSEDE_CANDIDATE,
    WORKFLOW_CANDIDATE_SUBMISSION,
    WORKFLOW_PROMOTION_OR_ENABLE,
}

/** Re-read at the last responsible production boundary; no caller may cache an allow decision. */
fun interface LearningPositiveMutationGate {
    fun allows(mutation: LearningPositiveMutation): Boolean
}

class FeatureFlagLearningPositiveMutationGate(
    private val flags: LearningFeatureFlagSource,
) : LearningPositiveMutationGate {
    override fun allows(mutation: LearningPositiveMutation): Boolean {
        val resolved = flags.current()
        if (!resolved.isValid) return false
        val effective = resolved.effective
        return when (mutation) {
            LearningPositiveMutation.POLICY_APPROVE_OR_RESUME,
            LearningPositiveMutation.POLICY_RESTORE_ARCHIVED_REVISION,
            -> effective.policyInjection

            LearningPositiveMutation.CURATOR_UPDATE_CANDIDATE ->
                effective.policyInjection && effective.curatorUpdate
            LearningPositiveMutation.CURATOR_MERGE_CANDIDATE ->
                effective.policyInjection && effective.curatorMerge
            LearningPositiveMutation.CURATOR_SPLIT_CANDIDATE ->
                effective.policyInjection && effective.curatorSplit
            LearningPositiveMutation.CURATOR_SUPERSEDE_CANDIDATE ->
                effective.policyInjection && effective.curatorSupersede

            LearningPositiveMutation.WORKFLOW_CANDIDATE_SUBMISSION ->
                effective.policyInjection && effective.workflowCandidate
            LearningPositiveMutation.WORKFLOW_PROMOTION_OR_ENABLE ->
                effective.policyInjection && effective.workflowCandidate &&
                    effective.workflowPromotion
        }
    }
}

object DisabledLearningPositiveMutationGate : LearningPositiveMutationGate {
    override fun allows(mutation: LearningPositiveMutation): Boolean = false
}

package me.rerere.rikkahub.learning.policy

data class PolicyShadowPromotionCommand(
    val fence: PolicyMutationFence,
    /** Frozen by the caller and retained across a retry. */
    val frozenNowMs: Long,
) {
    init {
        require(frozenNowMs >= 0L)
    }

    override fun toString(): String =
        "PolicyShadowPromotionCommand(scope=${fence.scope.kind}, revision=${fence.expectedRevision}, ids=<redacted>)"
}

/**
 * Local-only P1 lifecycle seam. It cannot activate or inject a policy: its sole promotion is the
 * deterministic, revision/artifact-fenced CANDIDATE -> SHADOW mutation through the canonical
 * [PolicyMutationStore].
 */
class PolicyShadowLifecycle(
    private val mutationStore: PolicyMutationStore,
) {
    suspend fun promote(command: PolicyShadowPromotionCommand): PolicyMutationResult =
        mutationStore.mutate(
            PolicyMutationRequest.Transition(
                fence = command.fence,
                target = LearningPolicyStatus.SHADOW,
                reason = PolicyLifecycleReason.SHADOW_ELIGIBLE,
                frozenNowMs = command.frozenNowMs,
                actor = PolicyMutationActor.CURATOR_REVIEW,
            ),
        )
}

package me.rerere.rikkahub.learning.curation

enum class PolicyCuratorQueueDisposition {
    QUEUED,
    DUPLICATE,
}

/** Enqueues the existing P1-007 Distiller path; it is not a second draft generator. */
fun interface PolicyDistillationRequestQueue {
    suspend fun enqueueValidated(candidate: PolicyDeltaCandidate): PolicyCuratorQueueDisposition
}

/** Review-only sink. Implementations must not mutate canonical Policy state. */
fun interface PolicyHarmReviewQueue {
    suspend fun enqueueValidated(candidate: PolicyDeltaCandidate): PolicyCuratorQueueDisposition
}

sealed interface PolicyCuratorRoutingResult {
    data object NoOp : PolicyCuratorRoutingResult

    data class NewDraftQueued(val disposition: PolicyCuratorQueueDisposition) :
        PolicyCuratorRoutingResult

    data class HarmReviewQueued(val disposition: PolicyCuratorQueueDisposition) :
        PolicyCuratorRoutingResult

    data class Rejected(val failure: PolicyCuratorValidationFailure) :
        PolicyCuratorRoutingResult
}

/**
 * Production-composable ACE-style Curator v0. Every operation passes the same local validator.
 * QUEUE_NEW_DRAFT only reaches the existing Distiller queue, and QUEUE_HARM_REVIEW only reaches a
 * review queue. This type intentionally has no PolicyMutationStore dependency or mutation method.
 */
class PolicyCuratorV0(
    private val distillationQueue: PolicyDistillationRequestQueue,
    private val harmReviewQueue: PolicyHarmReviewQueue,
) {
    suspend fun route(
        candidate: PolicyDeltaCandidate,
        evidenceAllowlist: Set<String>,
    ): PolicyCuratorRoutingResult = when (
        val validated = PolicyCuratorValidator.validate(candidate, evidenceAllowlist)
    ) {
        is PolicyCuratorValidationResult.Rejected ->
            PolicyCuratorRoutingResult.Rejected(validated.failure)

        is PolicyCuratorValidationResult.Valid -> when (validated.candidate.operation) {
            PolicyDeltaOperation.NO_OP -> PolicyCuratorRoutingResult.NoOp
            PolicyDeltaOperation.QUEUE_NEW_DRAFT -> PolicyCuratorRoutingResult.NewDraftQueued(
                distillationQueue.enqueueValidated(validated.candidate),
            )
            PolicyDeltaOperation.QUEUE_HARM_REVIEW -> PolicyCuratorRoutingResult.HarmReviewQueued(
                harmReviewQueue.enqueueValidated(validated.candidate),
            )
        }
    }
}

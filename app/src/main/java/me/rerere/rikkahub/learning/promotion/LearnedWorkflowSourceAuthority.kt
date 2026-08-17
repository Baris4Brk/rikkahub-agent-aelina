package me.rerere.rikkahub.learning.promotion

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate

/**
 * Exact Learning-side source authority for a learned workflow artifact.
 *
 * Candidate and grant provenance are not sufficient on their own: a source edit/delete can be
 * durable in the primary outbox before its derived Policy transition has committed. Production
 * therefore rechecks the current stream head, derived checkpoint, invalidation-job barrier and
 * exact ACTIVE Policy/evidence tuple at every promotion, enable and execution boundary.
 */
fun interface LearnedWorkflowSourceAuthorityPort {
    suspend fun isCurrent(candidate: LearnedWorkflowCandidate): Boolean
}

/** Source authority is a negative safety fence: uncertainty is always denial. */
internal suspend fun LearnedWorkflowSourceAuthorityPort.isCurrentFailClosed(
    candidate: LearnedWorkflowCandidate,
): Boolean = try {
    isCurrent(candidate)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    false
}

package me.rerere.rikkahub.learning.grant

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.model.LearningPositiveMutation
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate

/**
 * Recoverable cross-database saga. The durable AppDatabase grant is committed first. A crash or
 * LearningDatabase failure after that point never rolls authority back or reports false success;
 * replay sees a duplicate authority head and resumes the exact derived projection.
 */
class AppFirstPolicyGrantReviewCoordinator(
    private val authority: PolicyGrantService,
    private val lifecycle: PolicyGrantLifecycleProjector,
    private val positiveMutations: LearningPositiveMutationGate,
) : PolicyGrantReviewCoordinator {
    override suspend fun review(
        command: PolicyGrantReviewCommand,
    ): PolicyGrantCoordinatedReviewResult {
        if (command.fence != PolicyGrantFence.REVOKE &&
            !positiveMutations.allows(
                LearningPositiveMutation.POLICY_APPROVE_OR_RESUME,
            )
        ) {
            return PolicyGrantCoordinatedReviewResult.AuthorityRejected(
                PolicyGrantReviewResult.Conflict(PolicyGrantConflict.ROLLOUT_DISABLED),
            )
        }
        val authorityResult = try {
            authority.review(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return PolicyGrantCoordinatedReviewResult.AuthorityRejected(
                PolicyGrantReviewResult.Conflict(PolicyGrantConflict.STORAGE_FAILURE),
            )
        }
        val snapshot: PolicyGrantAuthoritySnapshot
        val authorityWasDuplicate: Boolean
        when (authorityResult) {
            is PolicyGrantReviewResult.Applied -> {
                snapshot = authorityResult.snapshot
                authorityWasDuplicate = false
            }
            is PolicyGrantReviewResult.Duplicate -> {
                snapshot = authorityResult.snapshot
                authorityWasDuplicate = true
            }
            is PolicyGrantReviewResult.Conflict ->
                return PolicyGrantCoordinatedReviewResult.AuthorityRejected(authorityResult)
        }
        if (!snapshot.isExactReceiptFor(command)) {
            return PolicyGrantCoordinatedReviewResult.AuthorityRejected(
                PolicyGrantReviewResult.Conflict(
                    reason = PolicyGrantConflict.IDENTITY_MISMATCH,
                    currentStateVersion = snapshot.stateVersion,
                ),
            )
        }

        val lifecycleResult = try {
            lifecycle.project(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            PolicyGrantLifecycleProjectionResult.Pending(
                PolicyGrantLifecyclePendingReason.STORAGE_FAILURE,
            )
        }
        return when (lifecycleResult) {
            is PolicyGrantLifecycleProjectionResult.Applied ->
                PolicyGrantCoordinatedReviewResult.Completed(
                    authority = snapshot,
                    lifecycleRevision = lifecycleResult.lifecycleRevision,
                    authorityWasDuplicate = authorityWasDuplicate,
                    lifecycleWasDuplicate = false,
                )
            is PolicyGrantLifecycleProjectionResult.Duplicate ->
                PolicyGrantCoordinatedReviewResult.Completed(
                    authority = snapshot,
                    lifecycleRevision = lifecycleResult.lifecycleRevision,
                    authorityWasDuplicate = authorityWasDuplicate,
                    lifecycleWasDuplicate = true,
                )
            is PolicyGrantLifecycleProjectionResult.AlreadySatisfied ->
                PolicyGrantCoordinatedReviewResult.Completed(
                    authority = snapshot,
                    lifecycleRevision = lifecycleResult.lifecycleRevision,
                    authorityWasDuplicate = authorityWasDuplicate,
                    lifecycleWasDuplicate = true,
                )
            is PolicyGrantLifecycleProjectionResult.Pending ->
                PolicyGrantCoordinatedReviewResult.AuthorityCommittedDerivedPending(
                    authority = snapshot,
                    reason = lifecycleResult.reason,
                    authorityWasDuplicate = authorityWasDuplicate,
                )
        }
    }
}

/** A derived projection never accepts an authority adapter's non-exact or stale receipt. */
internal fun PolicyGrantAuthoritySnapshot.isExactReceiptFor(
    command: PolicyGrantReviewCommand,
): Boolean {
    if (command.expectedGrantStateVersion == Long.MAX_VALUE) return false
    val expectedState = when (command.fence) {
        PolicyGrantFence.GRANT,
        PolicyGrantFence.UPDATE_EXACT_POLICY,
        -> PolicyGrantAuthorityState.GRANTED
        PolicyGrantFence.REVOKE -> PolicyGrantAuthorityState.REVOKED
    }
    return sourceStreamId == command.sourceStreamId &&
        scope == command.scope &&
        consumingAssistantId == command.consumingAssistantId &&
        policyId == command.policyId &&
        contentRevision == command.contentRevision &&
        artifactSha256 == command.artifactSha256 &&
        state == expectedState &&
        stateVersion == command.expectedGrantStateVersion + 1L &&
        reason == command.reason &&
        updatedAtEpochMs == command.frozenNowEpochMs &&
        (command.fence != PolicyGrantFence.REVOKE ||
            revokedAtEpochMs == command.frozenNowEpochMs)
}

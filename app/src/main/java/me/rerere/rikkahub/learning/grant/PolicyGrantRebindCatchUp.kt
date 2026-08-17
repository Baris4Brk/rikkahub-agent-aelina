package me.rerere.rikkahub.learning.grant

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity

/**
 * Process-local, bounded replay of durable AppDatabase grant heads into rebuilt derived state.
 *
 * The cursor advances only after the complete returned page has been projected. A crash or
 * cancellation therefore replays that page, while exact lifecycle CAS makes the replay
 * idempotent. The cursor is intentionally not durable: process restart begins at the first page,
 * then continues across later maintenance cycles and wraps to the first page after reaching EOF.
 */
internal class PolicyGrantRebindCatchUp(
    private val authoritySource: PolicyGrantAuthoritySource,
    private val pageSize: Int = MAX_POLICY_GRANT_REBIND_PAGE_SIZE,
) {
    private var activeStreamId: String? = null
    private var activeReplayGeneration: Long? = null
    private var after: PolicyGrantAuthorityScanCursor? = null

    init {
        require(pageSize in 1..MAX_POLICY_GRANT_REBIND_PAGE_SIZE) {
            "Unsafe grant rebind page size"
        }
    }

    suspend fun catchUp(
        expectedStreamId: String,
        expectedReplayGeneration: Long,
        isRuntimeCurrent: () -> Boolean,
        projector: PolicyGrantLifecycleProjector,
    ): PolicyGrantRebindCatchUpResult {
        require(expectedStreamId.isCanonicalPolicyGrantStreamId()) {
            "Invalid grant rebind stream"
        }
        require(expectedReplayGeneration >= 0L) { "Invalid grant rebind replay generation" }
        if (!isRuntimeCurrent()) return PolicyGrantRebindCatchUpResult.Retry
        if (activeStreamId != expectedStreamId ||
            activeReplayGeneration != expectedReplayGeneration
        ) {
            activeStreamId = expectedStreamId
            activeReplayGeneration = expectedReplayGeneration
            after = null
        }
        val requestedAfter = after
        val scan = try {
            authoritySource.listCurrentPage(requestedAfter, pageSize)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return PolicyGrantRebindCatchUpResult.Retry
        }
        val page = when (scan) {
            is PolicyGrantAuthorityScanResult.Ready -> scan.page
            PolicyGrantAuthorityScanResult.Unavailable ->
                return PolicyGrantRebindCatchUpResult.Retry
        }
        if (!page.hasSafeContinuationAfter(requestedAfter)) {
            return PolicyGrantRebindCatchUpResult.Retry
        }

        var projected = 0
        var applied = 0
        var satisfied = 0
        var pending = 0
        var retryablePending = 0
        var skippedOtherStream = 0
        for (snapshot in page.snapshots) {
            if (!isRuntimeCurrent()) return PolicyGrantRebindCatchUpResult.Retry
            if (snapshot.sourceStreamId != expectedStreamId) {
                skippedOtherStream += 1
                continue
            }
            projected += 1
            val projection = try {
                projector.project(snapshot)
            } catch (cancelled: CancellationException) {
                // [after] remains unchanged, so a new process/cycle safely replays the whole page.
                throw cancelled
            } catch (_: Throwable) {
                pending += 1
                retryablePending += 1
                continue
            }
            when (projection) {
                is PolicyGrantLifecycleProjectionResult.Applied -> applied += 1
                is PolicyGrantLifecycleProjectionResult.Duplicate,
                is PolicyGrantLifecycleProjectionResult.AlreadySatisfied,
                -> satisfied += 1
                is PolicyGrantLifecycleProjectionResult.Pending -> {
                    pending += 1
                    if (projection.reason.isRetryableRebindFailure()) retryablePending += 1
                }
            }
        }
        if (!isRuntimeCurrent()) return PolicyGrantRebindCatchUpResult.Retry

        // Commit the in-memory keyset only after every valid snapshot on this page was attempted.
        // EOF wraps to the start, making newly regenerated exact artifacts discoverable later.
        after = page.nextCursor
        return PolicyGrantRebindCatchUpResult.Completed(
            scannedHeadCount = page.scannedHeadCount,
            rejectedHeadCount = page.rejectedHeadCount,
            projectedHeadCount = projected,
            appliedHeadCount = applied,
            alreadySatisfiedHeadCount = satisfied,
            pendingHeadCount = pending,
            retryablePendingHeadCount = retryablePending,
            skippedOtherStreamHeadCount = skippedOtherStream,
            morePages = !page.endReached,
        )
    }
}

internal sealed interface PolicyGrantRebindCatchUpResult {
    data class Completed(
        val scannedHeadCount: Int,
        val rejectedHeadCount: Int,
        val projectedHeadCount: Int,
        val appliedHeadCount: Int,
        val alreadySatisfiedHeadCount: Int,
        val pendingHeadCount: Int,
        val retryablePendingHeadCount: Int,
        val skippedOtherStreamHeadCount: Int,
        val morePages: Boolean,
    ) : PolicyGrantRebindCatchUpResult {
        init {
            require(scannedHeadCount in 0..MAX_POLICY_GRANT_REBIND_PAGE_SIZE)
            require(rejectedHeadCount in 0..scannedHeadCount)
            require(projectedHeadCount >= 0)
            require(appliedHeadCount >= 0 && alreadySatisfiedHeadCount >= 0)
            require(pendingHeadCount >= 0)
            require(retryablePendingHeadCount in 0..pendingHeadCount)
            require(skippedOtherStreamHeadCount >= 0)
            require(
                projectedHeadCount + skippedOtherStreamHeadCount + rejectedHeadCount ==
                    scannedHeadCount,
            )
            require(
                appliedHeadCount + alreadySatisfiedHeadCount + pendingHeadCount ==
                    projectedHeadCount,
            )
        }

        val didWork: Boolean get() = appliedHeadCount > 0
        val workMayRemain: Boolean get() = morePages || retryablePendingHeadCount > 0
    }

    data object Retry : PolicyGrantRebindCatchUpResult
}

/**
 * Opens the rebind gate only for one fully bootstrapped, caught-up checkpoint on the exact current
 * AppDatabase stream. A null result leaves every durable grant inert for this maintenance cycle.
 */
internal fun exactCompletePolicyGrantRebindStreamOrNull(
    checkpoint: LearningStreamCheckpointEntity?,
    authorityStreamId: String,
    authorityHeadSequence: Long,
): String? {
    if (!authorityStreamId.isCanonicalPolicyGrantStreamId() || authorityHeadSequence <= 0L) {
        return null
    }
    val current = checkpoint ?: return null
    val bootstrapHead = current.bootstrapHeadSeq ?: return null
    return current.streamId.takeIf {
        current.streamId == authorityStreamId &&
            current.bootstrapState == LearningBootstrapState.COMPLETE.name &&
            bootstrapHead > 0L &&
            current.lastContiguousSeq >= bootstrapHead &&
            current.lastContiguousSeq >= authorityHeadSequence &&
            current.lastSeenHeadSeq >= authorityHeadSequence
    }
}

private fun PolicyGrantAuthorityScanPage.hasSafeContinuationAfter(
    requestedAfter: PolicyGrantAuthorityScanCursor?,
): Boolean {
    val next = nextCursor ?: return endReached
    if (endReached) return false
    val previous = requestedAfter ?: PolicyGrantAuthorityScanCursor.START
    return next.afterUpdatedAtEpochMs > previous.afterUpdatedAtEpochMs ||
        (next.afterUpdatedAtEpochMs == previous.afterUpdatedAtEpochMs &&
            next.afterGrantId > previous.afterGrantId)
}

private fun PolicyGrantLifecyclePendingReason.isRetryableRebindFailure(): Boolean = when (this) {
    PolicyGrantLifecyclePendingReason.RUNTIME_UNAVAILABLE,
    PolicyGrantLifecyclePendingReason.LIFECYCLE_CONFLICT,
    PolicyGrantLifecyclePendingReason.STORAGE_FAILURE,
    -> true
    PolicyGrantLifecyclePendingReason.POLICY_MISSING,
    PolicyGrantLifecyclePendingReason.EXACT_POLICY_MISMATCH,
    PolicyGrantLifecyclePendingReason.POLICY_NOT_TRANSITIONABLE,
    -> false
}

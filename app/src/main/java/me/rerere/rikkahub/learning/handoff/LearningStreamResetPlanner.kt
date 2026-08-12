package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import me.rerere.rikkahub.learning.storage.LearningStreamResetReason
import kotlin.math.max
import kotlin.uuid.Uuid

/**
 * Pure restore/replay decision.  In particular, [lastSeenHeadSeq] and the fixed bootstrap H0 are
 * authoritative observations too: comparing only the consumed sequence would miss a partial-page
 * rewind and leave the consumer in a permanent CAS conflict loop.
 */
sealed interface LearningStreamPlan {
    data class Reset(val reason: LearningStreamResetReason) : LearningStreamPlan

    data class Bootstrap(val headSequence: Long) : LearningStreamPlan

    data object Consume : LearningStreamPlan

    data object Idle : LearningStreamPlan
}

object LearningStreamResetPlanner {
    fun plan(
        streamId: Uuid,
        headSequence: Long,
        checkpoints: List<LearningStreamCheckpointEntity>,
    ): LearningStreamPlan {
        require(headSequence > 0L) { "A valid outbox must contain its stream sentinel" }
        if (checkpoints.isEmpty()) {
            return LearningStreamPlan.Reset(LearningStreamResetReason.DERIVED_DATABASE_RECREATED)
        }
        if (checkpoints.size != 1) {
            return LearningStreamPlan.Reset(LearningStreamResetReason.CORRUPTION)
        }

        val checkpoint = checkpoints.single()
        if (checkpoint.streamId != streamId.toString()) {
            return LearningStreamPlan.Reset(LearningStreamResetReason.NEW_STREAM)
        }
        val bootstrapState = LearningBootstrapState.valueOf(checkpoint.bootstrapState)
        val fixedBootstrapHead = checkpoint.bootstrapHeadSeq
        if (
            bootstrapState != LearningBootstrapState.COMPLETE &&
            fixedBootstrapHead != null &&
            (fixedBootstrapHead <= 0L || checkpoint.lastContiguousSeq > fixedBootstrapHead)
        ) {
            return LearningStreamPlan.Reset(LearningStreamResetReason.CORRUPTION)
        }
        if (
            (bootstrapState == LearningBootstrapState.RUNNING ||
                bootstrapState == LearningBootstrapState.DEGRADED) &&
            fixedBootstrapHead == null
        ) {
            return LearningStreamPlan.Reset(LearningStreamResetReason.CORRUPTION)
        }
        val highestObservedSequence = max(
            checkpoint.lastContiguousSeq,
            max(checkpoint.lastSeenHeadSeq, fixedBootstrapHead ?: 0L),
        )
        if (headSequence < highestObservedSequence) {
            return LearningStreamPlan.Reset(LearningStreamResetReason.HEAD_REWIND)
        }
        if (bootstrapState != LearningBootstrapState.COMPLETE) {
            // A retry must replay the persisted H0. Moving this to the latest head would let a
            // repeatedly interrupted bootstrap chase an unbounded stream and falsely widen its
            // declared coverage window.
            return LearningStreamPlan.Bootstrap(fixedBootstrapHead ?: headSequence)
        }
        return if (headSequence == checkpoint.lastContiguousSeq) {
            LearningStreamPlan.Idle
        } else {
            LearningStreamPlan.Consume
        }
    }
}

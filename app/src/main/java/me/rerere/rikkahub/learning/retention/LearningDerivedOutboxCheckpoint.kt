package me.rerere.rikkahub.learning.retention

import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity

/**
 * Freezes the one durable derived consumer position used by primary-outbox retention.
 *
 * A missing/duplicate row, unfinished bootstrap, missing bootstrap head, or cursor that has not
 * actually absorbed its bootstrap head is absence of prune authority. No best-effort selection is
 * allowed because a stale replay generation could otherwise authorize deletion after a reset.
 */
internal fun freezeDerivedOutboxConsumerCheckpointOrNull(
    checkpoints: List<LearningStreamCheckpointEntity>,
): LearningDurableConsumerCheckpoint? {
    val checkpoint = checkpoints.singleOrNull() ?: return null
    val bootstrapHead = checkpoint.bootstrapHeadSeq ?: return null
    if (
        checkpoint.bootstrapState != LearningBootstrapState.COMPLETE.name ||
        checkpoint.lastContiguousSeq < bootstrapHead ||
        checkpoint.lastSeenHeadSeq < checkpoint.lastContiguousSeq
    ) {
        return null
    }
    return LearningDurableConsumerCheckpoint(
        consumerId = LearningDurableConsumerId.LEARNING_DERIVED_RUNTIME,
        streamId = checkpoint.streamId,
        replayGeneration = checkpoint.replayGeneration,
        lastContiguousSequence = checkpoint.lastContiguousSeq,
        bootstrapComplete = true,
    )
}

/** The caller owns the reset/restore fence for the complete duration of this suspend call. */
internal suspend fun prunePrimaryOutboxFromFrozenCheckpoint(
    port: LearningPrimaryOutboxRetentionPort,
    checkpoint: LearningDurableConsumerCheckpoint,
    frozenNowMs: Long,
    batchSize: Int,
): LearningOutboxRetentionResult = port.pruneOnce(
    LearningOutboxRetentionRequest(
        checkpoints = listOf(checkpoint),
        frozenNowMs = frozenNowMs,
        batchSize = batchSize,
    ),
)

package me.rerere.rikkahub.learning.retention

import kotlin.math.max

/** Every durable outbox consumer must be registered here before it can affect production. */
enum class LearningDurableConsumerId {
    LEARNING_DERIVED_RUNTIME,
}

/** Content-free durable position read from the consumer's own authority store. */
data class LearningDurableConsumerCheckpoint(
    val consumerId: LearningDurableConsumerId,
    val streamId: String,
    /** Frozen with the cursor even though the primary log has no replay-generation column. */
    val replayGeneration: Long,
    val lastContiguousSequence: Long,
    val bootstrapComplete: Boolean,
) {
    init {
        require(streamId.matches(UUID_LOWER_OR_UPPER)) { "Invalid outbox checkpoint stream" }
        require(replayGeneration >= 0L) { "Negative consumer replay generation" }
        require(lastContiguousSequence >= 0L) { "Negative consumer checkpoint" }
    }
}

data class LearningOutboxRetentionInput(
    val streamId: String,
    val authoritativeHeadSequence: Long,
    val frozenNowMs: Long,
    val checkpoints: List<LearningDurableConsumerCheckpoint>,
    val minimumAgeMs: Long = DEFAULT_OUTBOX_MINIMUM_AGE_MS,
    val safetyFloorRows: Long = DEFAULT_OUTBOX_SAFETY_FLOOR_ROWS,
) {
    init {
        require(streamId.matches(UUID_LOWER_OR_UPPER)) { "Invalid authoritative outbox stream" }
        require(authoritativeHeadSequence > 0L) { "Outbox head must include STREAM_INIT" }
        require(frozenNowMs >= 0L)
        require(minimumAgeMs in 1L..MAX_OUTBOX_MINIMUM_AGE_MS)
        require(safetyFloorRows in 1L..MAX_OUTBOX_SAFETY_FLOOR_ROWS)
    }
}

data class LearningOutboxPrunePlan(
    val streamId: String,
    val throughMinConsumerSequence: Long,
    val createdBeforeMs: Long,
    /** Rows with sequence >= this value are kept even when every consumer has passed them. */
    val keepFromSequence: Long,
) {
    init {
        require(streamId.matches(UUID_LOWER_OR_UPPER))
        require(throughMinConsumerSequence >= 0L)
        require(createdBeforeMs >= 0L)
        require(keepFromSequence > 0L)
    }
}

enum class LearningOutboxPruneUnavailableReason {
    CONSUMER_MISSING,
    CONSUMER_DUPLICATE,
    CONSUMER_NOT_BOOTSTRAPPED,
    STREAM_MISMATCH,
    CHECKPOINT_AHEAD_OF_AUTHORITY,
}

sealed interface LearningOutboxPruneDecision {
    data class Ready(val plan: LearningOutboxPrunePlan) : LearningOutboxPruneDecision
    data class Unavailable(val reason: LearningOutboxPruneUnavailableReason) :
        LearningOutboxPruneDecision
}

/**
 * Pure all-consumer + age + safety-floor gate. An empty or partially registered consumer set is
 * unavailable, never interpreted as permission to prune.
 */
object LearningOutboxRetentionPlanner {
    fun plan(input: LearningOutboxRetentionInput): LearningOutboxPruneDecision {
        val byId = input.checkpoints.groupBy(LearningDurableConsumerCheckpoint::consumerId)
        if (byId.values.any { it.size != 1 }) {
            return LearningOutboxPruneDecision.Unavailable(
                LearningOutboxPruneUnavailableReason.CONSUMER_DUPLICATE,
            )
        }
        if (byId.keys != LearningDurableConsumerId.entries.toSet()) {
            return LearningOutboxPruneDecision.Unavailable(
                LearningOutboxPruneUnavailableReason.CONSUMER_MISSING,
            )
        }
        val checkpoints = LearningDurableConsumerId.entries.map { id -> byId.getValue(id).single() }
        if (checkpoints.any { !it.bootstrapComplete }) {
            return LearningOutboxPruneDecision.Unavailable(
                LearningOutboxPruneUnavailableReason.CONSUMER_NOT_BOOTSTRAPPED,
            )
        }
        if (checkpoints.any { it.streamId != input.streamId }) {
            return LearningOutboxPruneDecision.Unavailable(
                LearningOutboxPruneUnavailableReason.STREAM_MISMATCH,
            )
        }
        if (checkpoints.any { it.lastContiguousSequence > input.authoritativeHeadSequence }) {
            return LearningOutboxPruneDecision.Unavailable(
                LearningOutboxPruneUnavailableReason.CHECKPOINT_AHEAD_OF_AUTHORITY,
            )
        }
        val minimumCheckpoint = checkpoints.minOf { it.lastContiguousSequence }
        val keepFrom = max(
            1L,
            input.authoritativeHeadSequence - input.safetyFloorRows + 1L,
        )
        val ageCutoff = if (input.frozenNowMs < input.minimumAgeMs) {
            0L
        } else {
            input.frozenNowMs - input.minimumAgeMs
        }
        return LearningOutboxPruneDecision.Ready(
            LearningOutboxPrunePlan(
                streamId = input.streamId,
                throughMinConsumerSequence = minimumCheckpoint,
                createdBeforeMs = ageCutoff,
                keepFromSequence = keepFrom,
            ),
        )
    }
}

private const val DAY_MS = 24L * 60L * 60L * 1_000L
const val DEFAULT_OUTBOX_MINIMUM_AGE_MS: Long = 30L * DAY_MS
const val DEFAULT_OUTBOX_SAFETY_FLOOR_ROWS: Long = 1_024L
private const val MAX_OUTBOX_MINIMUM_AGE_MS: Long = 3650L * DAY_MS
private const val MAX_OUTBOX_SAFETY_FLOOR_ROWS: Long = 1_000_000L
private val UUID_LOWER_OR_UPPER = Regex(
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
)

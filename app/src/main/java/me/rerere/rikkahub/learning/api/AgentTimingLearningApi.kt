package me.rerere.rikkahub.learning.api

const val MAX_LEARNING_TIMING_DURATION_NS: Long = 7L * 24L * 60L * 60L * 1_000_000_000L
const val MAX_LEARNING_OBSERVED_COUNT: Int = 1_000_000

enum class LearningTimingMetricUnknownReason {
    MILESTONE_NOT_OBSERVED,
    INVALID_ORDER,
    OUT_OF_RANGE,
}

/** An absent duration remains explicitly unknown; it is never silently converted to zero. */
sealed interface LearningTimingDuration {
    data class Known(val nanoseconds: Long) : LearningTimingDuration {
        init {
            require(nanoseconds in 0L..MAX_LEARNING_TIMING_DURATION_NS) {
                "Timing duration is outside its persistence bound"
            }
        }
    }

    data class Unknown(
        val reason: LearningTimingMetricUnknownReason,
    ) : LearningTimingDuration
}

/**
 * A bounded observational count. When [saturated] is true, [value] is only a lower bound.
 */
data class LearningObservedCount(
    val value: Int,
    val saturated: Boolean,
) {
    init {
        require(value in 0..MAX_LEARNING_OBSERVED_COUNT) { "Observed count is out of bounds" }
        require(!saturated || value == MAX_LEARNING_OBSERVED_COUNT) {
            "A saturated count must use the public bound"
        }
    }
}

/**
 * Fixed, content-free timing features from one process-local AgentTiming snapshot.
 *
 * These values are optional diagnostics. They are never an Episode boundary, execution outcome,
 * provider exposure, reward, or recovery authority. In particular, an observed tool/round count
 * does not prove that a tool or provider completed successfully.
 */
data class AgentTimingLearningAggregate(
    val submissionToDurableAdmission: LearningTimingDuration,
    val durableQueueWait: LearningTimingDuration,
    val runtimeExecution: LearningTimingDuration,
    val memoryRetrieval: LearningTimingDuration,
    val providerDispatchToFirstProgress: LearningTimingDuration,
    val finalSave: LearningTimingDuration,
    val submissionToFirstVisibleDraw: LearningTimingDuration,
    val finishedWallTime: LearningTimingDuration,
    val observedRoundCount: LearningObservedCount,
    val observedToolTimingCount: LearningObservedCount,
    val observedEventCount: LearningObservedCount,
    val droppedRoundCount: LearningObservedCount,
    val droppedToolTimingCount: LearningObservedCount,
    val droppedEventCount: LearningObservedCount,
)

enum class AgentTimingLearningUnknownReason {
    /** Disabled, not yet observed, evicted, cleared, crashed, or restarted cannot be distinguished. */
    NOT_OBSERVED,
    TIMEOUT,
    SOURCE_FAILURE,
    INVALID_SNAPSHOT,
}

sealed interface AgentTimingLearningResult {
    data class Available(
        val aggregate: AgentTimingLearningAggregate,
    ) : AgentTimingLearningResult

    data class Unknown(
        val reason: AgentTimingLearningUnknownReason,
    ) : AgentTimingLearningResult
}

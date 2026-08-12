package me.rerere.rikkahub.learning.runtime

/** Production default until the handoff flag is explicitly enabled after P0 gates pass. */
interface LearningWakeSignal {
    fun wake(reason: LearningWakeReason)
}

enum class LearningWakeReason {
    COMMAND_COMMITTED,
    EXECUTION_COMMITTED,
    MAINTENANCE,
}

object NoOpLearningWakeSignal : LearningWakeSignal {
    override fun wake(reason: LearningWakeReason) = Unit
}

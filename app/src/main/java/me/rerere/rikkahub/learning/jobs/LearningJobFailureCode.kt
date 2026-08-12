package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.LearningJobErrorCode

/** Bounded worker-reported failures. Scheduler-owned fence/recovery codes are not forgeable here. */
enum class LearningJobFailureCode(
    internal val persistedCode: LearningJobErrorCode,
) {
    SOURCE_MISSING(LearningJobErrorCode.SOURCE_MISSING),
    SOURCE_STALE(LearningJobErrorCode.SOURCE_STALE),
    SOURCE_TOMBSTONED(LearningJobErrorCode.SOURCE_TOMBSTONED),
    WAITING_CONFIGURATION(LearningJobErrorCode.WAITING_CONFIGURATION),
    DEADLINE_EXCEEDED(LearningJobErrorCode.DEADLINE_EXCEEDED),
    INVALID_JOB_SPEC(LearningJobErrorCode.INVALID_JOB_SPEC),
    INTERNAL(LearningJobErrorCode.INTERNAL),
    UNKNOWN(LearningJobErrorCode.UNKNOWN),
}

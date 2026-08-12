package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.LearningJobType

fun interface LearningJobClock {
    fun nowMs(): Long
}

object SystemLearningJobClock : LearningJobClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

internal interface LearningJobStore {
    suspend fun claim(
        processSessionId: String,
        workerId: String,
        nowMs: Long,
        leaseDurationMs: Long,
        eligibleJobTypes: Set<LearningJobType>,
    ): LearningJobClaimResult

    suspend fun heartbeat(
        lease: LearningJobLease,
        clock: LearningJobClock,
        leaseDurationMs: Long,
    ): LearningJobLease

    suspend fun completeTyped(
        lease: LearningJobLease,
        clock: LearningJobClock,
        completion: PreparedLearningJobCompletion,
    )

    suspend fun failAttempt(
        lease: LearningJobLease,
        clock: LearningJobClock,
        retryDelayMs: Long,
        errorCode: LearningJobFailureCode,
    )

    suspend fun failPermanently(
        lease: LearningJobLease,
        clock: LearningJobClock,
        errorCode: LearningJobFailureCode,
    )

    suspend fun recoverOnStartup(
        currentProcessSessionId: String,
        nowMs: Long,
        retryDelayMs: Long,
    ): LearningJobStartupRecoveryResult

    suspend fun cancelAllActive(nowMs: Long): Int
}

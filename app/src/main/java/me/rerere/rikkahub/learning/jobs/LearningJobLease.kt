package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.LearningJobEntity

/**
 * An opaque capability proving ownership of one concrete Learning job attempt.
 *
 * There is intentionally no public constructor, mutable field, component function, or `copy`.
 * Only a successful database claim can create the private implementation accepted by the store.
 */
sealed interface LearningJobLease {
    val jobId: String

    val leaseUntilMs: Long
}

sealed interface LearningJobClaimResult {
    data class Claimed(val job: LearningJobEntity, val lease: LearningJobLease) : LearningJobClaimResult

    data object NoWork : LearningJobClaimResult

    data object Contended : LearningJobClaimResult

    data class ClockRollback(val jobId: String) : LearningJobClaimResult
}

sealed interface LearningJobStartupRecoveryResult {
    data class Recovered(
        val otherProcessSessions: Int,
        val expiredLeases: Int,
        val exhaustedAttempts: Int,
    ) : LearningJobStartupRecoveryResult {
        init {
            require(otherProcessSessions >= 0 && expiredLeases >= 0 && exhaustedAttempts >= 0)
        }
    }

    data class ClockRollback(val jobId: String) : LearningJobStartupRecoveryResult
}

class LearningLostLeaseException : IllegalStateException("Learning job lease was lost")

class LearningJobClockRollbackException :
    IllegalStateException("Learning job authority clock moved backwards")

class LearningJobInvariantException :
    IllegalStateException("Learning job storage invariant failed")

internal fun String.isSafeLeaseIdentifier(): Boolean =
    length in 1..192 && all { char ->
        char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '_' ||
            char == ':' ||
            char == '.'
    }

package me.rerere.rikkahub.learning.jobs

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobErrorCode
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType

internal class RoomLearningJobStore(
    private val database: LearningDatabase,
) : LearningJobStore {
    override suspend fun claim(
        processSessionId: String,
        workerId: String,
        nowMs: Long,
        leaseDurationMs: Long,
        eligibleJobTypes: Set<LearningJobType>,
    ): LearningJobClaimResult {
        requireUuid(processSessionId, "process session")
        requireUuid(workerId, "worker")
        requireValidNow(nowMs)
        require(leaseDurationMs > 0L) { "Lease duration must be positive" }
        require(eligibleJobTypes.isNotEmpty()) { "No eligible learning job type" }
        val leaseUntilMs = checkedAdd(nowMs, leaseDurationMs)
        val eligibleTypeNames = eligibleJobTypes.map(LearningJobType::name).sorted()

        return database.withTransaction {
            val dao = database.jobDao()
            dao.findActiveClockRollbackCandidate(nowMs, eligibleTypeNames)?.let { future ->
                return@withTransaction LearningJobClaimResult.ClockRollback(future.id)
            }
            // A crashed final attempt must not remain RUNNING forever, and an already-exhausted
            // pending/retry row must never be selected. Fence all exhausted active rows first.
            dao.deadLetterAllExhausted(
                nowMs = nowMs,
                errorCode = LearningJobErrorCode.ATTEMPTS_EXHAUSTED,
            )
            val candidate = dao.findClaimCandidate(nowMs, eligibleTypeNames)
                ?: return@withTransaction LearningJobClaimResult.NoWork
            val changed = dao.claim(
                id = candidate.id,
                expectedGeneration = candidate.leaseGeneration,
                processSessionId = processSessionId,
                workerId = workerId,
                nowMs = nowMs,
                leaseUntilMs = leaseUntilMs,
                eligibleJobTypes = eligibleTypeNames,
            )
            if (changed != 1) {
                return@withTransaction LearningJobClaimResult.Contended
            }

            val claimed = dao.findById(candidate.id) ?: throw LearningJobInvariantException()
            if (!claimed.isExactClaimOf(
                    previous = candidate,
                    processSessionId = processSessionId,
                    workerId = workerId,
                    nowMs = nowMs,
                    leaseUntilMs = leaseUntilMs,
                )
            ) {
                throw LearningJobInvariantException()
            }
            LearningJobClaimResult.Claimed(claimed, issueLearningJobLease(claimed))
        }
    }

    override suspend fun heartbeat(
        lease: LearningJobLease,
        clock: LearningJobClock,
        leaseDurationMs: Long,
    ): LearningJobLease {
        require(leaseDurationMs > 0L) { "Lease duration must be positive" }

        return database.withTransaction {
            val dao = database.jobDao()
            val fence = lease.requireIssuedFence()
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val commitNowMs = checkedNow(clock)
            if (commitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, commitNowMs)
            val requestedLeaseUntilMs = checkedAdd(commitNowMs, leaseDurationMs)

            val currentLeaseUntilMs = requireNotNull(current.leaseUntilMs)
            if (requestedLeaseUntilMs <= currentLeaseUntilMs) {
                return@withTransaction issueLearningJobLease(current)
            }

            val changed = dao.heartbeatExtendingOnly(
                id = fence.jobId,
                processSessionId = fence.processSessionId,
                workerId = fence.workerId,
                leaseGeneration = fence.generation,
                nowMs = commitNowMs,
                leaseUntilMs = requestedLeaseUntilMs,
            )
            if (changed != 1) throw LearningLostLeaseException()

            val extended = dao.findById(fence.jobId) ?: throw LearningJobInvariantException()
            if (!extended.isOwnedBy(fence) || extended.leaseUntilMs != requestedLeaseUntilMs) {
                throw LearningJobInvariantException()
            }
            issueLearningJobLease(extended)
        }
    }

    override suspend fun completeTyped(
        lease: LearningJobLease,
        clock: LearningJobClock,
        completion: PreparedLearningJobCompletion,
    ) {
        database.withTransaction {
            val dao = database.jobDao()
            val fence = lease.requireIssuedFence()
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val frozenCommitNowMs = checkedNow(clock)
            if (frozenCommitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, frozenCommitNowMs)

            // The typed output writer and terminal CAS share this Room transaction. Any writer
            // failure or affectedRows != 1 rolls both back, so a stale worker leaves no output.
            completion.persistInOpenTransaction(database, current)
            val changed = dao.finishDone(
                id = fence.jobId,
                processSessionId = fence.processSessionId,
                workerId = fence.workerId,
                leaseGeneration = fence.generation,
                nowMs = frozenCommitNowMs,
            )
            if (changed != 1) throw LearningLostLeaseException()
        }
    }

    override suspend fun failAttempt(
        lease: LearningJobLease,
        clock: LearningJobClock,
        retryDelayMs: Long,
        errorCode: LearningJobFailureCode,
    ) {
        require(retryDelayMs >= 0L) { "Negative retry delay" }

        database.withTransaction {
            val dao = database.jobDao()
            val fence = lease.requireIssuedFence()
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val commitNowMs = checkedNow(clock)
            if (commitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, commitNowMs)
            val notBeforeMs = checkedAdd(commitNowMs, retryDelayMs)

            val changed = if (current.attempts >= current.maxAttempts) {
                dao.finishDeadLetter(
                    id = fence.jobId,
                    processSessionId = fence.processSessionId,
                    workerId = fence.workerId,
                    leaseGeneration = fence.generation,
                    nowMs = commitNowMs,
                    errorCode = LearningJobErrorCode.ATTEMPTS_EXHAUSTED,
                )
            } else {
                dao.retry(
                    id = fence.jobId,
                    processSessionId = fence.processSessionId,
                    workerId = fence.workerId,
                    leaseGeneration = fence.generation,
                    nowMs = commitNowMs,
                    notBeforeMs = notBeforeMs,
                    errorCode = errorCode.persistedCode,
                )
            }
            if (changed != 1) throw LearningLostLeaseException()
        }
    }

    override suspend fun failPermanently(
        lease: LearningJobLease,
        clock: LearningJobClock,
        errorCode: LearningJobFailureCode,
    ) {
        database.withTransaction {
            val dao = database.jobDao()
            val fence = lease.requireIssuedFence()
            val observedNowMs = checkedNow(clock)
            val current = dao.findById(fence.jobId) ?: throw LearningLostLeaseException()
            val commitNowMs = checkedNow(clock)
            if (commitNowMs < observedNowMs) throw LearningJobClockRollbackException()
            current.requireOwnedBy(fence, commitNowMs)
            val changed = dao.finishDeadLetter(
                id = fence.jobId,
                processSessionId = fence.processSessionId,
                workerId = fence.workerId,
                leaseGeneration = fence.generation,
                nowMs = commitNowMs,
                errorCode = errorCode.persistedCode,
            )
            if (changed != 1) throw LearningLostLeaseException()
        }
    }

    override suspend fun recoverOnStartup(
        currentProcessSessionId: String,
        nowMs: Long,
        retryDelayMs: Long,
    ): LearningJobStartupRecoveryResult {
        requireUuid(currentProcessSessionId, "process session")
        requireValidNow(nowMs)
        require(retryDelayMs >= 0L) { "Negative retry delay" }
        val notBeforeMs = checkedAdd(nowMs, retryDelayMs)

        return database.withTransaction {
            val dao = database.jobDao()
            dao.findActiveClockRollbackCandidate(
                nowMs = nowMs,
                eligibleJobTypes = LearningJobType.entries.map(LearningJobType::name),
            )?.let { future ->
                return@withTransaction LearningJobStartupRecoveryResult.ClockRollback(future.id)
            }

            // Exhaustion is terminal before either recovery path, so no exhausted row can be
            // turned back into RETRY. All three updates fence the prior generation.
            val exhausted = dao.deadLetterAllExhausted(
                nowMs = nowMs,
                errorCode = LearningJobErrorCode.ATTEMPTS_EXHAUSTED,
            )
            val otherSessions = dao.recoverOtherProcessSessions(
                currentProcessSessionId = currentProcessSessionId,
                nowMs = nowMs,
                notBeforeMs = notBeforeMs,
                errorCode = LearningJobErrorCode.LOST_LEASE,
            )
            val expired = dao.recoverExpired(
                nowMs = nowMs,
                notBeforeMs = notBeforeMs,
                errorCode = LearningJobErrorCode.LEASE_EXPIRED,
            )
            LearningJobStartupRecoveryResult.Recovered(
                otherProcessSessions = otherSessions,
                expiredLeases = expired,
                exhaustedAttempts = exhausted,
            )
        }
    }

    override suspend fun cancelAllActive(nowMs: Long): Int {
        requireValidNow(nowMs)
        return database.withTransaction {
            val dao = database.jobDao()
            // Cancellation must still fence stale workers during a wall-clock rollback. Preserve
            // the database's monotonic authority timestamp rather than writing time backwards.
            val authorityNowMs = maxOf(nowMs, dao.maxActiveUpdatedAt() ?: nowMs)
            dao.cancelAllActive(
                nowMs = authorityNowMs,
                errorCode = LearningJobErrorCode.CANCELLED_BY_RESET,
            )
        }
    }
}

/** The only implementation accepted as a lease capability; it never leaves this source file. */
private class IssuedLearningJobLease(
    val fence: LearningJobFence,
) : LearningJobLease {
    override val jobId: String
        get() = fence.jobId

    override val leaseUntilMs: Long
        get() = fence.leaseUntilMs

    override fun toString(): String =
        "LearningJobLease(job=<redacted>, generation=${fence.generation}, deadline=$leaseUntilMs)"
}

private class LearningJobFence(
    val jobId: String,
    val processSessionId: String,
    val workerId: String,
    val generation: Long,
    val leaseUntilMs: Long,
) {
    init {
        require(jobId.isSafeLeaseIdentifier()) { "Invalid job identifier" }
        requireUuid(processSessionId, "process session")
        requireUuid(workerId, "worker")
        require(generation > 0L) { "Lease generation must be positive" }
        require(leaseUntilMs >= 0L) { "Negative lease deadline" }
    }
}

private fun issueLearningJobLease(job: LearningJobEntity): LearningJobLease =
    IssuedLearningJobLease(
        LearningJobFence(
            jobId = job.id,
            processSessionId = requireNotNull(job.leaseProcessSessionId),
            workerId = requireNotNull(job.leaseWorkerId),
            generation = job.leaseGeneration,
            leaseUntilMs = requireNotNull(job.leaseUntilMs),
        ),
    )

private fun LearningJobLease.requireIssuedFence(): LearningJobFence =
    (this as? IssuedLearningJobLease)?.fence ?: throw LearningLostLeaseException()

private fun LearningJobEntity.isExactClaimOf(
    previous: LearningJobEntity,
    processSessionId: String,
    workerId: String,
    nowMs: Long,
    leaseUntilMs: Long,
): Boolean =
    id == previous.id &&
        state == LearningJobState.RUNNING.name &&
        attempts == previous.attempts + 1 &&
        leaseGeneration == previous.leaseGeneration + 1L &&
        leaseProcessSessionId == processSessionId &&
        leaseWorkerId == workerId &&
        this.leaseUntilMs == leaseUntilMs &&
        updatedAtMs == nowMs &&
        lastErrorCode == null &&
        finishedAtMs == null

private fun LearningJobEntity.requireOwnedBy(fence: LearningJobFence, nowMs: Long) {
    if (updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    if (!isOwnedBy(fence) || requireNotNull(leaseUntilMs) <= nowMs) {
        throw LearningLostLeaseException()
    }
}

private fun LearningJobEntity.isOwnedBy(fence: LearningJobFence): Boolean =
    state == LearningJobState.RUNNING.name &&
        id == fence.jobId &&
        leaseProcessSessionId == fence.processSessionId &&
        leaseWorkerId == fence.workerId &&
        leaseGeneration == fence.generation

private fun checkedNow(clock: LearningJobClock): Long = clock.nowMs().also(::requireValidNow)

private fun requireValidNow(nowMs: Long) {
    require(nowMs >= 0L) { "Negative clock" }
}

private fun checkedAdd(left: Long, right: Long): Long =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Learning job timestamp overflow")
    }

private fun requireUuid(value: String, label: String) {
    require(
        value != "00000000-0000-0000-0000-000000000000" &&
            runCatching { kotlin.uuid.Uuid.parse(value) }.isSuccess
    ) {
        "Learning job $label must be a UUID"
    }
}

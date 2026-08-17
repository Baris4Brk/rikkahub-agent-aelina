package me.rerere.rikkahub.learning.jobs

import androidx.room.withTransaction
import me.rerere.rikkahub.data.ai.background.BackgroundProviderAttemptAuthority
import me.rerere.rikkahub.data.ai.background.BackgroundProviderTerminalOutcome
import me.rerere.rikkahub.data.ai.background.BackgroundProviderUsage
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningProviderAttemptEntity
import me.rerere.rikkahub.learning.storage.LearningProviderAttemptState
import me.rerere.rikkahub.learning.storage.LearningProviderBudgetState
import me.rerere.rikkahub.learning.storage.LearningProviderDispatchKnowledge
import me.rerere.rikkahub.learning.storage.LearningProviderTerminalOutcome as StoredTerminalOutcome

/** Storage-only fence copied from the job/attempt rows; it is never exposed to a handler. */
internal class RoomLearningProviderAttemptFence(
    val jobId: String,
    val attemptOrdinal: Int,
    val processSessionId: String,
    val workerId: String,
    val leaseGeneration: Long,
) {
    init {
        require(jobId.isSafeLeaseIdentifier()) { "Invalid provider attempt job identifier" }
        require(attemptOrdinal > 0) { "Invalid provider attempt ordinal" }
        require(leaseGeneration > 0L) { "Invalid provider attempt lease generation" }
    }
}

internal fun issueRoomLearningProviderAttemptAuthority(
    database: LearningDatabase,
    clock: LearningJobClock,
    fence: RoomLearningProviderAttemptFence,
    stableProviderIdempotencyKey: String,
    expectedDispatchAttestationSha256: String,
): BackgroundProviderAttemptAuthority = RoomLearningProviderAttemptAuthority(
    database = database,
    clock = clock,
    fence = fence,
    stableProviderIdempotencyKey = stableProviderIdempotencyKey,
    expectedDispatchAttestationSha256 = expectedDispatchAttestationSha256,
)

/**
 * The concrete provider capability is deliberately private. Every state change is a DAO CAS in a
 * Room transaction that first proves the paired job lease is still current.
 */
private class RoomLearningProviderAttemptAuthority(
    private val database: LearningDatabase,
    private val clock: LearningJobClock,
    private val fence: RoomLearningProviderAttemptFence,
    override val stableProviderIdempotencyKey: String,
    override val expectedDispatchAttestationSha256: String,
) : BackgroundProviderAttemptAuthority {
    override val expectedRuntimeAttestationSha256: String
        get() = expectedDispatchAttestationSha256

    init {
        require(
            stableProviderIdempotencyKey.matches(
                Regex("^learning-provider-v[0-9]+:[0-9a-f]{64}$"),
            ),
        ) { "Invalid stable provider request key" }
        require(expectedDispatchAttestationSha256.isLowerSha256()) {
            "Invalid provider dispatch attestation"
        }
    }

    override suspend fun markDispatchStarted(
        observedDispatchAttestationSha256: String,
    ): Boolean {
        if (observedDispatchAttestationSha256 != expectedDispatchAttestationSha256) return false
        return database.withTransaction {
            val nowMs = frozenAuthorityNow()
            if (!database.isJobFenceOwned(fence, nowMs)) return@withTransaction false
            val dao = database.providerExecutionDao()
            dao.findAttempt(fence.jobId, fence.attemptOrdinal)
                .requireAuthorityClockNotAhead(nowMs)
            val changed = dao.markDispatchStartedIfReserved(
                jobId = fence.jobId,
                attemptOrdinal = fence.attemptOrdinal,
                processSessionId = fence.processSessionId,
                workerId = fence.workerId,
                leaseGeneration = fence.leaseGeneration,
                nowMs = nowMs,
            )
            if (changed == 0) return@withTransaction false
            if (changed != 1 || !dao
                    .findAttempt(fence.jobId, fence.attemptOrdinal)
                    .isExactOwnedState(fence, LearningProviderAttemptState.DISPATCH_STARTED)
            ) {
                throw LearningJobInvariantException()
            }
            true
        }
    }

    override suspend fun releaseUndispatched(): Boolean = database.withTransaction {
        val nowMs = frozenAuthorityNow()
        if (!database.isJobFenceOwned(fence, nowMs)) return@withTransaction false
        val dao = database.providerExecutionDao()
        dao.findAttempt(fence.jobId, fence.attemptOrdinal)
            .requireAuthorityClockNotAhead(nowMs)
        val changed = dao.releaseUndispatchedIfOwned(
            jobId = fence.jobId,
            attemptOrdinal = fence.attemptOrdinal,
            processSessionId = fence.processSessionId,
            workerId = fence.workerId,
            leaseGeneration = fence.leaseGeneration,
            nowMs = nowMs,
        )
        if (changed == 1) {
            if (!dao.findAttempt(fence.jobId, fence.attemptOrdinal)
                    .isExactOwnedState(fence, LearningProviderAttemptState.RELEASED)
            ) {
                throw LearningJobInvariantException()
            }
            true
        } else {
            changed == 0 && dao.findAttempt(fence.jobId, fence.attemptOrdinal)
                .isExactOwnedState(fence, LearningProviderAttemptState.RELEASED)
        }
    }

    override suspend fun markTerminal(
        outcome: BackgroundProviderTerminalOutcome,
        usage: BackgroundProviderUsage,
    ): Boolean = database.withTransaction {
        val nowMs = frozenAuthorityNow()
        if (!database.isJobFenceOwned(fence, nowMs)) return@withTransaction false
        val storedOutcome = outcome.toStoredOutcome()
        val dao = database.providerExecutionDao()
        dao.findAttempt(fence.jobId, fence.attemptOrdinal)
            .requireAuthorityClockNotAhead(nowMs)
        val changed = dao.markTerminalIfOwned(
            jobId = fence.jobId,
            attemptOrdinal = fence.attemptOrdinal,
            processSessionId = fence.processSessionId,
            workerId = fence.workerId,
            leaseGeneration = fence.leaseGeneration,
            terminalOutcome = storedOutcome,
            actualInputTokens = usage.inputTokens,
            actualOutputTokens = usage.outputTokens,
            actualCostMicros = usage.costMicros,
            nowMs = nowMs,
        )
        val terminal = dao.findAttempt(fence.jobId, fence.attemptOrdinal)
        if (changed == 1) {
            if (!terminal.isExactTerminalFact(fence, storedOutcome, usage)) {
                throw LearningJobInvariantException()
            }
            true
        } else {
            changed == 0 && terminal.isExactTerminalFact(fence, storedOutcome, usage)
        }
    }

    private fun frozenAuthorityNow(): Long {
        val observed = clock.nowMs().also(::requireProviderNow)
        val commit = clock.nowMs().also(::requireProviderNow)
        if (commit < observed) throw LearningJobClockRollbackException()
        return commit
    }

    override fun toString(): String =
        "BackgroundProviderAttemptAuthority(attempt=${fence.attemptOrdinal}, identity=<redacted>)"
}

private suspend fun LearningDatabase.isJobFenceOwned(
    fence: RoomLearningProviderAttemptFence,
    nowMs: Long,
): Boolean {
    val job = jobDao().findById(fence.jobId) ?: return false
    if (job.updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    return job.state == LearningJobState.RUNNING.name &&
        job.leaseProcessSessionId == fence.processSessionId &&
        job.leaseWorkerId == fence.workerId &&
        job.leaseGeneration == fence.leaseGeneration &&
        requireNotNull(job.leaseUntilMs) > nowMs
}

private fun LearningProviderAttemptEntity?.isExactOwnedState(
    fence: RoomLearningProviderAttemptFence,
    expectedState: LearningProviderAttemptState,
): Boolean = this != null &&
    jobId == fence.jobId &&
    attemptOrdinal == fence.attemptOrdinal &&
    leaseProcessSessionId == fence.processSessionId &&
    leaseWorkerId == fence.workerId &&
    leaseGeneration == fence.leaseGeneration &&
    state == expectedState.name

private fun LearningProviderAttemptEntity?.requireAuthorityClockNotAhead(
    nowMs: Long,
): LearningProviderAttemptEntity {
    val attempt = this ?: throw LearningJobInvariantException()
    if (attempt.updatedAtMs > nowMs) throw LearningJobClockRollbackException()
    return attempt
}

private fun LearningProviderAttemptEntity?.isExactTerminalFact(
    fence: RoomLearningProviderAttemptFence,
    expectedOutcome: StoredTerminalOutcome,
    usage: BackgroundProviderUsage,
): Boolean = this != null &&
    isExactOwnedState(fence, LearningProviderAttemptState.TERMINAL) &&
    dispatchKnowledge == LearningProviderDispatchKnowledge.TERMINAL_OBSERVED.name &&
    budgetState == LearningProviderBudgetState.COMMITTED.name &&
    terminalOutcome == expectedOutcome.name &&
    actualProviderCalls == 1 &&
    actualInputTokens == usage.inputTokens &&
    actualOutputTokens == usage.outputTokens &&
    actualCostMicros == usage.costMicros

private fun BackgroundProviderTerminalOutcome.toStoredOutcome(): StoredTerminalOutcome = when (this) {
    BackgroundProviderTerminalOutcome.SUCCESS -> StoredTerminalOutcome.SUCCESS
    BackgroundProviderTerminalOutcome.DEFERRED,
    BackgroundProviderTerminalOutcome.FAILED,
    -> StoredTerminalOutcome.FAILED
    BackgroundProviderTerminalOutcome.CANCELLED -> StoredTerminalOutcome.CANCELLED
    BackgroundProviderTerminalOutcome.TIMED_OUT -> StoredTerminalOutcome.TIMED_OUT
}

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun requireProviderNow(nowMs: Long) {
    require(nowMs >= 0L) { "Negative provider authority clock" }
}

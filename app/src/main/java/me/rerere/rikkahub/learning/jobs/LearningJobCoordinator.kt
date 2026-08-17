package me.rerere.rikkahub.learning.jobs

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobType

private const val DEFAULT_MAX_LEASE_DURATION_MS = 15L * 60L * 1_000L
private const val DEFAULT_MAX_RETRY_DELAY_MS = 24L * 60L * 60L * 1_000L
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"

/**
 * Database-fenced job operations bound to one immutable process-session UUID.
 *
 * The runtime must construct this once per process start with a freshly generated
 * [processSessionId]; every worker coroutine must likewise use its own fresh UUID.
 */
class LearningJobCoordinator(
    database: LearningDatabase,
    private val processSessionId: Uuid,
    private val clock: LearningJobClock = SystemLearningJobClock,
    private val maxLeaseDurationMs: Long = DEFAULT_MAX_LEASE_DURATION_MS,
    private val maxRetryDelayMs: Long = DEFAULT_MAX_RETRY_DELAY_MS,
) : LearningJobExecutionCoordinator {
    private val store: LearningJobStore = RoomLearningJobStore(database, clock)

    init {
        require(processSessionId.toString() != NIL_UUID) { "Process session UUID cannot be nil" }
        require(maxLeaseDurationMs in 1L..DEFAULT_MAX_LEASE_DURATION_MS) {
            "Unsafe learning lease duration limit"
        }
        require(maxRetryDelayMs in 0L..DEFAULT_MAX_RETRY_DELAY_MS) {
            "Unsafe learning retry delay limit"
        }
    }

    suspend fun claimNext(
        workerId: Uuid,
        leaseDurationMs: Long,
    ): LearningJobClaimResult = claimNext(
        workerId = workerId,
        leaseDurationMs = leaseDurationMs,
        eligibleJobTypes = LearningJobType.entries.toSet(),
    )

    override suspend fun claimNext(
        workerId: Uuid,
        leaseDurationMs: Long,
        eligibleJobTypes: Set<LearningJobType>,
    ): LearningJobClaimResult {
        require(workerId.toString() != NIL_UUID) { "Worker UUID cannot be nil" }
        requireLeaseDuration(leaseDurationMs)
        return store.claim(
            processSessionId = processSessionId.toString(),
            workerId = workerId.toString(),
            nowMs = checkedNowMs(),
            leaseDurationMs = leaseDurationMs,
            eligibleJobTypes = eligibleJobTypes,
        )
    }

    override suspend fun heartbeat(
        lease: LearningJobLease,
        leaseDurationMs: Long,
    ): LearningJobLease {
        requireLeaseDuration(leaseDurationMs)
        return store.heartbeat(
            lease = lease,
            clock = clock,
            leaseDurationMs = leaseDurationMs,
        )
    }

    override suspend fun completeTyped(
        lease: LearningJobLease,
        completion: PreparedLearningJobCompletion,
    ) = store.completeTyped(
        lease = lease,
        clock = clock,
        completion = completion,
    )

    override suspend fun failAttempt(
        lease: LearningJobLease,
        retryDelayMs: Long,
        errorCode: LearningJobFailureCode,
    ): LearningJobAttemptFailureResult {
        requireRetryDelay(retryDelayMs)
        return store.failAttempt(
            lease = lease,
            clock = clock,
            retryDelayMs = retryDelayMs,
            errorCode = errorCode,
        )
    }

    override suspend fun failPermanently(
        lease: LearningJobLease,
        errorCode: LearningJobFailureCode,
    ) = store.failPermanently(
        lease = lease,
        clock = clock,
        errorCode = errorCode,
    )

    /** One startup transaction: recover provider facts, then fence exhausted/old/expired jobs. */
    suspend fun recoverOnStartup(
        retryDelayMs: Long = 0L,
    ): LearningJobStartupRecoveryResult {
        requireRetryDelay(retryDelayMs)
        return store.recoverOnStartup(
            currentProcessSessionId = processSessionId.toString(),
            nowMs = checkedNowMs(),
            retryDelayMs = retryDelayMs,
        )
    }

    /** Reset cancellation always increments the lease generation before clearing its owner. */
    suspend fun cancelAllActive(): Int = store.cancelAllActive(checkedNowMs())

    private fun requireLeaseDuration(leaseDurationMs: Long) {
        require(leaseDurationMs in 1L..maxLeaseDurationMs) { "Unsafe lease duration" }
    }

    private fun requireRetryDelay(retryDelayMs: Long) {
        require(retryDelayMs in 0L..maxRetryDelayMs) { "Unsafe retry delay" }
    }

    private fun checkedNowMs(): Long = clock.nowMs().also { nowMs ->
        require(nowMs >= 0L) { "Negative clock" }
    }
}

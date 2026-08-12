package me.rerere.rikkahub.learning.jobs

import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.learning.storage.LearningJobType

private const val DEFAULT_RUNNER_LEASE_MS = 60_000L
private const val DEFAULT_DEADLINE_RETRY_DELAY_MS = 30_000L
private const val MAX_RUNNER_JOBS = 16
internal const val MIN_PROVIDER_EFFECT_JOB_BUDGET_MS = 125_000L
private val NIL_UUID = Uuid.parse("00000000-0000-0000-0000-000000000000")
private val PROVIDER_EFFECT_JOB_TYPES = setOf(
    LearningJobType.REFLECT_EPISODE_V1,
    LearningJobType.DISTILL_POLICY_V1,
)

/** Narrow execution port used by the pure runner; Room remains behind its production adapter. */
interface LearningJobExecutionCoordinator {
    suspend fun claimNext(
        workerId: Uuid,
        leaseDurationMs: Long,
        eligibleJobTypes: Set<LearningJobType>,
    ): LearningJobClaimResult

    suspend fun heartbeat(lease: LearningJobLease, leaseDurationMs: Long): LearningJobLease

    suspend fun completeTyped(
        lease: LearningJobLease,
        completion: PreparedLearningJobCompletion,
    )

    suspend fun failAttempt(
        lease: LearningJobLease,
        retryDelayMs: Long,
        errorCode: LearningJobFailureCode,
    )

    suspend fun failPermanently(
        lease: LearningJobLease,
        errorCode: LearningJobFailureCode,
    )
}

enum class LearningJobBatchStopReason {
    IDLE,
    LIMIT_REACHED,
    DEADLINE_REACHED,
    CONTENDED,
    CLOCK_ROLLBACK,
    NO_REGISTERED_HANDLER,
    WAITING_CONFIGURATION,
    LOST_LEASE,
}

data class LearningJobBatchResult(
    val claimed: Int,
    val completed: Int,
    val retried: Int,
    val deadLettered: Int,
    val stopReason: LearningJobBatchStopReason,
) {
    init {
        require(claimed >= 0 && completed >= 0 && retried >= 0 && deadLettered >= 0)
        require(completed + retried + deadLettered <= claimed)
    }

    val didWork: Boolean get() = claimed > 0
}

/**
 * Bounded, fenced job executor. It creates a new worker UUID for every claim, never exposes Room
 * to handlers, heartbeats while a handler is suspended, and honors one monotonic batch deadline.
 */
internal class LearningJobRunner(
    private val coordinator: LearningJobExecutionCoordinator,
    private val registry: LearningJobHandlerRegistry,
    private val monotonicMs: () -> Long,
    private val workerIdFactory: () -> Uuid = { Uuid.random() },
    private val leaseDurationMs: Long = DEFAULT_RUNNER_LEASE_MS,
) {
    init {
        require(leaseDurationMs in 3L..15L * 60L * 1_000L) { "Unsafe runner lease duration" }
    }

    suspend fun runBounded(
        maxJobs: Int,
        monotonicDeadlineMs: Long,
        isRuntimeCurrent: () -> Boolean,
    ): LearningJobBatchResult {
        require(maxJobs in 1..MAX_RUNNER_JOBS) { "Unsafe job batch limit" }
        require(monotonicDeadlineMs >= 0L) { "Negative job deadline" }

        val readiness = registry.readiness()
        if (readiness.registeredCount == 0) {
            return emptyResult(LearningJobBatchStopReason.NO_REGISTERED_HANDLER)
        }
        if (readiness.readyTypes.isEmpty()) {
            return emptyResult(LearningJobBatchStopReason.WAITING_CONFIGURATION)
        }

        var claimed = 0
        var completed = 0
        var retried = 0
        var deadLettered = 0
        var providerEffectJobClaimed = false
        val issuedWorkerIds = hashSetOf<Uuid>()

        while (claimed < maxJobs) {
            if (!isRuntimeCurrent()) {
                return result(
                    claimed,
                    completed,
                    retried,
                    deadLettered,
                    LearningJobBatchStopReason.LOST_LEASE,
                )
            }
            val remainingBudgetMs = remainingMs(monotonicDeadlineMs)
            if (remainingBudgetMs <= 0L) {
                return result(
                    claimed,
                    completed,
                    retried,
                    deadLettered,
                    LearningJobBatchStopReason.DEADLINE_REACHED,
                )
            }
            val eligibleJobTypes = eligibleLearningJobTypesForBudget(
                readiness.readyTypes,
                remainingBudgetMs,
                providerEffectJobClaimed,
            )
            val providerJobsDeferred = eligibleJobTypes != readiness.readyTypes
            if (eligibleJobTypes.isEmpty()) {
                return result(
                    claimed,
                    completed,
                    retried,
                    deadLettered,
                    LearningJobBatchStopReason.DEADLINE_REACHED,
                )
            }
            val workerId = workerIdFactory()
            check(workerId != NIL_UUID && issuedWorkerIds.add(workerId)) {
                "Learning worker UUID must be fresh"
            }
            when (
                val claim = coordinator.claimNext(
                    workerId = workerId,
                    leaseDurationMs = leaseDurationMs,
                    eligibleJobTypes = eligibleJobTypes,
                )
            ) {
                LearningJobClaimResult.NoWork -> return result(
                    claimed,
                    completed,
                    retried,
                    deadLettered,
                    if (providerJobsDeferred) {
                        if (providerEffectJobClaimed) LearningJobBatchStopReason.LIMIT_REACHED
                        else LearningJobBatchStopReason.DEADLINE_REACHED
                    } else {
                        LearningJobBatchStopReason.IDLE
                    },
                )

                LearningJobClaimResult.Contended -> return result(
                    claimed,
                    completed,
                    retried,
                    deadLettered,
                    LearningJobBatchStopReason.CONTENDED,
                )

                is LearningJobClaimResult.ClockRollback -> return result(
                    claimed,
                    completed,
                    retried,
                    deadLettered,
                    LearningJobBatchStopReason.CLOCK_ROLLBACK,
                )

                is LearningJobClaimResult.Claimed -> {
                    claimed += 1
                    val claimedType = LearningJobType.entries.single { it.name == claim.job.jobType }
                    if (claimedType in PROVIDER_EFFECT_JOB_TYPES) providerEffectJobClaimed = true
                    val execution = try {
                        executeWithHeartbeat(
                            claim = claim,
                            monotonicDeadlineMs = monotonicDeadlineMs,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: LearningLostLeaseException) {
                        return result(
                            claimed,
                            completed,
                            retried,
                            deadLettered,
                            LearningJobBatchStopReason.LOST_LEASE,
                        )
                    } catch (_: Exception) {
                        if (!failRetryBestEffort(
                                lease = claim.lease,
                                delayMs = DEFAULT_DEADLINE_RETRY_DELAY_MS,
                                code = LearningJobFailureCode.INTERNAL,
                            )
                        ) {
                            return result(
                                claimed,
                                completed,
                                retried,
                                deadLettered,
                                LearningJobBatchStopReason.LOST_LEASE,
                            )
                        }
                        retried += 1
                        continue
                    }

                    if (execution == null) {
                        if (!failRetryBestEffort(
                                lease = claim.lease,
                                delayMs = DEFAULT_DEADLINE_RETRY_DELAY_MS,
                                code = LearningJobFailureCode.DEADLINE_EXCEEDED,
                            )
                        ) {
                            return result(
                                claimed,
                                completed,
                                retried,
                                deadLettered,
                                LearningJobBatchStopReason.LOST_LEASE,
                            )
                        }
                        retried += 1
                        return result(
                            claimed,
                            completed,
                            retried,
                            deadLettered,
                            LearningJobBatchStopReason.DEADLINE_REACHED,
                        )
                    }

                    try {
                        when (val dispatch = execution.dispatch) {
                            is LearningJobDispatchResult.Success -> {
                                coordinator.completeTyped(execution.lease, dispatch.completion)
                                completed += 1
                            }

                            is LearningJobDispatchResult.Retry -> {
                                coordinator.failAttempt(
                                    lease = execution.lease,
                                    retryDelayMs = dispatch.retryDelayMs,
                                    errorCode = dispatch.errorCode,
                                )
                                retried += 1
                            }

                            is LearningJobDispatchResult.DeadLetter -> {
                                coordinator.failPermanently(
                                    lease = execution.lease,
                                    errorCode = dispatch.errorCode,
                                )
                                deadLettered += 1
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: LearningLostLeaseException) {
                        return result(
                            claimed,
                            completed,
                            retried,
                            deadLettered,
                            LearningJobBatchStopReason.LOST_LEASE,
                        )
                    }
                }
            }
        }

        return result(
            claimed,
            completed,
            retried,
            deadLettered,
            LearningJobBatchStopReason.LIMIT_REACHED,
        )
    }

    private suspend fun executeWithHeartbeat(
        claim: LearningJobClaimResult.Claimed,
        monotonicDeadlineMs: Long,
    ): HeartbeatedDispatch? = coroutineScope {
        val lease = AtomicReference(claim.lease)
        val execution = async {
            registry.dispatch(
                job = claim.job,
                monotonicDeadlineMs = monotonicDeadlineMs,
                monotonicMs = monotonicMs,
            )
        }
        val heartbeatIntervalMs = (leaseDurationMs / 3L).coerceAtLeast(1L)
        try {
            while (true) {
                val remaining = remainingMs(monotonicDeadlineMs)
                if (remaining <= 0L) return@coroutineScope null
                val waitMs = minOf(heartbeatIntervalMs, remaining)
                val dispatch = withTimeoutOrNull(waitMs) { execution.await() }
                if (dispatch != null) {
                    return@coroutineScope HeartbeatedDispatch(dispatch, lease.get())
                }
                if (remainingMs(monotonicDeadlineMs) <= 0L) return@coroutineScope null
                lease.set(coordinator.heartbeat(lease.get(), leaseDurationMs))
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } finally {
            if (!execution.isCompleted) execution.cancelAndJoin()
        }
    }

    private suspend fun failRetryBestEffort(
        lease: LearningJobLease,
        delayMs: Long,
        code: LearningJobFailureCode,
    ): Boolean = try {
        coordinator.failAttempt(lease, delayMs, code)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: LearningLostLeaseException) {
        false
    }

    private fun remainingMs(deadlineMs: Long): Long {
        val nowMs = monotonicMs()
        check(nowMs >= 0L) { "Negative monotonic clock" }
        return if (nowMs >= deadlineMs) 0L else deadlineMs - nowMs
    }
}

internal fun eligibleLearningJobTypesForBudget(
    readyTypes: Set<LearningJobType>,
    remainingBudgetMs: Long,
    providerEffectAlreadyClaimed: Boolean = false,
): Set<LearningJobType> {
    require(remainingBudgetMs >= 0L)
    return if (
        providerEffectAlreadyClaimed || remainingBudgetMs < MIN_PROVIDER_EFFECT_JOB_BUDGET_MS
    ) {
        readyTypes - PROVIDER_EFFECT_JOB_TYPES
    } else {
        readyTypes
    }
}

private data class HeartbeatedDispatch(
    val dispatch: LearningJobDispatchResult,
    val lease: LearningJobLease,
)

private fun emptyResult(reason: LearningJobBatchStopReason): LearningJobBatchResult =
    LearningJobBatchResult(0, 0, 0, 0, reason)

private fun result(
    claimed: Int,
    completed: Int,
    retried: Int,
    deadLettered: Int,
    reason: LearningJobBatchStopReason,
): LearningJobBatchResult = LearningJobBatchResult(
    claimed = claimed,
    completed = completed,
    retried = retried,
    deadLettered = deadLettered,
    stopReason = reason,
)

package me.rerere.rikkahub.memory.dreaming.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId

const val DREAM_UTC_DAY_MILLIS = 86_400_000L

data class DreamUtcDayWindow(
    val startInclusiveEpochMs: Long,
    val endExclusiveEpochMs: Long,
) {
    init {
        require(startInclusiveEpochMs >= 0L)
        require(endExclusiveEpochMs > startInclusiveEpochMs)
        require(endExclusiveEpochMs - startInclusiveEpochMs == DREAM_UTC_DAY_MILLIS)
    }

    operator fun contains(epochMs: Long): Boolean =
        epochMs >= startInclusiveEpochMs && epochMs < endExclusiveEpochMs
}

fun dreamUtcDayWindowOrNull(nowEpochMs: Long): DreamUtcDayWindow? {
    if (nowEpochMs < 0L) return null
    return try {
        val start = Math.multiplyExact(
            Math.floorDiv(nowEpochMs, DREAM_UTC_DAY_MILLIS),
            DREAM_UTC_DAY_MILLIS,
        )
        DreamUtcDayWindow(start, Math.addExact(start, DREAM_UTC_DAY_MILLIS))
    } catch (_: ArithmeticException) {
        null
    }
}

/** Aggregate across every private/global synthesis scope; Observer rows must be excluded. */
data class DreamDailyUsage(
    val startedRunCount: Int,
    val knownInputTokens: Long,
    val knownOutputTokens: Long,
    val unmeasuredInputRunCount: Int,
    val unmeasuredOutputRunCount: Int,
) {
    fun isValid(): Boolean =
        startedRunCount >= 0 && knownInputTokens >= 0L && knownOutputTokens >= 0L &&
            unmeasuredInputRunCount in 0..startedRunCount &&
            unmeasuredOutputRunCount in 0..startedRunCount
}

data class DreamDailyUsageQuery(
    val window: DreamUtcDayWindow,
    /** The current row is excluded only before its first provider attempt. */
    val excludingRunId: String?,
)

fun interface DreamDailyUsageStore {
    suspend fun readGlobalUtcUsage(query: DreamDailyUsageQuery): DreamDailyUsage
}

data class DreamBudgetAdmissionRequest(
    val scopeId: DreamScopeId,
    val runId: String,
    val nowEpochMs: Long,
    val firstProviderAttempt: Boolean,
    val estimatedInputTokens: Long?,
    val maxOutputTokens: Long,
) {
    fun isValid(): Boolean =
        runId.isNotBlank() && runId.length <= 512 && nowEpochMs >= 0L &&
            (estimatedInputTokens == null || estimatedInputTokens >= 0L) &&
            maxOutputTokens >= 0L
}

data class DreamBudgetAdmission(
    val request: DreamBudgetAdmissionRequest,
    val policy: DreamingCostPolicy,
    val window: DreamUtcDayWindow,
    val usageBefore: DreamDailyUsage,
    val projectedRunCount: Long,
    /** Null means this dimension was intentionally uncapped or could not be totaled. */
    val projectedInputTokens: Long?,
    val projectedOutputTokens: Long?,
)

enum class DreamBudgetDenialReason {
    INVALID_REQUEST,
    INVALID_POLICY,
    POLICY_UNAVAILABLE,
    INVALID_CLOCK,
    USAGE_UNAVAILABLE,
    INVALID_USAGE,
    DAILY_RUN_LIMIT,
    INPUT_USAGE_UNMEASURED,
    OUTPUT_USAGE_UNMEASURED,
    INPUT_ESTIMATE_UNAVAILABLE,
    INPUT_TOKEN_LIMIT,
    OUTPUT_TOKEN_LIMIT,
    TOKEN_ARITHMETIC_OVERFLOW,
}

data class DreamBudgetDenial(
    val reason: DreamBudgetDenialReason,
    val window: DreamUtcDayWindow? = null,
)

sealed interface DreamBudgetPermitResult<out T> {
    data class Granted<T>(
        val value: T,
        val admission: DreamBudgetAdmission,
    ) : DreamBudgetPermitResult<T>

    data class Denied(val denial: DreamBudgetDenial) : DreamBudgetPermitResult<Nothing>
}

internal sealed interface DreamBudgetEvaluation {
    data class Allowed(val admission: DreamBudgetAdmission) : DreamBudgetEvaluation
    data class Denied(val denial: DreamBudgetDenial) : DreamBudgetEvaluation
}

/**
 * Admission is serialized across every gate instance in this app process. The lock deliberately
 * remains held through [block], so callers must persist measured usage before returning. DI must
 * still provide one normal gate instance; this process-wide mutex is a final defence against
 * accidental duplicate instances. This is not a cross-process lock.
 */
class DreamBudgetGate(
    private val policySource: DreamingCostPolicySource,
    private val usageStore: DreamDailyUsageStore,
) {
    suspend fun <T> withPermit(
        request: DreamBudgetAdmissionRequest,
        block: suspend (DreamBudgetAdmission) -> T,
    ): DreamBudgetPermitResult<T> = PROCESS_DREAM_BUDGET_MUTEX.withLock {
        if (!request.isValid()) {
            return@withLock denied(DreamBudgetDenialReason.INVALID_REQUEST)
        }
        val window = dreamUtcDayWindowOrNull(request.nowEpochMs)
            ?: return@withLock denied(DreamBudgetDenialReason.INVALID_CLOCK)
        val policy = try {
            policySource.costPolicy()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock denied(DreamBudgetDenialReason.POLICY_UNAVAILABLE, window)
        }
        val validatedPolicy = policy.validatedOrNull()
            ?: return@withLock denied(DreamBudgetDenialReason.INVALID_POLICY, window)
        val query = DreamDailyUsageQuery(
            window = window,
            excludingRunId = request.runId.takeIf { request.firstProviderAttempt },
        )
        val usage = try {
            usageStore.readGlobalUtcUsage(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock denied(DreamBudgetDenialReason.USAGE_UNAVAILABLE, window)
        }
        when (val evaluation = evaluateDreamBudget(request, validatedPolicy, window, usage)) {
            is DreamBudgetEvaluation.Denied -> DreamBudgetPermitResult.Denied(evaluation.denial)
            is DreamBudgetEvaluation.Allowed -> DreamBudgetPermitResult.Granted(
                value = block(evaluation.admission),
                admission = evaluation.admission,
            )
        }
    }
}

internal fun evaluateDreamBudget(
    request: DreamBudgetAdmissionRequest,
    policy: DreamingCostPolicy,
    window: DreamUtcDayWindow,
    usage: DreamDailyUsage,
): DreamBudgetEvaluation {
    if (!request.isValid()) return evaluationDenied(DreamBudgetDenialReason.INVALID_REQUEST, window)
    if (policy.validatedOrNull() == null) {
        return evaluationDenied(DreamBudgetDenialReason.INVALID_POLICY, window)
    }
    if (!usage.isValid()) return evaluationDenied(DreamBudgetDenialReason.INVALID_USAGE, window)

    val projectedRuns = addExactOrNull(
        usage.startedRunCount.toLong(),
        if (request.firstProviderAttempt) 1L else 0L,
    ) ?: return evaluationDenied(DreamBudgetDenialReason.TOKEN_ARITHMETIC_OVERFLOW, window)
    if (projectedRuns > policy.dailyRunLimit.toLong()) {
        return evaluationDenied(DreamBudgetDenialReason.DAILY_RUN_LIMIT, window)
    }

    val projectedInput = policy.dailyInputTokenLimit?.let { limit ->
        if (usage.unmeasuredInputRunCount > 0) {
            return evaluationDenied(DreamBudgetDenialReason.INPUT_USAGE_UNMEASURED, window)
        }
        val estimate = request.estimatedInputTokens
            ?: return evaluationDenied(DreamBudgetDenialReason.INPUT_ESTIMATE_UNAVAILABLE, window)
        val projected = addExactOrNull(usage.knownInputTokens, estimate)
            ?: return evaluationDenied(DreamBudgetDenialReason.TOKEN_ARITHMETIC_OVERFLOW, window)
        if (projected > limit) {
            return evaluationDenied(DreamBudgetDenialReason.INPUT_TOKEN_LIMIT, window)
        }
        projected
    }
    val projectedOutput = policy.dailyOutputTokenLimit?.let { limit ->
        if (usage.unmeasuredOutputRunCount > 0) {
            return evaluationDenied(DreamBudgetDenialReason.OUTPUT_USAGE_UNMEASURED, window)
        }
        val projected = addExactOrNull(usage.knownOutputTokens, request.maxOutputTokens)
            ?: return evaluationDenied(DreamBudgetDenialReason.TOKEN_ARITHMETIC_OVERFLOW, window)
        if (projected > limit) {
            return evaluationDenied(DreamBudgetDenialReason.OUTPUT_TOKEN_LIMIT, window)
        }
        projected
    }
    return DreamBudgetEvaluation.Allowed(
        DreamBudgetAdmission(
            request = request,
            policy = policy,
            window = window,
            usageBefore = usage,
            projectedRunCount = projectedRuns,
            projectedInputTokens = projectedInput,
            projectedOutputTokens = projectedOutput,
        ),
    )
}

private val PROCESS_DREAM_BUDGET_MUTEX = Mutex()

private fun denied(
    reason: DreamBudgetDenialReason,
    window: DreamUtcDayWindow? = null,
): DreamBudgetPermitResult.Denied = DreamBudgetPermitResult.Denied(DreamBudgetDenial(reason, window))

private fun evaluationDenied(
    reason: DreamBudgetDenialReason,
    window: DreamUtcDayWindow,
): DreamBudgetEvaluation.Denied = DreamBudgetEvaluation.Denied(DreamBudgetDenial(reason, window))

private fun addExactOrNull(left: Long, right: Long): Long? = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    null
}

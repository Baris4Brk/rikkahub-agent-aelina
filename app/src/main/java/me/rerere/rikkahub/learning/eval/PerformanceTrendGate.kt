package me.rerere.rikkahub.learning.eval

import java.math.BigInteger

enum class PerformanceThresholdStatus { DRAFT, FROZEN }

data class PerformanceTrendThresholds(
    val baselineId: String,
    val status: PerformanceThresholdStatus,
    /** 10_000 = baseline; 11_500 = at most 15% deterministic-operation regression. */
    val maxOperationRatioBasisPoints: Int,
    /** Logical allocation units only; never Runtime heap samples. */
    val maxAllocationRatioBasisPoints: Int,
) {
    init {
        requireSafeEvalLabel(baselineId)
        require(maxOperationRatioBasisPoints in 10_000..100_000)
        require(maxAllocationRatioBasisPoints in 10_000..100_000)
    }
}

data class PerformanceBaseline(
    val baselineId: String,
    val environmentDigestSha256: String,
    val corpusDigestSha256: String,
    val counters: PerformanceCounterSnapshot,
) {
    init {
        requireSafeEvalLabel(baselineId)
        require(environmentDigestSha256.matches(Regex("[0-9a-f]{64}")))
        require(corpusDigestSha256.matches(Regex("[0-9a-f]{64}")))
        require(counters.deterministicOperationUnits > 0L)
        require(counters.logicalAllocationUnits > 0L)
    }
}

enum class PerformanceGateState { PASSED, FAILED, NOT_ENFORCED }

enum class PerformanceGateReason {
    WITHIN_FROZEN_RELATIVE_LIMITS,
    OPERATION_TREND_REGRESSION,
    ALLOCATION_TREND_REGRESSION,
    BASELINE_MISSING,
    THRESHOLDS_NOT_FROZEN,
    BASELINE_ID_MISMATCH,
    CORPUS_IDENTITY_MISMATCH,
    MANIFEST_IDENTITY_MISMATCH,
    CURRENT_ENVIRONMENT_IDENTITY_MISSING,
    ENVIRONMENT_IDENTITY_MISMATCH,
}

data class PerformanceGateResult(
    val state: PerformanceGateState,
    val reason: PerformanceGateReason,
    val operationRatioBasisPoints: Int?,
    val allocationRatioBasisPoints: Int?,
)

object DeterministicPerformanceTrendGate {
    fun evaluate(
        currentCorpusDigestSha256: String,
        current: PerformanceCounterSnapshot,
        baseline: PerformanceBaseline?,
        thresholds: PerformanceTrendThresholds,
    ): PerformanceGateResult {
        if (baseline == null) return notEnforced(PerformanceGateReason.BASELINE_MISSING)
        if (thresholds.status != PerformanceThresholdStatus.FROZEN) {
            return notEnforced(PerformanceGateReason.THRESHOLDS_NOT_FROZEN)
        }
        if (baseline.baselineId != thresholds.baselineId) {
            return notEnforced(PerformanceGateReason.BASELINE_ID_MISMATCH)
        }
        if (currentCorpusDigestSha256 != baseline.corpusDigestSha256) {
            return notEnforced(PerformanceGateReason.CORPUS_IDENTITY_MISMATCH)
        }
        val operationRatio = ratioBasisPointsCeiling(
            current.deterministicOperationUnits,
            baseline.counters.deterministicOperationUnits,
        )
        val allocationRatio = ratioBasisPointsCeiling(
            current.logicalAllocationUnits,
            baseline.counters.logicalAllocationUnits,
        )
        val operationFailed = operationRatio > thresholds.maxOperationRatioBasisPoints
        val allocationFailed = allocationRatio > thresholds.maxAllocationRatioBasisPoints
        return when {
            operationFailed -> PerformanceGateResult(
                PerformanceGateState.FAILED,
                PerformanceGateReason.OPERATION_TREND_REGRESSION,
                operationRatio,
                allocationRatio,
            )
            allocationFailed -> PerformanceGateResult(
                PerformanceGateState.FAILED,
                PerformanceGateReason.ALLOCATION_TREND_REGRESSION,
                operationRatio,
                allocationRatio,
            )
            else -> PerformanceGateResult(
                PerformanceGateState.PASSED,
                PerformanceGateReason.WITHIN_FROZEN_RELATIVE_LIMITS,
                operationRatio,
                allocationRatio,
            )
        }
    }

    private fun ratioBasisPointsCeiling(current: Long, baseline: Long): Int {
        require(current >= 0L && baseline > 0L)
        val numerator = BigInteger.valueOf(current).multiply(BigInteger.valueOf(10_000L))
        val denominator = BigInteger.valueOf(baseline)
        val division = numerator.divideAndRemainder(denominator)
        val rounded = division[0] + if (division[1] == BigInteger.ZERO) {
            BigInteger.ZERO
        } else {
            BigInteger.ONE
        }
        return rounded.coerceAtMost(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }

    private fun notEnforced(reason: PerformanceGateReason) = PerformanceGateResult(
        state = PerformanceGateState.NOT_ENFORCED,
        reason = reason,
        operationRatioBasisPoints = null,
        allocationRatioBasisPoints = null,
    )
}

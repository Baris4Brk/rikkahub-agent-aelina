package me.rerere.rikkahub.learning.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceTrendGateTest {
    private val baseline = PerformanceBaseline(
        baselineId = "linux-jvm-fixture-v1",
        environmentDigestSha256 = "a".repeat(64),
        corpusDigestSha256 = FrozenReplayCorpusV1.digestSha256,
        counters = PerformanceCounterSnapshot(
            deterministicOperationUnits = 10_000L,
            logicalAllocationUnits = 2_000L,
        ),
    )

    @Test
    fun `draft thresholds never gate a change`() {
        val result = DeterministicPerformanceTrendGate.evaluate(
            FrozenReplayCorpusV1.digestSha256,
            PerformanceCounterSnapshot(50_000L, 20_000L),
            baseline,
            thresholds(PerformanceThresholdStatus.DRAFT),
        )
        assertEquals(PerformanceGateState.NOT_ENFORCED, result.state)
        assertEquals(PerformanceGateReason.THRESHOLDS_NOT_FROZEN, result.reason)
        assertNull(result.operationRatioBasisPoints)
    }

    @Test
    fun `frozen relative operation and allocation counters pass at boundary`() {
        val result = DeterministicPerformanceTrendGate.evaluate(
            FrozenReplayCorpusV1.digestSha256,
            PerformanceCounterSnapshot(11_500L, 2_300L),
            baseline,
            thresholds(PerformanceThresholdStatus.FROZEN),
        )
        assertEquals(PerformanceGateState.PASSED, result.state)
        assertEquals(11_500, result.operationRatioBasisPoints)
        assertEquals(11_500, result.allocationRatioBasisPoints)
    }

    @Test
    fun `operation regression is distinguished from allocation regression`() {
        val operation = DeterministicPerformanceTrendGate.evaluate(
            FrozenReplayCorpusV1.digestSha256,
            PerformanceCounterSnapshot(11_501L, 2_000L),
            baseline,
            thresholds(PerformanceThresholdStatus.FROZEN),
        )
        val allocation = DeterministicPerformanceTrendGate.evaluate(
            FrozenReplayCorpusV1.digestSha256,
            PerformanceCounterSnapshot(10_000L, 2_301L),
            baseline,
            thresholds(PerformanceThresholdStatus.FROZEN),
        )
        assertEquals(PerformanceGateReason.OPERATION_TREND_REGRESSION, operation.reason)
        assertEquals(PerformanceGateReason.ALLOCATION_TREND_REGRESSION, allocation.reason)
    }

    @Test
    fun `missing baseline or corpus mismatch is not silently enforced`() {
        val missing = DeterministicPerformanceTrendGate.evaluate(
            FrozenReplayCorpusV1.digestSha256,
            PerformanceCounterSnapshot(1L, 1L),
            null,
            thresholds(PerformanceThresholdStatus.FROZEN),
        )
        val mismatch = DeterministicPerformanceTrendGate.evaluate(
            "b".repeat(64),
            PerformanceCounterSnapshot(10_000L, 2_000L),
            baseline,
            thresholds(PerformanceThresholdStatus.FROZEN),
        )
        assertEquals(PerformanceGateReason.BASELINE_MISSING, missing.reason)
        assertEquals(PerformanceGateReason.CORPUS_IDENTITY_MISMATCH, mismatch.reason)
        assertEquals(PerformanceGateState.NOT_ENFORCED, mismatch.state)
    }

    private fun thresholds(status: PerformanceThresholdStatus) = PerformanceTrendThresholds(
        baselineId = baseline.baselineId,
        status = status,
        maxOperationRatioBasisPoints = 11_500,
        maxAllocationRatioBasisPoints = 11_500,
    )
}

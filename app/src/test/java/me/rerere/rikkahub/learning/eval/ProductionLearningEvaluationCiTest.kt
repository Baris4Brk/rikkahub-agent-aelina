package me.rerere.rikkahub.learning.eval

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic CI half of the P5 gate. It freezes replay/performance, but deliberately cannot
 * approve without both disposable-emulator Room and exact durable four-arm runtime attestations.
 */
class ProductionLearningEvaluationCiTest {
    @Test
    fun `frozen arm-blind authority trace cannot manufacture a learning benefit`() {
        FrozenReplayCorpusV1.units.forEach { unit ->
            requireNotNull(FrozenProductionComponentReplayV1.fixture(unit))
            val trace = requireNotNull(FrozenArmBlindAuthorityTraceV1.recordFor(unit.unitId))
            // Outcome/correction/retry are one explicit unit trace, never maps keyed by arm.
            assertTrue(trace.toolRetries in 0..trace.toolCalls)
            assertTrue(trace.userCorrectionCount >= 0)
            assertEquals(1, OfflineEvalArm.entries.map { trace.taskOutcome }.distinct().size)
            assertEquals(1, OfflineEvalArm.entries.map {
                trace.userCorrectionCount
            }.distinct().size)
            assertEquals(1, OfflineEvalArm.entries.map { trace.toolRetries }.distinct().size)
        }
    }

    @Test
    fun `JVM replay publishes one redacted abstention until Room integration is supplied`() = runBlocking {
        val adapters = FrozenProductionComponentReplayV1.adapters
        val independentRun = ProductionFourArmFixtureRunner(adapters).run()
        val observedEnvironment = ProductionEvalRuntimeEnvironment.capture()
        if (observedEnvironment.frozenMatchRequired) {
            assertTrue(
                "Frozen CI enforcement requires every explicit build input",
                observedEnvironment.hasExplicitBuildBinding,
            )
            assertEquals(
                "Pinned CI runtime drifted from the reviewed environment",
                FrozenProductionEvalBaselineV1.reviewedEnvironment,
                observedEnvironment,
            )
        }
        val result = ProductionLearningEvaluationCiEntry.evaluate(
            adapters = adapters,
            baseline = FrozenProductionEvalBaselineV1.baseline,
            currentEnvironmentDigestSha256 = observedEnvironment.digestSha256,
        )

        val output = File(
            System.getProperty(
                OUTPUT_PROPERTY,
                "build/reports/agent-learning/p5-production-eval-redacted.txt",
            ),
        )
        output.parentFile?.mkdirs()
        output.writeText(result.artifact.redactedReport, Charsets.UTF_8)

        FrozenProductionEvalBaselineV1.requireExactCandidateRun(independentRun)
        assertTrue(independentRun.report.judgeDivergence.llmVsDeterministicDivergenceCount > 0)
        assertTrue(independentRun.report.judgeDivergence.llmVsHumanDivergenceCount > 0)
        assertEquals(
            PerformanceGateState.NOT_ENFORCED,
            result.performance.state,
        )
        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.PERFORMANCE_NOT_ENFORCED,
            result.rollout.reason,
        )
        assertEquals(PerformanceGateReason.THRESHOLDS_NOT_FROZEN, result.performance.reason)
        assertEquals(null, result.roomIntegration)
        assertTrue(output.readText().contains("room_integration_state=UNOBSERVED"))
        assertTrue(output.readText().contains("four_arm_runtime_state=UNOBSERVED"))
        assertEquals(null, result.fourArmRuntime)
        assertEquals(FrozenProductionEvalBaselineV1.expectedCounters, result.runnerPerformance)
        assertTrue(result.componentCoverage.all { it.abstainedCount == 0 })
        assertTrue(output.isFile && output.length() > 0L)
        FORBIDDEN_REPORT_MARKERS.forEach { marker ->
            assertFalse("redacted artifact contains $marker", output.readText().contains(marker))
        }
    }

    private companion object {
        const val OUTPUT_PROPERTY = "rikkahub.p5.eval.output"
        val FORBIDDEN_REPORT_MARKERS = listOf(
            "prompt=",
            "completion=",
            "message=",
            "file://",
            "http://",
            "https://",
            "Bearer ",
        )
    }
}

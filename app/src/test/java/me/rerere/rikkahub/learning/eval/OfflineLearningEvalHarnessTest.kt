package me.rerere.rikkahub.learning.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLearningEvalHarnessTest {
    @Test
    fun `frozen corpus covers every preregistered slice dimension`() {
        assertEquals(20, FrozenReplayCorpusV1.units.size)
        EvalSliceDimension.entries.forEach { dimension ->
            val values = FrozenReplayCorpusV1.units.map {
                it.slice.dimensions().getValue(dimension)
            }.distinct()
            assertTrue("missing coverage for $dimension", values.size >= 2)
        }
        assertEquals(
            FrozenReplayCorpusV1.units.size,
            FrozenReplayCorpusV1.units.map(OfflineReplayUnit::unitId).distinct().size,
        )
    }

    @Test
    fun `four arms are complete matched replays and full runtime has no scripts`() {
        assertEquals(FrozenReplayCorpusV1.units.size, report.matchedCohortCount)
        assertEquals(0, report.incompleteMatchedCohortCount)
        assertEquals(OfflineEvalArm.entries, report.arms.map(ArmEvalSummary::arm))
        assertTrue(report.arms.all { it.sampleSize == FrozenReplayCorpusV1.units.size })
        assertEquals(
            0L,
            report.arms.single {
                it.arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
            }.scriptActionCount,
        )
    }

    @Test
    fun `preregistered assignment is deterministic and corpus order independent`() {
        val forward = PreRegisteredAssignmentEngine.assign(
            FrozenReplayCorpusV1.units,
            FrozenOfflineLearningEvaluation.plan,
        )
        val reversed = PreRegisteredAssignmentEngine.assign(
            FrozenReplayCorpusV1.units.reversed(),
            FrozenOfflineLearningEvaluation.plan,
        )
        assertEquals(forward, reversed)
        assertEquals(
            PreRegisteredAssignmentEngine.manifestDigest(forward),
            report.assignmentManifestSha256,
        )
    }

    @Test
    fun `assignment salt is part of frozen assignment identity`() {
        val alternate = FrozenOfflineLearningEvaluation.plan.copy(
            assignmentSalt = "pre-registered-alternate",
        )
        val changed = PreRegisteredAssignmentEngine.manifestDigest(
            PreRegisteredAssignmentEngine.assign(FrozenReplayCorpusV1.units, alternate),
        )
        assertNotEquals(report.assignmentManifestSha256, changed)
        assertNotEquals(report.planDigestSha256, alternate.digestSha256())
    }

    @Test
    fun `holdout is explicit and partition accounting is complete`() {
        assertTrue(report.holdoutUnitCount in 1 until FrozenReplayCorpusV1.units.size)
        EvalPartition.entries.forEach { partition ->
            OfflineEvalArm.entries.forEach { arm ->
                val row = report.partitions.single {
                    it.partition == partition && it.arm == arm
                }
                val expected = if (partition == EvalPartition.HOLDOUT) {
                    report.holdoutUnitCount
                } else {
                    FrozenReplayCorpusV1.units.size - report.holdoutUnitCount
                }
                assertEquals(expected, row.sampleSize)
            }
        }
    }

    @Test
    fun `unknown and censored outcomes remain separate from observed denominator`() {
        report.arms.forEach { arm ->
            assertEquals(2, arm.taskSuccess.unknownCount)
            assertEquals(2, arm.taskSuccess.censoredCount)
            assertEquals(16, arm.taskSuccess.observedCount)
            assertEquals(20, arm.taskSuccess.totalCount)
        }
    }

    @Test
    fun `every slice value has a separate row for every arm`() {
        EvalSliceDimension.entries.forEach { dimension ->
            val expectedValues = FrozenReplayCorpusV1.units.map {
                it.slice.dimensions().getValue(dimension)
            }.distinct().sorted()
            expectedValues.forEach { value ->
                assertEquals(
                    OfflineEvalArm.entries,
                    report.slices.filter { it.dimension == dimension && it.value == value }
                        .map(SliceEvalSummary::arm),
                )
            }
        }
        assertTrue(report.slices.all { it.sampleSize > 0 })
    }

    @Test
    fun `llm judge divergence is separate from deterministic and human outcomes`() {
        val divergence = report.judgeDivergence
        assertTrue(divergence.llmVsDeterministicComparableCount > 0)
        assertTrue(divergence.llmVsDeterministicDivergenceCount > 0)
        assertEquals(
            divergence.llmVsDeterministicComparableCount,
            divergence.llmVsHumanComparableCount,
        )
        assertEquals(
            divergence.llmVsDeterministicDivergenceCount,
            divergence.llmVsHumanDivergenceCount,
        )
    }

    @Test
    fun `arm comparisons are labeled observed association and never causal effect`() {
        assertEquals(3, report.associations.size)
        report.associations.forEach { association ->
            assertEquals(OfflineEvalArm.A_NO_LEARNING, association.baselineArm)
            assertEquals(
                AssociationInterpretation.OBSERVED_ASSOCIATION_ONLY_NOT_CAUSAL,
                association.interpretation,
            )
            assertEquals(16, association.pairedObservedCount)
            assertEquals(2, association.unknownPairCount)
            assertEquals(2, association.censoredPairCount)
        }
    }

    @Test
    fun `frozen safe corpus has zero scope stale harmful and script hard failures`() {
        report.arms.forEach { arm ->
            assertEquals(0L, arm.scopeLeakCount)
            assertEquals(0L, arm.staleHitCount)
            assertEquals(0, arm.harmfulRate.positiveCount)
            assertEquals(0L, arm.scriptActionCount)
        }
    }

    @Test
    fun `script action from executor is rejected before report publication`() {
        val executor = OfflineReplayExecutor { unit, arm ->
            FrozenFixtureReplayExecutor.replay(unit, arm).copy(
                scriptActionCount = if (
                    arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
                ) 1 else 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineLearningEvalHarness.run(
                FrozenReplayCorpusV1.units,
                FrozenOfflineLearningEvaluation.plan.copy(
                    bootstrap = BootstrapConfig(100, 9_000),
                ),
                executor,
                FrozenReplayCorpusV1.CORPUS_ID,
            )
        }
    }

    @Test
    fun `recorded latency and deterministic JVM counters are present without live timing`() {
        assertTrue(report.arms.all { it.recordedTtft.knowledge == MeasurementKnowledge.MEASURED })
        assertTrue(report.performance.deterministicOperationUnits > 0L)
        assertTrue(report.performance.logicalAllocationUnits > 0L)
        assertEquals(EnergyMeasurementState.UNMEASURED, report.energy.state)
        assertFalse(report.energy.dedicatedOdpmDeviceUsed)
        assertTrue(report.energy.primaryHonorDeviceTestingProhibited)
    }

    companion object {
        private val report: OfflineEvalReport by lazy {
            FrozenOfflineLearningEvaluation.run(FrozenFixtureReplayExecutor)
        }
    }
}

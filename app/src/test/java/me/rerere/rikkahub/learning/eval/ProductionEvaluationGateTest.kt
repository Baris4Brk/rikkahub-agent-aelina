package me.rerere.rikkahub.learning.eval

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionEvaluationGateTest {
    @Test
    fun `production runner has no scenario or synthetic executor dependency`() {
        val root = generateSequence(
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize(),
        ) { it.parent }.take(7).first { Files.isDirectory(it.resolve("app/src/main/java")) }
        val source = Files.readString(
            root.resolve(
                "app/src/main/java/me/rerere/rikkahub/learning/eval/" +
                    "ProductionComponentReplay.kt",
            ),
            StandardCharsets.UTF_8,
        )

        assertFalse(source.contains("FrozenFixtureReplayExecutor"))
        assertFalse(source.contains("unit.scenario"))
        listOf(
            "DreamProjectionReplayPort",
            "PolicyRetrievalReplayPort",
            "RecallCompilerReplayPort",
            "PolicyExposureReplayPort",
            "PolicyOutcomeReplayPort",
        ).forEach { assertTrue(source.contains(it)) }
    }

    @Test
    fun `default production runner has no synthetic success`() = runBlocking {
        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest()

        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.PRODUCTION_COMPONENT_ABSTAINED,
            result.rollout.reason,
        )
        assertTrue(result.report.arms.all { it.taskSuccess.observedCount == 0 })
        assertTrue(result.report.arms.all { it.taskSuccess.unknownCount == 20 })
        assertTrue(result.artifact.redactedReport.contains("energy=UNMEASURED"))
        assertTrue(
            result.artifact.redactedReport.contains(
                "component.RECALL_COMPILER=observed:0",
            ),
        )
        assertTrue(
            result.artifact.redactedReport.contains(
                "primary_honor_device_testing_prohibited=true",
            ),
        )
    }

    @Test
    fun `fixed four-arm runner invokes actual adapter ports and only their observations`() =
        runBlocking {
            val fixture = RecordingProductionAdapters()
            val run = ProductionFourArmFixtureRunner(fixture.adapters).run()

            assertEquals(60, fixture.dreamCalls)
            assertEquals(40, fixture.retrievalCalls)
            assertEquals(80, fixture.recallCalls)
            assertEquals(40, fixture.exposureCalls)
            assertEquals(80, fixture.outcomeCalls)
            assertEquals(0, run.abstainedComponentCount)
            assertEquals(80, run.observations.size)
            assertEquals(
                60,
                run.componentCoverage.single {
                    it.component == ProductionReplayComponent.DREAM_PROJECTION
                }.observedCount,
            )
            assertEquals(
                40,
                run.componentCoverage.single {
                    it.component == ProductionReplayComponent.POLICY_RETRIEVAL
                }.skippedCount,
            )
            assertTrue(run.observations.all { observation ->
                observation.taskOutcome == BinaryObservation.Observed(true)
            })
            assertTrue(run.observations.all { it.resources.inputTokens == 101 })
            assertFalse(
                run.observations.any { observation ->
                    observation.unitId.contains(observation.taskOutcome.toString())
                },
            )
        }

    @Test
    fun `sample shortage is explicit abstain even when every component returned an observation`() =
        runBlocking {
            val fixture = RecordingProductionAdapters(observedTaskOutcome = false)
            val manifest = FrozenProductionEvalManifest.freeze(fixture.adapters)
            val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
            val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
                fixture.adapters,
                baseline(manifest, run.runnerPerformance),
                ENVIRONMENT_SHA256,
            )

            assertEquals(0, run.abstainedComponentCount)
            assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
            assertEquals(
                ProductionRolloutDecisionReason.SAMPLE_SIZE_INSUFFICIENT,
                result.rollout.reason,
            )
            assertEquals(0, result.rollout.minimumObservedTaskOutcomes)
        }

    @Test
    fun `checked-in replay cannot approve even with matching Room gate`() = runBlocking {
        val adapters = FrozenProductionComponentReplayV1.adapters
        val manifest = frozenManifest(adapters)
        val run = ProductionFourArmFixtureRunner(adapters).run()

        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            adapters,
            baseline(manifest, run.runnerPerformance),
            // This unit isolates the post-performance rollout gate with an explicit matched
            // environment. ProductionLearningEvaluationCiTest always uses observed capture().
            ENVIRONMENT_SHA256,
            completeRoomAttestation(),
            checkedInRegressionAttestation(manifest, run),
            testFrozenThresholds(),
        )

        assertEquals(PerformanceGateState.PASSED, result.performance.state)
        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.DURABLE_FOUR_ARM_RUNTIME_NOT_OBSERVED,
            result.rollout.reason,
        )
        assertEquals(10_000, result.performance.operationRatioBasisPoints)
        assertEquals(10_000, result.performance.allocationRatioBasisPoints)
    }

    @Test
    fun `independent authority matrix with confident matched gain can approve`() = runBlocking {
        val fixture = IndependentRuntimeEvalTestFixture(treatmentGain = true)
        val manifest = frozenManifest(fixture.adapters)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            adapters = fixture.adapters,
            baseline = baseline(manifest, run.runnerPerformance),
            currentEnvironmentDigestSha256 = ENVIRONMENT_SHA256,
            roomIntegration = completeRoomAttestation(),
            fourArmRuntime = independentFourArmAttestation(fixture, manifest, run),
            performanceThresholds = testFrozenThresholds(),
        )

        assertEquals(ProductionRolloutDecisionState.APPROVE, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.FROZEN_GATES_PASSED,
            result.rollout.reason,
        )
        val association = result.report.associations.single {
            it.comparisonArm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
        }
        assertTrue(requireNotNull(association.successRateDifference).lower > 0.0)
    }

    @Test
    fun `independent zero-gain matrix abstains on frozen association criterion`() = runBlocking {
        val fixture = IndependentRuntimeEvalTestFixture(treatmentGain = false)
        val manifest = frozenManifest(fixture.adapters)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            adapters = fixture.adapters,
            baseline = baseline(manifest, run.runnerPerformance),
            currentEnvironmentDigestSha256 = ENVIRONMENT_SHA256,
            roomIntegration = completeRoomAttestation(),
            fourArmRuntime = independentFourArmAttestation(fixture, manifest, run),
            performanceThresholds = testFrozenThresholds(),
        )

        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.ASSOCIATION_CRITERION_NOT_MET,
            result.rollout.reason,
        )
        val association = result.report.associations.single {
            it.comparisonArm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
        }
        val confidenceInterval = requireNotNull(association.successRateDifference)
        assertEquals(0.0, confidenceInterval.lower, 0.0)
        assertEquals(0.0, confidenceInterval.estimate, 0.0)
    }

    @Test
    fun `Room smoke cannot turn frozen component fixtures into rollout evidence`() = runBlocking {
        val fixture = RecordingProductionAdapters()
        val manifest = frozenManifest(fixture.adapters)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()

        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            fixture.adapters,
            baseline(manifest, run.runnerPerformance),
            ENVIRONMENT_SHA256,
            completeRoomAttestation(),
            performanceThresholds = testFrozenThresholds(),
        )

        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.DURABLE_FOUR_ARM_RUNTIME_NOT_OBSERVED,
            result.rollout.reason,
        )
        assertEquals(null, result.artifact.fourArmRuntimeDigestSha256)
    }

    @Test
    fun `four-arm attestation is exact-bound to manifest and report`() = runBlocking {
        val fixture = RecordingProductionAdapters()
        val manifest = frozenManifest(fixture.adapters)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
        val frozenManifest = frozenManifest(FrozenProductionComponentReplayV1.adapters)
        val frozenRun = ProductionFourArmFixtureRunner(
            FrozenProductionComponentReplayV1.adapters,
        ).run()
        val mismatched = checkedInRegressionAttestation(frozenManifest, frozenRun)

        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            adapters = fixture.adapters,
            baseline = baseline(manifest, run.runnerPerformance),
            currentEnvironmentDigestSha256 = ENVIRONMENT_SHA256,
            roomIntegration = completeRoomAttestation(),
            fourArmRuntime = mismatched,
            performanceThresholds = testFrozenThresholds(),
        )

        assertEquals(ProductionRolloutDecisionState.REJECT, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.FOUR_ARM_RUNTIME_HARD_FAILURE,
            result.rollout.reason,
        )
        assertFalse(mismatched.toString().contains(frozenManifest.digestSha256))
    }

    @Test
    fun `deterministic replay cannot approve without disposable Room integration`() = runBlocking {
        val fixture = RecordingProductionAdapters()
        val manifest = frozenManifest(fixture.adapters)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()

        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            fixture.adapters,
            baseline(manifest, run.runnerPerformance),
            ENVIRONMENT_SHA256,
            performanceThresholds = testFrozenThresholds(),
        )

        assertEquals(PerformanceGateState.PASSED, result.performance.state)
        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.ROOM_INTEGRATION_NOT_OBSERVED,
            result.rollout.reason,
        )
        assertTrue(result.artifact.redactedReport.contains("room_integration_state=UNOBSERVED"))
        assertEquals(null, result.artifact.roomIntegrationDigestSha256)
    }

    @Test
    fun `hard Room integration invariant failure rejects otherwise passing replay`() = runBlocking {
        val fixture = RecordingProductionAdapters()
        val manifest = FrozenProductionEvalManifest.freeze(fixture.adapters)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
        val integration = ProductionRoomIntegrationAttestationFactory.rejected(
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
            setOf(ProductionRoomIntegrationCheck.APP_DATABASE_ROOM_OPENED),
        )

        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            fixture.adapters,
            baseline(manifest, run.runnerPerformance),
            ENVIRONMENT_SHA256,
            integration,
        )

        assertEquals(ProductionRolloutDecisionState.REJECT, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.ROOM_INTEGRATION_HARD_FAILURE,
            result.rollout.reason,
        )
        assertEquals(
            integration.attestationDigestSha256,
            result.artifact.roomIntegrationDigestSha256,
        )
    }

    @Test
    fun `scope leak is a hard rejection and cannot hide behind aggregate success`() =
        runBlocking {
            val fixture = RecordingProductionAdapters(scopeLeakCount = 1)
            val manifest = frozenManifest(fixture.adapters)
            val run = ProductionFourArmFixtureRunner(fixture.adapters).run()

            val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
                fixture.adapters,
                baseline(manifest, run.runnerPerformance),
                ENVIRONMENT_SHA256,
                completeRoomAttestation(),
                performanceThresholds = testFrozenThresholds(),
            )

            assertEquals(ProductionRolloutDecisionState.REJECT, result.rollout.state)
            assertEquals(
                ProductionRolloutDecisionReason.HARD_SAFETY_FAILURE,
                result.rollout.reason,
            )
        }

    @Test
    fun `report counters include deterministic work returned by component adapters`() =
        runBlocking {
            val one = ProductionFourArmFixtureRunner(
                RecordingProductionAdapters(workMultiplier = 1L).adapters,
            ).run()
            val two = ProductionFourArmFixtureRunner(
                RecordingProductionAdapters(workMultiplier = 2L).adapters,
            ).run()

            // 300 actual adapter invocations across the fixed matrix; harness bookkeeping is
            // identical and therefore cancels from the comparison.
            assertEquals(
                300L,
                two.runnerPerformance.deterministicOperationUnits -
                    one.runnerPerformance.deterministicOperationUnits,
            )
            assertEquals(
                300L,
                two.runnerPerformance.logicalAllocationUnits -
                    one.runnerPerformance.logicalAllocationUnits,
            )
        }

    @Test
    fun `manifest report and redacted artifact digests are frozen and content-free`() =
        runBlocking {
            val fixture = RecordingProductionAdapters()
            val manifest = FrozenProductionEvalManifest.freeze(fixture.adapters)
            val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
            val baseline = baseline(manifest, run.runnerPerformance)

            val first = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
                fixture.adapters,
                baseline,
                ENVIRONMENT_SHA256,
            )
            val second = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
                fixture.adapters,
                baseline,
                ENVIRONMENT_SHA256,
            )

            assertEquals(first.manifest.digestSha256, second.manifest.digestSha256)
            assertEquals(first.reportDigestSha256, second.reportDigestSha256)
            assertEquals(
                first.artifact.artifactDigestSha256,
                second.artifact.artifactDigestSha256,
            )
            assertEquals(first.report.digestSha256(), first.reportDigestSha256)
            assertFalse(first.artifact.redactedReport.contains("fixture-u"))
            assertFalse(first.artifact.redactedReport.contains("prompt="))
            assertFalse(first.artifact.redactedReport.contains("output="))
            assertTrue(first.artifact.redactedReport.contains("slice_coverage_complete=true"))
            first.report.slices.forEach { slice ->
                assertTrue(
                    first.artifact.redactedReport.contains(
                        "slice.${slice.dimension.name}.${slice.value}.${slice.arm.name}=",
                    ),
                )
            }
        }

    @Test
    fun `manifest mismatch keeps frozen performance gate non-enforced and rollout abstains`() =
        runBlocking {
            val fixture = RecordingProductionAdapters()
            val manifest = frozenManifest(fixture.adapters)
            val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
            val mismatched = ProductionEvalPerformanceBaseline(
                manifestDigestSha256 = "f".repeat(64),
                baseline = baseline(manifest, run.runnerPerformance).baseline,
            )

            val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
                fixture.adapters,
                mismatched,
                ENVIRONMENT_SHA256,
                completeRoomAttestation(),
                performanceThresholds = testFrozenThresholds(),
            )

            assertEquals(PerformanceGateState.NOT_ENFORCED, result.performance.state)
            assertEquals(
                PerformanceGateReason.MANIFEST_IDENTITY_MISMATCH,
                result.performance.reason,
            )
            assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
            assertEquals(
                ProductionRolloutDecisionReason.PERFORMANCE_NOT_ENFORCED,
                result.rollout.reason,
            )
        }

    @Test
    fun `missing current environment identity never passes performance`() = runBlocking {
        val fixture = RecordingProductionAdapters()
        val manifest = frozenManifest(fixture.adapters)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()

        val result = ProductionLearningEvaluationCiEntry.evaluateForContractTest(
            fixture.adapters,
            baseline(manifest, run.runnerPerformance),
            roomIntegration = completeRoomAttestation(),
            performanceThresholds = testFrozenThresholds(),
        )

        assertEquals(PerformanceGateState.NOT_ENFORCED, result.performance.state)
        assertEquals(
            PerformanceGateReason.CURRENT_ENVIRONMENT_IDENTITY_MISSING,
            result.performance.reason,
        )
        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
    }

    @Test
    fun `draft contract exposes candidate relative limits without enforcement`() {
        val thresholds = FrozenProductionEvalContractV1.performanceThresholds
        assertEquals(PerformanceThresholdStatus.DRAFT, thresholds.status)
        assertEquals(11_000, thresholds.maxOperationRatioBasisPoints)
        assertEquals(11_500, thresholds.maxAllocationRatioBasisPoints)
        val result = kotlinx.coroutines.runBlocking {
            ProductionLearningEvaluationCiEntry.evaluateForContractTest(
                adapters = FrozenProductionComponentReplayV1.adapters,
                baseline = FrozenProductionEvalBaselineV1.baseline,
                currentEnvironmentDigestSha256 =
                    FrozenProductionEvalBaselineV1.reviewedEnvironment.digestSha256,
            )
        }
        assertEquals(PerformanceGateState.NOT_ENFORCED, result.performance.state)
        assertEquals(PerformanceGateReason.THRESHOLDS_NOT_FROZEN, result.performance.reason)
        assertEquals(ProductionRolloutDecisionState.ABSTAIN, result.rollout.state)
        assertEquals(
            ProductionRolloutDecisionReason.PERFORMANCE_NOT_ENFORCED,
            result.rollout.reason,
        )
        assertNotEquals("", FrozenProductionEvalContractV1.RUNNER_VERSION)
        val criteria = FrozenProductionEvalContractV1.rolloutCriteria
        assertEquals(
            ObservedAssociationDecisionRule.CONFIDENT_POSITIVE_GAIN,
            criteria.associationDecisionRule,
        )
        assertEquals(
            OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS,
            criteria.requiredAssociationComparisonArm,
        )
        assertEquals(1, criteria.minimumAssociationLowerBoundBasisPoints)
    }

    private fun baseline(
        manifest: FrozenProductionEvalManifest,
        counters: PerformanceCounterSnapshot,
    ) = ProductionEvalPerformanceBaseline(
        manifestDigestSha256 = manifest.digestSha256,
        baseline = PerformanceBaseline(
            baselineId = FrozenProductionEvalContractV1.performanceThresholds.baselineId,
            environmentDigestSha256 = ENVIRONMENT_SHA256,
            corpusDigestSha256 = manifest.corpusDigestSha256,
            counters = counters,
        ),
    )

    private fun testFrozenThresholds() = FrozenProductionEvalContractV1.performanceThresholds.copy(
        status = PerformanceThresholdStatus.FROZEN,
    )

    private fun frozenManifest(adapters: ProductionComponentReplayAdapters) =
        FrozenProductionEvalManifest.freeze(
            adapters = adapters,
            performanceThresholds = testFrozenThresholds(),
        )

    private fun completeRoomAttestation(): ProductionRoomIntegrationAttestation =
        ProductionRoomIntegrationAttestationFactory.passed(
            FrozenProductionRoomIntegrationContractV1.requiredChecks,
        )

    private fun checkedInRegressionAttestation(
        manifest: FrozenProductionEvalManifest,
        run: ProductionComponentReplayRun,
    ): ProductionFourArmRuntimeAttestation {
        val preRegistration = DurableFourArmPreRegistration.freeze(manifest)
        val evidence = DurableFourArmRuntimeEvidenceCapture.captureCheckedInFixture(
            manifest,
            preRegistration,
            run,
        )
        return ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            expectedManifest = manifest,
            committedSnapshotDigestSha256 = evidence.snapshotDigestSha256(),
            reopenedPreRegistration = preRegistration,
            reopened = evidence,
        )
    }

    private fun independentFourArmAttestation(
        fixture: IndependentRuntimeEvalTestFixture,
        manifest: FrozenProductionEvalManifest,
        run: ProductionComponentReplayRun,
    ): ProductionFourArmRuntimeAttestation {
        val preRegistration = DurableFourArmPreRegistration.freeze(manifest)
        val evidence = DurableFourArmRuntimeEvidenceCapture.captureIndependentRuntime(
            manifest = manifest,
            preRegistration = preRegistration,
            run = run,
            authoritySourceId = "independent-runtime-authority-journal-v1",
            authorityRecordsByObservationKey = fixture.authorityRows(run),
            judgeSources = fixture.judgeSources,
        )
        return ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            expectedManifest = manifest,
            committedSnapshotDigestSha256 = evidence.snapshotDigestSha256(),
            reopenedPreRegistration = preRegistration,
            reopened = evidence,
        )
    }

    private companion object {
        val ENVIRONMENT_SHA256: String = "a".repeat(64)
    }
}

private class RecordingProductionAdapters(
    private val observedTaskOutcome: Boolean = true,
    private val scopeLeakCount: Int = 0,
    private val workMultiplier: Long = 1L,
) {
    var dreamCalls = 0
    var retrievalCalls = 0
    var recallCalls = 0
    var exposureCalls = 0
    var outcomeCalls = 0

    private fun identity(component: ProductionReplayComponent) =
        ProductionComponentAdapterIdentity(
            component = component,
            adapterVersion = "recording-production-adapter-v1",
            implementationSha256 = EvalDigest.sha256(
                "recording-production-adapter-v1",
                listOf(
                    component.name,
                    observedTaskOutcome.toString(),
                    scopeLeakCount.toString(),
                    workMultiplier.toString(),
                ),
            ),
        )

    private fun work() = DeterministicComponentWork(workMultiplier, workMultiplier)

    val adapters = ProductionComponentReplayAdapters(
        dreaming = object : DreamProjectionReplayPort {
            override val identity = this@RecordingProductionAdapters.identity(
                ProductionReplayComponent.DREAM_PROJECTION,
            )

            override suspend fun project(request: ProductionComponentReplayRequest) =
                ProductionComponentReplayResult.Observed(
                    DreamProjectionReplayObservation(projectedItemCount = 1),
                    work(),
                ).also { dreamCalls++ }
        },
        retrieval = object : PolicyRetrievalReplayPort {
            override val identity = this@RecordingProductionAdapters.identity(
                ProductionReplayComponent.POLICY_RETRIEVAL,
            )

            override suspend fun retrieve(
                request: ProductionComponentReplayRequest,
                dream: DreamProjectionReplayObservation?,
            ) = ProductionComponentReplayResult.Observed(
                PolicyRetrievalReplayObservation(
                    candidateCount = 1,
                    retrievalTokens = 7,
                    scopeLeakCount = this@RecordingProductionAdapters.scopeLeakCount,
                    staleHitCount = 0,
                ),
                work(),
            ).also { retrievalCalls++ }
        },
        recall = object : RecallCompilerReplayPort {
            override val identity = this@RecordingProductionAdapters.identity(
                ProductionReplayComponent.RECALL_COMPILER,
            )

            override suspend fun compile(request: RecallCompilerReplayRequest) =
                ProductionComponentReplayResult.Observed(
                    RecallCompilerReplayObservation(
                        inputTokens = 101,
                        contextTokens = when (request.replay.arm) {
                            OfflineEvalArm.A_NO_LEARNING -> 0
                            OfflineEvalArm.B_DREAMING_ONLY -> 10
                            OfflineEvalArm.C_DREAMING_REVIEWED_POLICY,
                            OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS,
                            -> 20
                        },
                    ),
                    work(),
                ).also { recallCalls++ }
        },
        exposure = object : PolicyExposureReplayPort {
            override val identity = this@RecordingProductionAdapters.identity(
                ProductionReplayComponent.POLICY_EXPOSURE,
            )

            override suspend fun expose(request: PolicyExposureReplayRequest) =
                ProductionComponentReplayResult.Observed(
                    PolicyExposureReplayObservation(compiledCount = 1, dispatchCount = 1),
                    work(),
                ).also { exposureCalls++ }
        },
        outcome = object : PolicyOutcomeReplayPort {
            override val identity = this@RecordingProductionAdapters.identity(
                ProductionReplayComponent.POLICY_OUTCOME,
            )

            override suspend fun observe(request: PolicyOutcomeReplayRequest) =
                ProductionComponentReplayResult.Observed(
                    PolicyOutcomeReplayObservation(
                        taskOutcome = if (observedTaskOutcome) {
                            BinaryObservation.Observed(true)
                        } else {
                            BinaryObservation.Unknown(BinaryUnknownReason.OUTCOME_NOT_RECORDED)
                        },
                        harmfulOutcome = if (observedTaskOutcome) {
                            BinaryObservation.Observed(false)
                        } else {
                            BinaryObservation.Unknown(BinaryUnknownReason.OUTCOME_NOT_RECORDED)
                        },
                        userCorrectionCount = 0,
                        outputTokens = 31,
                        toolCalls = 1,
                        toolRetries = 0,
                        recordedLatency = RecordedLatencyObservation(1_000L, 2_000L),
                        policyOutcome = if (request.replay.arm in setOf(
                                OfflineEvalArm.C_DREAMING_REVIEWED_POLICY,
                                OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS,
                            ) && observedTaskOutcome
                        ) {
                            BinaryObservation.Observed(true)
                        } else {
                            BinaryObservation.Unknown(BinaryUnknownReason.OUTCOME_NOT_RECORDED)
                        },
                        deterministicJudge = if (observedTaskOutcome) {
                            JudgeVerdict.SUCCESS
                        } else {
                            JudgeVerdict.UNKNOWN
                        },
                        humanJudge = if (observedTaskOutcome) {
                            JudgeVerdict.SUCCESS
                        } else {
                            JudgeVerdict.UNKNOWN
                        },
                        llmJudge = if (observedTaskOutcome) {
                            JudgeVerdict.SUCCESS
                        } else {
                            JudgeVerdict.UNKNOWN
                        },
                        scriptActionCount = 0,
                    ),
                    work(),
                ).also { outcomeCalls++ }
        },
    )
}

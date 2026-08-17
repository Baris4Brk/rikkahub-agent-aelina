package me.rerere.rikkahub.learning.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableFourArmRuntimeEvidenceTest {
    @Test
    fun `only exact reopened independent 20 by 4 authority-backed matrix passes`() = runBlocking {
        val fixture = independentFixture()
        val attestation = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            expectedManifest = fixture.manifest,
            committedSnapshotDigestSha256 = fixture.evidence.snapshotDigestSha256(),
            reopenedPreRegistration = DurableFourArmPreRegistration.freeze(fixture.manifest),
            reopened = fixture.evidence,
        )

        assertEquals(ProductionFourArmRuntimeState.PASSED, attestation.state)
        assertEquals(
            FrozenProductionFourArmRuntimeContractV1.requiredChecks,
            attestation.observedChecks,
        )
        assertEquals(
            fixture.evidence.snapshotDigestSha256(),
            attestation.durableEvidenceDigestSha256,
        )
        assertEquals(80, fixture.evidence.observationRecords.size)
        assertEquals(400, fixture.evidence.receiptRecords.size)
        assertEquals(80, fixture.evidence.authorityRecords.size)
        assertEquals(
            DurableRuntimeEvidenceOrigin.INDEPENDENT_RUNTIME_CAPTURE,
            fixture.evidence.origin,
        )
    }

    @Test
    fun `checked-in component replay remains regression evidence and always abstains`() =
        runBlocking {
            val fixture = regressionFixture()
            val attestation = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
                expectedManifest = fixture.manifest,
                committedSnapshotDigestSha256 = fixture.evidence.snapshotDigestSha256(),
                reopenedPreRegistration = DurableFourArmPreRegistration.freeze(fixture.manifest),
                reopened = fixture.evidence,
            )

            assertEquals(ProductionFourArmRuntimeState.ABSTAINED, attestation.state)
            assertEquals(
                ProductionFourArmRuntimeReason.CHECKED_IN_REGRESSION_FIXTURE_ONLY,
                attestation.reason,
            )
            assertEquals(20, fixture.evidence.authorityRecords.size)
            assertTrue(
                ProductionFourArmRuntimeCheck.INDEPENDENT_RUNTIME_AUTHORITY_CAPTURED !in
                    attestation.observedChecks,
            )
            assertTrue(
                ProductionFourArmRuntimeCheck.INDEPENDENT_JUDGE_SOURCES_OBSERVED !in
                    attestation.observedChecks,
            )
        }

    @Test
    fun `missing durable row abstains and duplicate row rejects`() = runBlocking {
        val fixture = independentFixture()
        val missing = fixture.evidence.copy(
            observationRecords = fixture.evidence.observationRecords.dropLast(1),
        )
        val missingResult = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            fixture.manifest,
            missing.snapshotDigestSha256(),
            DurableFourArmPreRegistration.freeze(fixture.manifest),
            missing,
        )
        assertEquals(ProductionFourArmRuntimeState.ABSTAINED, missingResult.state)
        assertEquals(ProductionFourArmRuntimeReason.WINDOW_INCOMPLETE, missingResult.reason)

        val duplicate = fixture.evidence.copy(
            observationRecords = fixture.evidence.observationRecords.dropLast(1) +
                fixture.evidence.observationRecords.first(),
        )
        val duplicateResult = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            fixture.manifest,
            duplicate.snapshotDigestSha256(),
            DurableFourArmPreRegistration.freeze(fixture.manifest),
            duplicate,
        )
        assertEquals(ProductionFourArmRuntimeState.REJECTED, duplicateResult.state)
        assertEquals(
            ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION,
            duplicateResult.reason,
        )
    }

    @Test
    fun `assignment authority and reopen digest tampering never pass`() = runBlocking {
        val fixture = independentFixture()
        val first = fixture.evidence.observationRecords.first()
        val wrongAssignment = fixture.evidence.copy(
            observationRecords = listOf(
                first.copy(primaryArm = OfflineEvalArm.entries.first { it != first.primaryArm }),
            ) + fixture.evidence.observationRecords.drop(1),
        )
        val assignmentResult = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            fixture.manifest,
            wrongAssignment.snapshotDigestSha256(),
            DurableFourArmPreRegistration.freeze(fixture.manifest),
            wrongAssignment,
        )
        assertEquals(ProductionFourArmRuntimeState.REJECTED, assignmentResult.state)

        val trace = fixture.evidence.authorityRecords.first()
        val splitAuthority = fixture.evidence.copy(
            authorityRecords = listOf(
                trace.copy(userCorrectionCount = trace.userCorrectionCount + 1),
            ) + fixture.evidence.authorityRecords.drop(1),
        )
        val authorityResult = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            fixture.manifest,
            splitAuthority.snapshotDigestSha256(),
            DurableFourArmPreRegistration.freeze(fixture.manifest),
            splitAuthority,
        )
        assertEquals(ProductionFourArmRuntimeState.REJECTED, authorityResult.state)
        assertEquals(
            ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH,
            authorityResult.reason,
        )

        val reopenResult = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            fixture.manifest,
            "f".repeat(64),
            DurableFourArmPreRegistration.freeze(fixture.manifest),
            fixture.evidence,
        )
        assertEquals(ProductionFourArmRuntimeState.REJECTED, reopenResult.state)
        assertEquals(
            ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION,
            reopenResult.reason,
        )
    }

    @Test
    fun `three named judge sources cannot pass when verdict columns are copied`() = runBlocking {
        val fixture = IndependentRuntimeEvalTestFixture(treatmentGain = true)
        val manifest = FrozenProductionEvalManifest.freeze(fixture.adapters)
        val preRegistration = DurableFourArmPreRegistration.freeze(manifest)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
        val copiedRows = fixture.authorityRows(run).mapValues { (_, row) ->
            row.copy(
                humanJudge = row.deterministicJudge,
                llmJudge = row.deterministicJudge,
            )
        }
        val evidence = DurableFourArmRuntimeEvidenceCapture.captureIndependentRuntime(
            manifest = manifest,
            preRegistration = preRegistration,
            run = run.copy(
                observations = run.observations.map { observation ->
                    observation.copy(
                        humanJudge = observation.deterministicJudge,
                        llmJudge = observation.deterministicJudge,
                    )
                },
            ),
            authoritySourceId = "independent-copied-judge-stream-v1",
            authorityRecordsByObservationKey = copiedRows,
            judgeSources = fixture.judgeSources,
        )
        val result = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            manifest,
            evidence.snapshotDigestSha256(),
            preRegistration,
            evidence,
        )

        assertEquals(ProductionFourArmRuntimeState.REJECTED, result.state)
        assertEquals(
            ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH,
            result.reason,
        )
    }

    @Test
    fun `report identity and codec round trip are exact`() = runBlocking {
        val fixture = independentFixture()
        val evidence = fixture.evidence
        val decoded = DurableFourArmRuntimeEvidenceCodec.decodeEvidence(
            evidence.headerWire(),
            evidence.authorityRecords.map(DurableFourArmRuntimeEvidenceCodec::encodeAuthority),
            evidence.observationRecords.map(
                DurableFourArmRuntimeEvidenceCodec::encodeObservation,
            ),
            evidence.receiptRecords.map(DurableFourArmRuntimeEvidenceCodec::encodeReceipt),
        )
        assertEquals(evidence, decoded)
        assertEquals(evidence.snapshotDigestSha256(), decoded.snapshotDigestSha256())

        val wrongReport = evidence.copy(reportDigestSha256 = "e".repeat(64))
        val rejected = ProductionFourArmRuntimeEvidenceVerifier.verifyReopened(
            fixture.manifest,
            wrongReport.snapshotDigestSha256(),
            DurableFourArmPreRegistration.freeze(fixture.manifest),
            wrongReport,
        )
        assertEquals(ProductionFourArmRuntimeState.REJECTED, rejected.state)
        assertNotEquals(ProductionFourArmRuntimeState.PASSED, rejected.state)
        assertTrue(rejected.observedChecks.contains(
            ProductionFourArmRuntimeCheck.INDEPENDENT_RUNTIME_AUTHORITY_CAPTURED,
        ))
    }

    private suspend fun regressionFixture(): Fixture {
        val adapters = FrozenProductionComponentReplayV1.adapters
        val manifest = FrozenProductionEvalManifest.freeze(adapters)
        val preRegistration = DurableFourArmPreRegistration.freeze(manifest)
        val run = ProductionFourArmFixtureRunner(adapters).run()
        return Fixture(
            manifest,
            DurableFourArmRuntimeEvidenceCapture.captureCheckedInFixture(
                manifest,
                preRegistration,
                run,
            ),
        )
    }

    private suspend fun independentFixture(): Fixture {
        val fixture = IndependentRuntimeEvalTestFixture(treatmentGain = true)
        val manifest = FrozenProductionEvalManifest.freeze(fixture.adapters)
        val preRegistration = DurableFourArmPreRegistration.freeze(manifest)
        val run = ProductionFourArmFixtureRunner(fixture.adapters).run()
        return Fixture(
            manifest,
            DurableFourArmRuntimeEvidenceCapture.captureIndependentRuntime(
                manifest = manifest,
                preRegistration = preRegistration,
                run = run,
                authoritySourceId = "independent-runtime-authority-journal-v1",
                authorityRecordsByObservationKey = fixture.authorityRows(run),
                judgeSources = fixture.judgeSources,
            ),
        )
    }

    private data class Fixture(
        val manifest: FrozenProductionEvalManifest,
        val evidence: DurableFourArmRuntimeEvidence,
    )
}

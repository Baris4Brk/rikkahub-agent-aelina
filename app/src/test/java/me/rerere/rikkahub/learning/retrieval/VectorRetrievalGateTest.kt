package me.rerere.rikkahub.learning.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorRetrievalGateTest {
    @Test
    fun currentNoEvidenceStateIsFullyFailClosed() {
        val decision = VectorRetrievalGate.evaluate(false, emptyEvidence())
            as VectorRetrievalGateDecision.Disabled
        assertEquals(VectorRetrievalGateFailure.entries.toSet(), decision.failures)
    }

    @Test
    fun everyEvidenceFieldIsAnIndependentGate() {
        val complete = completeEvidence()
        val variants = listOf(
            complete.copy(ftsExactReplayBaselineSha256 = null) to
                VectorRetrievalGateFailure.FTS_BASELINE_MISSING,
            complete.copy(failureSliceReportSha256 = null) to
                VectorRetrievalGateFailure.FAILURE_SLICES_MISSING,
            complete.copy(embeddingArtifactSha256 = null) to
                VectorRetrievalGateFailure.EMBEDDING_IDENTITY_MISSING,
            complete.copy(tokenizerIdentitySha256 = null) to
                VectorRetrievalGateFailure.EMBEDDING_IDENTITY_MISSING,
            complete.copy(preprocessingIdentitySha256 = null) to
                VectorRetrievalGateFailure.EMBEDDING_IDENTITY_MISSING,
            complete.copy(indexSchemaVersion = null) to
                VectorRetrievalGateFailure.INDEX_CONTRACT_MISSING,
            complete.copy(rebuildContractPassed = false) to
                VectorRetrievalGateFailure.INDEX_CONTRACT_MISSING,
            complete.copy(timeoutContractPassed = false) to
                VectorRetrievalGateFailure.EXECUTION_FENCES_MISSING,
            complete.copy(cancellationContractPassed = false) to
                VectorRetrievalGateFailure.EXECUTION_FENCES_MISSING,
            complete.copy(resourceGovernorContractPassed = false) to
                VectorRetrievalGateFailure.EXECUTION_FENCES_MISSING,
            complete.copy(offFallbackContractPassed = false) to
                VectorRetrievalGateFailure.OFF_FALLBACK_MISSING,
            complete.copy(deviceLatencyMemoryThermalBatteryBaselineSha256 = null) to
                VectorRetrievalGateFailure.DEVICE_BASELINE_MISSING,
        )
        variants.forEach { (evidence, expected) ->
            val decision = VectorRetrievalGate.evaluate(true, evidence)
                as VectorRetrievalGateDecision.Disabled
            assertTrue(expected in decision.failures)
        }
    }

    @Test
    fun completeEvidenceOnlyAuthorizesShadowExperiment() {
        assertTrue(
            VectorRetrievalGate.evaluate(true, completeEvidence()) is
                VectorRetrievalGateDecision.ShadowEligible,
        )
    }

    @Test
    fun completeEvidenceCannotOverrideKillSwitch() {
        assertEquals(
            setOf(VectorRetrievalGateFailure.FEATURE_DISABLED),
            (VectorRetrievalGate.evaluate(false, completeEvidence()) as
                VectorRetrievalGateDecision.Disabled).failures,
        )
    }

    private fun emptyEvidence() = VectorRetrievalReadinessEvidence(
        ftsExactReplayBaselineSha256 = null,
        failureSliceReportSha256 = null,
        embeddingArtifactSha256 = null,
        tokenizerIdentitySha256 = null,
        preprocessingIdentitySha256 = null,
        indexSchemaVersion = null,
        rebuildContractPassed = false,
        timeoutContractPassed = false,
        cancellationContractPassed = false,
        resourceGovernorContractPassed = false,
        offFallbackContractPassed = false,
        deviceLatencyMemoryThermalBatteryBaselineSha256 = null,
    )

    private fun completeEvidence() = VectorRetrievalReadinessEvidence(
        ftsExactReplayBaselineSha256 = HASH,
        failureSliceReportSha256 = HASH,
        embeddingArtifactSha256 = HASH,
        tokenizerIdentitySha256 = HASH,
        preprocessingIdentitySha256 = HASH,
        indexSchemaVersion = 1,
        rebuildContractPassed = true,
        timeoutContractPassed = true,
        cancellationContractPassed = true,
        resourceGovernorContractPassed = true,
        offFallbackContractPassed = true,
        deviceLatencyMemoryThermalBatteryBaselineSha256 = HASH,
    )

    private companion object {
        val HASH = "a".repeat(64)
    }
}

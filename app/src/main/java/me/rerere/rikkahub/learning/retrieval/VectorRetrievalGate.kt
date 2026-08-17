package me.rerere.rikkahub.learning.retrieval

private val VECTOR_GATE_SHA256 = Regex("[0-9a-f]{64}")

/** Vector/Hybrid remains an optional shadow-only experiment; this gate never enables injection. */
data class VectorRetrievalReadinessEvidence(
    val ftsExactReplayBaselineSha256: String?,
    val failureSliceReportSha256: String?,
    val embeddingArtifactSha256: String?,
    val tokenizerIdentitySha256: String?,
    val preprocessingIdentitySha256: String?,
    val indexSchemaVersion: Int?,
    val rebuildContractPassed: Boolean,
    val timeoutContractPassed: Boolean,
    val cancellationContractPassed: Boolean,
    val resourceGovernorContractPassed: Boolean,
    val offFallbackContractPassed: Boolean,
    val deviceLatencyMemoryThermalBatteryBaselineSha256: String?,
) {
    init {
        listOfNotNull(
            ftsExactReplayBaselineSha256,
            failureSliceReportSha256,
            embeddingArtifactSha256,
            tokenizerIdentitySha256,
            preprocessingIdentitySha256,
            deviceLatencyMemoryThermalBatteryBaselineSha256,
        ).forEach { require(it.matches(VECTOR_GATE_SHA256)) { "Invalid vector gate evidence" } }
        require(indexSchemaVersion == null || indexSchemaVersion > 0)
    }

    override fun toString(): String =
        "VectorRetrievalReadinessEvidence(indexSchema=$indexSchemaVersion, digests=<redacted>)"
}

enum class VectorRetrievalGateFailure {
    FEATURE_DISABLED,
    FTS_BASELINE_MISSING,
    FAILURE_SLICES_MISSING,
    EMBEDDING_IDENTITY_MISSING,
    INDEX_CONTRACT_MISSING,
    EXECUTION_FENCES_MISSING,
    OFF_FALLBACK_MISSING,
    DEVICE_BASELINE_MISSING,
}

sealed interface VectorRetrievalGateDecision {
    /** Eligible only for FTS/exact-vs-vector-vs-hybrid shadow comparison, never provider bytes. */
    data class ShadowEligible(val evidence: VectorRetrievalReadinessEvidence) :
        VectorRetrievalGateDecision

    data class Disabled(val failures: Set<VectorRetrievalGateFailure>) :
        VectorRetrievalGateDecision {
        init {
            require(failures.isNotEmpty())
        }
    }
}

object VectorRetrievalGate {
    fun evaluate(
        configured: Boolean,
        evidence: VectorRetrievalReadinessEvidence,
    ): VectorRetrievalGateDecision {
        val failures = buildSet {
            if (!configured) add(VectorRetrievalGateFailure.FEATURE_DISABLED)
            if (evidence.ftsExactReplayBaselineSha256 == null) {
                add(VectorRetrievalGateFailure.FTS_BASELINE_MISSING)
            }
            if (evidence.failureSliceReportSha256 == null) {
                add(VectorRetrievalGateFailure.FAILURE_SLICES_MISSING)
            }
            if (listOf(
                    evidence.embeddingArtifactSha256,
                    evidence.tokenizerIdentitySha256,
                    evidence.preprocessingIdentitySha256,
                ).any { it == null }
            ) {
                add(VectorRetrievalGateFailure.EMBEDDING_IDENTITY_MISSING)
            }
            if (evidence.indexSchemaVersion == null || !evidence.rebuildContractPassed) {
                add(VectorRetrievalGateFailure.INDEX_CONTRACT_MISSING)
            }
            if (!evidence.timeoutContractPassed || !evidence.cancellationContractPassed ||
                !evidence.resourceGovernorContractPassed
            ) {
                add(VectorRetrievalGateFailure.EXECUTION_FENCES_MISSING)
            }
            if (!evidence.offFallbackContractPassed) {
                add(VectorRetrievalGateFailure.OFF_FALLBACK_MISSING)
            }
            if (evidence.deviceLatencyMemoryThermalBatteryBaselineSha256 == null) {
                add(VectorRetrievalGateFailure.DEVICE_BASELINE_MISSING)
            }
        }
        return if (failures.isEmpty()) {
            VectorRetrievalGateDecision.ShadowEligible(evidence)
        } else {
            VectorRetrievalGateDecision.Disabled(failures)
        }
    }
}

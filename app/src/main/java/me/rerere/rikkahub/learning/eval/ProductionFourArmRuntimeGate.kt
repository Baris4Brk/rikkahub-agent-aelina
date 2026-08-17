package me.rerere.rikkahub.learning.eval

/**
 * Evidence that the report was produced by four authority-backed runtime arms, rather than by the
 * checked-in component fixture. The fixture remains useful for deterministic regressions, but it
 * is intentionally incapable of authorizing rollout on its own.
 */
object FrozenProductionFourArmRuntimeContractV1 {
    const val CONTRACT_ID: String = "p5-durable-four-arm-runtime-attestation-v3"

    val requiredChecks: Set<ProductionFourArmRuntimeCheck> =
        ProductionFourArmRuntimeCheck.entries.toSet()
}

enum class ProductionFourArmRuntimeCheck {
    A_NO_LEARNING_OBSERVED,
    B_DREAMING_ONLY_OBSERVED,
    C_DREAMING_REVIEWED_POLICY_OBSERVED,
    D_FULL_REVIEWED_RUNTIME_NO_JS_OBSERVED,
    AUTHORITY_OUTCOMES_DURABLE,
    MATCHED_COHORTS_COMPLETE,
    ARM_ASSIGNMENT_PRE_REGISTERED,
    INDEPENDENT_RUNTIME_AUTHORITY_CAPTURED,
    INDEPENDENT_JUDGE_SOURCES_OBSERVED,
    REQUIRED_SLICES_COMPLETE,
}

enum class ProductionFourArmRuntimeState {
    PASSED,
    ABSTAINED,
    REJECTED,
}

enum class ProductionFourArmRuntimeReason {
    ALL_DURABLE_RUNTIME_CHECKS_OBSERVED,
    SOURCE_UNAVAILABLE,
    WINDOW_INCOMPLETE,
    CHECKED_IN_REGRESSION_FIXTURE_ONLY,
    AUTHORITY_OR_IDENTITY_MISMATCH,
    DURABLE_INVARIANT_VIOLATION,
}

class ProductionFourArmRuntimeAttestation internal constructor(
    val contractId: String,
    val manifestDigestSha256: String,
    val reportDigestSha256: String,
    val state: ProductionFourArmRuntimeState,
    val reason: ProductionFourArmRuntimeReason,
    val observedChecks: Set<ProductionFourArmRuntimeCheck>,
    val durableEvidenceDigestSha256: String?,
    val attestationDigestSha256: String,
) {
    init {
        require(contractId == FrozenProductionFourArmRuntimeContractV1.CONTRACT_ID)
        require(manifestDigestSha256.isEvalSha256())
        require(reportDigestSha256.isEvalSha256())
        require(observedChecks.all(FrozenProductionFourArmRuntimeContractV1.requiredChecks::contains))
        durableEvidenceDigestSha256?.let { require(it.isEvalSha256()) }
        require(
            (state == ProductionFourArmRuntimeState.PASSED) ==
                (reason == ProductionFourArmRuntimeReason.ALL_DURABLE_RUNTIME_CHECKS_OBSERVED),
        )
        if (state == ProductionFourArmRuntimeState.PASSED) {
            require(observedChecks == FrozenProductionFourArmRuntimeContractV1.requiredChecks)
            require(durableEvidenceDigestSha256 != null)
        }
        require(attestationDigestSha256 == computeDigest(
            manifestDigestSha256 = manifestDigestSha256,
            reportDigestSha256 = reportDigestSha256,
            state = state,
            reason = reason,
            observedChecks = observedChecks,
            durableEvidenceDigestSha256 = durableEvidenceDigestSha256,
        ))
    }

    val observedCheckCount: Int get() = observedChecks.size

    override fun toString(): String =
        "ProductionFourArmRuntimeAttestation(state=$state, reason=$reason, " +
            "checks=$observedCheckCount, ids=<redacted>)"

    companion object {
        internal fun computeDigest(
            manifestDigestSha256: String,
            reportDigestSha256: String,
            state: ProductionFourArmRuntimeState,
            reason: ProductionFourArmRuntimeReason,
            observedChecks: Set<ProductionFourArmRuntimeCheck>,
            durableEvidenceDigestSha256: String?,
        ): String = EvalDigest.sha256(
            "p5-durable-four-arm-runtime-attestation-v3",
            buildList {
                add(FrozenProductionFourArmRuntimeContractV1.CONTRACT_ID)
                add(manifestDigestSha256)
                add(reportDigestSha256)
                add(state.name)
                add(reason.name)
                add(durableEvidenceDigestSha256 ?: "UNAVAILABLE")
                observedChecks.sortedBy { it.ordinal }.forEach { add(it.name) }
            },
        )
    }
}

object ProductionFourArmRuntimeAttestationFactory {
    internal fun passed(
        manifestDigestSha256: String,
        reportDigestSha256: String,
        observedChecks: Set<ProductionFourArmRuntimeCheck> =
            FrozenProductionFourArmRuntimeContractV1.requiredChecks,
        durableEvidenceDigestSha256: String,
    ): ProductionFourArmRuntimeAttestation = create(
        manifestDigestSha256 = manifestDigestSha256,
        reportDigestSha256 = reportDigestSha256,
        state = ProductionFourArmRuntimeState.PASSED,
        reason = ProductionFourArmRuntimeReason.ALL_DURABLE_RUNTIME_CHECKS_OBSERVED,
        observedChecks = observedChecks,
        durableEvidenceDigestSha256 = durableEvidenceDigestSha256,
    )

    fun abstained(
        manifestDigestSha256: String,
        reportDigestSha256: String,
        reason: ProductionFourArmRuntimeReason,
        observedChecks: Set<ProductionFourArmRuntimeCheck> = emptySet(),
        durableEvidenceDigestSha256: String? = null,
    ): ProductionFourArmRuntimeAttestation {
        require(reason in setOf(
            ProductionFourArmRuntimeReason.SOURCE_UNAVAILABLE,
            ProductionFourArmRuntimeReason.WINDOW_INCOMPLETE,
            ProductionFourArmRuntimeReason.CHECKED_IN_REGRESSION_FIXTURE_ONLY,
        ))
        return create(
            manifestDigestSha256,
            reportDigestSha256,
            ProductionFourArmRuntimeState.ABSTAINED,
            reason,
            observedChecks,
            durableEvidenceDigestSha256,
        )
    }

    fun rejected(
        manifestDigestSha256: String,
        reportDigestSha256: String,
        reason: ProductionFourArmRuntimeReason,
        observedChecks: Set<ProductionFourArmRuntimeCheck> = emptySet(),
        durableEvidenceDigestSha256: String? = null,
    ): ProductionFourArmRuntimeAttestation {
        require(reason in setOf(
            ProductionFourArmRuntimeReason.AUTHORITY_OR_IDENTITY_MISMATCH,
            ProductionFourArmRuntimeReason.DURABLE_INVARIANT_VIOLATION,
        ))
        return create(
            manifestDigestSha256,
            reportDigestSha256,
            ProductionFourArmRuntimeState.REJECTED,
            reason,
            observedChecks,
            durableEvidenceDigestSha256,
        )
    }

    private fun create(
        manifestDigestSha256: String,
        reportDigestSha256: String,
        state: ProductionFourArmRuntimeState,
        reason: ProductionFourArmRuntimeReason,
        observedChecks: Set<ProductionFourArmRuntimeCheck>,
        durableEvidenceDigestSha256: String?,
    ): ProductionFourArmRuntimeAttestation = ProductionFourArmRuntimeAttestation(
        contractId = FrozenProductionFourArmRuntimeContractV1.CONTRACT_ID,
        manifestDigestSha256 = manifestDigestSha256,
        reportDigestSha256 = reportDigestSha256,
        state = state,
        reason = reason,
        observedChecks = observedChecks.toSet(),
        durableEvidenceDigestSha256 = durableEvidenceDigestSha256,
        attestationDigestSha256 = ProductionFourArmRuntimeAttestation.computeDigest(
            manifestDigestSha256,
            reportDigestSha256,
            state,
            reason,
            observedChecks,
            durableEvidenceDigestSha256,
        ),
    )
}

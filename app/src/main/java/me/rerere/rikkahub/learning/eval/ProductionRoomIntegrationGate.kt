package me.rerere.rikkahub.learning.eval

/**
 * Frozen, content-free proof surface for the disposable-emulator half of P5-005.
 *
 * The ordinary JVM replay is useful for deterministic A/B aggregation, but it cannot prove that
 * the production AppDatabase/LearningDatabase joins, SQLite extension, lifecycle transactions or
 * ledgers still work. A rollout may therefore be approved only when the same final evaluation also
 * carries a complete attestation produced by the Android Room integration harness.
 */
object FrozenProductionRoomIntegrationContractV1 {
    const val CONTRACT_ID: String = "p5-room-integration-v1"
    const val FIXTURE_ID: String = "p5-room-synthetic-corpus-v1"

    val fixtureDigestSha256: String = EvalDigest.sha256(
        domain = "p5-room-integration-fixture-v1",
        fields = listOf(
            FIXTURE_ID,
            "non-user-synthetic",
            "app-database-v49",
            "learning-database-current",
            "fts5-simple-jieba-query",
            "exact-grant-active-recall-exposure-outcome-utility",
        ),
    )

    val requiredChecks: Set<ProductionRoomIntegrationCheck> =
        ProductionRoomIntegrationCheck.entries.toSet()
}

/** Each item names a production boundary, never a fixture payload or user identifier. */
enum class ProductionRoomIntegrationCheck {
    APP_DATABASE_ROOM_OPENED,
    LEARNING_DATABASE_FACADE_OPENED,
    AUTHORITATIVE_STREAM_BOUND,
    EXACT_GRANT_COMMITTED,
    POLICY_LIFECYCLE_ACTIVE,
    REAL_FTS5_CHINESE_MATCH,
    ACTIVE_POLICY_ROOM_RETRIEVAL,
    RECALL_COMPILER_INCLUDED_WHOLE_POLICY,
    EXPOSURE_RESERVED,
    EXPOSURE_COMPILED,
    EXPOSURE_INJECTED,
    EXPOSURE_HOST_DISPATCHED,
    EXPOSURE_FIRST_PROGRESS,
    EXPOSURE_RESPONSE_FINISHED,
    PROVIDER_TERMINAL_COMMITTED,
    TERMINAL_AUTHORITY_OUTCOME_LINKED,
    OBSERVED_UTILITY_RECEIPT_COMMITTED,
    EXACT_ROOM_ROWS_RELOADED,
}

enum class ProductionRoomIntegrationState {
    PASSED,
    REJECTED,
    ABSTAINED,
}

/** Closed reason vocabulary; exception strings and corpus text never enter the gate artifact. */
enum class ProductionRoomIntegrationReason {
    ALL_REQUIRED_PRODUCTION_BOUNDARIES_OBSERVED,
    NOT_EXECUTED_ON_DISPOSABLE_EMULATOR,
    RUNTIME_OR_STORAGE_UNAVAILABLE,
    REQUIRED_BOUNDARY_NOT_OBSERVED,
    EXACT_IDENTITY_OR_AUTHORITY_MISMATCH,
    DURABLE_STATE_INVARIANT_VIOLATION,
}

data class ProductionRoomIntegrationAttestation internal constructor(
    val schemaVersion: Int,
    val contractId: String,
    val fixtureDigestSha256: String,
    val state: ProductionRoomIntegrationState,
    val reason: ProductionRoomIntegrationReason,
    val observedChecks: Set<ProductionRoomIntegrationCheck>,
    val attestationDigestSha256: String,
) {
    init {
        require(schemaVersion == 1)
        require(contractId == FrozenProductionRoomIntegrationContractV1.CONTRACT_ID)
        require(fixtureDigestSha256 ==
            FrozenProductionRoomIntegrationContractV1.fixtureDigestSha256)
        require(observedChecks.all {
            it in FrozenProductionRoomIntegrationContractV1.requiredChecks
        })
        require(
            (state == ProductionRoomIntegrationState.PASSED) ==
                (reason == ProductionRoomIntegrationReason
                    .ALL_REQUIRED_PRODUCTION_BOUNDARIES_OBSERVED),
        )
        if (state == ProductionRoomIntegrationState.PASSED) {
            require(observedChecks == FrozenProductionRoomIntegrationContractV1.requiredChecks)
        }
        require(attestationDigestSha256 == computeDigest(
            state = state,
            reason = reason,
            observedChecks = observedChecks,
        ))
    }

    val observedCheckCount: Int get() = observedChecks.size
    val requiredCheckCount: Int
        get() = FrozenProductionRoomIntegrationContractV1.requiredChecks.size

    override fun toString(): String =
        "ProductionRoomIntegrationAttestation(state=$state, reason=$reason, " +
            "checks=$observedCheckCount/$requiredCheckCount, ids=<redacted>)"

    companion object {
        internal fun computeDigest(
            state: ProductionRoomIntegrationState,
            reason: ProductionRoomIntegrationReason,
            observedChecks: Set<ProductionRoomIntegrationCheck>,
        ): String = EvalDigest.sha256(
            domain = "p5-room-integration-attestation-v1",
            fields = listOf(
                FrozenProductionRoomIntegrationContractV1.CONTRACT_ID,
                FrozenProductionRoomIntegrationContractV1.fixtureDigestSha256,
                state.name,
                reason.name,
                *observedChecks.sortedBy { it.name }.map { it.name }.toTypedArray(),
            ),
        )
    }
}

/** Factory deliberately accepts only closed checks/reasons and cannot retain failure text. */
object ProductionRoomIntegrationAttestationFactory {
    fun passed(
        observedChecks: Set<ProductionRoomIntegrationCheck>,
    ): ProductionRoomIntegrationAttestation = create(
        state = ProductionRoomIntegrationState.PASSED,
        reason = ProductionRoomIntegrationReason.ALL_REQUIRED_PRODUCTION_BOUNDARIES_OBSERVED,
        observedChecks = observedChecks,
    )

    fun abstained(
        reason: ProductionRoomIntegrationReason,
        observedChecks: Set<ProductionRoomIntegrationCheck> = emptySet(),
    ): ProductionRoomIntegrationAttestation {
        require(reason !in setOf(
            ProductionRoomIntegrationReason.ALL_REQUIRED_PRODUCTION_BOUNDARIES_OBSERVED,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        ))
        return create(ProductionRoomIntegrationState.ABSTAINED, reason, observedChecks)
    }

    fun rejected(
        reason: ProductionRoomIntegrationReason,
        observedChecks: Set<ProductionRoomIntegrationCheck> = emptySet(),
    ): ProductionRoomIntegrationAttestation {
        require(reason in setOf(
            ProductionRoomIntegrationReason.EXACT_IDENTITY_OR_AUTHORITY_MISMATCH,
            ProductionRoomIntegrationReason.DURABLE_STATE_INVARIANT_VIOLATION,
        ))
        return create(ProductionRoomIntegrationState.REJECTED, reason, observedChecks)
    }

    private fun create(
        state: ProductionRoomIntegrationState,
        reason: ProductionRoomIntegrationReason,
        observedChecks: Set<ProductionRoomIntegrationCheck>,
    ) = ProductionRoomIntegrationAttestation(
        schemaVersion = 1,
        contractId = FrozenProductionRoomIntegrationContractV1.CONTRACT_ID,
        fixtureDigestSha256 = FrozenProductionRoomIntegrationContractV1.fixtureDigestSha256,
        state = state,
        reason = reason,
        observedChecks = observedChecks.toSet(),
        attestationDigestSha256 = ProductionRoomIntegrationAttestation.computeDigest(
            state,
            reason,
            observedChecks,
        ),
    )
}

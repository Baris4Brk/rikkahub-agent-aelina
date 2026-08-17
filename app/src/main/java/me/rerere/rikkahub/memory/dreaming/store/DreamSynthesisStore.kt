package me.rerere.rikkahub.memory.dreaming.store

import me.rerere.rikkahub.memory.dreaming.input.DreamInputBuildRequest
import me.rerere.rikkahub.memory.dreaming.model.DreamAuthorityPin
import me.rerere.rikkahub.memory.dreaming.model.DREAM_AUTHORITY_PIN_ORDER
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisFence
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisMode
import me.rerere.rikkahub.memory.dreaming.model.DreamValidatedPlan
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import me.rerere.rikkahub.memory.dreaming.model.requireDreamLeaseOwner
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamCompiledSnapshot
import me.rerere.rikkahub.memory.dreaming.synthesis.DreamModelAudit
import me.rerere.rikkahub.memory.dreaming.synthesis.MAX_DREAM_PROPOSAL_OPERATIONS
import me.rerere.rikkahub.memory.dreaming.temporal.strictZoneOrNull

data class BeginDreamSynthesisRequest(
    val scopeId: DreamScopeId,
    val runId: String,
    val leaseOwner: String,
    /** Wall-clock time for this lease attempt; it is not the synthesis semantic clock. */
    val attemptNowEpochMs: Long,
    /** Expected run-frozen IANA zone. A resumed run must match its persisted value exactly. */
    val sourceTimezoneId: String,
    val mode: DreamSynthesisMode,
) {
    init {
        requireCanonicalDreamRunId(runId)
        requireDreamLeaseOwner(leaseOwner)
        require(attemptNowEpochMs >= 0L)
        require(strictZoneOrNull(sourceTimezoneId) != null) { "sourceTimezoneId must be a strict IANA zone" }
    }
}

sealed interface BeginDreamSynthesisResult {
    data class Ready(val fence: DreamSynthesisFence) : BeginDreamSynthesisResult
    data class Terminal(val succeeded: Boolean) : BeginDreamSynthesisResult
    data class Rejected(val reason: DreamSynthesisStoreRejection) : BeginDreamSynthesisResult
}

sealed interface ReadDreamInputSeedResult {
    data class Ready(val request: DreamInputBuildRequest) : ReadDreamInputSeedResult
    data class Rejected(val reason: DreamSynthesisStoreRejection) : ReadDreamInputSeedResult
}

sealed interface DreamSynthesisStoreResult {
    data object Accepted : DreamSynthesisStoreResult
    data class Rejected(val reason: DreamSynthesisStoreRejection) : DreamSynthesisStoreResult
}

/**
 * Durable pre-dispatch marker. Once this transaction commits, a process death leaves provider
 * spend indeterminate, so the run must count against the daily provider-run budget even if the
 * provider never returns an audit payload.
 */
data class DreamProviderDispatchRequest(
    val fence: DreamSynthesisFence,
    val promptContractVersion: String,
    val validatorVersion: String,
    val inputMemoryCount: Int,
    val inputManifestHash: DreamSha256,
    val markedAtEpochMs: Long,
) {
    init {
        require(promptContractVersion.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
        require(validatorVersion.matches(Regex("^[A-Za-z0-9._-]{1,64}$")))
        require(inputMemoryCount in 0..1_024)
        require(markedAtEpochMs >= fence.frozenNowEpochMs)
    }
}

data class DreamSynthesisCommitRequest(
    val fence: DreamSynthesisFence,
    val plan: DreamValidatedPlan,
    val snapshot: DreamCompiledSnapshot,
    /**
     * Pins that must pass the complete live authority gate in the commit transaction. This is the
     * exact union of every resulting ACTIVE Claim source and every model-operation evidence pin.
     */
    val liveAuthorityPins: List<DreamAuthorityPin>,
    /**
     * Old provenance copied into immutable transition history. Implementations may verify
     * referential existence, but must not apply current-head, lifecycle, truth, expiry, or
     * tombstone gates to these pins. A pin also present in [liveAuthorityPins] belongs only there.
     */
    val historicalTransitionPins: List<DreamAuthorityPin>,
    val inputManifestHash: DreamSha256,
    val outputManifestHash: DreamSha256,
    val modelAudit: DreamModelAudit,
    /** Actual number of Memory objects serialized into the model request. */
    val inputMemoryCount: Int,
    /** Actual number of operations parsed from the model's output, including NO_OP. */
    val outputOperationCount: Int,
    val committedAtEpochMs: Long,
) {
    init {
        require(plan.fence == fence)
        require(committedAtEpochMs >= fence.frozenNowEpochMs)
        require(inputMemoryCount in 0..1_024)
        require(outputOperationCount in 1..MAX_DREAM_PROPOSAL_OPERATIONS)
        require(liveAuthorityPins.all { it.scopeId == fence.scopeId })
        require(historicalTransitionPins.all { it.scopeId == fence.scopeId })
        require(liveAuthorityPins == liveAuthorityPins.distinct().sortedWith(DREAM_AUTHORITY_PIN_ORDER)) {
            "Live authority pins must be unique and canonically ordered"
        }
        require(
            historicalTransitionPins ==
                historicalTransitionPins.distinct().sortedWith(DREAM_AUTHORITY_PIN_ORDER),
        ) {
            "Historical transition pins must be unique and canonically ordered"
        }
        require(liveAuthorityPins.intersect(historicalTransitionPins.toSet()).isEmpty()) {
            "A transition pin promoted to a live gate must not also be classified as historical"
        }
        val expectedLivePins = (
            plan.resultingClaims
                .filter { it.state == DreamClaimState.ACTIVE_CONTEXTUAL }
                .flatMap { it.sources.filter { source -> source.directAuthority }.map { source -> source.authority } } +
                plan.modelEvidencePins
            ).distinct()
            .sortedWith(DREAM_AUTHORITY_PIN_ORDER)
        val expectedHistoricalPins = plan.transitions
            .flatMap { transition ->
                transition.nextVersion.sources
                    .filter { source -> source.directAuthority }
                    .map { source -> source.authority }
            }
            .distinct()
            .filterNot(expectedLivePins.toSet()::contains)
            .sortedWith(DREAM_AUTHORITY_PIN_ORDER)
        require(liveAuthorityPins == expectedLivePins) {
            "Live commit pins must exactly cover active Claims and model evidence"
        }
        require(historicalTransitionPins == expectedHistoricalPins) {
            "Historical commit pins must exactly cover transition-only provenance"
        }
    }
}

sealed interface DreamSynthesisCommitResult {
    data class Committed(
        val snapshotId: String,
        val committedDreamRevision: Long,
    ) : DreamSynthesisCommitResult {
        init {
            me.rerere.rikkahub.memory.dreaming.model.requireDreamStableId(snapshotId)
            require(committedDreamRevision > 0L)
        }
    }

    data class Rejected(val reason: DreamSynthesisCommitRejection) : DreamSynthesisCommitResult
}

enum class DreamSynthesisCommitRejection {
    FENCE_CONFLICT,
    MEMORY_EPOCH_CONFLICT,
    DREAM_REVISION_CONFLICT,
    ACTIVE_SNAPSHOT_CONFLICT,
    LEASE_MISSING,
    LEASE_OWNER_MISMATCH,
    LEASE_EXPIRED,
    RUN_NOT_RUNNING,
    EVIDENCE_SCOPE_MISMATCH,
    EVIDENCE_REVISION_MISMATCH,
    EVIDENCE_FINGERPRINT_MISMATCH,
    EVIDENCE_SOURCE_MANIFEST_MISMATCH,
    EVIDENCE_TOMBSTONED,
    CLAIM_REVISION_CONFLICT,
    STORE_CORRUPTION,
}

enum class DreamSynthesisStoreRejection {
    FEATURE_DISABLED,
    RUN_NOT_FOUND,
    RUN_NOT_RUNNING,
    SCOPE_MISMATCH,
    OWNER_MISMATCH,
    LEASE_EXPIRED,
    FENCE_CONFLICT,
    STORE_CORRUPTION,
}

enum class DreamSynthesisFailure {
    INPUT_REJECTED,
    MODEL_PERMANENT_FAILURE,
    MODEL_UNAVAILABLE,
    MODEL_PROVIDER_UNAVAILABLE,
    MODEL_TIMEOUT,
    MODEL_CANCELLED_BY_PROVIDER,
    MODEL_OUTPUT_LIMIT,
    MODEL_SAFETY_REJECTION,
    MODEL_INVALID_CONFIGURATION,
    MODEL_AUDIT_MISMATCH,
    MODEL_OUTPUT_PARSE_REJECTED,
    MODEL_OUTPUT_VALIDATION_REJECTED,
    SNAPSHOT_COMPILATION_FAILED,
    STORE_FAILURE,
}

/** Stable, allow-listed diagnostic mapping. No model or user text is ever persisted as a code. */
fun DreamSynthesisFailure.toRunFailureCode(): DreamRunFailureCode = when (this) {
    DreamSynthesisFailure.INPUT_REJECTED -> DreamRunFailureCode.INPUT_REJECTED
    DreamSynthesisFailure.MODEL_PERMANENT_FAILURE -> DreamRunFailureCode.MODEL_PERMANENT_FAILURE
    DreamSynthesisFailure.MODEL_UNAVAILABLE -> DreamRunFailureCode.MODEL_UNAVAILABLE
    DreamSynthesisFailure.MODEL_PROVIDER_UNAVAILABLE -> DreamRunFailureCode.MODEL_PROVIDER_UNAVAILABLE
    DreamSynthesisFailure.MODEL_TIMEOUT -> DreamRunFailureCode.MODEL_TIMEOUT
    DreamSynthesisFailure.MODEL_CANCELLED_BY_PROVIDER -> DreamRunFailureCode.MODEL_CANCELLED_BY_PROVIDER
    DreamSynthesisFailure.MODEL_OUTPUT_LIMIT -> DreamRunFailureCode.MODEL_OUTPUT_LIMIT
    DreamSynthesisFailure.MODEL_SAFETY_REJECTION -> DreamRunFailureCode.MODEL_SAFETY_REJECTION
    DreamSynthesisFailure.MODEL_INVALID_CONFIGURATION -> DreamRunFailureCode.MODEL_INVALID_CONFIGURATION
    DreamSynthesisFailure.MODEL_AUDIT_MISMATCH -> DreamRunFailureCode.MODEL_AUDIT_MISMATCH
    DreamSynthesisFailure.MODEL_OUTPUT_PARSE_REJECTED -> DreamRunFailureCode.MODEL_OUTPUT_PARSE_REJECTED
    DreamSynthesisFailure.MODEL_OUTPUT_VALIDATION_REJECTED ->
        DreamRunFailureCode.MODEL_OUTPUT_VALIDATION_REJECTED
    DreamSynthesisFailure.SNAPSHOT_COMPILATION_FAILED -> DreamRunFailureCode.SNAPSHOT_COMPILATION_FAILED
    DreamSynthesisFailure.STORE_FAILURE -> DreamRunFailureCode.STORE_FAILURE
}

/**
 * Every method is a complete short transaction boundary. Implementations must never retain a Room
 * transaction after returning [ReadDreamInputSeedResult.Ready]. The model is therefore necessarily
 * invoked outside all DB transactions.
 */
interface DreamSynthesisStore {
    suspend fun begin(request: BeginDreamSynthesisRequest): BeginDreamSynthesisResult

    /**
     * Reads the frozen seed while checking lease ownership against the operational attempt clock.
     * Semantic/temporal decisions inside the returned seed still use [DreamSynthesisFence.frozenNowEpochMs].
     */
    suspend fun readInputSeed(
        fence: DreamSynthesisFence,
        attemptNowEpochMs: Long,
    ): ReadDreamInputSeedResult

    suspend fun heartbeat(
        fence: DreamSynthesisFence,
        nowMs: Long,
        leaseDurationMs: Long,
    ): DreamSynthesisStoreResult

    /**
     * Persist the exact model-input identity immediately before the provider call. This is the
     * durable accounting boundary used by the daily Dream budget.
     */
    suspend fun markProviderDispatch(request: DreamProviderDispatchRequest): DreamSynthesisStoreResult

    /** One transaction: verify every fence/pin, write versions+sources+snapshot, then advance both CAS values. */
    suspend fun commit(request: DreamSynthesisCommitRequest): DreamSynthesisCommitResult

    /** Independent transaction after a rolled-back [commit]. Never called from inside that transaction. */
    suspend fun terminalizeConflict(
        fence: DreamSynthesisFence,
        reason: DreamSynthesisCommitRejection,
        nowMs: Long,
    ): DreamSynthesisStoreResult

    suspend fun fail(
        fence: DreamSynthesisFence,
        failure: DreamSynthesisFailure,
        nowMs: Long,
    ): DreamSynthesisStoreResult
}

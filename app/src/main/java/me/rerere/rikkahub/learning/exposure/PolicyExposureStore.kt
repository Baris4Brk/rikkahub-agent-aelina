package me.rerere.rikkahub.learning.exposure

import me.rerere.rikkahub.data.ai.ProviderAttemptEvent
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind

private val EXPOSURE_SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val EXPOSURE_CODE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")

/** Non-secret identities frozen before a Policy-bearing provider attempt is reserved. */
data class PolicyExposureMetadata(
    val replayGeneration: Long,
    val scope: LearningScope,
    val taskSignature: String,
    val treatmentArm: String,
    val modelIdentity: String,
    val providerIdentity: String,
    val providerGeneration: Long,
    val toolsetFingerprint: String,
    val contextCompilerAbi: String,
) {
    init {
        require(replayGeneration >= 0L) { "Negative exposure replay generation" }
        require(taskSignature.matches(EXPOSURE_CODE_PATTERN)) { "Invalid exposure task signature" }
        require(treatmentArm.matches(EXPOSURE_CODE_PATTERN)) { "Invalid exposure treatment arm" }
        require(modelIdentity.matches(EXPOSURE_CODE_PATTERN)) { "Invalid exposure model identity" }
        require(providerIdentity.matches(EXPOSURE_CODE_PATTERN)) { "Invalid exposure provider identity" }
        require(providerGeneration >= 0L) { "Negative exposure provider generation" }
        require(toolsetFingerprint.matches(EXPOSURE_SHA256_PATTERN)) {
            "Invalid exposure toolset fingerprint"
        }
        require(contextCompilerAbi.matches(EXPOSURE_CODE_PATTERN)) {
            "Invalid exposure context compiler ABI"
        }
    }

    override fun toString(): String =
        "PolicyExposureMetadata(replay=$replayGeneration, scope=${scope.kind}, " +
            "providerGeneration=$providerGeneration, ids=<redacted>)"
}

/** Exact authoritative object that committed the terminal Conversation/Command outcome. */
data class PolicyExposureOutcomeAuthority(
    val sourceKind: LearningSourceKind,
    val sourceId: String,
    val sourceRevision: Long,
) {
    init {
        require(
            sourceKind == LearningSourceKind.COMMAND ||
                sourceKind == LearningSourceKind.CONVERSATION_MESSAGE
        ) { "Unsupported exposure outcome authority type" }
        require(sourceId.matches(EXPOSURE_CODE_PATTERN)) {
            "Invalid exposure outcome authority ID"
        }
        require(sourceRevision > 0L) { "Invalid exposure outcome authority revision" }
    }

    override fun toString(): String =
        "PolicyExposureOutcomeAuthority(kind=$sourceKind, revision=$sourceRevision, id=<redacted>)"
}

enum class PolicyExposureWriteDisposition {
    APPLIED,
    DUPLICATE,
}

enum class PolicyExposureStoreConflict {
    EPISODE_NOT_FOUND,
    EPISODE_IDENTITY_MISMATCH,
    EPISODE_NOT_ELIGIBLE,
    RESERVATION_CONFLICT,
    ITEM_CONFLICT,
    CORRUPT_SNAPSHOT,
    CLOCK_ROLLBACK,
    STATE_VERSION_MISMATCH,
    INVALID_TRANSITION,
    ATTEMPT_ORDINAL_MISMATCH,
    OUTCOME_NOT_ELIGIBLE,
    OUTCOME_AUTHORITY_MISMATCH,
    DROP_OBSERVATION_CONFLICT,
    CAS_LOST,
}

enum class PolicyExposureStoreUnavailable {
    DATABASE_UNAVAILABLE,
    STORAGE_FAILURE,
}

/**
 * Facade-neutral result. Storage failures fail attribution closed without leaking Room/DAO types
 * or throwing through the provider callback boundary.
 */
sealed interface PolicyExposureStoreResult {
    data class Available(
        val receipt: PolicyExposureReceipt,
        val disposition: PolicyExposureWriteDisposition = PolicyExposureWriteDisposition.APPLIED,
    ) : PolicyExposureStoreResult

    data class Conflict(
        val reason: PolicyExposureStoreConflict,
        val currentReceipt: PolicyExposureReceipt? = null,
    ) : PolicyExposureStoreResult

    data class Unavailable(
        val reason: PolicyExposureStoreUnavailable,
    ) : PolicyExposureStoreResult
}

/** Capability needed before choosing the Policy-bearing request over the baseline request. */
fun interface PolicyExposureReservationPort {
    suspend fun reserve(
        reservation: PolicyExposureReservation,
        metadata: PolicyExposureMetadata,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult
}

/** Capability used by compilation and provider-attempt observations after a reservation exists. */
interface PolicyExposureMutationPort {
    /**
     * Records why every item in an observation-only reservation was excluded before injection.
     * This never manufactures INJECTED/HOST_DISPATCHED or usage attribution.
     */
    suspend fun recordDrops(
        reservationId: String,
        expectedStateVersion: Long,
        reasonByPolicyId: Map<String, String>,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult

    suspend fun observeMilestone(
        reservationId: String,
        expectedStateVersion: Long,
        state: PolicyExposureState,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult

    suspend fun observeProviderAttempt(
        reservationId: String,
        expectedStateVersion: Long,
        event: ProviderAttemptEvent,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult

    suspend fun linkOutcome(
        reservationId: String,
        expectedStateVersion: Long,
        authority: PolicyExposureOutcomeAuthority,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult

    /** Rehydrates the exact process-death snapshot. This does not mutate usage counters. */
    suspend fun load(reservationId: String): PolicyExposureStoreResult
}

interface PolicyExposureStore : PolicyExposureReservationPort, PolicyExposureMutationPort

/**
 * Deliberately separate ownership boundary: exposure milestones never update Policy usage_count or
 * last_used. A later cross-Policy outcome commit owns that aggregate mutation atomically.
 */
fun interface PolicyExposureUsageCommitPort {
    suspend fun commitLinkedUsage(receipt: PolicyExposureReceipt): Boolean
}

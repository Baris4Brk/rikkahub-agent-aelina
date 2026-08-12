package me.rerere.rikkahub.memory.dreaming.temporal

import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId

/** Minimal authority states accepted by the future derived-memory source gate. */
enum class TemporalSourceLifecycle {
    ACTIVE,
    ARCHIVED,
    STALE,
    DELETED,
    UNKNOWN,
}

enum class TemporalSourceTruth {
    CONFIRMED,
    DISPUTED,
    SUPERSEDED,
    UNKNOWN,
}

enum class TemporalSourceInvalidReason {
    INVALID_TIMEZONE,
    SCOPE_MISMATCH,
    REVISION_MISMATCH,
    HASH_MISMATCH,
    SOURCE_TOMBSTONED,
    LIFECYCLE_NOT_ACTIVE,
    TRUTH_NOT_CONFIRMED,
    MEMORY_EXPIRED,
}

/**
 * Pure snapshot used by tests and future M4 validation. It deliberately contains no Room type.
 * IDs and hashes are compared exactly and are never normalized across an authority boundary.
 */
data class TemporalSourceValidityRequest(
    val expectedScopeId: DreamScopeId,
    val actualScopeId: DreamScopeId,
    val expectedRevision: Long,
    val actualRevision: Long,
    val expectedContentHash: String,
    val actualContentHash: String,
    val lifecycle: TemporalSourceLifecycle,
    val truth: TemporalSourceTruth,
    val expiresAtEpochMs: Long?,
    val sourceTombstoned: Boolean,
    val frozenNowEpochMs: Long,
    val sourceTimestampEpochMs: Long?,
    val timezoneId: String,
)

data class TemporalSourceValidityResult(
    val isUsable: Boolean,
    /** Stable enum order; no source text or identifier is copied into diagnostics. */
    val reasons: List<TemporalSourceInvalidReason>,
)

object TemporalSourceValidityGate {
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    fun evaluate(request: TemporalSourceValidityRequest): TemporalSourceValidityResult {
        val reasons = buildList {
            if (strictZoneOrNull(request.timezoneId) == null) {
                add(TemporalSourceInvalidReason.INVALID_TIMEZONE)
            }
            if (request.expectedScopeId != request.actualScopeId) {
                add(TemporalSourceInvalidReason.SCOPE_MISMATCH)
            }
            if (
                request.expectedRevision <= 0L ||
                request.actualRevision <= 0L ||
                request.expectedRevision != request.actualRevision
            ) {
                add(TemporalSourceInvalidReason.REVISION_MISMATCH)
            }
            if (
                !sha256Pattern.matches(request.expectedContentHash) ||
                !sha256Pattern.matches(request.actualContentHash) ||
                request.expectedContentHash != request.actualContentHash
            ) {
                add(TemporalSourceInvalidReason.HASH_MISMATCH)
            }
            if (request.sourceTombstoned) {
                add(TemporalSourceInvalidReason.SOURCE_TOMBSTONED)
            }
            if (request.lifecycle != TemporalSourceLifecycle.ACTIVE) {
                add(TemporalSourceInvalidReason.LIFECYCLE_NOT_ACTIVE)
            }
            if (request.truth != TemporalSourceTruth.CONFIRMED) {
                add(TemporalSourceInvalidReason.TRUTH_NOT_CONFIRMED)
            }
            if (
                request.expiresAtEpochMs != null &&
                request.expiresAtEpochMs <= request.frozenNowEpochMs
            ) {
                add(TemporalSourceInvalidReason.MEMORY_EXPIRED)
            }
        }
        return TemporalSourceValidityResult(
            isUsable = reasons.isEmpty(),
            reasons = reasons,
        )
    }
}

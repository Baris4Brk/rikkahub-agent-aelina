package me.rerere.rikkahub.memory.dreaming.runtime

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.memory.dreaming.model.DreamCanonicalJson
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.model.canonicalMapOf
import me.rerere.rikkahub.memory.dreaming.model.requireDreamStableId
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotSection
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState

const val DREAM_RUNTIME_COMPILER_REVISION = "dream-runtime-context-v1"
const val MAX_DREAM_RUNTIME_PROJECTION_CLAIMS = 1_024

/** Runtime-only identity. It may be used for usage bookkeeping, but must not enter persisted traces. */
data class DreamRuntimeClaimRef(
    val claimId: String,
    val claimRevision: Long,
) {
    init {
        requireDreamStableId(claimId)
        require(claimRevision > 0L)
    }
}

enum class DreamRuntimeSnapshotStatus {
    ACTIVE,
    SUPERSEDED,
    STALE,
    TOMBSTONED,
    UNKNOWN,
}

enum class DreamRuntimePayloadIntegrity {
    VERIFIED,
    MISMATCH,
    UNKNOWN,
}

enum class DreamRuntimeFragmentIntegrity {
    VERIFIED,
    MISMATCH,
    UNKNOWN,
}

/**
 * The projection reader must produce [ATOMIC] only when the scope state, active snapshot and
 * current Claim heads were read under one database snapshot/transaction.
 */
enum class DreamRuntimeReadConsistency {
    ATOMIC,
    UNKNOWN,
}

/** Aggregate result of revalidating every pinned Memory revision behind one Claim. */
enum class DreamRuntimeSourceValidity {
    CURRENT_CONFIRMED,
    MISSING,
    SCOPE_MISMATCH,
    REVISION_MISMATCH,
    FINGERPRINT_MISMATCH,
    SOURCE_MANIFEST_MISMATCH,
    LIFECYCLE_INVALID,
    TRUTH_INVALID,
    EXPIRED,
    TOMBSTONED,
    UNKNOWN,
}

data class DreamRuntimeSourceFence(
    val validity: DreamRuntimeSourceValidity,
    /** Must equal the generation's frozen clock exactly. */
    val validatedAtEpochMs: Long,
    /** Exact immutable Claim revision whose evidence was checked. */
    val validatedClaimRevision: Long,
    val directAuthoritySourceCount: Int,
    /** Direct SUPPORTS/SUPERSEDES pins; CONTEXT/CONTRADICTS alone can never justify injection. */
    val directSupportingSourceCount: Int,
    /** Any non-zero value is a forbidden Claim-to-Claim/multi-hop dependency in V1. */
    val indirectDerivedSourceCount: Int,
)

/**
 * One immutable Snapshot manifest member plus its live Claim/source projection.
 *
 * Text comes from the immutable Claim version referenced by [ref]. [currentState] and
 * [currentRevision] come from the live Claim head and intentionally remain separate so an old
 * Snapshot cannot make a rejected or superseded Claim look current.
 */
data class DreamRuntimeClaimProjection(
    val ref: DreamRuntimeClaimRef,
    val scopeId: DreamScopeId,
    val section: DreamSnapshotSection,
    val ordinal: Int,
    val snapshotState: DreamClaimState,
    val currentState: DreamClaimState?,
    val currentRevision: Long?,
    val currentVersionHash: DreamSha256?,
    val storageClass: DreamStorageClass,
    val epistemicType: DreamEpistemicType,
    val title: String,
    val statement: String,
    val confidencePermille: Int,
    val temporalState: TemporalState,
    val validFromEpochMs: Long?,
    val validToEpochMs: Long?,
    val versionHash: DreamSha256,
    val fragmentIntegrity: DreamRuntimeFragmentIntegrity,
    val sourceFence: DreamRuntimeSourceFence,
)

enum class DreamSnapshotProjectionUnavailableReason {
    FEATURE_NOT_READY,
    SCOPE_STATE_MISSING,
    ACTIVE_SNAPSHOT_MISSING,
    SNAPSHOT_ROW_MISSING,
    PAYLOAD_PARSE_FAILED,
    PAYLOAD_HASH_INVALID,
    MANIFEST_INVALID,
    CLAIM_VERSION_MISSING,
    DATABASE_READ_FAILED,
    UNKNOWN_SCHEMA,
    UNKNOWN,
}

/**
 * Fail-closed storage boundary. Corrupt/missing data is represented as [Unavailable], never as an
 * empty-but-valid Snapshot. No raw database exception or identifier belongs in [Unavailable].
 */
sealed interface DreamSnapshotProjection {
    data class Available(
        val scopeId: DreamScopeId,
        val schemaVersion: Int,
        val snapshotId: String,
        val activeSnapshotId: String?,
        val snapshotStatus: DreamRuntimeSnapshotStatus,
        val snapshotRevision: Long,
        val sourceMemoryEpoch: Long,
        val currentMemoryEpoch: Long,
        val committedDreamRevision: Long,
        val currentDreamRevision: Long,
        val payloadHash: DreamSha256,
        val payloadIntegrity: DreamRuntimePayloadIntegrity,
        val snapshotCompilerRevision: String,
        val expectedClaimCount: Int,
        val readConsistency: DreamRuntimeReadConsistency,
        val claims: List<DreamRuntimeClaimProjection>,
    ) : DreamSnapshotProjection

    data class Unavailable(
        val reason: DreamSnapshotProjectionUnavailableReason,
    ) : DreamSnapshotProjection
}

enum class DreamRuntimeFenceFailure {
    PROJECTION_UNAVAILABLE,
    READ_NOT_ATOMIC,
    SCOPE_MISMATCH,
    SCHEMA_UNSUPPORTED,
    SNAPSHOT_ID_INVALID,
    SNAPSHOT_NOT_ACTIVE,
    ACTIVE_POINTER_MISMATCH,
    EPOCH_VALUE_INVALID,
    MEMORY_EPOCH_MISMATCH,
    DREAM_REVISION_MISMATCH,
    SNAPSHOT_REVISION_MISMATCH,
    PAYLOAD_INTEGRITY_FAILED,
    COMPILER_REVISION_INVALID,
    CLAIM_COUNT_INVALID,
    CLAIM_SCOPE_MISMATCH,
    DUPLICATE_CLAIM_REF,
    MANIFEST_ORDINAL_INVALID,
    CLAIM_FRAGMENT_INTEGRITY_FAILED,
}

sealed interface DreamRuntimeFenceResult {
    data class Valid(
        val projection: DreamSnapshotProjection.Available,
    ) : DreamRuntimeFenceResult

    data class Invalid(
        val failures: List<DreamRuntimeFenceFailure>,
        val unavailableReason: DreamSnapshotProjectionUnavailableReason? = null,
    ) : DreamRuntimeFenceResult {
        init {
            require(failures.isNotEmpty())
            require(failures == failures.distinct())
            require(
                (DreamRuntimeFenceFailure.PROJECTION_UNAVAILABLE in failures) ==
                    (unavailableReason != null),
            )
        }
    }
}

sealed interface DreamRuntimeRanking {
    data object SnapshotOrder : DreamRuntimeRanking

    /** A query ranker may select a strict subset, but every ref must belong to this Snapshot. */
    data class Explicit(
        val refs: List<DreamRuntimeClaimRef>,
    ) : DreamRuntimeRanking
}

data class DreamRuntimeSelectionRequest(
    val fence: DreamRuntimeFenceResult.Valid,
    val frozenNowEpochMs: Long,
    val ranking: DreamRuntimeRanking = DreamRuntimeRanking.SnapshotOrder,
)

enum class DreamRuntimeDropReason {
    CLAIM_SCOPE_MISMATCH,
    CLAIM_HEAD_MISSING,
    CLAIM_REJECTED,
    CLAIM_STALE,
    CLAIM_NOT_ACTIVE,
    CLAIM_REVISION_CHANGED,
    CLAIM_VERSION_HASH_CHANGED,
    SNAPSHOT_CLAIM_NOT_ACTIVE,
    DERIVED_PREFERENCE_EXCLUDED,
    BELIEF_EXCLUDED,
    PROFILE_STORAGE_EXCLUDED,
    EPISTEMIC_TYPE_UNSUPPORTED,
    SECTION_NOT_ALLOWED,
    SECTION_TYPE_MISMATCH,
    SOURCE_CHECK_TIME_MISMATCH,
    SOURCE_CHECK_REVISION_MISMATCH,
    NO_DIRECT_AUTHORITY_SOURCE,
    NO_DIRECT_SUPPORTING_SOURCE,
    MULTIHOP_SOURCE_EXCLUDED,
    SOURCE_NOT_CURRENT,
    TEMPORAL_STATE_NOT_CURRENT,
    NOT_YET_VALID,
    EXPIRED,
    INVALID_TIME_WINDOW,
    INVALID_UNICODE,
    CONTROL_CHARACTER_EXCLUDED,
    CLAIM_FIELD_OUT_OF_BOUNDS,
    NOT_SELECTED_BY_RANKER,
    CLAIM_LIMIT_EXCEEDED,
    CHAR_BUDGET_EXCEEDED,
    UTF8_BUDGET_EXCEEDED,
    TOKEN_BUDGET_EXCEEDED,
    TOKEN_ESTIMATOR_FAILED,
}

/** Runtime-only ref; persist only aggregate [reason] counts. */
data class DreamRuntimeClaimDrop(
    val ref: DreamRuntimeClaimRef,
    val reason: DreamRuntimeDropReason,
)

data class DreamRuntimeSelection(
    val claims: List<DreamRuntimeClaimProjection>,
    val dropped: List<DreamRuntimeClaimDrop>,
)

sealed interface DreamRuntimeSelectionResult {
    data class Selected(
        val selection: DreamRuntimeSelection,
    ) : DreamRuntimeSelectionResult

    data class Invalid(
        val failures: List<DreamRuntimeRequestFailure>,
    ) : DreamRuntimeSelectionResult {
        init {
            require(failures.isNotEmpty())
            require(failures == failures.distinct())
        }
    }
}

fun interface DreamRuntimeTokenEstimator {
    fun estimate(text: String): Int

    companion object {
        /** Byte-count is deliberately conservative; provider-specific integration should inject its estimator. */
        val ConservativeUtf8: DreamRuntimeTokenEstimator = DreamRuntimeTokenEstimator { text ->
            text.toByteArray(StandardCharsets.UTF_8).size
        }
    }
}

data class DreamRuntimeCompileLimits(
    val maxTokens: Int,
    val maxChars: Int,
    val maxUtf8Bytes: Int,
    val maxClaims: Int,
)

data class DreamContextCompileRequest(
    val useDreams: Boolean,
    val expectedScopeId: DreamScopeId,
    val projection: DreamSnapshotProjection,
    val frozenNowEpochMs: Long,
    val limits: DreamRuntimeCompileLimits,
    val ranking: DreamRuntimeRanking = DreamRuntimeRanking.SnapshotOrder,
    val tokenEstimator: DreamRuntimeTokenEstimator = DreamRuntimeTokenEstimator.ConservativeUtf8,
)

enum class DreamRuntimeCompileStatus {
    DISABLED,
    SNAPSHOT_REJECTED,
    INVALID_REQUEST,
    TOKEN_ESTIMATOR_FAILED,
    EMPTY,
    COMPILED,
}

enum class DreamRuntimeRequestFailure {
    INVALID_FROZEN_NOW,
    INVALID_TOKEN_BUDGET,
    INVALID_CHAR_BOUND,
    INVALID_UTF8_BOUND,
    INVALID_CLAIM_BOUND,
    DUPLICATE_RANK_REFERENCE,
    UNKNOWN_RANK_REFERENCE,
    TOKEN_ESTIMATOR_FAILED,
    FINAL_HARD_BOUND_VIOLATION,
}

enum class DreamRuntimeHardBoundStatus {
    NO_SECTION,
    SATISFIED,
    REQUEST_REJECTED,
    ESTIMATOR_FAILED,
}

data class DreamCacheClaimDigestComponent(
    val claimRevision: Long,
    val versionHash: DreamSha256,
    val section: DreamSnapshotSection,
    val ordinal: Int,
)

/**
 * Canonical cache material contains content hashes and revisions only: never scope, Snapshot ID,
 * Claim ID, title or statement. Scope remains a separate required cache identity dimension.
 */
data class DreamCacheProjectionDigestInput(
    val snapshotSchemaVersion: Int,
    val snapshotPayloadHash: DreamSha256,
    val snapshotRevision: Long,
    val sourceMemoryEpoch: Long,
    val committedDreamRevision: Long,
    val snapshotCompilerRevision: String,
    val runtimeCompilerRevision: String,
    val actualClaims: List<DreamCacheClaimDigestComponent>,
    val renderedSectionHash: DreamSha256,
) {
    fun canonicalJson(): String = DreamCanonicalJson.encode(
        JsonObject(
            canonicalMapOf(
                "actual_claims" to JsonArray(
                    actualClaims.map { claim ->
                        JsonObject(
                            canonicalMapOf(
                                "claim_revision" to JsonPrimitive(claim.claimRevision),
                                "ordinal" to JsonPrimitive(claim.ordinal),
                                "section" to JsonPrimitive(claim.section.wireName),
                                "version_hash" to JsonPrimitive(claim.versionHash.value),
                            ),
                        )
                    },
                ),
                "committed_dream_revision" to JsonPrimitive(committedDreamRevision),
                "rendered_section_hash" to JsonPrimitive(renderedSectionHash.value),
                "runtime_compiler_revision" to JsonPrimitive(runtimeCompilerRevision),
                "snapshot_compiler_revision" to JsonPrimitive(snapshotCompilerRevision),
                "snapshot_payload_hash" to JsonPrimitive(snapshotPayloadHash.value),
                "snapshot_revision" to JsonPrimitive(snapshotRevision),
                "snapshot_schema_version" to JsonPrimitive(snapshotSchemaVersion),
                "source_memory_epoch" to JsonPrimitive(sourceMemoryEpoch),
            ),
        ),
    )
}

data class DreamContextCompileResult(
    val status: DreamRuntimeCompileStatus,
    val renderedSection: String,
    val actualClaimRefs: List<DreamRuntimeClaimRef>,
    val dropped: List<DreamRuntimeClaimDrop>,
    val fenceFailures: List<DreamRuntimeFenceFailure>,
    val projectionUnavailableReason: DreamSnapshotProjectionUnavailableReason?,
    val requestFailures: List<DreamRuntimeRequestFailure>,
    val compilerRevision: String,
    val estimatedTokens: Int,
    val hardBoundStatus: DreamRuntimeHardBoundStatus,
    val cacheProjectionDigestInput: DreamCacheProjectionDigestInput?,
) {
    val actualClaimCount: Int
        get() = actualClaimRefs.size

    init {
        require(estimatedTokens >= 0)
        require(actualClaimRefs == actualClaimRefs.distinct())
        require(dropped.map { it.ref } == dropped.map { it.ref }.distinct())
        require(actualClaimRefs.none { actual -> dropped.any { it.ref == actual } })
        require(fenceFailures == fenceFailures.distinct())
        require(requestFailures == requestFailures.distinct())
        require(
            (DreamRuntimeFenceFailure.PROJECTION_UNAVAILABLE in fenceFailures) ==
                (projectionUnavailableReason != null),
        )
        require(renderedSection.isNotEmpty() == actualClaimRefs.isNotEmpty())
        require((cacheProjectionDigestInput != null) == renderedSection.isNotEmpty())
        if (renderedSection.isEmpty()) {
            require(estimatedTokens == 0)
            require(status != DreamRuntimeCompileStatus.COMPILED)
        } else {
            require(status == DreamRuntimeCompileStatus.COMPILED)
            require(hardBoundStatus == DreamRuntimeHardBoundStatus.SATISFIED)
            val cacheInput = requireNotNull(cacheProjectionDigestInput)
            require(cacheInput.actualClaims.size == actualClaimRefs.size)
            require(
                cacheInput.actualClaims.map { it.claimRevision } ==
                    actualClaimRefs.map { it.claimRevision },
            )
        }
        if (status == DreamRuntimeCompileStatus.SNAPSHOT_REJECTED) {
            require(fenceFailures.isNotEmpty())
        }
        if (status == DreamRuntimeCompileStatus.INVALID_REQUEST ||
            status == DreamRuntimeCompileStatus.TOKEN_ESTIMATOR_FAILED
        ) {
            require(requestFailures.isNotEmpty())
        }
    }
}

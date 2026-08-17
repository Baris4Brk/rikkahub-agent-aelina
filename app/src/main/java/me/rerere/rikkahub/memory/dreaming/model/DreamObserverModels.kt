package me.rerere.rikkahub.memory.dreaming.model

import kotlin.uuid.Uuid
import me.rerere.rikkahub.memory.dreaming.temporal.strictZoneOrNull

const val MAX_DREAM_ENTITY_ID_LENGTH = 512
const val MAX_DREAM_LEASE_OWNER_LENGTH = 128
const val MAX_DREAM_RUN_LEASE_DURATION_MS = 60L * 60_000L

/** M1 ships the schema and observer contract without enabling any generation or runtime behavior. */
data class DreamingFeatureFlags(
    val schemaReady: Boolean = false,
    val generate: Boolean = false,
    val shadow: Boolean = false,
    val use: Boolean = false,
    val deepRebuild: Boolean = false,
    val relationRoute: Boolean = false,
) {
    init {
        require(schemaReady || !(generate || shadow || use || deepRebuild || relationRoute)) {
            "Dreaming behavior cannot be enabled before its schema is ready"
        }
        require(generate || !(shadow || deepRebuild || relationRoute)) {
            "Dream generation must be enabled before generation modes"
        }
    }

    companion object {
        val M1AllOff: DreamingFeatureFlags = DreamingFeatureFlags()
    }
}

enum class AuthorityEntityKind {
    MEMORY,
    LINK,
    EVIDENCE,
    SOURCE,
    SCOPE_PURGE,
}

enum class AuthorityChangeOperation {
    CREATE,
    UPDATE,
    ARCHIVE,
    RESTORE,
    EXPIRE,
    STALE,
    INVALIDATE,
    DELETE,
    SCRUB,
    REVIEW,
}

/** Codes are intentionally metadata-only: never put Memory, message, or user text in this field. */
enum class AuthorityChangeReason {
    EXTRACTION_COMMIT,
    MEMORY_REVIEW,
    RELATION_REVIEW,
    USER_MUTATION,
    LIFECYCLE_CHANGE,
    EXPIRY,
    SOURCE_INVALIDATION,
    ASSISTANT_PURGE,
    SCOPE_PURGE,
    PRIVACY_SCRUB,
    RESTORE_REVISION,
    MAINTENANCE,
    RUN_CLAIMED,
    RUN_HEARTBEAT,
    RUN_FINISHED,
    LEASE_RECOVERED,
    OBSERVER_CHECKPOINT_ADVANCED,
}

enum class DreamRunMode {
    OBSERVER_REPLAY,
    INCREMENTAL,
    FULL,
}

enum class DreamRunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    CONFLICT,
    CANCELLED,
    FAILED,
    DISCARDED,
    ;

    val isTerminal: Boolean
        get() = this != PENDING && this != RUNNING
}

enum class DreamRunFailureCode {
    LEASE_EXPIRED,
    MEMORY_EPOCH_CONFLICT,
    OBSERVER_CHECKPOINT_CONFLICT,
    JOURNAL_GAP,
    OWNER_MISMATCH,
    FEATURE_DISABLED,
    CANCELLED_BY_POLICY,
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

/**
 * One semantic authority change before transaction-local coalescing.
 *
 * The mutation owner must omit actual no-ops. The recorder then keeps only the final input for
 * each `(scope, entityKind, entityId)` key. This gives one net receipt without trying to infer the
 * caller's before-image (for example, whether ARCHIVE then RESTORE restored identical bytes).
 */
data class AuthorityChange(
    val scopeId: DreamScopeId,
    val entityKind: AuthorityEntityKind,
    val entityId: String,
    val entityRevision: Long? = null,
    val operation: AuthorityChangeOperation,
    val reasonCode: AuthorityChangeReason,
) {
    init {
        requireValidAuthorityEntity(entityId, entityRevision)
        require(reasonCode.isAuthorityMutationReason) {
            "Run lifecycle reasons cannot be written to the authority journal"
        }
    }
}

data class AuthorityChangeReceipt(
    val changeId: Long,
    val scopeId: DreamScopeId,
    val memoryEpoch: Long,
    val entityKind: AuthorityEntityKind,
    val entityId: String,
    val entityRevision: Long?,
    val operation: AuthorityChangeOperation,
    val reasonCode: AuthorityChangeReason,
    val createdAtMs: Long,
) {
    init {
        require(changeId > 0L)
        require(memoryEpoch > 0L)
        require(createdAtMs >= 0L)
        requireValidAuthorityEntity(entityId, entityRevision)
        require(reasonCode.isAuthorityMutationReason) {
            "Run lifecycle reasons cannot be restored as authority journal reasons"
        }
    }
}

data class DreamScopeState(
    val scopeId: DreamScopeId,
    val memoryEpoch: Long = 0L,
    val observerCheckpointEpoch: Long = 0L,
    val activeRunId: String? = null,
    val activeRunLeaseUntilMs: Long? = null,
    val updatedAtMs: Long,
    val lastReasonCode: AuthorityChangeReason? = null,
) {
    init {
        require(memoryEpoch >= 0L)
        require(observerCheckpointEpoch in 0L..memoryEpoch)
        require((activeRunId == null) == (activeRunLeaseUntilMs == null)) {
            "Active run ID and lease deadline must be both present or both absent"
        }
        activeRunId?.let(::requireCanonicalDreamRunId)
        require(activeRunLeaseUntilMs == null || activeRunLeaseUntilMs >= 0L)
        require(updatedAtMs >= 0L)
    }
}

data class DreamRun(
    val runId: String,
    val scopeId: DreamScopeId,
    val mode: DreamRunMode,
    val status: DreamRunStatus,
    val baseMemoryEpoch: Long,
    val baseObserverCheckpointEpoch: Long,
    val attempt: Int,
    val leaseOwner: String?,
    val leaseUntilMs: Long?,
    /** Highest journal epoch this run has completely replayed; this is not a phase enum. */
    val checkpointEpoch: Long,
    val failureCode: DreamRunFailureCode?,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val updatedAtMs: Long,
    val finishedAtMs: Long?,
    /** Null for local Observer runs; frozen on first claim for synthesis runs. */
    val sourceTimezoneId: String? = null,
) {
    init {
        requireCanonicalDreamRunId(runId)
        require(baseMemoryEpoch >= 0L)
        require(baseObserverCheckpointEpoch in 0L..baseMemoryEpoch)
        require(checkpointEpoch in baseObserverCheckpointEpoch..baseMemoryEpoch)
        require(attempt >= 0)
        require((leaseOwner == null) == (leaseUntilMs == null))
        leaseOwner?.let(::requireDreamLeaseOwner)
        require(leaseUntilMs == null || leaseUntilMs >= 0L)
        require(createdAtMs >= 0L)
        require(startedAtMs == null || startedAtMs >= createdAtMs)
        require(updatedAtMs >= createdAtMs)
        require(finishedAtMs == null || finishedAtMs >= createdAtMs)
        require(sourceTimezoneId == null || strictZoneOrNull(sourceTimezoneId) != null)
        when (status) {
            DreamRunStatus.PENDING -> {
                require(attempt == 0 && leaseOwner == null && startedAtMs == null)
                require(checkpointEpoch == baseObserverCheckpointEpoch)
                require(finishedAtMs == null && failureCode == null)
            }

            DreamRunStatus.RUNNING -> {
                require(attempt > 0 && leaseOwner != null && startedAtMs != null)
                require(finishedAtMs == null && failureCode == null)
            }

            else -> {
                require(leaseOwner == null && leaseUntilMs == null)
                require(finishedAtMs != null)
                if (status == DreamRunStatus.SUCCEEDED) {
                    require(checkpointEpoch == baseMemoryEpoch && failureCode == null)
                } else {
                    require(failureCode != null) {
                        "Every non-success terminal run must retain an enum failure reason"
                    }
                }
            }
        }
    }
}

/** Case-sensitive storage codecs. Unknown or normalized text always fails closed as null. */
object DreamObserverStorageCodec {
    fun authorityEntityKindOrNull(raw: String?): AuthorityEntityKind? = raw.enumOrNull()

    fun authorityOperationOrNull(raw: String?): AuthorityChangeOperation? = raw.enumOrNull()

    fun authorityReasonOrNull(raw: String?): AuthorityChangeReason? = raw.enumOrNull()

    fun runModeOrNull(raw: String?): DreamRunMode? = raw.enumOrNull()

    fun runStatusOrNull(raw: String?): DreamRunStatus? = raw.enumOrNull()

    fun runFailureCodeOrNull(raw: String?): DreamRunFailureCode? = raw.enumOrNull()

    private inline fun <reified T : Enum<T>> String?.enumOrNull(): T? =
        this?.let { candidate -> enumValues<T>().singleOrNull { it.name == candidate } }
}

fun requireCanonicalDreamRunId(runId: String) {
    require(runId.length == 36 && runId == runId.lowercase()) {
        "Dream run ID must be a canonical lower-case UUID"
    }
    val parsed = runCatching { Uuid.parse(runId) }.getOrNull()
    require(parsed?.toString() == runId) { "Dream run ID must be a canonical lower-case UUID" }
}

fun requireDreamLeaseOwner(owner: String) {
    require(owner.isNotBlank() && owner.length <= MAX_DREAM_LEASE_OWNER_LENGTH) {
        "Dream lease owner must be non-blank and bounded"
    }
    require(!owner.any(Char::isISOControl)) {
        "Dream lease owner must not contain control characters"
    }
}

val AuthorityChangeReason.isAuthorityMutationReason: Boolean
    get() = when (this) {
        AuthorityChangeReason.RUN_CLAIMED,
        AuthorityChangeReason.RUN_HEARTBEAT,
        AuthorityChangeReason.RUN_FINISHED,
        AuthorityChangeReason.LEASE_RECOVERED,
        AuthorityChangeReason.OBSERVER_CHECKPOINT_ADVANCED,
        -> false

        else -> true
    }

private fun requireValidAuthorityEntity(entityId: String, entityRevision: Long?) {
    require(entityId.isNotBlank() && entityId.length <= MAX_DREAM_ENTITY_ID_LENGTH) {
        "Authority entity ID must be non-blank and bounded"
    }
    require(!entityId.any(Char::isISOControl)) {
        "Authority entity ID must not contain control characters"
    }
    require(entityRevision == null || entityRevision > 0L) {
        "Authority entity revision must be positive when present"
    }
}

package me.rerere.rikkahub.learning.grant

import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import kotlin.uuid.Uuid

/** Bounded authority scan cap; relevance and the final context limit are applied afterwards. */
const val MAX_POLICY_GRANT_AUTHORITY_RESULTS: Int = 1_024

/** One maintenance turn may inspect at most one Room-sized page of global grant heads. */
const val MAX_POLICY_GRANT_REBIND_PAGE_SIZE: Int = 200

/** The only user-review transitions accepted by the grant authority writer. */
enum class PolicyGrantFence {
    GRANT,
    UPDATE_EXACT_POLICY,
    REVOKE,
}

/** Content-free, closed audit vocabulary. Arbitrary UI text never enters AppDatabase. */
enum class PolicyGrantReason {
    USER_APPROVED_CONTEXTUAL_ADVICE,
    USER_REVIEWED_POLICY_UPDATE,
    USER_RESTORED_POLICY_REVISION,
    USER_REVOKED_CONTEXTUAL_ADVICE,
    /** Automatic fail-closed removal after the owning second-user epoch entered REVOKING. */
    SECOND_USER_AUTHORITY_REVOKED,
}

enum class PolicyGrantAuthorityState {
    GRANTED,
    REVOKED,
}

/**
 * One frozen review intent. The actor is deliberately absent: every accepted command is authored
 * by the fixed `USER_REVIEW` authority. A state version of zero is the compare-and-set fence for
 * an absent head; persisted heads start at one.
 */
data class PolicyGrantReviewCommand(
    val fence: PolicyGrantFence,
    val sourceStreamId: String,
    val scope: LearningScope,
    /** Assistant that may consume this grant; never inferred from AUTHORITY_SUBJECT scope. */
    val consumingAssistantId: Uuid,
    val policyId: String,
    val contentRevision: Long,
    val artifactSha256: String,
    val expectedGrantStateVersion: Long,
    val frozenNowEpochMs: Long,
    val reason: PolicyGrantReason,
) {
    init {
        require(sourceStreamId.isCanonicalPolicyGrantStreamId()) { "Invalid grant stream" }
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject) {
            "Policy grants require an exact non-global scope"
        }
        require(consumingAssistantId.toString() != NIL_UUID) { "Nil consuming Assistant ID" }
        if (scope is LearningScope.Assistant) {
            require(scope.assistantId == consumingAssistantId) {
                "Assistant-scoped grant has a different consumer"
            }
        }
        require(policyId.isPolicyGrantReference()) { "Invalid Policy ID" }
        require(contentRevision > 0L) { "Invalid Policy content revision" }
        require(artifactSha256.isPolicyGrantSha256()) { "Invalid Policy artifact digest" }
        require(expectedGrantStateVersion >= 0L) { "Invalid expected grant state version" }
        require(frozenNowEpochMs >= 0L) { "Invalid frozen review time" }
        require(reason.isAllowedFor(fence)) { "Review reason is not allowed for this transition" }
    }

    override fun toString(): String =
        "PolicyGrantReviewCommand(fence=$fence, scope=${scope.kind}, " +
            "expectedVersion=$expectedGrantStateVersion, reason=$reason, ids=<redacted>)"
}

/** Content-free exact authority snapshot; Policy body/evidence remain in LearningDatabase. */
data class PolicyGrantAuthoritySnapshot(
    val grantId: String,
    val sourceStreamId: String,
    val scope: LearningScope,
    val consumingAssistantId: Uuid,
    val policyId: String,
    val contentRevision: Long,
    val artifactSha256: String,
    val state: PolicyGrantAuthorityState,
    val stateVersion: Long,
    val grantedAtEpochMs: Long,
    val revokedAtEpochMs: Long?,
    val reason: PolicyGrantReason,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(sourceStreamId.isCanonicalPolicyGrantStreamId()) { "Invalid grant stream" }
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(consumingAssistantId.toString() != NIL_UUID)
        if (scope is LearningScope.Assistant) require(scope.assistantId == consumingAssistantId)
        require(policyId.isPolicyGrantReference()) { "Invalid Policy ID" }
        require(contentRevision > 0L) { "Invalid Policy content revision" }
        require(artifactSha256.isPolicyGrantSha256()) { "Invalid Policy artifact digest" }
        require(grantId == policyGrantId(sourceStreamId, scope, consumingAssistantId, policyId)) {
            "Grant identity does not match its exact tuple"
        }
        require(stateVersion > 0L) { "Invalid grant state version" }
        require(grantedAtEpochMs >= 0L && createdAtEpochMs >= 0L)
        require(updatedAtEpochMs >= createdAtEpochMs)
        require(
            (state == PolicyGrantAuthorityState.GRANTED && revokedAtEpochMs == null) ||
                (state == PolicyGrantAuthorityState.REVOKED && revokedAtEpochMs != null &&
                    revokedAtEpochMs >= grantedAtEpochMs && updatedAtEpochMs >= revokedAtEpochMs),
        ) { "Grant state/time mismatch" }
        require(
            (state == PolicyGrantAuthorityState.REVOKED && reason in setOf(
                PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
                PolicyGrantReason.SECOND_USER_AUTHORITY_REVOKED,
            )) ||
                (state == PolicyGrantAuthorityState.GRANTED && reason !in setOf(
                    PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
                    PolicyGrantReason.SECOND_USER_AUTHORITY_REVOKED,
                )),
        ) { "Grant state/reason mismatch" }
        if (reason == PolicyGrantReason.SECOND_USER_AUTHORITY_REVOKED) {
            require(scope is LearningScope.AuthoritySubject) {
                "Authority revocation requires an authority-subject scope"
            }
        }
    }

    override fun toString(): String =
        "PolicyGrantAuthoritySnapshot(scope=${scope.kind}, state=$state, " +
            "version=$stateVersion, reason=$reason, ids=<redacted>)"
}

enum class PolicyGrantConflict {
    ROLLOUT_DISABLED,
    MISSING_HEAD,
    HEAD_ALREADY_EXISTS,
    STALE_STATE_VERSION,
    INVALID_TRANSITION,
    IDENTITY_MISMATCH,
    POLICY_REVISION_IDENTITY_MISMATCH,
    CLOCK_ROLLBACK,
    STATE_VERSION_OVERFLOW,
    AUDIT_REVISION_MISSING,
    AUTHORITY_SUBJECT_INACTIVE,
    STORAGE_FAILURE,
}

sealed interface PolicyGrantReviewResult {
    data class Applied(val snapshot: PolicyGrantAuthoritySnapshot) : PolicyGrantReviewResult {
        override fun toString(): String = "PolicyGrantReviewResult.Applied($snapshot)"
    }

    data class Duplicate(val snapshot: PolicyGrantAuthoritySnapshot) : PolicyGrantReviewResult {
        override fun toString(): String = "PolicyGrantReviewResult.Duplicate($snapshot)"
    }

    data class Conflict(
        val reason: PolicyGrantConflict,
        val currentStateVersion: Long? = null,
    ) : PolicyGrantReviewResult {
        init {
            require(currentStateVersion == null || currentStateVersion > 0L)
        }

        override fun toString(): String =
            "PolicyGrantReviewResult.Conflict(reason=$reason, currentVersion=$currentStateVersion)"
    }
}

fun interface PolicyGrantService {
    suspend fun review(command: PolicyGrantReviewCommand): PolicyGrantReviewResult
}

/**
 * Projection of an already-committed AppDatabase grant into rebuildable Policy lifecycle state.
 * Implementations must treat the snapshot as an exact content-free receipt and never manufacture
 * authority when its stream/scope/consumer/content tuple does not match LearningDatabase.
 */
fun interface PolicyGrantLifecycleProjector {
    suspend fun project(snapshot: PolicyGrantAuthoritySnapshot): PolicyGrantLifecycleProjectionResult
}

sealed interface PolicyGrantLifecycleProjectionResult {
    data class Applied(
        val policyId: String,
        val lifecycleRevision: Long,
    ) : PolicyGrantLifecycleProjectionResult

    data class Duplicate(
        val policyId: String,
        val lifecycleRevision: Long,
    ) : PolicyGrantLifecycleProjectionResult

    data class AlreadySatisfied(
        val policyId: String,
        val lifecycleRevision: Long,
    ) : PolicyGrantLifecycleProjectionResult

    data class Pending(
        val reason: PolicyGrantLifecyclePendingReason,
    ) : PolicyGrantLifecycleProjectionResult
}

enum class PolicyGrantLifecyclePendingReason {
    RUNTIME_UNAVAILABLE,
    POLICY_MISSING,
    EXACT_POLICY_MISMATCH,
    POLICY_NOT_TRANSITIONABLE,
    LIFECYCLE_CONFLICT,
    STORAGE_FAILURE,
}

/** User-facing review boundary: authority is always committed before derived lifecycle work. */
fun interface PolicyGrantReviewCoordinator {
    suspend fun review(command: PolicyGrantReviewCommand): PolicyGrantCoordinatedReviewResult
}

sealed interface PolicyGrantCoordinatedReviewResult {
    data class Completed(
        val authority: PolicyGrantAuthoritySnapshot,
        val lifecycleRevision: Long,
        val authorityWasDuplicate: Boolean,
        val lifecycleWasDuplicate: Boolean,
    ) : PolicyGrantCoordinatedReviewResult

    data class AuthorityRejected(
        val conflict: PolicyGrantReviewResult.Conflict,
    ) : PolicyGrantCoordinatedReviewResult

    /** AppDatabase is already authoritative; replaying the same command deterministically resumes. */
    data class AuthorityCommittedDerivedPending(
        val authority: PolicyGrantAuthoritySnapshot,
        val reason: PolicyGrantLifecyclePendingReason,
        val authorityWasDuplicate: Boolean,
    ) : PolicyGrantCoordinatedReviewResult
}

/**
 * Runtime authority boundary. Retrieval returns only current GRANTED heads and performs exact
 * revalidation immediately before an exposure reservation or provider dispatch. Maintenance may
 * also page current REVOKED heads so their fail-closed lifecycle projection is recoverable.
 */
interface PolicyGrantAuthoritySource {
    suspend fun listExactGranted(
        scope: LearningScope,
        consumingAssistantId: Uuid,
        sourceStreamId: String,
        limit: Int,
    ): List<PolicyGrantAuthoritySnapshot>

    suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean

    /**
     * Scans current AppDatabase grant heads across every scope and consumer. This is maintenance,
     * not retrieval: both GRANTED and REVOKED exact heads are returned so a rebuilt Learning DB
     * can resume the appropriate lifecycle projection. Invalid/missing current audit revisions
     * are counted but never materialized as authority snapshots.
     */
    suspend fun listCurrentPage(
        after: PolicyGrantAuthorityScanCursor?,
        limit: Int,
    ): PolicyGrantAuthorityScanResult
}

/** Exclusive `(updated_at_ms, grant_id)` keyset cursor for deterministic cross-page replay. */
data class PolicyGrantAuthorityScanCursor(
    val afterUpdatedAtEpochMs: Long,
    val afterGrantId: String,
) {
    init {
        require(afterUpdatedAtEpochMs >= 0L) { "Invalid grant scan time" }
        require(afterGrantId.isEmpty() || afterGrantId.isPolicyGrantReference()) {
            "Invalid grant scan ID"
        }
    }

    override fun toString(): String =
        "PolicyGrantAuthorityScanCursor(after=$afterUpdatedAtEpochMs, id=<redacted>)"

    companion object {
        val START = PolicyGrantAuthorityScanCursor(0L, "")
    }
}

data class PolicyGrantAuthorityScanPage(
    val snapshots: List<PolicyGrantAuthoritySnapshot>,
    /** Non-null only when another keyset query may be required. */
    val nextCursor: PolicyGrantAuthorityScanCursor?,
    /** Raw heads inspected, including heads rejected by exact-revision validation. */
    val scannedHeadCount: Int,
    val rejectedHeadCount: Int,
    val endReached: Boolean,
) {
    init {
        require(scannedHeadCount in 0..MAX_POLICY_GRANT_REBIND_PAGE_SIZE)
        require(rejectedHeadCount in 0..scannedHeadCount)
        require(snapshots.size + rejectedHeadCount == scannedHeadCount)
        require(endReached == (nextCursor == null))
        require(!endReached || scannedHeadCount < MAX_POLICY_GRANT_REBIND_PAGE_SIZE)
    }

    override fun toString(): String =
        "PolicyGrantAuthorityScanPage(valid=${snapshots.size}, rejected=$rejectedHeadCount, " +
            "scanned=$scannedHeadCount, end=$endReached)"
}

sealed interface PolicyGrantAuthorityScanResult {
    data class Ready(val page: PolicyGrantAuthorityScanPage) : PolicyGrantAuthorityScanResult

    /** Storage/query/hydration failures are fail-closed and never masquerade as end-of-scan. */
    data object Unavailable : PolicyGrantAuthorityScanResult
}

fun policyGrantId(
    sourceStreamId: String,
    scope: LearningScope,
    consumingAssistantId: Uuid,
    policyId: String,
): String {
    require(sourceStreamId.isCanonicalPolicyGrantStreamId())
    require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
    require(consumingAssistantId.toString() != NIL_UUID)
    if (scope is LearningScope.Assistant) require(scope.assistantId == consumingAssistantId)
    require(policyId.isPolicyGrantReference())
    return POLICY_GRANT_ID_PREFIX + LearningCanonicalId.digest(
        domainVersion = "policy-grant-id-v1",
        fields = listOf(
            sourceStreamId,
            scope.kind.name,
            scope.storageId,
            consumingAssistantId.toString(),
            policyId,
        ),
    )
}

internal fun PolicyGrantReason.isAllowedFor(fence: PolicyGrantFence): Boolean = when (fence) {
    PolicyGrantFence.GRANT -> this == PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE ||
        this == PolicyGrantReason.USER_RESTORED_POLICY_REVISION
    PolicyGrantFence.UPDATE_EXACT_POLICY ->
        this == PolicyGrantReason.USER_REVIEWED_POLICY_UPDATE ||
            this == PolicyGrantReason.USER_RESTORED_POLICY_REVISION
    // SECOND_USER_AUTHORITY_REVOKED is deliberately absent: it is accepted only by the
    // persisted authority-revocation saga, never by a forged user-review command.
    PolicyGrantFence.REVOKE -> this == PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE
}

internal fun String.isCanonicalPolicyGrantStreamId(): Boolean =
    length == CANONICAL_UUID_CHARS && runCatching { Uuid.parse(this).toString() == this }.getOrDefault(false)

internal fun String.isPolicyGrantReference(): Boolean =
    length in 1..256 && all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == ':' || char == '@'
    }

internal fun String.isPolicyGrantSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private const val POLICY_GRANT_ID_PREFIX = "policy-grant-v1:"
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private const val CANONICAL_UUID_CHARS = 36

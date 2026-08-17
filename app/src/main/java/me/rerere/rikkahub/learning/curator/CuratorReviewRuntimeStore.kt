package me.rerere.rikkahub.learning.curator

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.DisabledLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate

data class CuratorReviewCursor(
    val updatedAtMs: Long,
    val candidateId: String,
) {
    init {
        require(updatedAtMs >= 0L)
        require(candidateId.isEmpty() || candidateId.isSafeCuratorId())
    }
}

data class CuratorReviewListRequest(
    val scope: LearningScope,
    val before: CuratorReviewCursor = CuratorReviewCursor(Long.MAX_VALUE, "z".repeat(256)),
    val limit: Int = 40,
) {
    init {
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(limit in 1..80)
    }
}

data class CuratorReviewListItem(
    val candidateId: String,
    val candidateSha256: String,
    val operation: CuratorDeltaOperation,
    val state: String,
    val stateVersion: Long,
    val scope: LearningScope,
    val sourceCount: Int,
    val evidenceCount: Int,
    val diffTargetCount: Int,
    val hasApplyPlan: Boolean,
    val conflictCode: String?,
    val updatedAtMs: Long,
) {
    init {
        require(candidateId.isSafeCuratorId())
        require(candidateSha256.isCuratorSha256())
        require(stateVersion > 0L)
        require(sourceCount in 0..MAX_CURATOR_SOURCES)
        require(evidenceCount in 0..MAX_CURATOR_EVIDENCE)
        require(diffTargetCount in 0..MAX_CURATOR_SPLIT_OUTPUTS)
        require(updatedAtMs >= 0L)
    }

    override fun toString(): String =
        "CuratorReviewListItem(operation=$operation, state=$state, version=$stateVersion, " +
            "sources=$sourceCount, evidence=$evidenceCount, ids=<redacted>)"
}

data class CuratorReviewDetail(
    val summary: CuratorReviewListItem,
    val candidate: CuratorDeltaCandidate?,
    val applyPlan: CuratorApplyPlan?,
    val revisions: List<CuratorReviewRevisionReceipt>,
    val lineage: List<CuratorReviewLineageReceipt>,
) {
    init {
        require(revisions.size <= MAX_CURATOR_REVIEW_RECEIPTS)
        require(lineage.size <= MAX_CURATOR_REVIEW_RECEIPTS)
        require(revisions.all { it.candidateId == summary.candidateId })
        require(lineage.all { it.candidateId == summary.candidateId })
        if (summary.state == CURATOR_REDACTED_REVIEW_STATE) {
            require(candidate == null && applyPlan == null)
        } else {
            requireNotNull(candidate)
            require((applyPlan != null) == summary.hasApplyPlan &&
                (applyPlan == null || applyPlan.candidateId == summary.candidateId))
        }
    }

    override fun toString(): String =
        "CuratorReviewDetail(summary=$summary, content=<redacted>, ids=<redacted>)"
}

data class CuratorReviewRevisionReceipt(
    val candidateId: String,
    val stateVersion: Long,
    val previousStateVersion: Long?,
    val state: String,
    val candidateSha256: String,
    val applyPlanId: String?,
    val reasonCode: String,
    val actor: String,
    val createdAtMs: Long,
) {
    init {
        require((stateVersion == 1L) == (previousStateVersion == null))
        previousStateVersion?.let { require(it == stateVersion - 1L) }
    }
}

data class CuratorReviewLineageReceipt(
    val candidateId: String,
    val applyPlanId: String,
    val parentPolicyId: String,
    val parentRevision: Long,
    val parentArtifactSha256: String,
    val childPolicyId: String,
    val childRevision: Long,
    val childArtifactSha256: String,
    val relationType: String,
    val active: Boolean,
    val stateVersion: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class CuratorReviewMutationRequest(
    val candidateId: String,
    val scope: LearningScope,
    val expectedOperation: CuratorDeltaOperation,
    val expectedState: String,
    val expectedStateVersion: Long,
    val expectedCandidateSha256: String,
    val expectedUpdatedAtMs: Long,
    val committedAtMs: Long,
) {
    init {
        require(candidateId.isSafeCuratorId())
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(expectedStateVersion > 0L && expectedStateVersion < Long.MAX_VALUE)
        require(expectedCandidateSha256.isCuratorSha256())
        require(expectedUpdatedAtMs >= 0L && committedAtMs >= expectedUpdatedAtMs)
        require(expectedState in CURATOR_REVIEW_MUTABLE_STATES)
    }
}

sealed interface CuratorReviewMutationResult {
    data class Applied(
        val candidateId: String,
        val state: String,
        val stateVersion: Long,
        val candidateSha256: String,
        val updatedAtMs: Long,
    ) : CuratorReviewMutationResult

    data class Duplicate(
        val candidateId: String,
        val state: String,
        val stateVersion: Long,
    ) : CuratorReviewMutationResult

    data class Conflict(val reason: CuratorReviewConflict) : CuratorReviewMutationResult
}

enum class CuratorReviewConflict {
    ROLLOUT_DISABLED,
    MISSING,
    SCOPE_CONFLICT,
    FENCE_CONFLICT,
    STATE_CONFLICT,
    CLOCK_CONFLICT,
    REDACTED_SOURCE,
}

data class CuratorRetentionArchiveRequest(
    val candidateId: String,
    val expectedState: String,
    val expectedStateVersion: Long,
    val expectedCandidateSha256: String,
    val expectedUpdatedAtMs: Long,
    val archivedAtMs: Long,
) {
    init {
        require(candidateId.isSafeCuratorId())
        require(expectedState in CURATOR_RETENTION_ARCHIVABLE_STATES)
        require(expectedStateVersion > 0L && expectedStateVersion < Long.MAX_VALUE)
        require(expectedCandidateSha256.isCuratorSha256())
        require(expectedUpdatedAtMs >= 0L && archivedAtMs >= expectedUpdatedAtMs)
    }
}

data class CuratorRetentionArchiveCursor(
    val updatedAtMs: Long = -1L,
    val candidateId: String = "",
) {
    init {
        require(updatedAtMs >= -1L)
        require(candidateId.isEmpty() || candidateId.isSafeCuratorId())
    }
}

interface CuratorReviewRuntimeStore {
    suspend fun list(request: CuratorReviewListRequest): List<CuratorReviewListItem>
    suspend fun read(candidateId: String, scope: LearningScope): CuratorReviewDetail?

    /** User-only PROPOSED -> APPROVED. */
    suspend fun approve(request: CuratorReviewMutationRequest): CuratorReviewMutationResult

    /** User-only PROPOSED/APPROVED -> REJECTED. */
    suspend fun reject(request: CuratorReviewMutationRequest): CuratorReviewMutationResult

    /** User-only reversible lifecycle archive; APPLIED must be rolled back first. */
    suspend fun archive(request: CuratorReviewMutationRequest): CuratorReviewMutationResult

    /** Content-free bounded maintenance scan; excludes APPROVED/APPLYING/APPLIED/ROLLED_BACK. */
    suspend fun listRetentionArchivable(
        cutoffMs: Long,
        after: CuratorRetentionArchiveCursor,
        limit: Int,
    ): List<CuratorReviewListItem>

    /** Retention-only exact CAS for the fixed eligible-state set. */
    suspend fun archiveRetention(
        request: CuratorRetentionArchiveRequest,
    ): CuratorReviewMutationResult
}

/** UI-facing coordinator; it deliberately has no proposal insertion or generic state mutation. */
class CuratorReviewRuntimeCoordinator(
    private val store: CuratorReviewRuntimeStore,
    private val positiveMutations: LearningPositiveMutationGate =
        DisabledLearningPositiveMutationGate,
) {
    suspend fun list(request: CuratorReviewListRequest): List<CuratorReviewListItem> =
        store.list(request)

    suspend fun read(candidateId: String, scope: LearningScope): CuratorReviewDetail? =
        store.read(candidateId, scope)

    suspend fun approve(request: CuratorReviewMutationRequest): CuratorReviewMutationResult {
        if (!positiveMutations.allows(request.expectedOperation)) {
            return CuratorReviewMutationResult.Conflict(CuratorReviewConflict.ROLLOUT_DISABLED)
        }
        return store.approve(request)
    }

    suspend fun reject(request: CuratorReviewMutationRequest): CuratorReviewMutationResult =
        store.reject(request)

    suspend fun archive(request: CuratorReviewMutationRequest): CuratorReviewMutationResult =
        store.archive(request)
}

internal val CURATOR_RETENTION_ARCHIVABLE_STATES = setOf(
    "PROPOSED",
    "REJECTED",
    "APPLY_CONFLICT",
    "ROLLBACK_CONFLICT",
)
internal val CURATOR_REVIEW_MUTABLE_STATES = CURATOR_RETENTION_ARCHIVABLE_STATES + setOf(
    "APPROVED",
    "ROLLED_BACK",
)
private const val CURATOR_REDACTED_REVIEW_STATE = "REDACTED_SOURCE"
const val MAX_CURATOR_REVIEW_RECEIPTS: Int = 100

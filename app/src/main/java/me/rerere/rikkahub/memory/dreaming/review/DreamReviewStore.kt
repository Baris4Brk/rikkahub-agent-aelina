package me.rerere.rikkahub.memory.dreaming.review

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId

/** Storage seam. Each mutation is one complete transaction and must apply every supplied fence. */
interface DreamReviewStore {
    fun observeProjection(scopeId: DreamScopeId): Flow<DreamReviewProjection>

    suspend fun readClaim(target: DreamClaimMutationTarget): DreamReviewReadResult<DreamClaimDetail>

    /** Must revalidate current authority and return at most [maxChars] local characters. */
    suspend fun readEvidence(
        reference: DreamEvidenceReference,
        maxChars: Int,
    ): DreamEvidenceRevealResult

    suspend fun validateTarget(
        target: DreamClaimMutationTarget,
    ): DreamReviewReadResult<DreamValidatedCorrectionTarget>

    /**
     * Atomically copies exact content/sources to N+1 REJECTED/USER_REJECTED and recompiles.
     * Only a PENDING_REVIEW or ACTIVE_CONTEXTUAL head may be rejected.
     */
    suspend fun reject(command: DreamRejectCommand): DreamReviewStoreMutationResult

    /**
     * Atomically writes N+1 SUPERSEDED/USER_CORRECTION pinned to the new authority and recompiles.
     * Only a preflighted PENDING_REVIEW or ACTIVE_CONTEXTUAL head may be corrected.
     * The authority write is expected to be the sole intervening epoch (+1); every other fence
     * remains at preflight values and the exact new USER_REVIEWED/DREAM_USER_CORRECTION Memory is
     * revalidated. Any concurrent extra authority write is a conflict, never a relaxed success.
     */
    suspend fun markCorrected(command: DreamMarkCorrectedCommand): DreamReviewStoreMutationResult

    /** Clears only derived Claims/snapshots. An already-empty scope must not advance dreamRevision. */
    suspend fun clearDerived(command: DreamClearDerivedCommand): DreamReviewStoreMutationResult
}

interface DreamAuthorityCorrectionPort {
    suspend fun create(request: DreamAuthorityCorrectionRequest): DreamAuthorityCorrectionResult
}

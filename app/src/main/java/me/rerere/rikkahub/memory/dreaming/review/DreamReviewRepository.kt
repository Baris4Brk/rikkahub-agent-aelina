package me.rerere.rikkahub.memory.dreaming.review

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId

interface DreamReviewRepository {
    fun observeScope(scopeId: DreamScopeId): Flow<DreamReviewProjection>

    suspend fun readClaim(target: DreamClaimMutationTarget): DreamReviewReadResult<DreamClaimDetail>

    suspend fun revealEvidence(reference: DreamEvidenceReference): DreamEvidenceRevealResult

    suspend fun reject(target: DreamClaimMutationTarget): DreamReviewMutationResult

    suspend fun correct(draft: DreamCorrectionDraft): DreamCorrectionResult

    suspend fun clearDerived(fence: DreamReviewFence): DreamReviewMutationResult
}

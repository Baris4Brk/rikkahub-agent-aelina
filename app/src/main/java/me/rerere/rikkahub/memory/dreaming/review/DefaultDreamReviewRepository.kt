package me.rerere.rikkahub.memory.dreaming.review

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId

class DefaultDreamReviewRepository(
    private val store: DreamReviewStore,
    private val authority: DreamAuthorityCorrectionPort,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val mutationIdGenerator: () -> String = { Uuid.random().toString() },
) : DreamReviewRepository {
    override fun observeScope(scopeId: DreamScopeId): Flow<DreamReviewProjection> =
        store.observeProjection(scopeId)

    override suspend fun readClaim(
        target: DreamClaimMutationTarget,
    ): DreamReviewReadResult<DreamClaimDetail> = store.readClaim(target)

    override suspend fun revealEvidence(
        reference: DreamEvidenceReference,
    ): DreamEvidenceRevealResult = store.readEvidence(
        reference = reference,
        maxChars = DREAM_EVIDENCE_EXCERPT_MAX_CHARS,
    )

    override suspend fun reject(target: DreamClaimMutationTarget): DreamReviewMutationResult =
        store.reject(
            DreamRejectCommand(
                mutationId = mutationIdGenerator(),
                target = target,
                nowEpochMs = nowMs(),
            ),
        ).toRepositoryResult()

    override suspend fun correct(draft: DreamCorrectionDraft): DreamCorrectionResult {
        val validated = when (val result = store.validateTarget(draft.target)) {
            is DreamReviewReadResult.Found -> result.value
            is DreamReviewReadResult.Conflict -> return DreamCorrectionResult.Conflict(result.conflict)
            DreamReviewReadResult.NotFound -> return DreamCorrectionResult.NotFound
            DreamReviewReadResult.InvalidState -> return DreamCorrectionResult.InvalidState
            DreamReviewReadResult.Corrupt -> return DreamCorrectionResult.Corrupt
        }
        val mutationId = mutationIdGenerator()
        val authorityApplied = when (val result = authority.create(
            DreamAuthorityCorrectionRequest(
                mutationId = mutationId,
                scopeId = validated.target.fence.scopeId,
                title = draft.title,
                content = draft.content,
                kind = draft.kind,
                tags = draft.tags,
                expiresAtEpochMs = draft.expiresAtEpochMs,
                capturedOriginAssistantId = validated.capturedOriginAssistantId,
            ),
        )) {
            is DreamAuthorityCorrectionResult.Applied -> result
            is DreamAuthorityCorrectionResult.AppliedRebuildPending -> {
                return DreamCorrectionResult.AuthorityAppliedRebuildPending(
                    memoryId = result.memoryId,
                    memoryRevision = result.revision,
                )
            }
            DreamAuthorityCorrectionResult.Conflict -> return DreamCorrectionResult.Conflict(null)
            DreamAuthorityCorrectionResult.NotFound -> return DreamCorrectionResult.NotFound
            is DreamAuthorityCorrectionResult.Rejected -> {
                return DreamCorrectionResult.AuthorityRejected(result.code)
            }
        }
        val preflightEpoch = validated.target.fence.expectedMemoryEpoch
        if (preflightEpoch == Long.MAX_VALUE || authorityApplied.resultingMemoryEpoch != preflightEpoch + 1L) {
            return DreamCorrectionResult.AuthorityAppliedRebuildPending(
                memoryId = authorityApplied.memoryId,
                memoryRevision = authorityApplied.revision,
            )
        }
        val derived = store.markCorrected(
            DreamMarkCorrectedCommand(
                mutationId = mutationId,
                validatedTarget = validated,
                authorityMemoryId = authorityApplied.memoryId,
                authorityMemoryRevision = authorityApplied.revision,
                expectedAuthorityMemoryEpoch = authorityApplied.resultingMemoryEpoch,
                nowEpochMs = nowMs(),
            ),
        )
        return if (derived is DreamReviewStoreMutationResult.Applied) {
            DreamCorrectionResult.Applied(
                memoryId = authorityApplied.memoryId,
                memoryRevision = authorityApplied.revision,
                fence = derived.fence,
            )
        } else {
            // Authority is formal truth. A stale derived phase is rebuilt from the advanced epoch.
            DreamCorrectionResult.AuthorityAppliedRebuildPending(
                memoryId = authorityApplied.memoryId,
                memoryRevision = authorityApplied.revision,
            )
        }
    }

    override suspend fun clearDerived(fence: DreamReviewFence): DreamReviewMutationResult =
        store.clearDerived(
            DreamClearDerivedCommand(
                mutationId = mutationIdGenerator(),
                fence = fence,
                nowEpochMs = nowMs(),
            ),
        ).toRepositoryResult()
}

private fun DreamReviewStoreMutationResult.toRepositoryResult(): DreamReviewMutationResult = when (this) {
    is DreamReviewStoreMutationResult.Applied -> DreamReviewMutationResult.Applied(fence)
    is DreamReviewStoreMutationResult.Conflict -> DreamReviewMutationResult.Conflict(conflict)
    DreamReviewStoreMutationResult.NotFound -> DreamReviewMutationResult.NotFound
    DreamReviewStoreMutationResult.InvalidState -> DreamReviewMutationResult.InvalidState
    DreamReviewStoreMutationResult.Corrupt -> DreamReviewMutationResult.Corrupt
    DreamReviewStoreMutationResult.AlreadyClear -> DreamReviewMutationResult.AlreadyClear
}

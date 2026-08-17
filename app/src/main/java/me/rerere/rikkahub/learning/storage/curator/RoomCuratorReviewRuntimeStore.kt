package me.rerere.rikkahub.learning.storage.curator

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.curator.CURATOR_RETENTION_ARCHIVABLE_STATES
import me.rerere.rikkahub.learning.curator.CuratorReviewConflict
import me.rerere.rikkahub.learning.curator.CuratorReviewDetail
import me.rerere.rikkahub.learning.curator.CuratorReviewListItem
import me.rerere.rikkahub.learning.curator.CuratorReviewListRequest
import me.rerere.rikkahub.learning.curator.CuratorReviewLineageReceipt
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationRequest
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationResult
import me.rerere.rikkahub.learning.curator.CuratorReviewRevisionReceipt
import me.rerere.rikkahub.learning.curator.CuratorReviewRuntimeStore
import me.rerere.rikkahub.learning.curator.CuratorRetentionArchiveCursor
import me.rerere.rikkahub.learning.curator.CuratorRetentionArchiveRequest
import me.rerere.rikkahub.learning.curator.isSafeCuratorId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase

/** Exact, scope-bound review and retention lifecycle facade; it exposes no generic transition. */
class RoomCuratorReviewRuntimeStore(
    private val database: LearningDatabase,
) : CuratorReviewRuntimeStore {
    override suspend fun list(request: CuratorReviewListRequest): List<CuratorReviewListItem> =
        database.curatorDeltaDao().listScopePage(
            scopeKind = request.scope.kind.name,
            scopeId = request.scope.storageId,
            beforeUpdatedAtMs = request.before.updatedAtMs,
            beforeId = request.before.candidateId,
            limit = request.limit,
        ).map { it.toReviewListItem(includeCandidateCounts = true) }

    override suspend fun read(
        candidateId: String,
        scope: LearningScope,
    ): CuratorReviewDetail? {
        require(candidateId.isSafeCuratorId())
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        val entity = database.curatorDeltaDao().find(candidateId) ?: return null
        if (!entity.hasScope(scope)) return null
        val revisions = database.curatorDeltaDao().listRevisionPage(
            candidateId,
            Long.MAX_VALUE,
            100,
        ).map { revision ->
            CuratorReviewRevisionReceipt(
                candidateId = revision.candidateId,
                stateVersion = revision.stateVersion,
                previousStateVersion = revision.previousStateVersion,
                state = revision.state,
                candidateSha256 = revision.candidateSha256,
                applyPlanId = revision.applyPlanId,
                reasonCode = revision.reasonCode,
                actor = revision.actor,
                createdAtMs = revision.createdAtMs,
            )
        }
        val lineage = database.curatorDeltaDao().listLineagePage(
            candidateId,
            "",
            "",
            "",
            100,
        ).map { edge ->
            CuratorReviewLineageReceipt(
                candidateId = edge.candidateId,
                applyPlanId = edge.applyPlanId,
                parentPolicyId = edge.parentPolicyId,
                parentRevision = edge.parentRevision,
                parentArtifactSha256 = edge.parentArtifactSha256,
                childPolicyId = edge.childPolicyId,
                childRevision = edge.childRevision,
                childArtifactSha256 = edge.childArtifactSha256,
                relationType = edge.relationType,
                active = edge.active,
                stateVersion = edge.stateVersion,
                createdAtMs = edge.createdAtMs,
                updatedAtMs = edge.updatedAtMs,
            )
        }
        return CuratorReviewDetail(
            summary = entity.toReviewListItem(includeCandidateCounts = true),
            candidate = entity.decodeCandidateOrNull(),
            applyPlan = entity.decodeApplyPlanOrNull(),
            revisions = revisions,
            lineage = lineage,
        )
    }

    override suspend fun approve(
        request: CuratorReviewMutationRequest,
    ): CuratorReviewMutationResult {
        if (request.expectedState != CuratorDeltaStoredState.PROPOSED.name) {
            return CuratorReviewMutationResult.Conflict(CuratorReviewConflict.STATE_CONFLICT)
        }
        return mutateUser(
            request,
            target = CuratorDeltaStoredState.APPROVED,
            reason = CuratorDeltaRevisionReason.USER_APPROVED,
        )
    }

    override suspend fun reject(
        request: CuratorReviewMutationRequest,
    ): CuratorReviewMutationResult {
        if (request.expectedState !in setOf(
                CuratorDeltaStoredState.PROPOSED.name,
                CuratorDeltaStoredState.APPROVED.name,
            )
        ) return CuratorReviewMutationResult.Conflict(CuratorReviewConflict.STATE_CONFLICT)
        return mutateUser(
            request,
            target = CuratorDeltaStoredState.REJECTED,
            reason = CuratorDeltaRevisionReason.USER_REJECTED,
        )
    }

    override suspend fun archive(
        request: CuratorReviewMutationRequest,
    ): CuratorReviewMutationResult {
        if (request.expectedState !in USER_ARCHIVABLE_STATES) {
            return CuratorReviewMutationResult.Conflict(CuratorReviewConflict.STATE_CONFLICT)
        }
        return mutateUser(
            request,
            target = CuratorDeltaStoredState.ARCHIVED,
            reason = CuratorDeltaRevisionReason.ARCHIVED,
        )
    }

    override suspend fun listRetentionArchivable(
        cutoffMs: Long,
        after: CuratorRetentionArchiveCursor,
        limit: Int,
    ): List<CuratorReviewListItem> {
        require(cutoffMs >= 0L)
        require(limit in 1..128)
        return database.curatorDeltaDao().listRetentionArchivablePage(
            cutoffMs = cutoffMs,
            afterUpdatedAtMs = after.updatedAtMs,
            afterId = after.candidateId,
            limit = limit,
        ).map { it.toReviewListItem(includeCandidateCounts = false) }
    }

    override suspend fun archiveRetention(
        request: CuratorRetentionArchiveRequest,
    ): CuratorReviewMutationResult = database.withTransaction {
        val dao = database.curatorDeltaDao()
        val current = dao.find(request.candidateId)
            ?: return@withTransaction CuratorReviewMutationResult.Conflict(
                CuratorReviewConflict.MISSING,
            )
        exactDuplicateOrNull(
            current = current,
            expectedState = request.expectedState,
            expectedStateVersion = request.expectedStateVersion,
            expectedCandidateSha256 = request.expectedCandidateSha256,
            expectedUpdatedAtMs = request.expectedUpdatedAtMs,
            committedAtMs = request.archivedAtMs,
            target = CuratorDeltaStoredState.ARCHIVED,
            reason = CuratorDeltaRevisionReason.ARCHIVED,
            actor = CuratorDeltaRevisionActor.RETENTION,
        )?.let { return@withTransaction it }
        if (current.state !in CURATOR_RETENTION_ARCHIVABLE_STATES ||
            current.state != request.expectedState
        ) return@withTransaction CuratorReviewMutationResult.Conflict(
            CuratorReviewConflict.STATE_CONFLICT,
        )
        if (current.stateVersion != request.expectedStateVersion ||
            current.candidateSha256 != request.expectedCandidateSha256 ||
            current.updatedAtMs != request.expectedUpdatedAtMs
        ) return@withTransaction CuratorReviewMutationResult.Conflict(
            CuratorReviewConflict.FENCE_CONFLICT,
        )
        transition(
            current,
            CuratorDeltaStoredState.ARCHIVED,
            CuratorDeltaRevisionReason.ARCHIVED,
            CuratorDeltaRevisionActor.RETENTION,
            request.archivedAtMs,
        )
    }

    private suspend fun mutateUser(
        request: CuratorReviewMutationRequest,
        target: CuratorDeltaStoredState,
        reason: CuratorDeltaRevisionReason,
    ): CuratorReviewMutationResult = database.withTransaction {
        val current = database.curatorDeltaDao().find(request.candidateId)
            ?: return@withTransaction CuratorReviewMutationResult.Conflict(
                CuratorReviewConflict.MISSING,
            )
        if (!current.hasScope(request.scope)) {
            return@withTransaction CuratorReviewMutationResult.Conflict(
                CuratorReviewConflict.SCOPE_CONFLICT,
            )
        }
        exactDuplicateOrNull(
            current = current,
            expectedState = request.expectedState,
            expectedStateVersion = request.expectedStateVersion,
            expectedCandidateSha256 = request.expectedCandidateSha256,
            expectedUpdatedAtMs = request.expectedUpdatedAtMs,
            committedAtMs = request.committedAtMs,
            target = target,
            reason = reason,
            actor = CuratorDeltaRevisionActor.USER,
        )?.let { return@withTransaction it }
        if (current.state == CuratorDeltaStoredState.REDACTED_SOURCE.name) {
            return@withTransaction CuratorReviewMutationResult.Conflict(
                CuratorReviewConflict.REDACTED_SOURCE,
            )
        }
        if (current.operation != request.expectedOperation.name ||
            current.state != request.expectedState
        ) return@withTransaction CuratorReviewMutationResult.Conflict(
            CuratorReviewConflict.STATE_CONFLICT,
        )
        if (current.stateVersion != request.expectedStateVersion ||
            current.candidateSha256 != request.expectedCandidateSha256 ||
            current.updatedAtMs != request.expectedUpdatedAtMs
        ) return@withTransaction CuratorReviewMutationResult.Conflict(
            CuratorReviewConflict.FENCE_CONFLICT,
        )
        if (request.committedAtMs < current.updatedAtMs) {
            return@withTransaction CuratorReviewMutationResult.Conflict(
                CuratorReviewConflict.CLOCK_CONFLICT,
            )
        }
        transition(
            current,
            target,
            reason,
            CuratorDeltaRevisionActor.USER,
            request.committedAtMs,
        )
    }

    private suspend fun transition(
        current: CuratorDeltaCandidateEntity,
        target: CuratorDeltaStoredState,
        reason: CuratorDeltaRevisionReason,
        actor: CuratorDeltaRevisionActor,
        committedAtMs: Long,
    ): CuratorReviewMutationResult {
        val next = current.copy(
            stateVersion = current.stateVersion + 1L,
            state = target.name,
            conflictCode = null,
            updatedAtMs = committedAtMs,
        )
        return when (database.curatorDeltaDao().transitionFenced(
            current,
            next,
            reason,
            actor,
        )) {
            is CuratorDeltaMutationResult.Applied -> CuratorReviewMutationResult.Applied(
                candidateId = next.id,
                state = next.state,
                stateVersion = next.stateVersion,
                candidateSha256 = next.candidateSha256,
                updatedAtMs = next.updatedAtMs,
            )
            is CuratorDeltaMutationResult.Conflict -> CuratorReviewMutationResult.Conflict(
                CuratorReviewConflict.FENCE_CONFLICT,
            )
        }
    }

    private suspend fun exactDuplicateOrNull(
        current: CuratorDeltaCandidateEntity,
        expectedState: String,
        expectedStateVersion: Long,
        expectedCandidateSha256: String,
        expectedUpdatedAtMs: Long,
        committedAtMs: Long,
        target: CuratorDeltaStoredState,
        reason: CuratorDeltaRevisionReason,
        actor: CuratorDeltaRevisionActor,
    ): CuratorReviewMutationResult.Duplicate? {
        if (current.state != target.name) return null
        if (current.stateVersion != expectedStateVersion + 1L ||
            current.candidateSha256 != expectedCandidateSha256 ||
            current.updatedAtMs != committedAtMs
        ) return null
        val before = database.curatorDeltaDao().findRevision(current.id, expectedStateVersion)
            ?: return null
        val terminal = database.curatorDeltaDao().findRevision(current.id, current.stateVersion)
            ?: return null
        if (before.state != expectedState || before.createdAtMs != expectedUpdatedAtMs ||
            terminal.state != target.name || terminal.reasonCode != reason.name ||
            terminal.actor != actor.name || terminal.createdAtMs != committedAtMs ||
            terminal.candidateSha256 != expectedCandidateSha256
        ) return null
        return CuratorReviewMutationResult.Duplicate(current.id, current.state, current.stateVersion)
    }
}

private fun CuratorDeltaCandidateEntity.hasScope(scope: LearningScope): Boolean =
    scopeKind == scope.kind.name && scopeId == scope.storageId

private fun CuratorDeltaCandidateEntity.toReviewListItem(
    includeCandidateCounts: Boolean,
): CuratorReviewListItem {
    val candidate = if (includeCandidateCounts) decodeCandidateOrNull() else null
    return CuratorReviewListItem(
        candidateId = id,
        candidateSha256 = candidateSha256,
        operation = me.rerere.rikkahub.learning.curator.CuratorDeltaOperation.valueOf(operation),
        state = state,
        stateVersion = stateVersion,
        scope = requireNotNull(LearningScope.parseOrNull(scopeKind, scopeId)),
        sourceCount = candidate?.sources?.size ?: 0,
        evidenceCount = candidate?.evidence?.size ?: 0,
        diffTargetCount = candidate?.diffs?.size ?: 0,
        hasApplyPlan = applyPlanId != null,
        conflictCode = conflictCode,
        updatedAtMs = updatedAtMs,
    )
}

private val USER_ARCHIVABLE_STATES = setOf(
    CuratorDeltaStoredState.PROPOSED.name,
    CuratorDeltaStoredState.APPROVED.name,
    CuratorDeltaStoredState.REJECTED.name,
    CuratorDeltaStoredState.APPLY_CONFLICT.name,
    CuratorDeltaStoredState.ROLLBACK_CONFLICT.name,
    CuratorDeltaStoredState.ROLLED_BACK.name,
)

package me.rerere.rikkahub.memory

import androidx.room.withTransaction
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryCaptureEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryEvidenceEntity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetriever
import kotlin.uuid.Uuid

class RoomMemoryProcessingStore(
    private val database: AppDatabase,
    private val memoryDao: MemoryDAO,
    private val memoryV2Dao: MemoryV2Dao,
    private val retriever: MemoryRetriever,
    private val json: Json,
    private val idGenerator: () -> String = { Uuid.random().toString() },
) : MemoryProcessingStore {
    override suspend fun claim(request: MemoryClaimRequest): List<MemoryCaptureRecord> =
        database.withTransaction {
            memoryV2Dao.recoverExpiredLeases(request.nowMs)
            val captureGroups = memoryV2Dao.findPendingCaptureGroups(
                request.scopeId,
                request.maxConversationGroups.coerceIn(1, 3),
            )
            val claimed = arrayListOf<MemoryCaptureRecord>()
            for (group in captureGroups) {
                val remaining = request.maxCaptures - claimed.size
                if (remaining <= 0) break
                val candidates = memoryV2Dao.findClaimableCaptures(
                    scopeId = request.scopeId,
                    conversationId = group.conversationId,
                    captureSource = group.captureSource,
                    limit = minOf(request.maxTurnsPerConversation, remaining),
                )
                // Freeze the context window on every captured turn. A later settings edit must
                // not unexpectedly turn an already queued 12-turn discussion into a 30-turn
                // provider request. The oldest queued turn defines this coherent batch.
                val contextTurnLimit = candidates.firstOrNull()?.contextTurnLimit
                    ?.coerceIn(
                        MIN_MEMORY_CONVERSATION_CONTEXT_TURNS,
                        MAX_MEMORY_CONVERSATION_CONTEXT_TURNS,
                    )
                    ?: MIN_MEMORY_CONVERSATION_CONTEXT_TURNS
                candidates.take(contextTurnLimit).forEach { capture ->
                    val won = memoryV2Dao.claimCapture(
                        id = capture.id,
                        workerId = request.workerId,
                        leaseUntilMs = request.leaseUntilMs,
                        nowMs = request.nowMs,
                    )
                    if (won == 1) claimed += capture.toRecord()
                }
            }
            claimed
        }

    override suspend fun findExisting(
        scopeId: String,
        query: String,
        limit: Int,
    ): List<ExistingMemoryRecord> {
        val matches = if (scopeId == MemoryRepository.GLOBAL_MEMORY_ID) {
            retriever.queryRelevant(
                assistantId = null,
                query = query,
                includeGlobal = true,
                limit = limit,
                maxChars = 20_000,
            )
        } else {
            val assistantId = runCatching { Uuid.parse(scopeId) }.getOrNull() ?: return emptyList()
            retriever.queryRelevant(
                assistantId = assistantId,
                query = query,
                includeGlobal = false,
                limit = limit,
                maxChars = 20_000,
            )
        }
        if (matches.isEmpty()) return emptyList()
        val byId = memoryDao.getMemoriesByIds(matches.map { it.memory.id }).associateBy { it.id }
        return matches.mapNotNull { match -> byId[match.memory.id]?.toExistingRecord(json) }
    }

    override suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult =
        database.withTransaction {
            var autoApplied = 0
            var pendingReview = 0
            var superseded = 0
            commit.candidates.forEach { decision ->
                var status = when (decision.disposition) {
                    MemoryCandidateDisposition.AUTO_APPLY -> MemoryCandidateStatus.AUTO_APPLIED
                    MemoryCandidateDisposition.REVIEW -> MemoryCandidateStatus.PENDING_REVIEW
                    MemoryCandidateDisposition.SUPERSEDE -> MemoryCandidateStatus.SUPERSEDED
                    MemoryCandidateDisposition.IGNORE -> MemoryCandidateStatus.SUPERSEDED
                }
                var appliedMemoryId: Int? = null
                if (decision.disposition == MemoryCandidateDisposition.AUTO_APPLY) {
                    val proposal = decision.proposal
                    val exact = memoryDao.findActiveByContentHash(
                        scopeId = commit.scopeId,
                        contentHash = memoryContentHash(proposal.content),
                        nowMs = commit.nowMs,
                    )
                    if (exact != null) {
                        status = MemoryCandidateStatus.SUPERSEDED
                    } else {
                        appliedMemoryId = insertCreatedMemory(
                            scopeId = commit.scopeId,
                            proposal = proposal,
                            approvalSource = MemoryApprovalSource.AUTO_SAFE,
                            sourceType = "AUTO_EXTRACTION",
                            sourceConversationId = commit.conversationId,
                            candidateId = decision.id,
                            originAssistantId = commit.assistantId,
                            evidenceCaptures = commit.captures,
                            nowMs = commit.nowMs,
                        )
                    }
                }
                memoryV2Dao.insertCandidate(
                    decision.toEntity(
                        commit = commit,
                        status = status,
                        appliedMemoryId = appliedMemoryId,
                        json = json,
                    ),
                )
                when (status) {
                    MemoryCandidateStatus.AUTO_APPLIED -> autoApplied++
                    MemoryCandidateStatus.PENDING_REVIEW -> pendingReview++
                    MemoryCandidateStatus.SUPERSEDED -> superseded++
                    else -> Unit
                }
            }
            memoryV2Dao.markCapturesProcessed(
                ids = commit.captures.map { it.id },
                nowMs = commit.nowMs,
                processingOutcome = if (commit.candidates.isEmpty()) {
                    "NO_LONG_TERM_SIGNAL"
                } else {
                    "CANDIDATES_CREATED"
                },
                candidateCount = commit.candidates.size,
            )
            MemoryCommitResult(autoApplied, pendingReview, superseded)
        }

    override suspend fun markFailed(
        captureIds: List<String>,
        code: String,
        message: String?,
        retryPolicy: MemoryFailureRetryPolicy,
        nowMs: Long,
    ) {
        if (captureIds.isEmpty()) return
        memoryV2Dao.markCapturesFailed(
            ids = captureIds,
            state = when (retryPolicy) {
                MemoryFailureRetryPolicy.AUTOMATIC -> MemoryCaptureState.FAILED.name
                MemoryFailureRetryPolicy.MANUAL_ONLY -> MemoryCaptureState.FAILED.name

                MemoryFailureRetryPolicy.NONE -> MemoryCaptureState.DISCARDED.name
            },
            code = code,
            message = message?.take(MAX_STORED_ERROR_CHARS),
            // A configuration error must be shown to the user and may be retried after they
            // repair the model/provider. Exhausting the automatic budget prevents a stale
            // configuration from being re-claimed by a later scheduled run.
            requiresManualRetry = retryPolicy == MemoryFailureRetryPolicy.MANUAL_ONLY,
            nowMs = nowMs,
        )
    }

    override suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long) {
        memoryV2Dao.pauseScope(scopeId, reason, nowMs)
    }

    override suspend fun releaseClaimed(captureIds: List<String>, nowMs: Long) {
        if (captureIds.isNotEmpty()) memoryV2Dao.releaseClaimedCaptures(captureIds, nowMs)
    }

    override suspend fun review(
        command: MemoryReviewCommand,
        nowMs: Long,
    ): MemoryReviewResult = database.withTransaction {
        val candidateId = when (command) {
            is MemoryReviewCommand.Accept -> command.candidateId
            is MemoryReviewCommand.Reject -> command.candidateId
        }
        val candidate = memoryV2Dao.findCandidate(candidateId) ?: return@withTransaction MemoryReviewResult.NotFound
        if (candidate.status == MemoryCandidateStatus.CONFLICT.name) {
            return@withTransaction MemoryReviewResult.Conflict
        }
        if (candidate.status != MemoryCandidateStatus.PENDING_REVIEW.name) {
            return@withTransaction MemoryReviewResult.AlreadyResolved
        }
        if (command is MemoryReviewCommand.Reject) {
            memoryV2Dao.resolveCandidate(
                candidateId = candidate.id,
                status = MemoryCandidateStatus.REJECTED.name,
                appliedMemoryId = null,
                resolutionError = null,
                nowMs = nowMs,
            )
            return@withTransaction MemoryReviewResult.Rejected
        }

        val base = candidate.toProposal(json)
        val edited = (command as MemoryReviewCommand.Accept).editedProposal
        val proposal = if (edited == null) {
            base
        } else {
            base.copy(
                title = edited.title,
                content = edited.content,
                kind = edited.kind,
                tags = edited.tags,
                importance = edited.importance,
                confidence = edited.confidence,
                expiresAtMs = edited.expiresAtMs,
                reason = edited.reason,
            )
        }
        if (!proposal.isValidReviewedEdit()) {
            return@withTransaction MemoryReviewResult.Failed("memory_review_edit_invalid")
        }

        val memoryId = when (proposal.action) {
            MemoryCandidateAction.CREATE -> {
                val duplicate = memoryDao.findActiveByContentHash(
                    scopeId = candidate.scopeId,
                    contentHash = memoryContentHash(proposal.content),
                    nowMs = nowMs,
                )
                if (duplicate != null) {
                    memoryV2Dao.resolveCandidate(
                        candidate.id,
                        MemoryCandidateStatus.CONFLICT.name,
                        duplicate.id,
                        "memory_exact_duplicate",
                        nowMs,
                    )
                    return@withTransaction MemoryReviewResult.Conflict
                }
                insertCreatedMemory(
                    scopeId = candidate.scopeId,
                    proposal = proposal,
                    approvalSource = MemoryApprovalSource.USER_REVIEWED,
                    sourceType = "AUTO_EXTRACTION",
                    sourceConversationId = candidate.sourceConversationId,
                    candidateId = candidate.id,
                    originAssistantId = candidate.assistantId,
                    nowMs = nowMs,
                )
            }

            MemoryCandidateAction.UPDATE -> applyUpdate(candidate, proposal, nowMs)
                ?: return@withTransaction markConflict(candidate, nowMs)

            MemoryCandidateAction.MERGE -> applyMerge(candidate, proposal, nowMs)
                ?: return@withTransaction markConflict(candidate, nowMs)

            MemoryCandidateAction.IGNORE -> {
                memoryV2Dao.resolveCandidate(
                    candidate.id,
                    MemoryCandidateStatus.REJECTED.name,
                    null,
                    null,
                    nowMs,
                )
                return@withTransaction MemoryReviewResult.Rejected
            }
        }
        memoryV2Dao.resolveCandidate(
            candidate.id,
            MemoryCandidateStatus.ACCEPTED.name,
            memoryId,
            null,
            nowMs,
        )
        MemoryReviewResult.Applied(memoryId)
    }

    override suspend fun mutate(
        command: MemoryMutationCommand,
        nowMs: Long,
    ): MemoryMutationResult = database.withTransaction {
        when (command) {
            is MemoryMutationCommand.Create -> {
                val title = command.title?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: command.content.trim().lineSequence().firstOrNull().orEmpty().take(80)
                val proposal = MemoryProposal(
                    action = MemoryCandidateAction.CREATE,
                    title = title,
                    content = command.content,
                    kind = command.kind,
                    tags = command.tags,
                    importance = command.importance,
                    confidence = command.confidence,
                    expiresAtMs = command.expiresAtMs,
                    evidenceMessageIds = command.sourceMessageIds,
                    reason = command.sourceType,
                )
                if (!proposal.isValidManualMutation()) {
                    return@withTransaction MemoryMutationResult.Rejected("memory_mutation_invalid")
                }
                val duplicate = memoryDao.findActiveByContentHash(
                    command.scopeId,
                    memoryContentHash(command.content),
                    nowMs,
                )
                if (duplicate != null) return@withTransaction MemoryMutationResult.Conflict
                val memoryId = insertCreatedMemory(
                    scopeId = command.scopeId,
                    proposal = proposal,
                    approvalSource = command.approvalSource,
                    sourceType = command.sourceType,
                    sourceConversationId = command.sourceConversationId,
                    candidateId = null,
                    originAssistantId = command.originAssistantId,
                    nowMs = nowMs,
                )
                MemoryMutationResult.Applied(memoryId, 1)
            }

            is MemoryMutationCommand.Update -> {
                val old = memoryDao.getMemoryById(command.memoryId)
                    ?: return@withTransaction MemoryMutationResult.NotFound
                if (command.expectedRevision != null && old.revision != command.expectedRevision) {
                    return@withTransaction MemoryMutationResult.Conflict
                }
                val updated = old.copy(
                    title = command.title?.trim().takeUnless { it.isNullOrEmpty() } ?: old.title,
                    content = command.content.trim(),
                    updatedAtMs = nowMs,
                    expiresAtMs = command.expiresAtMs,
                    memoryKind = command.kind?.name ?: old.memoryKind,
                    importance = command.importance ?: old.importance,
                    tagsJson = command.tags?.let { json.encodeToString(it) } ?: old.tagsJson,
                    tagsSearch = command.tags?.joinToString(" ") { it.trim() } ?: old.tagsSearch,
                    contentHash = memoryContentHash(command.content),
                    approvalSource = command.approvalSource.name,
                    revision = old.revision + 1,
                )
                if (updated.content.length !in 1..2_000) {
                    return@withTransaction MemoryMutationResult.Rejected("memory_content_invalid")
                }
                memoryDao.updateMemory(updated)
                memoryV2Dao.insertRevision(
                    revisionEntity(
                        memory = updated,
                        operation = MemoryRevisionOperation.UPDATE,
                        before = old,
                        actor = command.approvalSource.name,
                        candidateId = null,
                        nowMs = nowMs,
                    ),
                )
                memoryV2Dao.trimRevisions(updated.id)
                MemoryMutationResult.Applied(updated.id, updated.revision)
            }

            is MemoryMutationCommand.Archive -> mutateLifecycle(
                memoryId = command.memoryId,
                lifecycle = MemoryLifecycleStatus.ARCHIVED,
                operation = MemoryRevisionOperation.ARCHIVE,
                approvalSource = command.approvalSource,
                nowMs = nowMs,
            )

            is MemoryMutationCommand.Restore -> mutateLifecycle(
                memoryId = command.memoryId,
                lifecycle = MemoryLifecycleStatus.ACTIVE,
                operation = MemoryRevisionOperation.RESTORE,
                approvalSource = command.approvalSource,
                nowMs = nowMs,
            )

            is MemoryMutationCommand.RestoreRevision -> {
                val old = memoryDao.getMemoryById(command.memoryId)
                    ?: return@withTransaction MemoryMutationResult.NotFound
                val revision = memoryV2Dao.findRevision(command.memoryId, command.revision)
                    ?: return@withTransaction MemoryMutationResult.NotFound
                val raw = revision.afterSnapshotJson
                    ?: return@withTransaction MemoryMutationResult.Rejected("memory_revision_empty")
                val snapshot = runCatching { json.decodeFromString<MemoryRecordSnapshot>(raw) }
                    .getOrElse {
                        return@withTransaction MemoryMutationResult.Rejected("memory_revision_invalid")
                    }
                val restored = old.copy(
                    title = snapshot.title,
                    content = snapshot.content,
                    updatedAtMs = nowMs,
                    expiresAtMs = snapshot.expiresAtMs,
                    memoryKind = snapshot.kind,
                    importance = snapshot.importance,
                    confidence = snapshot.confidence,
                    tagsJson = json.encodeToString(snapshot.tags),
                    tagsSearch = snapshot.tags.joinToString(" "),
                    contentHash = memoryContentHash(snapshot.content),
                    lifecycleStatus = MemoryLifecycleStatus.ACTIVE.name,
                    approvalSource = command.approvalSource.name,
                    revision = old.revision + 1,
                )
                memoryDao.updateMemory(restored)
                memoryV2Dao.insertRevision(
                    revisionEntity(
                        memory = restored,
                        operation = MemoryRevisionOperation.RESTORE,
                        before = old,
                        actor = command.approvalSource.name,
                        candidateId = null,
                        nowMs = nowMs,
                    ),
                )
                memoryV2Dao.trimRevisions(restored.id)
                MemoryMutationResult.Applied(restored.id, restored.revision)
            }
        }
    }

    private suspend fun insertCreatedMemory(
        scopeId: String,
        proposal: MemoryProposal,
        approvalSource: MemoryApprovalSource,
        sourceType: String,
        sourceConversationId: String?,
        candidateId: String?,
        originAssistantId: String? = null,
        evidenceCaptures: List<MemoryCaptureRecord> = emptyList(),
        nowMs: Long,
    ): Int {
        val memory = proposal.toNewEntity(
            scopeId = scopeId,
            approvalSource = approvalSource,
            sourceType = sourceType,
            sourceConversationId = sourceConversationId,
            originAssistantId = originAssistantId,
            nowMs = nowMs,
            json = json,
        )
        val memoryId = memoryDao.insertMemory(memory).toInt()
        val inserted = memory.copy(id = memoryId)
        memoryV2Dao.insertRevision(
            revisionEntity(
                memory = inserted,
                operation = MemoryRevisionOperation.CREATE,
                before = null,
                actor = approvalSource.name,
                candidateId = candidateId,
                nowMs = nowMs,
            ),
        )
        val evidence = buildEvidenceCapsules(inserted, proposal, evidenceCaptures, nowMs)
        if (evidence.isNotEmpty()) memoryV2Dao.insertEvidence(evidence)
        return memoryId
    }

    private suspend fun applyUpdate(
        candidate: MemoryCandidateEntity,
        proposal: MemoryProposal,
        nowMs: Long,
    ): Int? {
        val targetId = proposal.targetIds.singleOrNull() ?: return null
        val expected = proposal.expectedRevisions.singleOrNull() ?: return null
        val old = memoryDao.getMemoryById(targetId) ?: return null
        if (old.assistantId != candidate.scopeId || old.revision != expected) return null
        val updated = old.applyProposal(proposal, nowMs)
        memoryDao.updateMemory(updated)
        memoryV2Dao.insertRevision(
            revisionEntity(
                memory = updated,
                operation = MemoryRevisionOperation.UPDATE,
                before = old,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                candidateId = candidate.id,
                nowMs = nowMs,
            ),
        )
        memoryV2Dao.trimRevisions(updated.id)
        return updated.id
    }

    private suspend fun applyMerge(
        candidate: MemoryCandidateEntity,
        proposal: MemoryProposal,
        nowMs: Long,
    ): Int? {
        if (proposal.targetIds.size < 2 ||
            proposal.targetIds.size != proposal.expectedRevisions.size
        ) return null
        val byId = memoryDao.getMemoriesByIds(proposal.targetIds).associateBy { it.id }
        val targets = proposal.targetIds.map { byId[it] ?: return null }
        if (targets.any { it.assistantId != candidate.scopeId } ||
            targets.zip(proposal.expectedRevisions).any { (memory, expected) ->
                memory.revision != expected
            }
        ) return null

        val primaryOld = targets.first()
        val primary = primaryOld.applyProposal(proposal, nowMs)
        memoryDao.updateMemory(primary)
        memoryV2Dao.insertRevision(
            revisionEntity(
                memory = primary,
                operation = MemoryRevisionOperation.MERGE,
                before = primaryOld,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                candidateId = candidate.id,
                nowMs = nowMs,
            ),
        )
        memoryV2Dao.trimRevisions(primary.id)
        targets.drop(1).forEach { old ->
            val archived = old.copy(
                lifecycleStatus = MemoryLifecycleStatus.ARCHIVED.name,
                approvalSource = MemoryApprovalSource.USER_REVIEWED.name,
                updatedAtMs = nowMs,
                revision = old.revision + 1,
            )
            memoryDao.updateMemory(archived)
            memoryV2Dao.insertRevision(
                revisionEntity(
                    memory = archived,
                    operation = MemoryRevisionOperation.ARCHIVE,
                    before = old,
                    actor = MemoryApprovalSource.USER_REVIEWED.name,
                    candidateId = candidate.id,
                    nowMs = nowMs,
                ),
            )
            memoryV2Dao.trimRevisions(archived.id)
        }
        return primary.id
    }

    private suspend fun markConflict(
        candidate: MemoryCandidateEntity,
        nowMs: Long,
    ): MemoryReviewResult {
        memoryV2Dao.resolveCandidate(
            candidate.id,
            MemoryCandidateStatus.CONFLICT.name,
            null,
            "memory_revision_conflict",
            nowMs,
        )
        return MemoryReviewResult.Conflict
    }

    private suspend fun mutateLifecycle(
        memoryId: Int,
        lifecycle: MemoryLifecycleStatus,
        operation: MemoryRevisionOperation,
        approvalSource: MemoryApprovalSource,
        nowMs: Long,
    ): MemoryMutationResult {
        val old = memoryDao.getMemoryById(memoryId) ?: return MemoryMutationResult.NotFound
        if (old.lifecycleStatus == lifecycle.name) {
            return MemoryMutationResult.Applied(old.id, old.revision)
        }
        val updated = old.copy(
            lifecycleStatus = lifecycle.name,
            approvalSource = approvalSource.name,
            updatedAtMs = nowMs,
            revision = old.revision + 1,
        )
        memoryDao.updateMemory(updated)
        memoryV2Dao.insertRevision(
            revisionEntity(
                memory = updated,
                operation = operation,
                before = old,
                actor = approvalSource.name,
                candidateId = null,
                nowMs = nowMs,
            ),
        )
        memoryV2Dao.trimRevisions(updated.id)
        return MemoryMutationResult.Applied(updated.id, updated.revision)
    }

    private fun revisionEntity(
        memory: MemoryEntity,
        operation: MemoryRevisionOperation,
        before: MemoryEntity?,
        actor: String,
        candidateId: String?,
        nowMs: Long,
    ) = MemoryRevisionEntity(
        id = idGenerator(),
        memoryId = memory.id,
        revision = memory.revision,
        operation = operation.name,
        beforeSnapshotJson = before?.let { json.encodeToString(it.toSnapshot(json)) },
        afterSnapshotJson = json.encodeToString(memory.toSnapshot(json)),
        actor = actor,
        candidateId = candidateId,
        sourceConversationId = memory.sourceConversationId,
        sourceMessageIdsJson = memory.sourceMessageIdsJson,
        createdAtMs = nowMs,
    )
}

private fun MemoryCaptureEntity.toRecord() = MemoryCaptureRecord(
    id = id,
    assistantId = assistantId,
    scopeId = scopeId,
    conversationId = conversationId,
    userMessageId = userMessageId,
    assistantMessageId = assistantMessageId,
    origin = enumValueOrDefault(origin, MemoryCaptureOrigin.INTERNAL),
    captureSource = enumValueOrDefault(captureSource, MemoryCaptureSource.AUTOMATIC_TURN),
    autoSaveMode = enumValueOrDefault(autoSaveMode, MemoryAutoSaveMode.REVIEW_ALL),
    userText = userText,
    assistantText = assistantText,
    createdAtMs = createdAtMs,
    conversationContextTurns = contextTurnLimit,
    narrativeEventsEnabled = narrativeEventsEnabled,
    insightsTheoriesEnabled = insightsTheoriesEnabled,
)

private fun MemoryEntity.toExistingRecord(json: Json) = ExistingMemoryRecord(
    id = id,
    scopeId = assistantId,
    title = title,
    content = content,
    revision = revision,
    kind = enumValueOrDefault(memoryKind, MemoryKind.OTHER),
    tags = decodeList(json, tagsJson),
)

private fun MemoryCandidateDecision.toEntity(
    commit: MemoryProcessCommit,
    status: MemoryCandidateStatus,
    appliedMemoryId: Int?,
    json: Json,
) = MemoryCandidateEntity(
    id = id,
    scopeId = commit.scopeId,
    assistantId = commit.assistantId,
    sourceConversationId = commit.conversationId,
    captureIdsJson = json.encodeToString(commit.captures.map { it.id }),
    action = proposal.action.name,
    targetMemoryIdsJson = json.encodeToString(proposal.targetIds),
    expectedRevisionsJson = json.encodeToString(proposal.expectedRevisions),
    title = proposal.title.trim(),
    content = proposal.content.trim(),
    memoryKind = proposal.kind.name,
    tagsJson = json.encodeToString(proposal.tags.map(String::trim).filter(String::isNotEmpty)),
    importance = proposal.importance,
    confidence = proposal.confidence,
    expiresAtMs = proposal.expiresAtMs,
    riskFlagsJson = json.encodeToString(risks.map(MemoryRiskFlag::name)),
    reason = proposal.reason.take(MAX_STORED_REASON_CHARS),
    evidenceMessageIdsJson = json.encodeToString(proposal.evidenceMessageIds),
    status = status.name,
    appliedMemoryId = appliedMemoryId,
    createdAtMs = commit.nowMs,
    updatedAtMs = commit.nowMs,
    proposalKey = proposal.proposalKey,
    attribution = proposal.attribution.name,
    truthStatus = proposal.truthStatus.name,
    occurredAtMs = proposal.occurredAtMs,
    participantsJson = json.encodeToString(proposal.participants),
    outcome = proposal.outcome,
)

private fun MemoryCandidateEntity.toProposal(json: Json) = MemoryProposal(
    action = enumValueOrDefault(action, MemoryCandidateAction.IGNORE),
    targetIds = decodeList(json, targetMemoryIdsJson),
    expectedRevisions = decodeList(json, expectedRevisionsJson),
    title = title,
    content = content,
    kind = enumValueOrDefault(memoryKind, MemoryKind.OTHER),
    tags = decodeList(json, tagsJson),
    importance = importance,
    confidence = confidence,
    expiresAtMs = expiresAtMs,
    evidenceMessageIds = decodeList(json, evidenceMessageIdsJson),
    reason = reason,
    proposalKey = proposalKey,
    attribution = enumValueOrDefault(attribution, MemoryAttribution.UNKNOWN),
    truthStatus = enumValueOrDefault(truthStatus, MemoryTruthStatus.CONFIRMED),
    occurredAtMs = occurredAtMs,
    participants = decodeList(json, participantsJson),
    outcome = outcome,
)

private fun MemoryProposal.toNewEntity(
    scopeId: String,
    approvalSource: MemoryApprovalSource,
    sourceType: String,
    sourceConversationId: String?,
    originAssistantId: String?,
    nowMs: Long,
    json: Json,
) = MemoryEntity(
    assistantId = scopeId,
    content = content.trim(),
    title = title.trim(),
    updatedAtMs = nowMs,
    importance = importance,
    createdAtMs = nowMs,
    expiresAtMs = expiresAtMs,
    memoryKind = kind.name,
    confidence = confidence,
    tagsJson = json.encodeToString(tags.map(String::trim).filter(String::isNotEmpty)),
    tagsSearch = tags.joinToString(" ") { it.trim() },
    contentHash = memoryContentHash(content),
    sourceType = sourceType,
    sourceConversationId = sourceConversationId,
    sourceMessageIdsJson = json.encodeToString(evidenceMessageIds),
    lifecycleStatus = MemoryLifecycleStatus.ACTIVE.name,
    approvalSource = approvalSource.name,
    revision = 1,
    originAssistantId = originAssistantId,
    attribution = attribution.name,
    truthStatus = truthStatus.name,
    occurredAtMs = occurredAtMs,
    participantsJson = json.encodeToString(participants),
    outcome = outcome,
)

private fun MemoryEntity.applyProposal(proposal: MemoryProposal, nowMs: Long) = copy(
    title = proposal.title.trim(),
    content = proposal.content.trim(),
    updatedAtMs = nowMs,
    expiresAtMs = proposal.expiresAtMs,
    memoryKind = proposal.kind.name,
    confidence = proposal.confidence,
    importance = proposal.importance,
    tagsJson = Json.encodeToString(proposal.tags.map(String::trim).filter(String::isNotEmpty)),
    tagsSearch = proposal.tags.joinToString(" ") { it.trim() },
    contentHash = memoryContentHash(proposal.content),
    sourceType = "AUTO_EXTRACTION",
    sourceMessageIdsJson = Json.encodeToString(proposal.evidenceMessageIds),
    lifecycleStatus = MemoryLifecycleStatus.ACTIVE.name,
    approvalSource = MemoryApprovalSource.USER_REVIEWED.name,
    revision = revision + 1,
    attribution = proposal.attribution.name,
    truthStatus = proposal.truthStatus.name,
    occurredAtMs = proposal.occurredAtMs,
    participantsJson = Json.encodeToString(proposal.participants),
    outcome = proposal.outcome,
)

private fun MemoryEntity.toSnapshot(json: Json) = MemoryRecordSnapshot(
    id = id,
    scopeId = assistantId,
    title = title,
    content = content,
    kind = memoryKind,
    tags = decodeList(json, tagsJson),
    importance = importance,
    confidence = confidence,
    expiresAtMs = expiresAtMs,
    lifecycleStatus = lifecycleStatus,
    approvalSource = approvalSource,
    revision = revision,
    updatedAtMs = updatedAtMs,
    originAssistantId = originAssistantId,
    attribution = attribution,
    truthStatus = truthStatus,
    occurredAtMs = occurredAtMs,
    participants = decodeList(json, participantsJson),
    outcome = outcome,
)

private fun MemoryProposal.isValidReviewedEdit(): Boolean =
    title.trim().length in 1..80 &&
        content.trim().length in 8..maxContentChars() &&
        tags.size <= 8 &&
        tags.all { tag ->
            tag.trim().length in 1..32 && '|' !in tag && tag.none(Char::isISOControl)
        } &&
        importance in 0f..1f && confidence in 0f..1f

private fun MemoryProposal.isValidManualMutation(): Boolean =
    title.trim().length in 1..80 &&
        content.trim().length in 1..maxContentChars() &&
        tags.size <= 8 &&
        tags.all { tag ->
            tag.trim().length in 1..32 && '|' !in tag && tag.none(Char::isISOControl)
        } &&
        importance in 0f..1f && confidence in 0f..1f

private fun MemoryProposal.maxContentChars(): Int =
    if (kind == MemoryKind.INSIGHT || kind == MemoryKind.THEORY) 4_000 else 2_000

private fun buildEvidenceCapsules(
    memory: MemoryEntity,
    proposal: MemoryProposal,
    captures: List<MemoryCaptureRecord>,
    nowMs: Long,
): List<MemoryEvidenceEntity> {
    val guard = MemoryContentGuard()
    var remaining = 1_500
    return captures.flatMap { capture ->
        listOf(
            Triple(capture.userMessageId, "USER", capture.userText),
            Triple(capture.assistantMessageId, "ASSISTANT", capture.assistantText),
        )
    }.filter { (messageId) -> messageId in proposal.evidenceMessageIds }
        .take(3)
        .mapNotNull { (messageId, role, text) ->
            if (remaining <= 0) return@mapNotNull null
            val excerpt = guard.redact(text).text.trim().take(minOf(600, remaining))
            if (excerpt.isEmpty()) return@mapNotNull null
            remaining -= excerpt.length
            MemoryEvidenceEntity(
                id = "evidence-${memory.id}-$messageId",
                memoryId = memory.id,
                conversationId = memory.sourceConversationId.orEmpty(),
                messageId = messageId,
                role = role,
                excerpt = excerpt,
                contentHash = memoryContentHash(excerpt),
                capturedAtMs = nowMs,
            )
        }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

private inline fun <reified T> decodeList(json: Json, raw: String): List<T> =
    runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())

private const val MAX_STORED_ERROR_CHARS = 1_000
private const val MAX_STORED_REASON_CHARS = 2_000

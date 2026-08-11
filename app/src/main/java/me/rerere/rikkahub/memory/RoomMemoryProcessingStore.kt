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
import me.rerere.rikkahub.data.db.entity.MemoryLinkEntity
import me.rerere.rikkahub.data.db.entity.MemoryLinkRevisionEntity
import me.rerere.rikkahub.data.db.entity.MemoryRelationCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetrievalRequest
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
            requireValidScope(request.scopeId)
            require(request.workerId.isNotBlank()) { "memory_worker_id_missing" }
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
                        scopeId = request.scopeId,
                        workerId = request.workerId,
                        leaseUntilMs = request.leaseUntilMs,
                        nowMs = request.nowMs,
                    )
                    if (won == 1) claimed += capture.toRecord(request.workerId)
                }
            }
            claimed
        }

    override suspend fun findExisting(
        scopeId: String,
        query: String,
        limit: Int,
        frozenNowMs: Long,
    ): List<ExistingMemoryRecord> {
        val matches = if (scopeId == MemoryRepository.GLOBAL_MEMORY_ID) {
            retriever.retrieve(MemoryRetrievalRequest(
                assistantId = null,
                query = query,
                includeGlobal = true,
                limit = limit,
                maxChars = 20_000,
                frozenNowMs = frozenNowMs,
            )).matches
        } else {
            val assistantId = runCatching { Uuid.parse(scopeId) }.getOrNull() ?: return emptyList()
            retriever.retrieve(MemoryRetrievalRequest(
                assistantId = assistantId,
                query = query,
                includeGlobal = false,
                limit = limit,
                maxChars = 20_000,
                frozenNowMs = frozenNowMs,
            )).matches
        }
        if (matches.isEmpty()) return emptyList()
        val byId = memoryDao.getMemoriesByIds(
            ids = matches.map { it.memory.id },
            scopeId = scopeId,
        ).associateBy { it.id }
        return matches.mapNotNull { match -> byId[match.memory.id]?.toExistingRecord(json) }
    }

    override suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult =
        database.withTransaction {
            validateCommitLease(commit)
            var autoApplied = 0
            var pendingReview = 0
            var superseded = 0
            val candidateStatesByKey = linkedMapOf<String, PersistedCandidateState>()
            commit.candidates.forEach { decision ->
                var status = when (decision.disposition) {
                    MemoryCandidateDisposition.AUTO_APPLY -> MemoryCandidateStatus.AUTO_APPLIED
                    MemoryCandidateDisposition.REVIEW -> MemoryCandidateStatus.PENDING_REVIEW
                    MemoryCandidateDisposition.SUPERSEDE -> MemoryCandidateStatus.SUPERSEDED
                    MemoryCandidateDisposition.IGNORE -> MemoryCandidateStatus.SUPERSEDED
                }
                var appliedMemoryId: Int? = null
                if (decision.disposition == MemoryCandidateDisposition.AUTO_APPLY ||
                    decision.disposition == MemoryCandidateDisposition.SUPERSEDE
                ) {
                    val proposal = decision.proposal
                    val exact = memoryDao.findActiveByContentHash(
                        scopeId = commit.scopeId,
                        contentHash = memoryContentHash(proposal.content),
                        nowMs = commit.nowMs,
                    )
                    if (exact != null) {
                        status = MemoryCandidateStatus.SUPERSEDED
                        // Preserve the exact target so a batch-local relation proposal can still
                        // resolve this proposal key without manufacturing another memory row.
                        appliedMemoryId = exact.id
                    } else if (decision.disposition == MemoryCandidateDisposition.AUTO_APPLY) {
                        appliedMemoryId = insertCreatedMemory(
                            scopeId = commit.scopeId,
                            proposal = proposal,
                            approvalSource = MemoryApprovalSource.AUTO_SAFE,
                            sourceType = "AUTO_EXTRACTION",
                            sourceConversationId = commit.conversationId,
                            candidateId = decision.id,
                            originAssistantId = commit.assistantId,
                            nowMs = commit.nowMs,
                        )
                    }
                }
                val candidate = decision.toEntity(
                    commit = commit,
                    status = status,
                    appliedMemoryId = appliedMemoryId,
                    json = json,
                )
                memoryV2Dao.insertCandidate(candidate)
                val evidence = buildCandidateEvidenceCapsules(
                    candidateId = candidate.id,
                    memoryId = appliedMemoryId,
                    conversationId = commit.conversationId,
                    evidenceMessageIds = decision.proposal.evidenceMessageIds,
                    captures = commit.captures,
                    nowMs = commit.nowMs,
                )
                if (evidence.isNotEmpty()) memoryV2Dao.insertEvidence(evidence)
                decision.proposal.proposalKey?.let { key ->
                    candidateStatesByKey[key] = PersistedCandidateState(
                        id = decision.id,
                        appliedMemoryId = appliedMemoryId,
                    )
                }
                when (status) {
                    MemoryCandidateStatus.AUTO_APPLIED -> autoApplied++
                    MemoryCandidateStatus.PENDING_REVIEW -> pendingReview++
                    MemoryCandidateStatus.SUPERSEDED -> superseded++
                    else -> Unit
                }
            }

            commit.relations.forEach { relation ->
                persistRelationCandidate(
                    commit = commit,
                    decision = relation,
                    candidateStatesByKey = candidateStatesByKey,
                )
            }

            val processed = memoryV2Dao.markCapturesProcessed(
                ids = commit.captures.map { it.id },
                scopeId = commit.scopeId,
                assistantId = commit.assistantId,
                conversationId = commit.conversationId,
                workerId = commit.workerId,
                nowMs = commit.nowMs,
                processingOutcome = if (commit.candidates.isEmpty()) {
                    "NO_LONG_TERM_SIGNAL"
                } else {
                    "CANDIDATES_CREATED"
                },
                candidateCount = commit.candidates.size,
            )
            check(processed == commit.captures.size) { "memory_lease_lost_before_commit" }
            MemoryCommitResult(autoApplied, pendingReview, superseded)
        }

    override suspend fun markFailed(
        captureIds: List<String>,
        scopeId: String,
        workerId: String,
        code: String,
        message: String?,
        retryPolicy: MemoryFailureRetryPolicy,
        nowMs: Long,
    ) {
        if (captureIds.isEmpty()) return
        memoryV2Dao.markCapturesFailed(
            ids = captureIds,
            scopeId = scopeId,
            workerId = workerId,
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

    override suspend fun releaseClaimed(
        captureIds: List<String>,
        scopeId: String,
        workerId: String,
        nowMs: Long,
    ) {
        if (captureIds.isNotEmpty()) {
            memoryV2Dao.releaseClaimedCaptures(captureIds, scopeId, workerId, nowMs)
        }
    }

    override suspend fun review(
        command: MemoryReviewCommand,
        nowMs: Long,
    ): MemoryReviewResult = database.withTransaction {
        val candidateId = when (command) {
            is MemoryReviewCommand.Accept -> command.candidateId
            is MemoryReviewCommand.Reject -> command.candidateId
        }
        val expectedScopeId = when (command) {
            is MemoryReviewCommand.Accept -> command.expectedScopeId
            is MemoryReviewCommand.Reject -> command.expectedScopeId
        }
        val candidate = memoryV2Dao.findCandidate(candidateId, expectedScopeId)
            ?: return@withTransaction MemoryReviewResult.NotFound
        if (candidate.status == MemoryCandidateStatus.CONFLICT.name) {
            return@withTransaction MemoryReviewResult.Conflict
        }
        if (candidate.status != MemoryCandidateStatus.PENDING_REVIEW.name) {
            return@withTransaction MemoryReviewResult.AlreadyResolved
        }
        if (command is MemoryReviewCommand.Reject) {
            memoryV2Dao.resolveCandidate(
                candidateId = candidate.id,
                scopeId = expectedScopeId,
                status = MemoryCandidateStatus.REJECTED.name,
                appliedMemoryId = null,
                resolutionError = null,
                nowMs = nowMs,
            )
            memoryV2Dao.invalidateRelationsForMemoryCandidate(
                memoryCandidateId = candidate.id,
                scopeId = expectedScopeId,
                reason = "MEMORY_CANDIDATE_REJECTED",
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
                        candidate.scopeId,
                        MemoryCandidateStatus.CONFLICT.name,
                        duplicate.id,
                        "memory_exact_duplicate",
                        nowMs,
                    )
                    memoryV2Dao.invalidateRelationsForMemoryCandidate(
                        candidate.id,
                        candidate.scopeId,
                        "MEMORY_CANDIDATE_CONFLICT",
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
                    candidate.scopeId,
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
            candidate.scopeId,
            MemoryCandidateStatus.ACCEPTED.name,
            memoryId,
            null,
            nowMs,
        )
        memoryV2Dao.attachCandidateEvidenceToMemory(candidate.id, memoryId)
        MemoryReviewResult.Applied(memoryId)
    }

    override suspend fun reviewRelation(
        command: MemoryRelationReviewCommand,
        nowMs: Long,
    ): MemoryRelationReviewResult = database.withTransaction {
        val candidate = memoryV2Dao.findRelationCandidate(
            candidateId = command.relationCandidateId,
            scopeId = command.expectedScopeId,
        ) ?: return@withTransaction MemoryRelationReviewResult.NotFound
        when (candidate.status) {
            MemoryRelationCandidateStatus.ACCEPTED.name,
            MemoryRelationCandidateStatus.REJECTED.name,
            MemoryRelationCandidateStatus.INVALIDATED.name,
            -> return@withTransaction MemoryRelationReviewResult.AlreadyResolved
        }
        if (command is MemoryRelationReviewCommand.Reject) {
            memoryV2Dao.resolveRelationCandidate(
                candidateId = candidate.id,
                scopeId = candidate.scopeId,
                status = MemoryRelationCandidateStatus.REJECTED.name,
                resolvedLinkId = null,
                resolutionError = null,
                nowMs = nowMs,
            )
            return@withTransaction MemoryRelationReviewResult.Rejected
        }

        val source = resolveRelationEndpoint(candidate, source = true, nowMs = nowMs)
            ?: return@withTransaction invalidateRelationCandidate(
                candidate,
                "RELATION_SOURCE_CONFLICT",
                nowMs,
            )
        val target = resolveRelationEndpoint(candidate, source = false, nowMs = nowMs)
            ?: return@withTransaction invalidateRelationCandidate(
                candidate,
                "RELATION_TARGET_CONFLICT",
                nowMs,
            )
        if (source.id == target.id) {
            return@withTransaction invalidateRelationCandidate(candidate, "RELATION_SELF", nowMs)
        }
        if (candidate.relationType == MemoryRelationType.DERIVED_FROM.name &&
            wouldCreateDerivedCycle(candidate.scopeId, source.id, target.id)
        ) {
            return@withTransaction invalidateRelationCandidate(candidate, "RELATION_CYCLE", nowMs)
        }
        val existing = memoryV2Dao.findLinkByEndpoints(
            scopeId = candidate.scopeId,
            sourceMemoryId = source.id,
            targetMemoryId = target.id,
            relationType = candidate.relationType,
        )
        if (existing != null) {
            if (existing.lifecycleStatus != MemoryLinkLifecycleStatus.ACTIVE.name) {
                return@withTransaction invalidateRelationCandidate(
                    candidate,
                    "RELATION_LINK_CONFLICT",
                    nowMs,
                )
            }
            memoryV2Dao.resolveRelationCandidate(
                candidate.id,
                candidate.scopeId,
                MemoryRelationCandidateStatus.ACCEPTED.name,
                existing.id,
                null,
                nowMs,
            )
            memoryV2Dao.attachRelationEvidenceToLink(candidate.id, existing.id)
            return@withTransaction MemoryRelationReviewResult.Applied(existing.id)
        }

        val (reviewedSource, reviewedTarget) = applyReviewedRelationTruth(
            candidate.relationType,
            source,
            target,
            nowMs,
        )
        val link = MemoryLinkEntity(
            id = idGenerator(),
            sourceMemoryId = source.id,
            targetMemoryId = target.id,
            relationType = candidate.relationType,
            weight = candidate.weight,
            description = candidate.description,
            evidenceMessageIdsJson = candidate.evidenceMessageIdsJson,
            createdByAssistantId = candidate.createdByAssistantId,
            createdAtMs = nowMs,
            revision = 1,
            scopeId = candidate.scopeId,
            lifecycleStatus = MemoryLinkLifecycleStatus.ACTIVE.name,
            sourceRevision = reviewedSource.revision,
            targetRevision = reviewedTarget.revision,
            sourceSemanticHash = reviewedSource.semanticHash(json),
            targetSemanticHash = reviewedTarget.semanticHash(json),
            relationCandidateId = candidate.id,
            updatedAtMs = nowMs,
        )
        val insert = memoryV2Dao.insertLinks(listOf(link)).singleOrNull()
        check(insert != null && insert != -1L) { "memory_relation_link_insert_conflict" }
        memoryV2Dao.insertLinkRevision(
            linkRevisionEntity(
                link = link,
                operation = MemoryLinkRevisionOperation.CREATE,
                before = null,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                relationCandidateId = candidate.id,
                reasonCode = "RELATION_REVIEW_ACCEPTED",
                nowMs = nowMs,
            ),
        )
        memoryV2Dao.resolveRelationCandidate(
            candidate.id,
            candidate.scopeId,
            MemoryRelationCandidateStatus.ACCEPTED.name,
            link.id,
            null,
            nowMs,
        )
        memoryV2Dao.attachRelationEvidenceToLink(candidate.id, link.id)
        MemoryRelationReviewResult.Applied(link.id)
    }

    override suspend fun invalidateSourceConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int = database.withTransaction {
        requireValidScope(scopeId)
        memoryV2Dao.purgeCapturePayloadsForConversation(scopeId, conversationId, nowMs)
        memoryV2Dao.invalidateEvidenceForConversation(conversationId)
        memoryV2Dao.invalidateRelationCandidatesForConversation(scopeId, conversationId, nowMs)
        memoryV2Dao.invalidateCandidatesForConversation(scopeId, conversationId, nowMs)
        invalidateFormalMemorySources(
            scopeId = scopeId,
            conversationId = conversationId,
            deletedMessageIds = null,
            nowMs = nowMs,
        )
    }

    override suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
        nowMs: Long,
    ): Int = database.withTransaction {
        requireValidScope(scopeId)
        val ids = messageIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) return@withTransaction 0
        memoryV2Dao.purgeCapturePayloadsForMessages(scopeId, conversationId, ids, nowMs)
        memoryV2Dao.invalidateEvidenceForMessages(conversationId, ids)
        memoryV2Dao.invalidateRelationCandidatesForMessages(
            scopeId,
            conversationId,
            ids,
            nowMs,
        )
        memoryV2Dao.invalidateCandidatesForMessages(scopeId, conversationId, ids, nowMs)
        invalidateFormalMemorySources(
            scopeId = scopeId,
            conversationId = conversationId,
            deletedMessageIds = ids.toSet(),
            nowMs = nowMs,
        )
    }

    override suspend fun runRetention(nowMs: Long): Int = database.withTransaction {
        val processedPurged = memoryV2Dao.purgeProcessedCapturePayloads(
            processedBeforeMs = nowMs - PROCESSED_CAPTURE_RETENTION_MS,
            nowMs = nowMs,
        )
        val failedPurged = memoryV2Dao.discardAndPurgeFailedCapturePayloads(
            failedBeforeMs = nowMs - FAILED_CAPTURE_RETENTION_MS,
            nowMs = nowMs,
        )
        var expired = 0
        memoryDao.getAllDueForExpiryMaterialization(nowMs, MAX_EXPIRY_MATERIALIZATION_BATCH)
            .forEach { memory ->
                val current = memoryDao.getMemoryById(memory.id, memory.assistantId)
                    ?: return@forEach
                if (current.lifecycleStatus != MemoryLifecycleStatus.ACTIVE.name ||
                    current.expiresAtMs == null || current.expiresAtMs > nowMs
                ) return@forEach
                val materialized = current.copy(
                    lifecycleStatus = MemoryLifecycleStatus.EXPIRED.name,
                    revision = current.revision + 1,
                    updatedAtMs = nowMs,
                )
                check(memoryDao.updateMemory(materialized) == 1) { "memory_expiry_update_lost" }
                memoryV2Dao.insertRevision(
                    revisionEntity(
                        memory = materialized,
                        operation = MemoryRevisionOperation.EXPIRE,
                        before = current,
                        actor = "RETENTION",
                        candidateId = null,
                        nowMs = nowMs,
                        reasonCode = "TTL_EXPIRED",
                    ),
                )
                memoryV2Dao.trimRevisions(materialized.id)
                staleDerivedDescendants(materialized, "SOURCE_EXPIRED", "RETENTION", nowMs)
                suspendIncidentLinks(materialized, "RETENTION", nowMs)
                expired++
            }
        processedPurged + failedPurged + expired
    }

    override suspend fun purgeScope(scopeId: String, nowMs: Long): Int =
        database.withTransaction {
            require(scopeId != MemoryRepository.GLOBAL_MEMORY_ID) {
                "memory_global_scope_purge_forbidden"
            }
            requireValidScope(scopeId)
            var changed = 0

            // Global formal memories are shared and must survive assistant removal. Remove the
            // deleted assistant's raw/candidate provenance and fail closed only for unreviewed
            // automatic memories which no longer have evidence.
            changed += memoryV2Dao.scrubGlobalCapturesForAssistant(
                MemoryRepository.GLOBAL_MEMORY_ID,
                scopeId,
                nowMs,
            )
            changed += memoryV2Dao.deleteGlobalCandidateEvidenceForAssistant(
                MemoryRepository.GLOBAL_MEMORY_ID,
                scopeId,
            )
            memoryV2Dao.getActiveGlobalLinksCreatedByAssistant(
                MemoryRepository.GLOBAL_MEMORY_ID,
                scopeId,
            ).forEach { link ->
                if (link.relationType == MemoryRelationType.DERIVED_FROM.name) {
                    staleDerivedLinkAndDescendants(
                        link,
                        "ORIGIN_ASSISTANT_REMOVED",
                        "ASSISTANT_REMOVAL",
                        nowMs,
                        hashSetOf(),
                    )
                } else {
                    transitionLink(
                        link = link,
                        lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
                        operation = MemoryLinkRevisionOperation.INVALIDATE,
                        actor = "ASSISTANT_REMOVAL",
                        reason = "ORIGIN_ASSISTANT_REMOVED",
                        nowMs = nowMs,
                    )
                }
                changed++
            }
            changed += memoryV2Dao.deleteGlobalRelationCandidatesForAssistant(
                MemoryRepository.GLOBAL_MEMORY_ID,
                scopeId,
            )
            changed += memoryV2Dao.deleteGlobalCandidatesForAssistant(
                MemoryRepository.GLOBAL_MEMORY_ID,
                scopeId,
            )
            memoryDao.getGlobalMemoriesByOriginAssistant(
                MemoryRepository.GLOBAL_MEMORY_ID,
                scopeId,
            ).forEach { old ->
                val shouldStale = old.approvalSource == MemoryApprovalSource.AUTO_SAFE.name &&
                    memoryV2Dao.countValidEvidence(old.id) == 0
                val updated = old.copy(
                    lifecycleStatus = if (shouldStale) {
                        MemoryLifecycleStatus.STALE.name
                    } else {
                        old.lifecycleStatus
                    },
                    sourceConversationId = null,
                    sourceMessageIdsJson = "[]",
                    revision = old.revision + 1,
                    updatedAtMs = nowMs,
                )
                check(memoryDao.updateMemory(updated) == 1) { "memory_global_origin_update_lost" }
                memoryV2Dao.insertRevision(
                    revisionEntity(
                        memory = updated,
                        operation = if (shouldStale) {
                            MemoryRevisionOperation.STALE
                        } else {
                            MemoryRevisionOperation.UPDATE
                        },
                        before = old,
                        actor = "ASSISTANT_REMOVAL",
                        candidateId = null,
                        nowMs = nowMs,
                        reasonCode = "ORIGIN_ASSISTANT_REMOVED",
                    ),
                )
                memoryV2Dao.trimRevisions(updated.id)
                if (shouldStale) {
                    staleDerivedDescendants(
                        updated,
                        "ORIGIN_ASSISTANT_REMOVED",
                        "ASSISTANT_REMOVAL",
                        nowMs,
                    )
                    invalidateIncidentLinks(
                        updated,
                        "ORIGIN_ASSISTANT_REMOVED",
                        "ASSISTANT_REMOVAL",
                        nowMs,
                    )
                }
                changed++
            }

            changed += memoryV2Dao.deleteLinkRevisionsForScope(scopeId)
            changed += memoryV2Dao.deleteEvidenceForScope(scopeId)
            changed += memoryV2Dao.deleteMemoryRevisionsForScope(scopeId)
            changed += memoryV2Dao.deleteLinksForScope(scopeId)
            changed += memoryV2Dao.deleteRelationCandidatesForScope(scopeId)
            changed += memoryV2Dao.deleteCandidatesForScope(scopeId)
            changed += memoryV2Dao.deleteCapturesForScope(scopeId)
            changed += memoryV2Dao.deleteBackfillRunsForScope(scopeId)
            changed += memoryDao.deleteMemoriesOfAssistant(scopeId)
            changed
        }

    override suspend fun mutate(
        command: MemoryMutationCommand,
        nowMs: Long,
    ): MemoryMutationResult = database.withTransaction {
        when (command) {
            is MemoryMutationCommand.Create -> {
                if (!validCreateScope(command.scopeId, command.originAssistantId)) {
                    return@withTransaction MemoryMutationResult.Rejected("memory_scope_invalid")
                }
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
                val old = memoryDao.getMemoryById(command.memoryId, command.expectedScopeId)
                    ?: return@withTransaction MemoryMutationResult.NotFound
                if (command.expectedRevision != null && old.revision != command.expectedRevision) {
                    return@withTransaction MemoryMutationResult.Conflict
                }
                val updated = old.copy(
                    title = command.title?.trim().takeUnless { it.isNullOrEmpty() } ?: old.title,
                    content = command.content.trim(),
                    updatedAtMs = nowMs,
                    expiresAtMs = when (val expiry = command.expiryUpdate) {
                        MemoryExpiryUpdate.Keep -> old.expiresAtMs
                        MemoryExpiryUpdate.Clear -> null
                        is MemoryExpiryUpdate.Set -> expiry.expiresAtMs
                    },
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
                check(memoryDao.updateMemory(updated) == 1) { "memory_update_lost" }
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
                invalidateLinksAfterSemanticChange(old, updated, command.approvalSource.name, nowMs)
                MemoryMutationResult.Applied(updated.id, updated.revision)
            }

            is MemoryMutationCommand.Archive -> mutateLifecycle(
                memoryId = command.memoryId,
                scopeId = command.expectedScopeId,
                expectedRevision = command.expectedRevision,
                lifecycle = MemoryLifecycleStatus.ARCHIVED,
                operation = MemoryRevisionOperation.ARCHIVE,
                approvalSource = command.approvalSource,
                nowMs = nowMs,
            )

            is MemoryMutationCommand.Restore -> mutateLifecycle(
                memoryId = command.memoryId,
                scopeId = command.expectedScopeId,
                expectedRevision = command.expectedRevision,
                lifecycle = MemoryLifecycleStatus.ACTIVE,
                operation = MemoryRevisionOperation.RESTORE,
                approvalSource = command.approvalSource,
                nowMs = nowMs,
            )

            is MemoryMutationCommand.RestoreRevision -> {
                val old = memoryDao.getMemoryById(command.memoryId, command.expectedScopeId)
                    ?: return@withTransaction MemoryMutationResult.NotFound
                if (command.expectedCurrentRevision != null &&
                    command.expectedCurrentRevision != old.revision
                ) return@withTransaction MemoryMutationResult.Conflict
                val revision = memoryV2Dao.findRevision(
                    memoryId = command.memoryId,
                    revision = command.revision,
                    scopeId = command.expectedScopeId,
                )
                    ?: return@withTransaction MemoryMutationResult.NotFound
                val raw = revision.afterSnapshotJson
                    ?: return@withTransaction MemoryMutationResult.Rejected("memory_revision_empty")
                val snapshot = runCatching { json.decodeFromString<MemoryRecordSnapshot>(raw) }
                    .getOrElse {
                        return@withTransaction MemoryMutationResult.Rejected("memory_revision_invalid")
                    }
                if (snapshot.scopeId != old.assistantId) {
                    return@withTransaction MemoryMutationResult.Rejected("memory_revision_scope_mismatch")
                }
                if (snapshot.expiresAtMs != null && snapshot.expiresAtMs <= nowMs) {
                    return@withTransaction MemoryMutationResult.Rejected("memory_restore_expired")
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
                    originAssistantId = snapshot.originAssistantId,
                    attribution = snapshot.attribution,
                    truthStatus = snapshot.truthStatus,
                    occurredAtMs = snapshot.occurredAtMs,
                    participantsJson = json.encodeToString(snapshot.participants),
                    outcome = snapshot.outcome,
                    sourceType = snapshot.sourceType,
                    sourceConversationId = snapshot.sourceConversationId,
                    sourceMessageIdsJson = json.encodeToString(snapshot.sourceMessageIds),
                )
                check(memoryDao.updateMemory(restored) == 1) { "memory_restore_revision_lost" }
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
                invalidateLinksAfterSemanticChange(old, restored, command.approvalSource.name, nowMs)
                MemoryMutationResult.Applied(restored.id, restored.revision)
            }
        }
    }

    private suspend fun validateCommitLease(commit: MemoryProcessCommit) {
        requireValidScope(commit.scopeId)
        require(commit.workerId.isNotBlank()) { "memory_worker_id_missing" }
        require(commit.batchId.isNotBlank()) { "memory_batch_id_missing" }
        require(commit.captures.isNotEmpty()) { "memory_commit_empty" }
        require(commit.captures.map { it.id }.distinct().size == commit.captures.size) {
            "memory_commit_duplicate_capture"
        }
        require(commit.captures.all { capture ->
            capture.scopeId == commit.scopeId &&
                capture.assistantId == commit.assistantId &&
                capture.conversationId == commit.conversationId &&
                capture.leaseOwner == commit.workerId
        }) { "memory_commit_scope_or_lease_mismatch" }
        val owned = memoryV2Dao.countOwnedProcessingCaptures(
            ids = commit.captures.map { it.id },
            scopeId = commit.scopeId,
            assistantId = commit.assistantId,
            conversationId = commit.conversationId,
            workerId = commit.workerId,
            nowMs = commit.nowMs,
        )
        check(owned == commit.captures.size) { "memory_lease_lost_before_commit" }
    }

    private suspend fun persistRelationCandidate(
        commit: MemoryProcessCommit,
        decision: MemoryRelationDecision,
        candidateStatesByKey: Map<String, PersistedCandidateState>,
    ) {
        val proposal = decision.proposal
        val sourceCandidate = proposal.sourceProposalKey?.let(candidateStatesByKey::get)
        val targetCandidate = proposal.targetProposalKey?.let(candidateStatesByKey::get)
        var error: String? = null
        if (proposal.sourceProposalKey != null && sourceCandidate == null) {
            error = "RELATION_SOURCE_PROPOSAL_MISSING"
        }
        if (proposal.targetProposalKey != null && targetCandidate == null) {
            error = error ?: "RELATION_TARGET_PROPOSAL_MISSING"
        }
        val sourceExisting = proposal.sourceMemoryId?.let { id ->
            val memory = memoryDao.getMemoryById(id, commit.scopeId)
            if (memory == null || decision.sourceExpectedRevision == null ||
                memory.revision != decision.sourceExpectedRevision ||
                !memory.isActiveAt(commit.nowMs)
            ) {
                error = error ?: "RELATION_SOURCE_REVISION_CONFLICT"
                null
            } else {
                memory
            }
        }
        val targetExisting = proposal.targetMemoryId?.let { id ->
            val memory = memoryDao.getMemoryById(id, commit.scopeId)
            if (memory == null || decision.targetExpectedRevision == null ||
                memory.revision != decision.targetExpectedRevision ||
                !memory.isActiveAt(commit.nowMs)
            ) {
                error = error ?: "RELATION_TARGET_REVISION_CONFLICT"
                null
            } else {
                memory
            }
        }
        val resolvedSourceId = sourceExisting?.id ?: sourceCandidate?.appliedMemoryId
        val resolvedTargetId = targetExisting?.id ?: targetCandidate?.appliedMemoryId
        if (resolvedSourceId != null && resolvedSourceId == resolvedTargetId) {
            error = error ?: "RELATION_SELF"
        }
        val entity = MemoryRelationCandidateEntity(
            id = decision.id,
            batchId = commit.batchId,
            sourceProposalKey = proposal.sourceProposalKey,
            sourceMemoryId = proposal.sourceMemoryId,
            targetProposalKey = proposal.targetProposalKey,
            targetMemoryId = proposal.targetMemoryId,
            relationType = proposal.type.name,
            weight = proposal.weight,
            description = proposal.description.take(MAX_RELATION_DESCRIPTION_CHARS),
            evidenceMessageIdsJson = json.encodeToString(proposal.evidenceMessageIds),
            status = if (error == null) {
                MemoryRelationCandidateStatus.PENDING.name
            } else {
                MemoryRelationCandidateStatus.INVALIDATED.name
            },
            createdAtMs = commit.nowMs,
            scopeId = commit.scopeId,
            createdByAssistantId = commit.assistantId,
            sourceCandidateId = sourceCandidate?.id,
            targetCandidateId = targetCandidate?.id,
            sourceExpectedRevision = decision.sourceExpectedRevision,
            targetExpectedRevision = decision.targetExpectedRevision,
            resolutionError = error,
            updatedAtMs = commit.nowMs,
        )
        memoryV2Dao.insertRelationCandidate(entity)
        val evidence = buildRelationEvidenceCapsules(
            relationCandidateId = entity.id,
            conversationId = commit.conversationId,
            evidenceMessageIds = proposal.evidenceMessageIds,
            captures = commit.captures,
            nowMs = commit.nowMs,
        )
        if (evidence.isNotEmpty()) memoryV2Dao.insertEvidence(evidence)
    }

    private suspend fun resolveRelationEndpoint(
        relation: MemoryRelationCandidateEntity,
        source: Boolean,
        nowMs: Long,
    ): MemoryEntity? {
        val directId = if (source) relation.sourceMemoryId else relation.targetMemoryId
        val expectedRevision = if (source) {
            relation.sourceExpectedRevision
        } else {
            relation.targetExpectedRevision
        }
        val candidateId = if (source) relation.sourceCandidateId else relation.targetCandidateId
        val memory = if (directId != null) {
            val direct = memoryDao.getMemoryById(directId, relation.scopeId) ?: return null
            if (expectedRevision == null || direct.revision != expectedRevision) return null
            direct
        } else {
            val endpointCandidateId = candidateId ?: return null
            val endpointCandidate = memoryV2Dao.findCandidateInBatch(
                endpointCandidateId,
                relation.batchId,
                relation.scopeId,
            ) ?: return null
            if (endpointCandidate.status !in RELATION_RESOLVABLE_CANDIDATE_STATUSES) return null
            val appliedId = endpointCandidate.appliedMemoryId ?: return null
            memoryDao.getMemoryById(appliedId, relation.scopeId) ?: return null
        }
        return memory.takeIf { it.isActiveAt(nowMs) }
    }

    private suspend fun invalidateRelationCandidate(
        candidate: MemoryRelationCandidateEntity,
        reason: String,
        nowMs: Long,
    ): MemoryRelationReviewResult {
        memoryV2Dao.resolveRelationCandidate(
            candidate.id,
            candidate.scopeId,
            MemoryRelationCandidateStatus.INVALIDATED.name,
            null,
            reason,
            nowMs,
        )
        return MemoryRelationReviewResult.Conflict
    }

    private suspend fun wouldCreateDerivedCycle(
        scopeId: String,
        sourceMemoryId: Int,
        targetMemoryId: Int,
    ): Boolean {
        val pending = ArrayDeque<Int>()
        val visited = hashSetOf<Int>()
        pending.add(targetMemoryId)
        while (pending.isNotEmpty() && visited.size < MAX_DERIVATION_GRAPH_VISITS) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (current == sourceMemoryId) return true
            memoryV2Dao.getActiveDerivedLinksForSource(current, scopeId)
                .forEach { pending.addLast(it.targetMemoryId) }
        }
        return pending.isNotEmpty() || visited.size >= MAX_DERIVATION_GRAPH_VISITS
    }

    private suspend fun applyReviewedRelationTruth(
        relationType: String,
        source: MemoryEntity,
        target: MemoryEntity,
        nowMs: Long,
    ): Pair<MemoryEntity, MemoryEntity> {
        var updatedSource = source
        var updatedTarget = target
        when (relationType) {
            MemoryRelationType.SUPERSEDES.name,
            MemoryRelationType.CORRECTS.name,
            MemoryRelationType.UPDATES.name,
            -> updatedTarget = updateTruthStatus(
                target,
                MemoryTruthStatus.SUPERSEDED,
                source.id,
                relationType,
                nowMs,
            )

            MemoryRelationType.CONTRADICTS.name -> {
                updatedSource = updateTruthStatus(
                    source,
                    MemoryTruthStatus.DISPUTED,
                    target.id,
                    relationType,
                    nowMs,
                )
                updatedTarget = updateTruthStatus(
                    target,
                    MemoryTruthStatus.DISPUTED,
                    source.id,
                    relationType,
                    nowMs,
                )
            }

            else -> Unit
        }
        return updatedSource to updatedTarget
    }

    private suspend fun updateTruthStatus(
        old: MemoryEntity,
        truth: MemoryTruthStatus,
        causeMemoryId: Int,
        relationType: String,
        nowMs: Long,
    ): MemoryEntity {
        if (old.truthStatus == truth.name) return old
        val updated = old.copy(
            truthStatus = truth.name,
            revision = old.revision + 1,
            updatedAtMs = nowMs,
            approvalSource = MemoryApprovalSource.USER_REVIEWED.name,
        )
        check(memoryDao.updateMemory(updated) == 1) { "memory_relation_truth_update_lost" }
        memoryV2Dao.insertRevision(
            revisionEntity(
                memory = updated,
                operation = MemoryRevisionOperation.UPDATE,
                before = old,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                candidateId = null,
                nowMs = nowMs,
                reasonCode = "RELATION_$relationType",
                causeMemoryId = causeMemoryId,
            ),
        )
        memoryV2Dao.trimRevisions(updated.id)
        invalidateLinksAfterSemanticChange(
            old,
            updated,
            MemoryApprovalSource.USER_REVIEWED.name,
            nowMs,
        )
        return updated
    }

    private suspend fun invalidateFormalMemorySources(
        scopeId: String,
        conversationId: String,
        deletedMessageIds: Set<String>?,
        nowMs: Long,
    ): Int {
        var changed = 0
        memoryDao.getMemoriesBySourceConversation(scopeId, conversationId).forEach { old ->
            val sourceIds = decodeList<String>(json, old.sourceMessageIdsJson)
            if (deletedMessageIds != null && sourceIds.none(deletedMessageIds::contains)) {
                return@forEach
            }
            val retainedIds = if (deletedMessageIds == null) {
                emptyList()
            } else {
                sourceIds.filterNot(deletedMessageIds::contains)
            }
            val isDerived = memoryV2Dao.getActiveDerivedLinksForSource(old.id, scopeId).isNotEmpty()
            val isUnreviewedAutomatic = old.approvalSource == MemoryApprovalSource.AUTO_SAFE.name
            val hasEvidence = memoryV2Dao.countValidEvidence(old.id) > 0
            val shouldStale = isDerived || isUnreviewedAutomatic && !hasEvidence
            val updated = old.copy(
                sourceMessageIdsJson = json.encodeToString(retainedIds),
                lifecycleStatus = if (shouldStale) {
                    MemoryLifecycleStatus.STALE.name
                } else {
                    old.lifecycleStatus
                },
                revision = old.revision + 1,
                updatedAtMs = nowMs,
            )
            check(memoryDao.updateMemory(updated) == 1) { "memory_source_invalidation_lost" }
            memoryV2Dao.insertRevision(
                revisionEntity(
                    memory = updated,
                    operation = if (shouldStale) {
                        MemoryRevisionOperation.STALE
                    } else {
                        MemoryRevisionOperation.UPDATE
                    },
                    before = old,
                    actor = "SOURCE_INVALIDATION",
                    candidateId = null,
                    nowMs = nowMs,
                    reasonCode = if (deletedMessageIds == null) {
                        "SOURCE_CONVERSATION_DELETED"
                    } else {
                        "SOURCE_MESSAGE_DELETED"
                    },
                ),
            )
            memoryV2Dao.trimRevisions(updated.id)
            if (shouldStale) {
                staleDerivedDescendants(
                    basis = updated,
                    reason = "SOURCE_INVALIDATED",
                    actor = "SOURCE_INVALIDATION",
                    nowMs = nowMs,
                )
                invalidateIncidentLinks(
                    memory = updated,
                    reason = "SOURCE_INVALIDATED",
                    actor = "SOURCE_INVALIDATION",
                    nowMs = nowMs,
                )
            }
            changed++
        }
        return changed
    }

    private suspend fun invalidateLinksAfterSemanticChange(
        old: MemoryEntity,
        updated: MemoryEntity,
        actor: String,
        nowMs: Long,
    ) {
        if (old.semanticHash(json) == updated.semanticHash(json)) return
        val links = memoryV2Dao.getIncidentLinks(
            memoryId = old.id,
            scopeId = old.assistantId,
            lifecycle = MemoryLinkLifecycleStatus.ACTIVE.name,
        )
        links.forEach { link ->
            if (link.relationType == MemoryRelationType.DERIVED_FROM.name &&
                link.targetMemoryId == old.id
            ) {
                staleDerivedLinkAndDescendants(link, "SOURCE_UPDATED", actor, nowMs, hashSetOf())
            } else {
                transitionLink(
                    link = link,
                    lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
                    operation = MemoryLinkRevisionOperation.INVALIDATE,
                    actor = actor,
                    reason = "ENDPOINT_SEMANTICS_CHANGED",
                    nowMs = nowMs,
                )
            }
        }
    }

    private suspend fun staleDerivedDescendants(
        basis: MemoryEntity,
        reason: String,
        actor: String,
        nowMs: Long,
        visited: MutableSet<Int> = hashSetOf(),
    ) {
        if (!visited.add(basis.id) || visited.size > MAX_DERIVATION_GRAPH_VISITS) return
        memoryV2Dao.getActiveDerivedLinksForTarget(basis.id, basis.assistantId).forEach { link ->
            staleDerivedLinkAndDescendants(link, reason, actor, nowMs, visited)
        }
    }

    private suspend fun staleDerivedLinkAndDescendants(
        link: MemoryLinkEntity,
        reason: String,
        actor: String,
        nowMs: Long,
        visited: MutableSet<Int>,
    ) {
        transitionLink(
            link = link,
            lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
            operation = MemoryLinkRevisionOperation.INVALIDATE,
            actor = actor,
            reason = reason,
            nowMs = nowMs,
        )
        val oldDerived = memoryDao.getMemoryById(link.sourceMemoryId, link.scopeId) ?: return
        val derived = if (oldDerived.lifecycleStatus == MemoryLifecycleStatus.STALE.name) {
            oldDerived
        } else {
            oldDerived.copy(
                lifecycleStatus = MemoryLifecycleStatus.STALE.name,
                revision = oldDerived.revision + 1,
                updatedAtMs = nowMs,
            ).also { updated ->
                check(memoryDao.updateMemory(updated) == 1) { "memory_derived_stale_lost" }
                memoryV2Dao.insertRevision(
                    revisionEntity(
                        memory = updated,
                        operation = MemoryRevisionOperation.STALE,
                        before = oldDerived,
                        actor = actor,
                        candidateId = null,
                        nowMs = nowMs,
                        reasonCode = reason,
                        causeMemoryId = link.targetMemoryId,
                        causeLinkId = link.id,
                    ),
                )
                memoryV2Dao.trimRevisions(updated.id)
            }
        }
        staleDerivedDescendants(derived, reason, actor, nowMs, visited)
    }

    private suspend fun suspendIncidentLinks(
        memory: MemoryEntity,
        actor: String,
        nowMs: Long,
    ) {
        memoryV2Dao.getIncidentLinks(
            memory.id,
            memory.assistantId,
            MemoryLinkLifecycleStatus.ACTIVE.name,
        ).forEach { link ->
            transitionLink(
                link = link,
                lifecycle = MemoryLinkLifecycleStatus.SUSPENDED,
                operation = MemoryLinkRevisionOperation.SUSPEND,
                actor = actor,
                reason = "ENDPOINT_ARCHIVED_OR_EXPIRED",
                nowMs = nowMs,
            )
        }
    }

    private suspend fun invalidateIncidentLinks(
        memory: MemoryEntity,
        reason: String,
        actor: String,
        nowMs: Long,
    ) {
        memoryV2Dao.getIncidentLinks(
            memory.id,
            memory.assistantId,
            MemoryLinkLifecycleStatus.ACTIVE.name,
        ).forEach { link ->
            transitionLink(
                link = link,
                lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
                operation = MemoryLinkRevisionOperation.INVALIDATE,
                actor = actor,
                reason = reason,
                nowMs = nowMs,
            )
        }
    }

    private suspend fun restoreEligibleIncidentLinks(
        memory: MemoryEntity,
        actor: String,
        nowMs: Long,
    ) {
        memoryV2Dao.getIncidentLinks(
            memory.id,
            memory.assistantId,
            MemoryLinkLifecycleStatus.SUSPENDED.name,
        ).filterNot { it.relationType == MemoryRelationType.DERIVED_FROM.name }
            .forEach { link ->
                val source = memoryDao.getMemoryById(link.sourceMemoryId, link.scopeId)
                    ?: return@forEach
                val target = memoryDao.getMemoryById(link.targetMemoryId, link.scopeId)
                    ?: return@forEach
                if (!source.isActiveAt(nowMs) || !target.isActiveAt(nowMs)) return@forEach
                if (source.semanticHash(json) != link.sourceSemanticHash ||
                    target.semanticHash(json) != link.targetSemanticHash
                ) return@forEach
                transitionLink(
                    link = link,
                    lifecycle = MemoryLinkLifecycleStatus.ACTIVE,
                    operation = MemoryLinkRevisionOperation.RESTORE,
                    actor = actor,
                    reason = "ENDPOINT_RESTORED",
                    nowMs = nowMs,
                )
            }
    }

    private suspend fun transitionLink(
        link: MemoryLinkEntity,
        lifecycle: MemoryLinkLifecycleStatus,
        operation: MemoryLinkRevisionOperation,
        actor: String,
        reason: String,
        nowMs: Long,
    ): MemoryLinkEntity {
        if (link.lifecycleStatus == lifecycle.name) return link
        val invalidated = lifecycle == MemoryLinkLifecycleStatus.INVALIDATED
        val updated = link.copy(
            lifecycleStatus = lifecycle.name,
            revision = link.revision + 1,
            updatedAtMs = nowMs,
            invalidatedAtMs = if (invalidated) nowMs else null,
            invalidationReason = if (invalidated) reason else null,
        )
        val changed = memoryV2Dao.updateLinkLifecycle(
            linkId = link.id,
            scopeId = link.scopeId,
            expectedRevision = link.revision,
            lifecycle = lifecycle.name,
            nowMs = nowMs,
            invalidatedAtMs = updated.invalidatedAtMs,
            reason = updated.invalidationReason,
        )
        check(changed == 1) { "memory_link_revision_conflict" }
        memoryV2Dao.insertLinkRevision(
            linkRevisionEntity(
                link = updated,
                operation = operation,
                before = link,
                actor = actor,
                relationCandidateId = link.relationCandidateId,
                reasonCode = reason,
                nowMs = nowMs,
            ),
        )
        memoryV2Dao.trimLinkRevisions(link.id)
        return updated
    }

    private fun linkRevisionEntity(
        link: MemoryLinkEntity,
        operation: MemoryLinkRevisionOperation,
        before: MemoryLinkEntity?,
        actor: String,
        relationCandidateId: String?,
        reasonCode: String?,
        nowMs: Long,
    ) = MemoryLinkRevisionEntity(
        id = idGenerator(),
        linkId = link.id,
        revision = link.revision,
        operation = operation.name,
        beforeSnapshotJson = before?.let { json.encodeToString(it) },
        afterSnapshotJson = json.encodeToString(link),
        actor = actor,
        relationCandidateId = relationCandidateId,
        reasonCode = reasonCode,
        createdAtMs = nowMs,
    )

    private suspend fun insertCreatedMemory(
        scopeId: String,
        proposal: MemoryProposal,
        approvalSource: MemoryApprovalSource,
        sourceType: String,
        sourceConversationId: String?,
        candidateId: String?,
        originAssistantId: String? = null,
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
        return memoryId
    }

    private suspend fun applyUpdate(
        candidate: MemoryCandidateEntity,
        proposal: MemoryProposal,
        nowMs: Long,
    ): Int? {
        val targetId = proposal.targetIds.singleOrNull() ?: return null
        val expected = proposal.expectedRevisions.singleOrNull() ?: return null
        val old = memoryDao.getMemoryById(targetId, candidate.scopeId) ?: return null
        if (old.revision != expected) return null
        val updated = old.applyProposal(proposal, nowMs)
        if (memoryDao.updateMemory(updated) != 1) return null
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
        invalidateLinksAfterSemanticChange(
            old = old,
            updated = updated,
            actor = MemoryApprovalSource.USER_REVIEWED.name,
            nowMs = nowMs,
        )
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
        val byId = memoryDao.getMemoriesByIds(
            ids = proposal.targetIds,
            scopeId = candidate.scopeId,
        ).associateBy { it.id }
        val targets = proposal.targetIds.map { byId[it] ?: return null }
        if (targets.zip(proposal.expectedRevisions).any { (memory, expected) ->
                memory.revision != expected
            }
        ) return null

        val primaryOld = targets.first()
        val primary = primaryOld.applyProposal(proposal, nowMs)
        if (memoryDao.updateMemory(primary) != 1) return null
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
        invalidateLinksAfterSemanticChange(
            old = primaryOld,
            updated = primary,
            actor = MemoryApprovalSource.USER_REVIEWED.name,
            nowMs = nowMs,
        )
        targets.drop(1).forEach { old ->
            val archived = old.copy(
                lifecycleStatus = MemoryLifecycleStatus.ARCHIVED.name,
                approvalSource = MemoryApprovalSource.USER_REVIEWED.name,
                updatedAtMs = nowMs,
                revision = old.revision + 1,
            )
            check(memoryDao.updateMemory(archived) == 1) { "memory_merge_archive_lost" }
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
            suspendIncidentLinks(archived, MemoryApprovalSource.USER_REVIEWED.name, nowMs)
            staleDerivedDescendants(
                basis = archived,
                reason = "SOURCE_MERGED_ARCHIVED",
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                nowMs = nowMs,
            )
        }
        return primary.id
    }

    private suspend fun markConflict(
        candidate: MemoryCandidateEntity,
        nowMs: Long,
    ): MemoryReviewResult {
        memoryV2Dao.resolveCandidate(
            candidate.id,
            candidate.scopeId,
            MemoryCandidateStatus.CONFLICT.name,
            null,
            "memory_revision_conflict",
            nowMs,
        )
        memoryV2Dao.invalidateRelationsForMemoryCandidate(
            candidate.id,
            candidate.scopeId,
            "MEMORY_CANDIDATE_CONFLICT",
            nowMs,
        )
        return MemoryReviewResult.Conflict
    }

    private suspend fun mutateLifecycle(
        memoryId: Int,
        scopeId: String,
        expectedRevision: Int?,
        lifecycle: MemoryLifecycleStatus,
        operation: MemoryRevisionOperation,
        approvalSource: MemoryApprovalSource,
        nowMs: Long,
    ): MemoryMutationResult {
        val old = memoryDao.getMemoryById(memoryId, scopeId) ?: return MemoryMutationResult.NotFound
        if (expectedRevision != null && expectedRevision != old.revision) {
            return MemoryMutationResult.Conflict
        }
        if (lifecycle == MemoryLifecycleStatus.ACTIVE) {
            if (old.lifecycleStatus == MemoryLifecycleStatus.STALE.name) {
                return MemoryMutationResult.Rejected("memory_stale_review_required")
            }
            if (old.expiresAtMs != null && old.expiresAtMs <= nowMs) {
                return MemoryMutationResult.Rejected("memory_restore_expired")
            }
        }
        if (old.lifecycleStatus == lifecycle.name) {
            return MemoryMutationResult.Applied(old.id, old.revision)
        }
        val updated = old.copy(
            lifecycleStatus = lifecycle.name,
            approvalSource = approvalSource.name,
            updatedAtMs = nowMs,
            revision = old.revision + 1,
        )
        check(memoryDao.updateMemory(updated) == 1) { "memory_lifecycle_update_lost" }
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
        if (lifecycle == MemoryLifecycleStatus.ARCHIVED) {
            staleDerivedDescendants(
                basis = updated,
                reason = "SOURCE_ARCHIVED",
                actor = approvalSource.name,
                nowMs = nowMs,
            )
            suspendIncidentLinks(updated, approvalSource.name, nowMs)
        } else if (lifecycle == MemoryLifecycleStatus.ACTIVE) {
            restoreEligibleIncidentLinks(updated, approvalSource.name, nowMs)
        }
        return MemoryMutationResult.Applied(updated.id, updated.revision)
    }

    private fun revisionEntity(
        memory: MemoryEntity,
        operation: MemoryRevisionOperation,
        before: MemoryEntity?,
        actor: String,
        candidateId: String?,
        nowMs: Long,
        reasonCode: String? = null,
        causeMemoryId: Int? = null,
        causeLinkId: String? = null,
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
        reasonCode = reasonCode,
        causeMemoryId = causeMemoryId,
        causeLinkId = causeLinkId,
    )
}

private fun MemoryCaptureEntity.toRecord(workerId: String) = MemoryCaptureRecord(
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
    leaseOwner = workerId,
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
    batchId = commit.batchId,
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
    sourceType = sourceType,
    sourceConversationId = sourceConversationId,
    sourceMessageIds = decodeList(json, sourceMessageIdsJson),
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

private fun buildCandidateEvidenceCapsules(
    candidateId: String,
    memoryId: Int?,
    conversationId: String,
    evidenceMessageIds: List<String>,
    captures: List<MemoryCaptureRecord>,
    nowMs: Long,
): List<MemoryEvidenceEntity> = buildEvidenceCapsules(
    ownerId = "candidate-$candidateId",
    memoryId = memoryId,
    candidateId = candidateId,
    relationCandidateId = null,
    conversationId = conversationId,
    evidenceMessageIds = evidenceMessageIds,
    captures = captures,
    nowMs = nowMs,
)

private fun buildRelationEvidenceCapsules(
    relationCandidateId: String,
    conversationId: String,
    evidenceMessageIds: List<String>,
    captures: List<MemoryCaptureRecord>,
    nowMs: Long,
): List<MemoryEvidenceEntity> = buildEvidenceCapsules(
    ownerId = "relation-$relationCandidateId",
    memoryId = null,
    candidateId = null,
    relationCandidateId = relationCandidateId,
    conversationId = conversationId,
    evidenceMessageIds = evidenceMessageIds,
    captures = captures,
    nowMs = nowMs,
)

private fun buildEvidenceCapsules(
    ownerId: String,
    memoryId: Int?,
    candidateId: String?,
    relationCandidateId: String?,
    conversationId: String,
    evidenceMessageIds: List<String>,
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
    }.filter { (messageId) -> messageId in evidenceMessageIds }
        .take(3)
        .mapNotNull { (messageId, role, text) ->
            if (remaining <= 0) return@mapNotNull null
            val excerpt = guard.redact(text).text.trim().take(minOf(600, remaining))
            if (excerpt.isEmpty()) return@mapNotNull null
            remaining -= excerpt.length
            MemoryEvidenceEntity(
                id = "evidence-$ownerId-$messageId",
                memoryId = memoryId,
                candidateId = candidateId,
                relationCandidateId = relationCandidateId,
                conversationId = conversationId,
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

private data class PersistedCandidateState(
    val id: String,
    val appliedMemoryId: Int?,
)

private fun MemoryEntity.isActiveAt(nowMs: Long): Boolean =
    lifecycleStatus == MemoryLifecycleStatus.ACTIVE.name &&
        (expiresAtMs == null || expiresAtMs > nowMs)

private fun MemoryEntity.semanticHash(json: Json): String = memoryContentHash(
    listOf(
        title.orEmpty(),
        content,
        memoryKind,
        attribution,
        truthStatus,
        occurredAtMs?.toString().orEmpty(),
        participantsJson,
        outcome.orEmpty(),
    ).joinToString("\u001f"),
)

private fun requireValidScope(scopeId: String) {
    require(
        scopeId == MemoryRepository.GLOBAL_MEMORY_ID ||
            runCatching { Uuid.parse(scopeId) }.isSuccess,
    ) { "memory_scope_invalid" }
}

private fun validCreateScope(scopeId: String, originAssistantId: String?): Boolean {
    val origin = originAssistantId?.let { raw -> runCatching { Uuid.parse(raw) }.getOrNull() }
        ?: return false
    if (scopeId == MemoryRepository.GLOBAL_MEMORY_ID) return true
    val scope = runCatching { Uuid.parse(scopeId) }.getOrNull() ?: return false
    return scope == origin
}

private val RELATION_RESOLVABLE_CANDIDATE_STATUSES = setOf(
    MemoryCandidateStatus.AUTO_APPLIED.name,
    MemoryCandidateStatus.ACCEPTED.name,
    MemoryCandidateStatus.SUPERSEDED.name,
)

private const val MAX_STORED_ERROR_CHARS = 1_000
private const val MAX_STORED_REASON_CHARS = 2_000
private const val MAX_RELATION_DESCRIPTION_CHARS = 500
private const val MAX_DERIVATION_GRAPH_VISITS = 256
private const val MAX_EXPIRY_MATERIALIZATION_BATCH = 100
private const val PROCESSED_CAPTURE_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
private const val FAILED_CAPTURE_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L

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
import me.rerere.rikkahub.data.db.entity.MemorySourceTombstoneEntity
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetrievalRequest
import me.rerere.rikkahub.data.repository.MemoryRetriever
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChange
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeOperation
import me.rerere.rikkahub.memory.dreaming.model.AuthorityChangeReason
import me.rerere.rikkahub.memory.dreaming.model.AuthorityEntityKind
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.store.DreamPrivacyScrubRequest
import me.rerere.rikkahub.memory.dreaming.store.DreamPrivacyScrubResult
import me.rerere.rikkahub.memory.dreaming.store.DreamPrivacyScrubber
import me.rerere.rikkahub.memory.dreaming.store.DreamPrivacyTarget
import me.rerere.rikkahub.memory.dreaming.store.DreamObserverStore
import me.rerere.rikkahub.memory.dreaming.store.RecordAuthorityChangesRequest
import me.rerere.rikkahub.memory.dreaming.store.RoomDreamPrivacyScrubber
import me.rerere.rikkahub.memory.dreaming.store.RoomDreamObserverStore
import java.security.MessageDigest
import kotlin.uuid.Uuid

class RoomMemoryProcessingStore(
    private val database: AppDatabase,
    private val memoryDao: MemoryDAO,
    private val memoryV2Dao: MemoryV2Dao,
    private val retriever: MemoryRetriever,
    private val json: Json,
    private val dreamObserverStore: DreamObserverStore = RoomDreamObserverStore(
        database = database,
        dreamDao = database.dreamDao(),
    ),
    private val dreamPrivacyScrubber: DreamPrivacyScrubber = RoomDreamPrivacyScrubber(
        database = database,
        dreamDao = database.dreamDao(),
        synthesisDao = database.dreamSynthesisDao(),
        memoryDao = memoryDao,
        memoryV2Dao = memoryV2Dao,
        json = json,
    ),
    private val idGenerator: () -> String = { Uuid.random().toString() },
) : MemoryProcessingStore {
    private suspend fun <T> withAuthorityMutation(
        nowMs: Long,
        reason: AuthorityChangeReason,
        block: suspend (AuthorityMutationCollector) -> T,
    ): T = database.withTransaction {
        val collector = AuthorityMutationCollector(reason)
        val result = block(collector)
        dreamObserverStore.recordAuthorityChangesInCurrentTransaction(
            RecordAuthorityChangesRequest(
                changes = collector.snapshot(),
                createdAtMs = nowMs,
            ),
        )
        result
    }

    private suspend fun scrubDreamPrivacyOrThrow(
        scopeId: String,
        targets: List<DreamPrivacyTarget>,
        nowMs: Long,
    ) {
        check(database.inTransaction()) { "dream_privacy_authority_transaction_required" }
        if (targets.isEmpty()) return
        // Derived rows cannot exist without the scope-state FK parent. A never-observed empty
        // assistant therefore needs no Dream scrub; the authority journal may create its state.
        if (database.dreamDao().getScopeState(scopeId) == null) return
        targets.distinct().chunked(MAX_DREAM_PRIVACY_TARGETS).forEach { chunk ->
            when (val result = dreamPrivacyScrubber.scrubInCurrentTransaction(
                DreamPrivacyScrubRequest(
                    scopeId = DreamScopeId.requireCanonical(scopeId),
                    targets = chunk,
                    scrubbedAtEpochMs = nowMs,
                ),
            )) {
                is DreamPrivacyScrubResult.Scrubbed -> Unit
                is DreamPrivacyScrubResult.Rejected -> error(
                    "dream_privacy_scrub_rejected_${result.reason.name.lowercase()}",
                )
            }
        }
    }

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
        withAuthorityMutation(commit.nowMs, AuthorityChangeReason.EXTRACTION_COMMIT) { authority ->
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
                            sourceIdentities = sourceIdentitiesForEvidence(
                                captures = commit.captures,
                                evidenceMessageIds = proposal.evidenceMessageIds,
                            ),
                            nowMs = commit.nowMs,
                            authority = authority,
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
                insertEvidenceAndCollect(
                    evidence = evidence,
                    scopeId = commit.scopeId,
                    authority = authority,
                )
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
                nowMs = commit.leaseNowMs,
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
    ): MemoryReviewResult = withAuthorityMutation(
        nowMs,
        AuthorityChangeReason.MEMORY_REVIEW,
    ) review@ { authority ->
        val candidateId = when (command) {
            is MemoryReviewCommand.Accept -> command.candidateId
            is MemoryReviewCommand.Reject -> command.candidateId
        }
        val expectedScopeId = when (command) {
            is MemoryReviewCommand.Accept -> command.expectedScopeId
            is MemoryReviewCommand.Reject -> command.expectedScopeId
        }
        val candidate = memoryV2Dao.findCandidate(candidateId, expectedScopeId)
            ?: return@review MemoryReviewResult.NotFound
        if (candidate.status == MemoryCandidateStatus.CONFLICT.name) {
            return@review MemoryReviewResult.Conflict
        }
        if (candidate.status != MemoryCandidateStatus.PENDING_REVIEW.name) {
            return@review MemoryReviewResult.AlreadyResolved
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
            return@review MemoryReviewResult.Rejected
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
            return@review MemoryReviewResult.Failed("memory_review_edit_invalid")
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
                    return@review MemoryReviewResult.Conflict
                }
                insertCreatedMemory(
                    scopeId = candidate.scopeId,
                    proposal = proposal,
                    approvalSource = MemoryApprovalSource.USER_REVIEWED,
                    sourceType = "AUTO_EXTRACTION",
                    sourceConversationId = candidate.sourceConversationId,
                    candidateId = candidate.id,
                    originAssistantId = candidate.assistantId,
                    sourceIdentities = memoryV2Dao.getValidEvidenceForCandidate(candidate.id)
                        .toSourceIdentities(),
                    nowMs = nowMs,
                    authority = authority,
                )
            }

            MemoryCandidateAction.UPDATE -> applyUpdate(candidate, proposal, nowMs, authority)
                ?: return@review markConflict(candidate, nowMs)

            MemoryCandidateAction.MERGE -> applyMerge(candidate, proposal, nowMs, authority)
                ?: return@review markConflict(candidate, nowMs)

            MemoryCandidateAction.IGNORE -> {
                memoryV2Dao.resolveCandidate(
                    candidate.id,
                    candidate.scopeId,
                    MemoryCandidateStatus.REJECTED.name,
                    null,
                    null,
                    nowMs,
                )
                return@review MemoryReviewResult.Rejected
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
        attachCandidateEvidenceAndCollect(
            candidateId = candidate.id,
            memoryId = memoryId,
            scopeId = candidate.scopeId,
            authority = authority,
        )
        MemoryReviewResult.Applied(memoryId)
    }

    override suspend fun reviewRelation(
        command: MemoryRelationReviewCommand,
        nowMs: Long,
    ): MemoryRelationReviewResult = withAuthorityMutation(
        nowMs,
        AuthorityChangeReason.RELATION_REVIEW,
    ) relationReview@ { authority ->
        val candidate = memoryV2Dao.findRelationCandidate(
            candidateId = command.relationCandidateId,
            scopeId = command.expectedScopeId,
        ) ?: return@relationReview MemoryRelationReviewResult.NotFound
        when (candidate.status) {
            MemoryRelationCandidateStatus.ACCEPTED.name,
            MemoryRelationCandidateStatus.REJECTED.name,
            MemoryRelationCandidateStatus.INVALIDATED.name,
            -> return@relationReview MemoryRelationReviewResult.AlreadyResolved
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
            return@relationReview MemoryRelationReviewResult.Rejected
        }

        if (candidate.isSourceInvalidationReconciliation()) {
            return@relationReview acceptSourceInvalidationReconciliation(
                candidate,
                nowMs,
                authority,
            )
        }

        val source = resolveRelationEndpoint(candidate, source = true, nowMs = nowMs)
            ?: return@relationReview invalidateRelationCandidate(
                candidate,
                "RELATION_SOURCE_CONFLICT",
                nowMs,
            )
        val target = resolveRelationEndpoint(candidate, source = false, nowMs = nowMs)
            ?: return@relationReview invalidateRelationCandidate(
                candidate,
                "RELATION_TARGET_CONFLICT",
                nowMs,
            )
        if (source.id == target.id) {
            return@relationReview invalidateRelationCandidate(candidate, "RELATION_SELF", nowMs)
        }
        if (candidate.relationType == MemoryRelationType.DERIVED_FROM.name &&
            wouldCreateDerivedCycle(candidate.scopeId, source.id, target.id)
        ) {
            return@relationReview invalidateRelationCandidate(candidate, "RELATION_CYCLE", nowMs)
        }
        val existing = memoryV2Dao.findLinkByEndpoints(
            scopeId = candidate.scopeId,
            sourceMemoryId = source.id,
            targetMemoryId = target.id,
            relationType = candidate.relationType,
        )
        if (existing != null) {
            if (existing.lifecycleStatus != MemoryLinkLifecycleStatus.ACTIVE.name) {
                return@relationReview invalidateRelationCandidate(
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
            attachRelationEvidenceAndCollect(
                relationCandidateId = candidate.id,
                linkId = existing.id,
                scopeId = candidate.scopeId,
                authority = authority,
            )
            return@relationReview MemoryRelationReviewResult.Applied(existing.id)
        }

        // Allocate the durable link identity before truth mutation so every resulting memory
        // revision can name the exact reviewed relation which caused it.
        val linkId = idGenerator()
        val (reviewedSource, reviewedTarget) = applyReviewedRelationTruth(
            candidate.relationType,
            source,
            target,
            linkId,
            nowMs,
            authority,
        )
        val link = MemoryLinkEntity(
            id = linkId,
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
        insertLinkRevisionAndCollect(
            revision = linkRevisionEntity(
                link = link,
                operation = MemoryLinkRevisionOperation.CREATE,
                before = null,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                relationCandidateId = candidate.id,
                reasonCode = "RELATION_REVIEW_ACCEPTED",
                nowMs = nowMs,
            ),
            link = link,
            operation = MemoryLinkRevisionOperation.CREATE,
            authority = authority,
        )
        memoryV2Dao.resolveRelationCandidate(
            candidate.id,
            candidate.scopeId,
            MemoryRelationCandidateStatus.ACCEPTED.name,
            link.id,
            null,
            nowMs,
        )
        attachRelationEvidenceAndCollect(
            relationCandidateId = candidate.id,
            linkId = link.id,
            scopeId = candidate.scopeId,
            authority = authority,
        )
        MemoryRelationReviewResult.Applied(link.id)
    }

    override suspend fun invalidateSourceConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int = invalidateSources(
        MemorySourceInvalidationBatch(
            conversationId = conversationId,
            scopes = listOf(
                MemoryScopeSourceInvalidation(
                    scopeId = scopeId,
                    invalidateWholeConversation = true,
                ),
            ),
        ),
        nowMs,
    )

    override suspend fun invalidateSources(
        batch: MemorySourceInvalidationBatch,
        nowMs: Long,
    ): Int {
        require(batch.conversationId.isNotBlank()) { "memory_source_conversation_missing" }
        val normalized = normalizeSourceInvalidationBatch(batch)
        if (normalized.isEmpty()) return 0
        return withAuthorityMutation(
            nowMs,
            AuthorityChangeReason.SOURCE_INVALIDATION,
        ) { authority ->
            normalized.sumOf { request ->
                if (request.invalidateWholeConversation) {
                    invalidateSourceConversationInCurrentTransaction(
                        scopeId = request.scopeId,
                        conversationId = batch.conversationId,
                        nowMs = nowMs,
                        authority = authority,
                    )
                } else {
                    invalidateSelectedSources(
                        scopeId = request.scopeId,
                        conversationId = batch.conversationId,
                        removedMessageIds = request.removedMessageIds,
                        deletedSourceVersions = request.removedSourceVersions,
                        nowMs = nowMs,
                        authority = authority,
                    )
                }
            }
        }
    }

    private fun normalizeSourceInvalidationBatch(
        batch: MemorySourceInvalidationBatch,
    ): List<NormalizedSourceInvalidation> {
        val byScope = linkedMapOf<String, NormalizedSourceInvalidation>()
        batch.scopes.forEach { request ->
            requireValidScope(request.scopeId)
            val canonicalScope = DreamScopeId.requireCanonical(request.scopeId).value
            require(request.removedMessageIds.all { it.isNotBlank() && it == it.trim() }) {
                "memory_source_message_id_invalid"
            }
            require(request.removedSourceVersions.all { it.isValid() }) {
                "memory_source_version_invalid"
            }
            val current = byScope[canonicalScope]
            byScope[canonicalScope] = NormalizedSourceInvalidation(
                scopeId = canonicalScope,
                invalidateWholeConversation = request.invalidateWholeConversation ||
                    current?.invalidateWholeConversation == true,
                removedMessageIds = current?.removedMessageIds.orEmpty() +
                    request.removedMessageIds,
                removedSourceVersions = current?.removedSourceVersions.orEmpty() +
                    request.removedSourceVersions,
            )
        }
        return byScope.values
            .asSequence()
            .filter { request ->
                request.invalidateWholeConversation || request.removedMessageIds.isNotEmpty() ||
                    request.removedSourceVersions.isNotEmpty()
            }
            .map { request ->
                if (request.invalidateWholeConversation) {
                    request.copy(
                        removedMessageIds = emptySet(),
                        removedSourceVersions = emptySet(),
                    )
                } else {
                    request
                }
            }
            .sortedBy(NormalizedSourceInvalidation::scopeId)
            .toList()
    }

    private suspend fun invalidateSourceConversationInCurrentTransaction(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): Int {
        check(database.inTransaction()) { "memory_source_invalidation_transaction_required" }
        requireValidScope(scopeId)
        val formalEvidence = memoryV2Dao.getLiveFormalEvidenceForConversation(
            scopeId = scopeId,
            conversationId = conversationId,
        )
        scrubDreamPrivacyOrThrow(
            scopeId = scopeId,
            targets = (
                memoryDao.getMemoriesBySourceConversation(scopeId, conversationId)
                    .map(MemoryEntity::id) + formalEvidence.mapNotNull { it.memoryId }
                ).distinct().map { DreamPrivacyTarget.AuthorityMemory(it.toString()) },
            nowMs = nowMs,
        )
        insertSourceTombstonesAndCollect(
            listOf(
                sourceTombstone(
                    scopeId = scopeId,
                    conversationId = conversationId,
                    sourceKind = SOURCE_TOMBSTONE_CONVERSATION,
                    sourceId = conversationId,
                    reasonCode = "SOURCE_CONVERSATION_DELETED",
                    nowMs = nowMs,
                ),
            ),
            authority,
        )
        val affectedLinks = memoryV2Dao.getActiveLinksWithEvidenceForConversation(
            scopeId = scopeId,
            conversationId = conversationId,
        )
        memoryV2Dao.purgeCapturePayloadsForConversation(scopeId, conversationId, nowMs)
        memoryV2Dao.invalidateEvidenceForConversation(scopeId, conversationId)
        formalEvidence.forEach { evidence ->
            authority.evidence(scopeId, evidence.id, AuthorityChangeOperation.INVALIDATE)
        }
        val invalidatedLinks = affectedLinks.filter { link ->
            memoryV2Dao.countValidEvidenceForLink(link.id) == 0
        }
        invalidatedLinks.forEach { link ->
            invalidateLinkAfterSourceLoss(
                link = link,
                reason = "SOURCE_CONVERSATION_DELETED",
                nowMs = nowMs,
                authority = authority,
            )
        }
        memoryV2Dao.invalidateRelationCandidatesForConversation(scopeId, conversationId, nowMs)
        memoryV2Dao.invalidateCandidatesForConversation(scopeId, conversationId, nowMs)
        return invalidatedLinks.size + invalidateFormalMemorySources(
            scopeId = scopeId,
            conversationId = conversationId,
            deletedMessageIds = null,
            deletedSourceVersions = emptySet(),
            nowMs = nowMs,
            authority = authority,
        )
    }

    override suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
        nowMs: Long,
    ): Int = invalidateSources(
        MemorySourceInvalidationBatch(
            conversationId = conversationId,
            scopes = listOf(
                MemoryScopeSourceInvalidation(
                    scopeId = scopeId,
                    removedMessageIds = messageIds,
                ),
            ),
        ),
        nowMs,
    )

    override suspend fun invalidateSourceVersions(
        scopeId: String,
        conversationId: String,
        sourceVersions: Set<MemorySourceVersion>,
        nowMs: Long,
    ): Int = invalidateSources(
        MemorySourceInvalidationBatch(
            conversationId = conversationId,
            scopes = listOf(
                MemoryScopeSourceInvalidation(
                    scopeId = scopeId,
                    removedSourceVersions = sourceVersions,
                ),
            ),
        ),
        nowMs,
    )

    private suspend fun invalidateSelectedSources(
        scopeId: String,
        conversationId: String,
        removedMessageIds: Set<String>,
        deletedSourceVersions: Set<MemorySourceVersion>,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): Int {
        check(database.inTransaction()) { "memory_source_invalidation_transaction_required" }
        if (removedMessageIds.isEmpty() && deletedSourceVersions.isEmpty()) return 0
        val exactVersions = deletedSourceVersions.filterNot { it.messageId in removedMessageIds }
            .toSet()
        scrubDreamPrivacyOrThrow(
            scopeId = scopeId,
            targets = listOf(
                DreamPrivacyTarget.ConversationSource(
                    conversationId = conversationId,
                    messageIds = removedMessageIds + exactVersions.map { it.messageId },
                ),
            ),
            nowMs = nowMs,
        )
        val formalEvidence = memoryV2Dao.getLiveFormalEvidenceForConversation(
            scopeId = scopeId,
            conversationId = conversationId,
        ).filter { evidence ->
            evidence.messageId in removedMessageIds || exactVersions.any { version ->
                evidence.messageId == version.messageId &&
                    evidence.sourceDigest == version.consumedTextDigest
            }
        }
        insertSourceTombstonesAndCollect(
            tombstones = buildList {
                removedMessageIds.forEach { messageId ->
                    add(
                        sourceTombstone(
                            scopeId = scopeId,
                            conversationId = conversationId,
                            sourceKind = SOURCE_TOMBSTONE_MESSAGE,
                            sourceId = messageId,
                            reasonCode = "SOURCE_MESSAGE_DELETED",
                            nowMs = nowMs,
                        ),
                    )
                }
                exactVersions.forEach { version ->
                    add(
                        sourceTombstone(
                            scopeId = scopeId,
                            conversationId = conversationId,
                            sourceKind = SOURCE_TOMBSTONE_MESSAGE,
                            sourceId = version.messageId,
                            sourceDigest = version.consumedTextDigest,
                            reasonCode = "SOURCE_VERSION_DELETED",
                            nowMs = nowMs,
                        ),
                    )
                }
            },
            authority = authority,
        )
        val affectedLinks = buildList {
            if (removedMessageIds.isNotEmpty()) {
                addAll(
                    memoryV2Dao.getActiveLinksWithEvidenceForMessages(
                        scopeId = scopeId,
                        conversationId = conversationId,
                        messageIds = removedMessageIds.toList(),
                    ),
                )
            }
            exactVersions.forEach { version ->
                addAll(
                    memoryV2Dao.getActiveLinksWithEvidenceForSourceVersion(
                        scopeId = scopeId,
                        conversationId = conversationId,
                        messageId = version.messageId,
                        sourceDigest = version.consumedTextDigest,
                    ),
                )
            }
        }.distinctBy(MemoryLinkEntity::id)
        val affectedCaptureIds = memoryV2Dao.getCapturesForSourceConversation(
            scopeId,
            conversationId,
        ).filter { capture ->
            val identities = effectiveMemorySourceIdentities(
                capture.toRecord(capture.leaseOwner.orEmpty()),
            )
            (capture.payloadPurgedAtMs == null && identities.isEmpty()) ||
                identities.any { identity ->
                    identity.messageId in removedMessageIds ||
                        exactVersions.any { version -> version.matches(identity) }
                }
        }.map(MemoryCaptureEntity::id)
        if (affectedCaptureIds.isNotEmpty()) {
            memoryV2Dao.discardAndPurgeCapturePayloadsByIds(
                scopeId = scopeId,
                captureIds = affectedCaptureIds,
                nowMs = nowMs,
            )
        }
        if (removedMessageIds.isNotEmpty()) {
            memoryV2Dao.invalidateEvidenceForMessages(
                scopeId,
                conversationId,
                removedMessageIds.toList(),
            )
        }
        exactVersions.forEach { version ->
            memoryV2Dao.invalidateEvidenceForSourceVersion(
                scopeId = scopeId,
                conversationId = conversationId,
                messageId = version.messageId,
                sourceDigest = version.consumedTextDigest,
            )
        }
        formalEvidence.forEach { evidence ->
            authority.evidence(scopeId, evidence.id, AuthorityChangeOperation.INVALIDATE)
        }
        val invalidatedLinks = affectedLinks.filter { link ->
            memoryV2Dao.countValidEvidenceForLink(link.id) == 0
        }
        invalidatedLinks.forEach { link ->
            invalidateLinkAfterSourceLoss(
                link = link,
                reason = if (removedMessageIds.isNotEmpty()) {
                    "SOURCE_MESSAGE_DELETED"
                } else {
                    "SOURCE_VERSION_DELETED"
                },
                nowMs = nowMs,
                authority = authority,
            )
        }
        if (removedMessageIds.isNotEmpty()) {
            memoryV2Dao.invalidateRelationCandidatesForMessages(
                scopeId,
                conversationId,
                removedMessageIds.toList(),
                nowMs,
            )
            memoryV2Dao.invalidateCandidatesForMessages(
                scopeId,
                conversationId,
                removedMessageIds.toList(),
                nowMs,
            )
        }
        exactVersions.forEach { version ->
            memoryV2Dao.invalidateRelationCandidatesForSourceVersion(
                scopeId = scopeId,
                conversationId = conversationId,
                messageId = version.messageId,
                sourceDigest = version.consumedTextDigest,
                nowMs = nowMs,
            )
            memoryV2Dao.invalidateCandidatesForSourceVersion(
                scopeId = scopeId,
                conversationId = conversationId,
                messageId = version.messageId,
                sourceDigest = version.consumedTextDigest,
                nowMs = nowMs,
            )
        }
        return invalidatedLinks.size + invalidateFormalMemorySources(
            scopeId = scopeId,
            conversationId = conversationId,
            deletedMessageIds = removedMessageIds,
            deletedSourceVersions = exactVersions,
            nowMs = nowMs,
            authority = authority,
        )
    }

    override suspend fun runRetention(nowMs: Long): Int = withAuthorityMutation(
        nowMs,
        AuthorityChangeReason.EXPIRY,
    ) { authority ->
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
                insertMemoryRevisionAndCollect(
                    revision = revisionEntity(
                        memory = materialized,
                        operation = MemoryRevisionOperation.EXPIRE,
                        before = current,
                        actor = "RETENTION",
                        candidateId = null,
                        nowMs = nowMs,
                        reasonCode = "TTL_EXPIRED",
                    ),
                    memory = materialized,
                    operation = MemoryRevisionOperation.EXPIRE,
                    authority = authority,
                )
                memoryV2Dao.trimRevisions(materialized.id)
                staleDerivedDescendants(
                    materialized,
                    "SOURCE_EXPIRED",
                    "RETENTION",
                    nowMs,
                    authority = authority,
                )
                suspendIncidentLinks(materialized, "RETENTION", nowMs, authority)
                expired++
            }
        processedPurged + failedPurged + expired
    }

    override suspend fun purgeScope(scopeId: String, nowMs: Long): Int =
        withAuthorityMutation(nowMs, AuthorityChangeReason.PRIVACY_SCRUB) { authority ->
            require(scopeId != MemoryRepository.GLOBAL_MEMORY_ID) {
                "memory_global_scope_purge_forbidden"
            }
            requireValidScope(scopeId)
            val hadPrivateAuthority = memoryV2Dao.hasFormalAuthorityForScope(scopeId)
            var changed = 0
            scrubDreamPrivacyOrThrow(
                scopeId = scopeId,
                targets = listOf(DreamPrivacyTarget.EntireScope),
                nowMs = nowMs,
            )
            changed += scrubGlobalAuthorityForAssistant(scopeId, nowMs, authority)

            changed += memoryV2Dao.deleteLinkRevisionsForScope(scopeId)
            changed += memoryV2Dao.deleteEvidenceForScope(scopeId)
            changed += memoryV2Dao.deleteMemoryRevisionsForScope(scopeId)
            changed += memoryV2Dao.deleteLinksForScope(scopeId)
            changed += memoryV2Dao.deleteRelationCandidatesForScope(scopeId)
            changed += memoryV2Dao.deleteCandidatesForScope(scopeId)
            changed += memoryV2Dao.deleteCapturesForScope(scopeId)
            changed += memoryV2Dao.deleteSourceTombstonesForScope(scopeId)
            changed += memoryV2Dao.deleteBackfillRunsForScope(scopeId)
            changed += memoryDao.deleteMemoriesOfAssistant(scopeId)
            if (hadPrivateAuthority) authority.scopePurge(scopeId)
            changed
        }

    /**
     * Removes one assistant's provenance from shared authority without deleting unrelated global
     * memories. Candidate evidence may already be attached to a formal Memory or Link, so the
     * pre-delete snapshot drives both head sanitization and observer receipts.
     */
    private suspend fun scrubGlobalAuthorityForAssistant(
        assistantId: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): Int {
        check(database.inTransaction()) { "memory_global_scrub_transaction_required" }
        val globalScopeId = MemoryRepository.GLOBAL_MEMORY_ID
        val candidateEvidence = memoryV2Dao.getGlobalCandidateEvidenceForAssistant(
            globalScopeId = globalScopeId,
            assistantId = assistantId,
        )
        val liveFormalEvidence = candidateEvidence.filter { evidence ->
            evidence.quality != "SOURCE_DELETED" &&
                (evidence.memoryId != null || evidence.linkId != null)
        }
        val removedRelationCandidateIds = candidateEvidence.mapNotNullTo(mutableSetOf()) {
            evidence -> evidence.relationCandidateId
        }
        val originMemoryIds = memoryDao.getGlobalMemoriesByOriginAssistant(
            globalScopeId,
            assistantId,
        ).mapTo(linkedSetOf(), MemoryEntity::id)
        val createdLinks = memoryV2Dao.getGlobalLinksCreatedByAssistant(
            globalScopeId,
            assistantId,
        )
        val evidenceByMemory = liveFormalEvidence
            .mapNotNull { evidence -> evidence.memoryId?.let { it to evidence } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val memoryIds = linkedSetOf<Int>().apply {
            addAll(originMemoryIds)
            addAll(evidenceByMemory.keys)
        }
        scrubDreamPrivacyOrThrow(
            scopeId = globalScopeId,
            targets = memoryIds.map { DreamPrivacyTarget.AuthorityMemory(it.toString()) },
            nowMs = nowMs,
        )

        var changed = memoryV2Dao.scrubGlobalCapturesForAssistant(
            globalScopeId,
            assistantId,
            nowMs,
        )
        changed += memoryV2Dao.deleteGlobalCandidateEvidenceForAssistant(
            globalScopeId,
            assistantId,
        )
        liveFormalEvidence.forEach { evidence ->
            authority.evidence(
                scopeId = globalScopeId,
                evidenceId = evidence.id,
                operation = AuthorityChangeOperation.SCRUB,
            )
        }

        val evidenceByLink = liveFormalEvidence
            .mapNotNull { evidence -> evidence.linkId?.let { it to evidence } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        val linkIds = linkedSetOf<String>().apply {
            addAll(createdLinks.map(MemoryLinkEntity::id))
            addAll(evidenceByLink.keys)
        }
        val initialLinks = createdLinks.associateBy(MemoryLinkEntity::id)
        linkIds.forEach { linkId ->
            val current = initialLinks[linkId]
                ?: memoryV2Dao.findLink(linkId, globalScopeId)
                ?: return@forEach
            val removedEvidence = evidenceByLink[linkId].orEmpty()
            val removedMessageIds = removedEvidence.mapTo(mutableSetOf()) { it.messageId }
            val retainedMessageIds = decodeList<String>(json, current.evidenceMessageIdsJson)
                .filterNot(removedMessageIds::contains)
            val creatorRemoved = current.createdByAssistantId == assistantId
            val relationCandidateRemoved = current.relationCandidateId?.let(
                removedRelationCandidateIds::contains,
            ) == true
            val sanitized = current.copy(
                createdByAssistantId = if (creatorRemoved) globalScopeId else current.createdByAssistantId,
                evidenceMessageIdsJson = json.encodeToString(retainedMessageIds),
                relationCandidateId = current.relationCandidateId.takeUnless {
                    creatorRemoved || relationCandidateRemoved
                },
            )
            val needsHeadScrub = sanitized.createdByAssistantId != current.createdByAssistantId ||
                sanitized.evidenceMessageIdsJson != current.evidenceMessageIdsJson ||
                sanitized.relationCandidateId != current.relationCandidateId
            val shouldInvalidate = current.lifecycleStatus == MemoryLinkLifecycleStatus.ACTIVE.name &&
                (creatorRemoved || removedEvidence.isNotEmpty() &&
                    memoryV2Dao.countValidEvidenceForLink(current.id) == 0)
            if (!needsHeadScrub && !shouldInvalidate) return@forEach

            memoryV2Dao.tombstoneLinkRevisionPayloads(
                linkId = current.id,
                reasonCode = "ORIGIN_ASSISTANT_REMOVED",
            )
            if (shouldInvalidate) {
                if (sanitized.relationType == MemoryRelationType.DERIVED_FROM.name) {
                    staleDerivedLinkAndDescendants(
                        link = sanitized,
                        reason = "ORIGIN_ASSISTANT_REMOVED",
                        actor = "ASSISTANT_REMOVAL",
                        nowMs = nowMs,
                        visited = hashSetOf(),
                        authority = authority,
                    )
                } else {
                    transitionLink(
                        link = sanitized,
                        lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
                        operation = MemoryLinkRevisionOperation.INVALIDATE,
                        actor = "ASSISTANT_REMOVAL",
                        reason = "ORIGIN_ASSISTANT_REMOVED",
                        nowMs = nowMs,
                        authority = authority,
                    )
                }
            } else {
                val scrubbed = sanitized.copy(
                    revision = current.revision + 1,
                    updatedAtMs = nowMs,
                )
                check(
                    memoryV2Dao.scrubLinkProvenance(
                        linkId = current.id,
                        scopeId = current.scopeId,
                        expectedRevision = current.revision,
                        createdByAssistantId = scrubbed.createdByAssistantId,
                        evidenceMessageIdsJson = scrubbed.evidenceMessageIdsJson,
                        relationCandidateId = scrubbed.relationCandidateId,
                        nowMs = nowMs,
                    ) == 1,
                ) { "memory_global_link_scrub_conflict" }
                insertLinkRevisionAndCollect(
                    revision = linkRevisionEntity(
                        link = scrubbed,
                        operation = MemoryLinkRevisionOperation.SCRUB,
                        before = sanitized,
                        actor = "ASSISTANT_REMOVAL",
                        relationCandidateId = null,
                        reasonCode = "ORIGIN_ASSISTANT_REMOVED",
                        nowMs = nowMs,
                    ),
                    link = scrubbed,
                    operation = MemoryLinkRevisionOperation.SCRUB,
                    authority = authority,
                )
                memoryV2Dao.trimLinkRevisions(scrubbed.id)
            }
            changed++
        }

        changed += memoryV2Dao.deleteGlobalRelationCandidatesForAssistant(
            globalScopeId,
            assistantId,
        )
        changed += memoryV2Dao.deleteGlobalCandidatesForAssistant(globalScopeId, assistantId)

        memoryIds.forEach { memoryId ->
            val old = memoryDao.getMemoryById(memoryId, globalScopeId) ?: return@forEach
            val originRemoved = old.originAssistantId == assistantId
            val removedEvidence = evidenceByMemory[memoryId].orEmpty()
            val identities = decodeList<MemorySourceIdentity>(json, old.sourceIdentitiesJson)
            val retainedIdentities = if (originRemoved) {
                emptyList()
            } else {
                identities.filterNot { identity ->
                    removedEvidence.any { evidence ->
                        evidence.messageId == identity.messageId &&
                            (evidence.sourceDigest.isBlank() ||
                                evidence.sourceDigest == identity.consumedTextDigest)
                    }
                }
            }
            val removedMessageIds = removedEvidence.mapTo(mutableSetOf()) { it.messageId }
            val retainedMessageIds = if (originRemoved) {
                emptyList()
            } else {
                decodeList<String>(json, old.sourceMessageIdsJson).filter { messageId ->
                    messageId !in removedMessageIds ||
                        retainedIdentities.any { it.messageId == messageId }
                }
            }
            val retainedConversationId = old.sourceConversationId?.takeIf {
                !originRemoved && (retainedMessageIds.isNotEmpty() || retainedIdentities.isNotEmpty())
            }
            val sourceBound = old.sourceType == "AUTO_EXTRACTION" ||
                old.approvalSource == MemoryApprovalSource.AUTO_SAFE.name
            val shouldStale = old.lifecycleStatus != MemoryLifecycleStatus.STALE.name &&
                sourceBound && memoryV2Dao.countValidEvidence(old.id) == 0
            val projected = old.copy(
                originAssistantId = old.originAssistantId.takeUnless { originRemoved },
                sourceConversationId = retainedConversationId,
                sourceMessageIdsJson = json.encodeToString(retainedMessageIds),
                sourceIdentitiesJson = json.encodeToString(retainedIdentities),
                lifecycleStatus = if (shouldStale) {
                    MemoryLifecycleStatus.STALE.name
                } else {
                    old.lifecycleStatus
                },
                revision = old.revision + 1,
                updatedAtMs = nowMs,
            )
            memoryV2Dao.getRevisionsForMemory(old.id, globalScopeId).forEach { revision ->
                check(
                    memoryV2Dao.tombstoneRevisionPayload(
                        revisionId = revision.id,
                        memoryId = old.id,
                        reasonCode = "ORIGIN_ASSISTANT_REMOVED",
                    ) == 1,
                ) { "memory_global_revision_tombstone_lost" }
            }
            if (old.hasSameAuthorityProjection(projected)) return@forEach

            check(memoryDao.updateMemory(projected) == 1) { "memory_global_origin_update_lost" }
            val operation = if (shouldStale) {
                MemoryRevisionOperation.STALE
            } else {
                MemoryRevisionOperation.SCRUB
            }
            val sanitizedBefore = old.copy(
                originAssistantId = projected.originAssistantId,
                sourceConversationId = projected.sourceConversationId,
                sourceMessageIdsJson = projected.sourceMessageIdsJson,
                sourceIdentitiesJson = projected.sourceIdentitiesJson,
            )
            insertMemoryRevisionAndCollect(
                revision = revisionEntity(
                    memory = projected,
                    operation = operation,
                    before = sanitizedBefore,
                    actor = "ASSISTANT_REMOVAL",
                    candidateId = null,
                    nowMs = nowMs,
                    reasonCode = "ORIGIN_ASSISTANT_REMOVED",
                ),
                memory = projected,
                operation = operation,
                authority = authority,
            )
            memoryV2Dao.trimRevisions(projected.id)
            if (shouldStale) {
                staleDerivedDescendants(
                    basis = projected,
                    reason = "ORIGIN_ASSISTANT_REMOVED",
                    actor = "ASSISTANT_REMOVAL",
                    nowMs = nowMs,
                    authority = authority,
                )
                invalidateIncidentLinks(
                    memory = projected,
                    reason = "ORIGIN_ASSISTANT_REMOVED",
                    actor = "ASSISTANT_REMOVAL",
                    nowMs = nowMs,
                    authority = authority,
                )
            }
            changed++
        }
        return changed
    }

    override suspend fun mutate(
        command: MemoryMutationCommand,
        nowMs: Long,
    ): MemoryMutationResult = withAuthorityMutation(
        nowMs = nowMs,
        reason = when (command) {
            is MemoryMutationCommand.Create,
            is MemoryMutationCommand.Update,
            -> AuthorityChangeReason.USER_MUTATION
            is MemoryMutationCommand.Archive,
            is MemoryMutationCommand.Restore,
            -> AuthorityChangeReason.LIFECYCLE_CHANGE
            is MemoryMutationCommand.RestoreRevision -> AuthorityChangeReason.RESTORE_REVISION
        },
    ) mutation@ { authority ->
        when (command) {
            is MemoryMutationCommand.Create -> {
                if (!validCreateScope(command.scopeId, command.originAssistantId)) {
                    return@mutation MemoryMutationResult.Rejected("memory_scope_invalid")
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
                    return@mutation MemoryMutationResult.Rejected("memory_mutation_invalid")
                }
                val duplicate = memoryDao.findActiveByContentHash(
                    command.scopeId,
                    memoryContentHash(command.content),
                    nowMs,
                )
                if (duplicate != null) return@mutation MemoryMutationResult.Conflict
                val memoryId = insertCreatedMemory(
                    scopeId = command.scopeId,
                    proposal = proposal,
                    approvalSource = command.approvalSource,
                    sourceType = command.sourceType,
                    sourceConversationId = command.sourceConversationId,
                    candidateId = null,
                    originAssistantId = command.originAssistantId,
                    nowMs = nowMs,
                    authority = authority,
                )
                MemoryMutationResult.Applied(memoryId, 1)
            }

            is MemoryMutationCommand.Update -> {
                val old = memoryDao.getMemoryById(command.memoryId, command.expectedScopeId)
                    ?: return@mutation MemoryMutationResult.NotFound
                if (command.expectedRevision != null && old.revision != command.expectedRevision) {
                    return@mutation MemoryMutationResult.Conflict
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
                    return@mutation MemoryMutationResult.Rejected("memory_content_invalid")
                }
                if (old.hasSameAuthorityProjection(updated)) {
                    return@mutation MemoryMutationResult.Applied(old.id, old.revision)
                }
                check(memoryDao.updateMemory(updated) == 1) { "memory_update_lost" }
                insertMemoryRevisionAndCollect(
                    revision = revisionEntity(
                        memory = updated,
                        operation = MemoryRevisionOperation.UPDATE,
                        before = old,
                        actor = command.approvalSource.name,
                        candidateId = null,
                        nowMs = nowMs,
                    ),
                    memory = updated,
                    operation = MemoryRevisionOperation.UPDATE,
                    authority = authority,
                )
                memoryV2Dao.trimRevisions(updated.id)
                invalidateLinksAfterSemanticChange(
                    old,
                    updated,
                    command.approvalSource.name,
                    nowMs,
                    authority,
                )
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
                authority = authority,
            )

            is MemoryMutationCommand.Restore -> mutateLifecycle(
                memoryId = command.memoryId,
                scopeId = command.expectedScopeId,
                expectedRevision = command.expectedRevision,
                lifecycle = MemoryLifecycleStatus.ACTIVE,
                operation = MemoryRevisionOperation.RESTORE,
                approvalSource = command.approvalSource,
                nowMs = nowMs,
                authority = authority,
            )

            is MemoryMutationCommand.RestoreRevision -> {
                val old = memoryDao.getMemoryById(command.memoryId, command.expectedScopeId)
                    ?: return@mutation MemoryMutationResult.NotFound
                if (command.expectedCurrentRevision != null &&
                    command.expectedCurrentRevision != old.revision
                ) return@mutation MemoryMutationResult.Conflict
                val revision = memoryV2Dao.findRevision(
                    memoryId = command.memoryId,
                    revision = command.revision,
                    scopeId = command.expectedScopeId,
                )
                    ?: return@mutation MemoryMutationResult.NotFound
                val raw = revision.afterSnapshotJson
                    ?: return@mutation MemoryMutationResult.Rejected("memory_revision_empty")
                val snapshot = runCatching { json.decodeFromString<MemoryRecordSnapshot>(raw) }
                    .getOrElse {
                        return@mutation MemoryMutationResult.Rejected("memory_revision_invalid")
                    }
                if (snapshot.scopeId != old.assistantId) {
                    return@mutation MemoryMutationResult.Rejected("memory_revision_scope_mismatch")
                }
                if (snapshot.expiresAtMs != null && snapshot.expiresAtMs <= nowMs) {
                    return@mutation MemoryMutationResult.Rejected("memory_restore_expired")
                }
                if (snapshot.sourceConversationId != null && snapshot.sourceIdentities.isEmpty()) {
                    // A pre-v44 snapshot cannot prove which content version it consumed. Restoring
                    // it would permit a deleted/edited message to regain authority.
                    return@mutation MemoryMutationResult.Rejected(
                        "memory_revision_source_identity_unverifiable",
                    )
                }
                if (snapshot.sourceIdentities.isNotEmpty()) {
                    val sourceConversationId = snapshot.sourceConversationId
                        ?: return@mutation MemoryMutationResult.Rejected(
                            "memory_revision_source_conversation_missing",
                        )
                    if (snapshot.sourceIdentities.any { identity -> !identity.isValid() } ||
                        snapshot.sourceMessageIds.toSet() !=
                        snapshot.sourceIdentities.mapTo(mutableSetOf(), MemorySourceIdentity::messageId)
                    ) {
                        return@mutation MemoryMutationResult.Rejected(
                            "memory_revision_source_identity_invalid",
                        )
                    }
                    val tombstones = memoryV2Dao.getSourceTombstones(
                        scopeId = command.expectedScopeId,
                        conversationId = sourceConversationId,
                    )
                    if (snapshot.sourceIdentities.any { identity ->
                            identity.conversationId != sourceConversationId ||
                                identity.isTombstonedBy(tombstones)
                        }
                    ) {
                        return@mutation MemoryMutationResult.Rejected(
                            "memory_revision_source_deleted",
                        )
                    }
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
                    sourceIdentitiesJson = json.encodeToString(snapshot.sourceIdentities),
                )
                if (old.hasSameAuthorityProjection(restored)) {
                    return@mutation MemoryMutationResult.Applied(old.id, old.revision)
                }
                check(memoryDao.updateMemory(restored) == 1) { "memory_restore_revision_lost" }
                insertMemoryRevisionAndCollect(
                    revision = revisionEntity(
                        memory = restored,
                        operation = MemoryRevisionOperation.RESTORE,
                        before = old,
                        actor = command.approvalSource.name,
                        candidateId = null,
                        nowMs = nowMs,
                    ),
                    memory = restored,
                    operation = MemoryRevisionOperation.RESTORE,
                    authority = authority,
                )
                memoryV2Dao.trimRevisions(restored.id)
                invalidateLinksAfterSemanticChange(
                    old,
                    restored,
                    command.approvalSource.name,
                    nowMs,
                    authority,
                )
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
        require(isValidMemoryScopeBinding(commit.scopeId, commit.assistantId)) {
            "memory_commit_scope_mismatch"
        }
        commit.captures.forEach { capture ->
            val identities = effectiveMemorySourceIdentities(capture)
            require(identities.isNotEmpty()) { "memory_commit_source_identity_missing" }
            require(identities.all { identity ->
                identity.isValid() &&
                    identity.conversationId == capture.conversationId &&
                    identity.evidenceGroupId == capture.id
            }) { "memory_commit_source_identity_invalid" }
            val tombstones = memoryV2Dao.getSourceTombstones(
                scopeId = commit.scopeId,
                conversationId = capture.conversationId,
            )
            require(identities.none { identity -> identity.isTombstonedBy(tombstones) }) {
                "memory_commit_source_deleted"
            }
        }
        val owned = memoryV2Dao.countOwnedProcessingCaptures(
            ids = commit.captures.map { it.id },
            scopeId = commit.scopeId,
            assistantId = commit.assistantId,
            conversationId = commit.conversationId,
            workerId = commit.workerId,
            nowMs = commit.leaseNowMs,
        )
        check(owned == commit.captures.size) { "memory_lease_lost_before_commit" }
    }

    private suspend fun insertMemoryRevisionAndCollect(
        revision: MemoryRevisionEntity,
        memory: MemoryEntity,
        operation: MemoryRevisionOperation,
        authority: AuthorityMutationCollector,
        reason: AuthorityChangeReason? = null,
    ) {
        memoryV2Dao.insertRevision(revision)
        if (reason == null) {
            authority.memory(memory, operation)
        } else {
            authority.memory(memory, operation, reason)
        }
    }

    private suspend fun insertLinkRevisionAndCollect(
        revision: MemoryLinkRevisionEntity,
        link: MemoryLinkEntity,
        operation: MemoryLinkRevisionOperation,
        authority: AuthorityMutationCollector,
        reason: AuthorityChangeReason? = null,
    ) {
        memoryV2Dao.insertLinkRevision(revision)
        if (reason == null) {
            authority.link(link, operation)
        } else {
            authority.link(link, operation, reason)
        }
    }

    private suspend fun insertEvidenceAndCollect(
        evidence: List<MemoryEvidenceEntity>,
        scopeId: String,
        authority: AuthorityMutationCollector,
        operation: AuthorityChangeOperation = AuthorityChangeOperation.CREATE,
        reason: AuthorityChangeReason? = null,
    ): List<Long> {
        if (evidence.isEmpty()) return emptyList()
        val inserted = memoryV2Dao.insertEvidence(evidence)
        check(inserted.size == evidence.size) { "memory_evidence_insert_result_mismatch" }
        evidence.zip(inserted).forEach { (row, rowId) ->
            if (rowId != -1L && (row.memoryId != null || row.linkId != null)) {
                if (reason == null) {
                    authority.evidence(scopeId, row.id, operation)
                } else {
                    authority.evidence(scopeId, row.id, operation, reason)
                }
            }
        }
        return inserted
    }

    private suspend fun attachCandidateEvidenceAndCollect(
        candidateId: String,
        memoryId: Int,
        scopeId: String,
        authority: AuthorityMutationCollector,
    ) {
        val evidence = memoryV2Dao.getValidEvidenceForCandidate(candidateId)
            .filter { it.memoryId == null }
        val attached = memoryV2Dao.attachCandidateEvidenceToMemory(candidateId, memoryId)
        check(attached == evidence.size) { "memory_candidate_evidence_attach_mismatch" }
        evidence.forEach { row ->
            authority.evidence(scopeId, row.id, AuthorityChangeOperation.REVIEW)
        }
    }

    private suspend fun attachRelationEvidenceAndCollect(
        relationCandidateId: String,
        linkId: String,
        scopeId: String,
        authority: AuthorityMutationCollector,
    ) {
        val evidence = memoryV2Dao.getUnattachedValidEvidenceForRelationCandidate(
            relationCandidateId,
        )
        val attached = memoryV2Dao.attachRelationEvidenceToLink(relationCandidateId, linkId)
        check(attached == evidence.size) { "memory_relation_evidence_attach_mismatch" }
        evidence.forEach { row ->
            authority.evidence(scopeId, row.id, AuthorityChangeOperation.REVIEW)
        }
    }

    private suspend fun insertSourceTombstonesAndCollect(
        tombstones: List<MemorySourceTombstoneEntity>,
        authority: AuthorityMutationCollector,
    ) {
        if (tombstones.isEmpty()) return
        val inserted = memoryV2Dao.insertSourceTombstones(tombstones)
        check(inserted.size == tombstones.size) { "memory_source_tombstone_result_mismatch" }
        tombstones.zip(inserted).forEach { (tombstone, rowId) ->
            if (rowId != -1L) authority.source(tombstone)
        }
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
        causeLinkId: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
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
                causeLinkId,
                nowMs,
                authority,
            )

            MemoryRelationType.CONTRADICTS.name -> {
                updatedSource = updateTruthStatus(
                    source,
                    MemoryTruthStatus.DISPUTED,
                    target.id,
                    relationType,
                    causeLinkId,
                    nowMs,
                    authority,
                )
                updatedTarget = updateTruthStatus(
                    target,
                    MemoryTruthStatus.DISPUTED,
                    source.id,
                    relationType,
                    causeLinkId,
                    nowMs,
                    authority,
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
        causeLinkId: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): MemoryEntity {
        if (old.truthStatus == truth.name) return old
        val updated = old.copy(
            truthStatus = truth.name,
            revision = old.revision + 1,
            updatedAtMs = nowMs,
            approvalSource = MemoryApprovalSource.USER_REVIEWED.name,
        )
        check(memoryDao.updateMemory(updated) == 1) { "memory_relation_truth_update_lost" }
        insertMemoryRevisionAndCollect(
            revision = revisionEntity(
                memory = updated,
                operation = MemoryRevisionOperation.UPDATE,
                before = old,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                candidateId = null,
                nowMs = nowMs,
                reasonCode = "RELATION_$relationType",
                causeMemoryId = causeMemoryId,
                causeLinkId = causeLinkId,
            ),
            memory = updated,
            operation = MemoryRevisionOperation.UPDATE,
            authority = authority,
        )
        memoryV2Dao.trimRevisions(updated.id)
        invalidateLinksAfterSemanticChange(
            old,
            updated,
            MemoryApprovalSource.USER_REVIEWED.name,
            nowMs,
            authority,
        )
        return updated
    }

    private suspend fun invalidateFormalMemorySources(
        scopeId: String,
        conversationId: String,
        deletedMessageIds: Set<String>?,
        deletedSourceVersions: Set<MemorySourceVersion>,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): Int {
        var changed = 0
        val deletingConversation = deletedMessageIds == null
        val invalidationReason = when {
            deletingConversation -> "SOURCE_CONVERSATION_DELETED"
            !deletedMessageIds.isNullOrEmpty() -> "SOURCE_MESSAGE_DELETED"
            else -> "SOURCE_VERSION_DELETED"
        }
        memoryDao.getMemoriesBySourceConversation(scopeId, conversationId).forEach { snapshot ->
            // Link invalidation may already have changed this row (for example DERIVED_FROM ->
            // STALE), so never write the stale pre-link snapshot back over the authoritative row.
            val old = memoryDao.getMemoryById(snapshot.id, scopeId) ?: return@forEach
            val sourceIds = decodeList<String>(json, old.sourceMessageIdsJson)
            val decodedIdentities = runCatching {
                json.decodeFromString<List<MemorySourceIdentity>>(old.sourceIdentitiesJson)
            }
            val identities = decodedIdentities.getOrNull().orEmpty()
            val exactVersionIds = deletedSourceVersions.mapTo(mutableSetOf()) { it.messageId }
            val matchesExactVersion = identities.any { identity ->
                deletedSourceVersions.any { version -> version.matches(identity) }
            }
            val unverifiableLegacyVersionMatch = decodedIdentities.isFailure || identities.isEmpty() &&
                sourceIds.any(exactVersionIds::contains)
            val matchesDeletedMessage = deletedMessageIds?.let { ids ->
                sourceIds.any(ids::contains) || identities.any { it.messageId in ids }
            } == true
            if (!deletingConversation && !matchesDeletedMessage && !matchesExactVersion &&
                !unverifiableLegacyVersionMatch
            ) {
                return@forEach
            }
            val retainedIdentities = if (deletingConversation) {
                emptyList()
            } else {
                identities.filterNot { identity ->
                    identity.messageId in deletedMessageIds.orEmpty() ||
                        deletedSourceVersions.any { version -> version.matches(identity) }
                }
            }
            val retainedIds = if (deletingConversation) {
                emptyList()
            } else {
                sourceIds.filter { sourceId ->
                    sourceId !in deletedMessageIds.orEmpty() &&
                        (sourceId !in exactVersionIds ||
                            retainedIdentities.any { it.messageId == sourceId })
                }
            }
            val retainedConversationId = old.sourceConversationId?.takeIf {
                !deletingConversation && (retainedIds.isNotEmpty() || retainedIdentities.isNotEmpty())
            }
            val hasEvidence = memoryV2Dao.countValidEvidence(old.id) > 0
            // USER_REVIEWED extraction is still source-bound: review authorizes the proposal,
            // it does not make deleted conversation text an independent timeless fact. Manual
            // UI memories have no extraction source and remain user authority.
            val isSourceBoundExtraction = old.sourceType == "AUTO_EXTRACTION" ||
                old.approvalSource == MemoryApprovalSource.AUTO_SAFE.name
            val shouldStale = old.lifecycleStatus == MemoryLifecycleStatus.STALE.name ||
                isSourceBoundExtraction && !hasEvidence
            val sanitizedBefore = old.copy(
                sourceConversationId = retainedConversationId,
                sourceMessageIdsJson = json.encodeToString(retainedIds),
                sourceIdentitiesJson = json.encodeToString(retainedIdentities),
            )
            memoryV2Dao.getRevisionsForMemory(old.id, scopeId).forEach { revision ->
                check(
                    memoryV2Dao.tombstoneRevisionPayload(
                        revisionId = revision.id,
                        memoryId = old.id,
                        reasonCode = invalidationReason,
                    ) == 1,
                ) { "memory_revision_source_tombstone_lost" }
            }
            val updated = old.copy(
                sourceConversationId = retainedConversationId,
                sourceMessageIdsJson = sanitizedBefore.sourceMessageIdsJson,
                sourceIdentitiesJson = sanitizedBefore.sourceIdentitiesJson,
                lifecycleStatus = if (shouldStale) {
                    MemoryLifecycleStatus.STALE.name
                } else {
                    old.lifecycleStatus
                },
                revision = old.revision + 1,
                updatedAtMs = nowMs,
            )
            check(memoryDao.updateMemory(updated) == 1) { "memory_source_invalidation_lost" }
            val operation = if (shouldStale) {
                MemoryRevisionOperation.STALE
            } else {
                MemoryRevisionOperation.UPDATE
            }
            insertMemoryRevisionAndCollect(
                revision = revisionEntity(
                    memory = updated,
                    operation = operation,
                    // Never copy deleted provenance back into the new revision snapshot.
                    before = sanitizedBefore,
                    actor = "SOURCE_INVALIDATION",
                    candidateId = null,
                    nowMs = nowMs,
                    reasonCode = invalidationReason,
                ),
                memory = updated,
                operation = operation,
                authority = authority,
            )
            memoryV2Dao.trimRevisions(updated.id)
            if (shouldStale) {
                staleDerivedDescendants(
                    basis = updated,
                    reason = "SOURCE_INVALIDATED",
                    actor = "SOURCE_INVALIDATION",
                    nowMs = nowMs,
                    authority = authority,
                )
                invalidateIncidentLinks(
                    memory = updated,
                    reason = "SOURCE_INVALIDATED",
                    actor = "SOURCE_INVALIDATION",
                    nowMs = nowMs,
                    authority = authority,
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
        authority: AuthorityMutationCollector,
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
                staleDerivedLinkAndDescendants(
                    link,
                    "SOURCE_UPDATED",
                    actor,
                    nowMs,
                    hashSetOf(),
                    authority,
                )
            } else {
                transitionLink(
                    link = link,
                    lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
                    operation = MemoryLinkRevisionOperation.INVALIDATE,
                    actor = actor,
                    reason = "ENDPOINT_SEMANTICS_CHANGED",
                    nowMs = nowMs,
                    authority = authority,
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
        authority: AuthorityMutationCollector,
    ) {
        if (!visited.add(basis.id) || visited.size > MAX_DERIVATION_GRAPH_VISITS) return
        memoryV2Dao.getActiveDerivedLinksForTarget(basis.id, basis.assistantId).forEach { link ->
            staleDerivedLinkAndDescendants(link, reason, actor, nowMs, visited, authority)
        }
    }

    /**
     * A reviewed relation remains valid while at least one evidence capsule is live. Once its last
     * source disappears, DERIVED_FROM must stale the derived memory. Truth-bearing relations stay
     * fail-closed: their already DISPUTED/SUPERSEDED endpoint is retained and receives an explicit
     * review-required revision instead of being silently promoted back to confirmed truth.
     */
    private suspend fun invalidateLinkAfterSourceLoss(
        link: MemoryLinkEntity,
        reason: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ) {
        if (link.relationType == MemoryRelationType.DERIVED_FROM.name) {
            staleDerivedLinkAndDescendants(
                link = link,
                reason = reason,
                actor = "SOURCE_INVALIDATION",
                nowMs = nowMs,
                visited = hashSetOf(),
                authority = authority,
            )
            return
        }
        transitionLink(
            link = link,
            lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
            operation = MemoryLinkRevisionOperation.INVALIDATE,
            actor = "SOURCE_INVALIDATION",
            reason = reason,
            nowMs = nowMs,
            authority = authority,
        )
        when (enumValueOrDefault(link.relationType, MemoryRelationType.RELATED_TO)) {
            MemoryRelationType.CORRECTS,
            MemoryRelationType.SUPERSEDES,
            MemoryRelationType.UPDATES,
            -> recordRelationTruthReviewRequired(link.targetMemoryId, link, nowMs, authority)

            MemoryRelationType.CONTRADICTS -> {
                recordRelationTruthReviewRequired(link.sourceMemoryId, link, nowMs, authority)
                recordRelationTruthReviewRequired(link.targetMemoryId, link, nowMs, authority)
            }

            else -> Unit
        }
        if (link.relationType in RELATION_TRUTH_BEARING_TYPES) {
            createRelationSourceReconciliationCandidate(link, nowMs)
        }
    }

    /**
     * Source deletion never silently restores a truth-bearing relation. It creates a new,
     * scope-bound review row against the post-invalidation endpoint revisions. Repeated source
     * invalidation cannot duplicate it because only ACTIVE links enter this path.
     */
    private suspend fun createRelationSourceReconciliationCandidate(
        link: MemoryLinkEntity,
        nowMs: Long,
    ) {
        val source = memoryDao.getMemoryById(link.sourceMemoryId, link.scopeId) ?: return
        val target = memoryDao.getMemoryById(link.targetMemoryId, link.scopeId) ?: return
        val candidateId = idGenerator()
        memoryV2Dao.insertRelationCandidate(
            MemoryRelationCandidateEntity(
                id = candidateId,
                batchId = "source-reconciliation-${link.id}-${link.revision + 1}",
                sourceMemoryId = source.id,
                targetMemoryId = target.id,
                relationType = link.relationType,
                weight = link.weight,
                description = link.description.take(MAX_RELATION_DESCRIPTION_CHARS),
                evidenceMessageIdsJson = "[]",
                status = MemoryRelationCandidateStatus.PENDING.name,
                createdAtMs = nowMs,
                scopeId = link.scopeId,
                createdByAssistantId = link.createdByAssistantId,
                sourceExpectedRevision = source.revision,
                targetExpectedRevision = target.revision,
                resolvedLinkId = link.id,
                resolutionError = RELATION_SOURCE_RECONCILIATION_CODE,
                updatedAtMs = nowMs,
            ),
        )
    }

    /**
     * Explicit user acceptance is new authority. It reactivates the exact invalidated link and
     * adds a content-free USER_REVIEWED evidence capsule, so deleted conversation text is never
     * resurrected and the link still has a durable proof of the review decision.
     */
    private suspend fun acceptSourceInvalidationReconciliation(
        candidate: MemoryRelationCandidateEntity,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): MemoryRelationReviewResult {
        val source = resolveRelationEndpoint(candidate, source = true, nowMs = nowMs)
            ?: return invalidateRelationCandidate(candidate, "RELATION_SOURCE_CONFLICT", nowMs)
        val target = resolveRelationEndpoint(candidate, source = false, nowMs = nowMs)
            ?: return invalidateRelationCandidate(candidate, "RELATION_TARGET_CONFLICT", nowMs)
        val linkId = candidate.resolvedLinkId
            ?: return invalidateRelationCandidate(candidate, "RELATION_LINK_MISSING", nowMs)
        val link = memoryV2Dao.findLink(linkId, candidate.scopeId)
            ?: return invalidateRelationCandidate(candidate, "RELATION_LINK_MISSING", nowMs)
        if (link.lifecycleStatus != MemoryLinkLifecycleStatus.INVALIDATED.name ||
            link.sourceMemoryId != source.id || link.targetMemoryId != target.id ||
            link.relationType != candidate.relationType
        ) {
            return invalidateRelationCandidate(candidate, "RELATION_LINK_CONFLICT", nowMs)
        }
        val updated = link.copy(
            lifecycleStatus = MemoryLinkLifecycleStatus.ACTIVE.name,
            revision = link.revision + 1,
            sourceRevision = source.revision,
            targetRevision = target.revision,
            sourceSemanticHash = source.semanticHash(json),
            targetSemanticHash = target.semanticHash(json),
            relationCandidateId = candidate.id,
            updatedAtMs = nowMs,
            invalidatedAtMs = null,
            invalidationReason = null,
        )
        check(
            memoryV2Dao.reactivateInvalidatedLinkAfterReview(
                linkId = link.id,
                scopeId = link.scopeId,
                expectedRevision = link.revision,
                sourceRevision = updated.sourceRevision,
                targetRevision = updated.targetRevision,
                sourceSemanticHash = updated.sourceSemanticHash,
                targetSemanticHash = updated.targetSemanticHash,
                candidateId = candidate.id,
                nowMs = nowMs,
            ) == 1,
        ) { "memory_relation_reconciliation_conflict" }
        val reviewDigest = memoryContentHash("RELATION_REVIEW:${candidate.id}")
        insertEvidenceAndCollect(
            evidence = listOf(
                MemoryEvidenceEntity(
                    id = "evidence-relation-review-${candidate.id}",
                    relationCandidateId = candidate.id,
                    linkId = link.id,
                    conversationId = RELATION_REVIEW_SOURCE,
                    messageId = candidate.id,
                    role = MemorySourceRole.USER.name,
                    excerpt = "",
                    contentHash = reviewDigest,
                    capturedAtMs = nowMs,
                    quality = "USER_REVIEWED_RELATION",
                    evidenceGroupId = "relation-review-${candidate.id}",
                    sourceDigest = reviewDigest,
                    sourceKind = MemorySourceKind.TEXT.name,
                ),
            ),
            scopeId = candidate.scopeId,
            authority = authority,
        )
        insertLinkRevisionAndCollect(
            revision = linkRevisionEntity(
                link = updated,
                operation = MemoryLinkRevisionOperation.RESTORE,
                before = link,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                relationCandidateId = candidate.id,
                reasonCode = "RELATION_SOURCE_INVALIDATION_REVIEW_ACCEPTED",
                nowMs = nowMs,
            ),
            link = updated,
            operation = MemoryLinkRevisionOperation.RESTORE,
            authority = authority,
        )
        memoryV2Dao.trimLinkRevisions(link.id)
        check(
            memoryV2Dao.resolveRelationCandidate(
                candidateId = candidate.id,
                scopeId = candidate.scopeId,
                status = MemoryRelationCandidateStatus.ACCEPTED.name,
                resolvedLinkId = link.id,
                resolutionError = null,
                nowMs = nowMs,
            ) == 1,
        ) { "memory_relation_reconciliation_resolution_lost" }
        return MemoryRelationReviewResult.Applied(link.id)
    }

    private suspend fun recordRelationTruthReviewRequired(
        memoryId: Int,
        link: MemoryLinkEntity,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ) {
        val old = memoryDao.getMemoryById(memoryId, link.scopeId) ?: return
        if (old.truthStatus !in RELATION_REVIEW_REQUIRED_TRUTH_STATUSES) return
        val updated = old.copy(
            revision = old.revision + 1,
            updatedAtMs = nowMs,
        )
        check(memoryDao.updateMemory(updated) == 1) {
            "memory_relation_review_required_update_lost"
        }
        insertMemoryRevisionAndCollect(
            revision = revisionEntity(
                memory = updated,
                operation = MemoryRevisionOperation.UPDATE,
                before = old,
                actor = "SOURCE_INVALIDATION",
                candidateId = null,
                nowMs = nowMs,
                reasonCode = "RELATION_SOURCE_INVALIDATED_REVIEW_REQUIRED",
                causeMemoryId = if (memoryId == link.sourceMemoryId) {
                    link.targetMemoryId
                } else {
                    link.sourceMemoryId
                },
                causeLinkId = link.id,
            ),
            memory = updated,
            operation = MemoryRevisionOperation.UPDATE,
            authority = authority,
        )
        memoryV2Dao.trimRevisions(updated.id)
    }

    private suspend fun staleDerivedLinkAndDescendants(
        link: MemoryLinkEntity,
        reason: String,
        actor: String,
        nowMs: Long,
        visited: MutableSet<Int>,
        authority: AuthorityMutationCollector,
    ) {
        transitionLink(
            link = link,
            lifecycle = MemoryLinkLifecycleStatus.INVALIDATED,
            operation = MemoryLinkRevisionOperation.INVALIDATE,
            actor = actor,
            reason = reason,
            nowMs = nowMs,
            authority = authority,
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
                insertMemoryRevisionAndCollect(
                    revision = revisionEntity(
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
                    memory = updated,
                    operation = MemoryRevisionOperation.STALE,
                    authority = authority,
                )
                memoryV2Dao.trimRevisions(updated.id)
            }
        }
        staleDerivedDescendants(derived, reason, actor, nowMs, visited, authority)
    }

    private suspend fun suspendIncidentLinks(
        memory: MemoryEntity,
        actor: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
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
                authority = authority,
            )
        }
    }

    private suspend fun invalidateIncidentLinks(
        memory: MemoryEntity,
        reason: String,
        actor: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
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
                authority = authority,
            )
        }
    }

    private suspend fun restoreEligibleIncidentLinks(
        memory: MemoryEntity,
        actor: String,
        nowMs: Long,
        authority: AuthorityMutationCollector,
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
                    authority = authority,
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
        authority: AuthorityMutationCollector,
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
            createdByAssistantId = updated.createdByAssistantId,
            evidenceMessageIdsJson = updated.evidenceMessageIdsJson,
            relationCandidateId = updated.relationCandidateId,
            nowMs = nowMs,
            invalidatedAtMs = updated.invalidatedAtMs,
            reason = updated.invalidationReason,
        )
        check(changed == 1) { "memory_link_revision_conflict" }
        insertLinkRevisionAndCollect(
            revision = linkRevisionEntity(
                link = updated,
                operation = operation,
                before = link,
                actor = actor,
                relationCandidateId = link.relationCandidateId,
                reasonCode = reason,
                nowMs = nowMs,
            ),
            link = updated,
            operation = operation,
            authority = authority,
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
        sourceIdentities: List<MemorySourceIdentity> = emptyList(),
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): Int {
        val memory = proposal.toNewEntity(
            scopeId = scopeId,
            approvalSource = approvalSource,
            sourceType = sourceType,
            sourceConversationId = sourceConversationId,
            originAssistantId = originAssistantId,
            sourceIdentities = sourceIdentities,
            nowMs = nowMs,
            json = json,
        )
        val memoryId = memoryDao.insertMemory(memory).toInt()
        val inserted = memory.copy(id = memoryId)
        insertMemoryRevisionAndCollect(
            revision = revisionEntity(
                memory = inserted,
                operation = MemoryRevisionOperation.CREATE,
                before = null,
                actor = approvalSource.name,
                candidateId = candidateId,
                nowMs = nowMs,
            ),
            memory = inserted,
            operation = MemoryRevisionOperation.CREATE,
            authority = authority,
        )
        return memoryId
    }

    private suspend fun applyUpdate(
        candidate: MemoryCandidateEntity,
        proposal: MemoryProposal,
        nowMs: Long,
        authority: AuthorityMutationCollector,
    ): Int? {
        val targetId = proposal.targetIds.singleOrNull() ?: return null
        val expected = proposal.expectedRevisions.singleOrNull() ?: return null
        val old = memoryDao.getMemoryById(targetId, candidate.scopeId) ?: return null
        if (old.revision != expected) return null
        val updated = old.applyProposal(
            proposal = proposal,
            nowMs = nowMs,
            sourceConversationId = candidate.sourceConversationId,
            sourceIdentities = memoryV2Dao.getValidEvidenceForCandidate(candidate.id)
                .toSourceIdentities(),
        )
        if (old.hasSameAuthorityProjection(updated)) return old.id
        if (memoryDao.updateMemory(updated) != 1) return null
        insertMemoryRevisionAndCollect(
            revision = revisionEntity(
                memory = updated,
                operation = MemoryRevisionOperation.UPDATE,
                before = old,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                candidateId = candidate.id,
                nowMs = nowMs,
            ),
            memory = updated,
            operation = MemoryRevisionOperation.UPDATE,
            authority = authority,
        )
        memoryV2Dao.trimRevisions(updated.id)
        invalidateLinksAfterSemanticChange(
            old = old,
            updated = updated,
            actor = MemoryApprovalSource.USER_REVIEWED.name,
            nowMs = nowMs,
            authority = authority,
        )
        return updated.id
    }

    private suspend fun applyMerge(
        candidate: MemoryCandidateEntity,
        proposal: MemoryProposal,
        nowMs: Long,
        authority: AuthorityMutationCollector,
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
        val primary = primaryOld.applyProposal(
            proposal = proposal,
            nowMs = nowMs,
            sourceConversationId = candidate.sourceConversationId,
            sourceIdentities = memoryV2Dao.getValidEvidenceForCandidate(candidate.id)
                .toSourceIdentities(),
        )
        if (!primaryOld.hasSameAuthorityProjection(primary)) {
            if (memoryDao.updateMemory(primary) != 1) return null
            insertMemoryRevisionAndCollect(
                revision = revisionEntity(
                    memory = primary,
                    operation = MemoryRevisionOperation.MERGE,
                    before = primaryOld,
                    actor = MemoryApprovalSource.USER_REVIEWED.name,
                    candidateId = candidate.id,
                    nowMs = nowMs,
                ),
                memory = primary,
                operation = MemoryRevisionOperation.MERGE,
                authority = authority,
            )
            memoryV2Dao.trimRevisions(primary.id)
            invalidateLinksAfterSemanticChange(
                old = primaryOld,
                updated = primary,
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                nowMs = nowMs,
                authority = authority,
            )
        }
        targets.drop(1).forEach { old ->
            val archived = old.copy(
                lifecycleStatus = MemoryLifecycleStatus.ARCHIVED.name,
                approvalSource = MemoryApprovalSource.USER_REVIEWED.name,
                updatedAtMs = nowMs,
                revision = old.revision + 1,
            )
            check(memoryDao.updateMemory(archived) == 1) { "memory_merge_archive_lost" }
            insertMemoryRevisionAndCollect(
                revision = revisionEntity(
                    memory = archived,
                    operation = MemoryRevisionOperation.ARCHIVE,
                    before = old,
                    actor = MemoryApprovalSource.USER_REVIEWED.name,
                    candidateId = candidate.id,
                    nowMs = nowMs,
                ),
                memory = archived,
                operation = MemoryRevisionOperation.ARCHIVE,
                authority = authority,
            )
            memoryV2Dao.trimRevisions(archived.id)
            suspendIncidentLinks(
                archived,
                MemoryApprovalSource.USER_REVIEWED.name,
                nowMs,
                authority,
            )
            staleDerivedDescendants(
                basis = archived,
                reason = "SOURCE_MERGED_ARCHIVED",
                actor = MemoryApprovalSource.USER_REVIEWED.name,
                nowMs = nowMs,
                authority = authority,
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
        authority: AuthorityMutationCollector,
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
        insertMemoryRevisionAndCollect(
            revision = revisionEntity(
                memory = updated,
                operation = operation,
                before = old,
                actor = approvalSource.name,
                candidateId = null,
                nowMs = nowMs,
            ),
            memory = updated,
            operation = operation,
            authority = authority,
        )
        memoryV2Dao.trimRevisions(updated.id)
        if (lifecycle == MemoryLifecycleStatus.ARCHIVED) {
            staleDerivedDescendants(
                basis = updated,
                reason = "SOURCE_ARCHIVED",
                actor = approvalSource.name,
                nowMs = nowMs,
                authority = authority,
            )
            suspendIncidentLinks(updated, approvalSource.name, nowMs, authority)
        } else if (lifecycle == MemoryLifecycleStatus.ACTIVE) {
            restoreEligibleIncidentLinks(updated, approvalSource.name, nowMs, authority)
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
        sourceIdentitiesJson = memory.sourceIdentitiesJson,
        createdAtMs = nowMs,
        reasonCode = reasonCode,
        causeMemoryId = causeMemoryId,
        causeLinkId = causeLinkId,
    )
}

private fun MemoryCaptureEntity.toRecord(workerId: String): MemoryCaptureRecord {
    val decoded = runCatching {
        Json.decodeFromString<List<MemorySourceIdentity>>(sourceIdentitiesJson)
    }
    val fallbackAllowed = decoded.isSuccess && decoded.getOrNull().isNullOrEmpty() &&
        sourceIdentitiesJson.trim() == "[]" && payloadPurgedAtMs == null
    return MemoryCaptureRecord(
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
        sourceIdentities = decoded.getOrNull().orEmpty(),
        sourceIdentityFallbackAllowed = fallbackAllowed,
        createdAtMs = createdAtMs,
        conversationContextTurns = contextTurnLimit,
        narrativeEventsEnabled = narrativeEventsEnabled,
        insightsTheoriesEnabled = insightsTheoriesEnabled,
        leaseOwner = workerId,
    )
}

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
    sourceIdentities: List<MemorySourceIdentity>,
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
    sourceIdentitiesJson = json.encodeToString(sourceIdentities),
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

private fun MemoryEntity.applyProposal(
    proposal: MemoryProposal,
    nowMs: Long,
    sourceConversationId: String?,
    sourceIdentities: List<MemorySourceIdentity>,
) = copy(
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
    sourceConversationId = sourceConversationId,
    sourceMessageIdsJson = Json.encodeToString(proposal.evidenceMessageIds),
    sourceIdentitiesJson = Json.encodeToString(sourceIdentities),
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
    sourceIdentities = decodeList(json, sourceIdentitiesJson),
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

private fun sourceIdentitiesForEvidence(
    captures: List<MemoryCaptureRecord>,
    evidenceMessageIds: List<String>,
): List<MemorySourceIdentity> {
    val evidenceIds = evidenceMessageIds.toSet()
    return captures.asSequence()
        .flatMap { capture -> effectiveMemorySourceIdentities(capture).asSequence() }
        .filter { identity -> identity.messageId in evidenceIds }
        .distinctBy { identity ->
            Triple(identity.evidenceGroupId, identity.messageId, identity.consumedTextDigest)
        }
        .toList()
}

private fun List<MemoryEvidenceEntity>.toSourceIdentities(): List<MemorySourceIdentity> =
    asSequence()
        .filter { evidence ->
            evidence.conversationId.isNotBlank() &&
                evidence.messageId.isNotBlank() &&
                evidence.evidenceGroupId.isNotBlank() &&
                evidence.sourceDigest.isNotBlank()
        }
        .mapNotNull { evidence ->
            val role = runCatching { MemorySourceRole.valueOf(evidence.role) }.getOrNull()
                ?: return@mapNotNull null
            val sourceKind = runCatching { MemorySourceKind.valueOf(evidence.sourceKind) }.getOrNull()
                ?: return@mapNotNull null
            MemorySourceIdentity(
                conversationId = evidence.conversationId,
                messageId = evidence.messageId,
                role = role,
                consumedTextDigest = evidence.sourceDigest,
                evidenceGroupId = evidence.evidenceGroupId,
                sourceKind = sourceKind,
            )
        }
        .distinctBy { identity ->
            Triple(identity.evidenceGroupId, identity.messageId, identity.consumedTextDigest)
        }
        .toList()

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
    val evidenceIds = evidenceMessageIds.toSet()
    return captures.flatMap { capture ->
        effectiveMemorySourceIdentities(capture).map { identity -> capture to identity }
    }.filter { (_, identity) -> identity.messageId in evidenceIds }
        .distinctBy { (_, identity) ->
            Triple(identity.evidenceGroupId, identity.messageId, identity.consumedTextDigest)
        }
        .map { (capture, identity) ->
            val sourceText = when (identity.messageId) {
                capture.userMessageId -> capture.userText
                capture.assistantMessageId -> capture.assistantText
                else -> ""
            }
            // Lineage is complete even when the UI excerpt budget is exhausted. Extra source
            // rows keep a digest/group identity with an empty excerpt instead of disappearing.
            val excerpt = if (remaining <= 0 || sourceText.isBlank()) {
                ""
            } else {
                guard.redact(sourceText).text.trim().take(minOf(600, remaining))
            }
            remaining -= excerpt.length
            MemoryEvidenceEntity(
                id = "evidence-$ownerId-${identity.evidenceGroupId}-${identity.messageId}-" +
                    identity.consumedTextDigest.take(16),
                memoryId = memoryId,
                candidateId = candidateId,
                relationCandidateId = relationCandidateId,
                conversationId = conversationId,
                messageId = identity.messageId,
                role = identity.role.name,
                excerpt = excerpt,
                contentHash = excerpt.takeIf(String::isNotEmpty)?.let(::memoryContentHash)
                    ?: identity.consumedTextDigest,
                capturedAtMs = nowMs,
                evidenceGroupId = identity.evidenceGroupId,
                sourceDigest = identity.consumedTextDigest,
                sourceKind = identity.sourceKind.name,
            )
        }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

private inline fun <reified T> decodeList(json: Json, raw: String): List<T> =
    runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())

private fun MemoryRelationCandidateEntity.isSourceInvalidationReconciliation(): Boolean =
    resolutionError == RELATION_SOURCE_RECONCILIATION_CODE && resolvedLinkId != null

internal class AuthorityMutationCollector(
    private val defaultReason: AuthorityChangeReason,
) {
    private data class Key(
        val scopeId: DreamScopeId,
        val entityKind: AuthorityEntityKind,
        val entityId: String,
    )

    private val changes = linkedMapOf<Key, AuthorityChange>()

    fun memory(
        memory: MemoryEntity,
        operation: MemoryRevisionOperation,
        reason: AuthorityChangeReason = defaultReason,
    ) = record(
        scopeId = memory.assistantId,
        entityKind = AuthorityEntityKind.MEMORY,
        entityId = memory.id.toString(),
        entityRevision = memory.revision.toLong(),
        operation = operation.toAuthorityOperation(),
        reason = reason,
    )

    fun link(
        link: MemoryLinkEntity,
        operation: MemoryLinkRevisionOperation,
        reason: AuthorityChangeReason = defaultReason,
    ) = record(
        scopeId = link.scopeId,
        entityKind = AuthorityEntityKind.LINK,
        entityId = link.id,
        entityRevision = link.revision.toLong(),
        operation = operation.toAuthorityOperation(),
        reason = reason,
    )

    fun evidence(
        scopeId: String,
        evidenceId: String,
        operation: AuthorityChangeOperation,
        reason: AuthorityChangeReason = defaultReason,
    ) = record(
        scopeId = scopeId,
        entityKind = AuthorityEntityKind.EVIDENCE,
        entityId = evidenceId,
        entityRevision = null,
        operation = operation,
        reason = reason,
    )

    fun source(
        tombstone: MemorySourceTombstoneEntity,
        reason: AuthorityChangeReason = defaultReason,
    ) = record(
        scopeId = tombstone.scopeId,
        entityKind = AuthorityEntityKind.SOURCE,
        entityId = tombstone.authorityEntityId(),
        entityRevision = null,
        operation = AuthorityChangeOperation.INVALIDATE,
        reason = reason,
    )

    fun scopePurge(scopeId: String) = record(
        scopeId = scopeId,
        entityKind = AuthorityEntityKind.SCOPE_PURGE,
        entityId = DreamScopeId.requireCanonical(scopeId).value,
        entityRevision = null,
        operation = AuthorityChangeOperation.DELETE,
        reason = AuthorityChangeReason.SCOPE_PURGE,
    )

    fun snapshot(): List<AuthorityChange> = changes.values.sortedWith(
        compareBy({ it.scopeId.value }, { it.entityKind.name }, { it.entityId }),
    )

    private fun record(
        scopeId: String,
        entityKind: AuthorityEntityKind,
        entityId: String,
        entityRevision: Long?,
        operation: AuthorityChangeOperation,
        reason: AuthorityChangeReason,
    ) {
        val scope = DreamScopeId.requireCanonical(scopeId)
        val key = Key(scope, entityKind, entityId)
        changes[key] = AuthorityChange(
            scopeId = scope,
            entityKind = entityKind,
            entityId = entityId,
            entityRevision = entityRevision,
            operation = operation,
            reasonCode = reason,
        )
    }
}

private fun MemoryRevisionOperation.toAuthorityOperation(): AuthorityChangeOperation = when (this) {
    MemoryRevisionOperation.CREATE -> AuthorityChangeOperation.CREATE
    MemoryRevisionOperation.UPDATE,
    MemoryRevisionOperation.MERGE,
    -> AuthorityChangeOperation.UPDATE
    MemoryRevisionOperation.ARCHIVE -> AuthorityChangeOperation.ARCHIVE
    MemoryRevisionOperation.RESTORE -> AuthorityChangeOperation.RESTORE
    MemoryRevisionOperation.EXPIRE -> AuthorityChangeOperation.EXPIRE
    MemoryRevisionOperation.STALE -> AuthorityChangeOperation.STALE
    MemoryRevisionOperation.SCRUB -> AuthorityChangeOperation.SCRUB
}

private fun MemoryLinkRevisionOperation.toAuthorityOperation(): AuthorityChangeOperation =
    when (this) {
        MemoryLinkRevisionOperation.CREATE -> AuthorityChangeOperation.CREATE
        MemoryLinkRevisionOperation.SUSPEND -> AuthorityChangeOperation.ARCHIVE
        MemoryLinkRevisionOperation.RESTORE -> AuthorityChangeOperation.RESTORE
        MemoryLinkRevisionOperation.INVALIDATE -> AuthorityChangeOperation.INVALIDATE
        MemoryLinkRevisionOperation.SCRUB -> AuthorityChangeOperation.SCRUB
    }

private fun MemorySourceTombstoneEntity.authorityEntityId(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(scopeId, conversationId, sourceKind, sourceId, sourceDigest).forEach { component ->
        val bytes = component.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(bytes)
    }
    return "source-" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun MemoryEntity.hasSameAuthorityProjection(other: MemoryEntity): Boolean =
    copy(updatedAtMs = 0L, lastAccessedAtMs = null, revision = 0) ==
        other.copy(updatedAtMs = 0L, lastAccessedAtMs = null, revision = 0)

private data class PersistedCandidateState(
    val id: String,
    val appliedMemoryId: Int?,
)

private data class NormalizedSourceInvalidation(
    val scopeId: String,
    val invalidateWholeConversation: Boolean,
    val removedMessageIds: Set<String>,
    val removedSourceVersions: Set<MemorySourceVersion>,
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

private fun sourceTombstone(
    scopeId: String,
    conversationId: String,
    sourceKind: String,
    sourceId: String,
    sourceDigest: String = "",
    reasonCode: String,
    nowMs: Long,
) = MemorySourceTombstoneEntity(
    scopeId = scopeId,
    conversationId = conversationId,
    sourceKind = sourceKind,
    sourceId = sourceId,
    sourceDigest = sourceDigest,
    reasonCode = reasonCode,
    tombstonedAtMs = nowMs,
)

private fun MemorySourceVersion.isValid(): Boolean =
    messageId.isNotBlank() && consumedTextDigest.length == 64 &&
        consumedTextDigest.all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }

private fun MemorySourceVersion.matches(identity: MemorySourceIdentity): Boolean =
    messageId == identity.messageId && consumedTextDigest == identity.consumedTextDigest

private fun MemorySourceIdentity.isValid(): Boolean =
    conversationId.isNotBlank() && messageId.isNotBlank() && evidenceGroupId.isNotBlank() &&
        MemorySourceVersion(messageId, consumedTextDigest).isValid()

private fun MemorySourceIdentity.isTombstonedBy(
    tombstones: List<MemorySourceTombstoneEntity>,
): Boolean = tombstones.any { tombstone ->
    tombstone.scopeId.isNotBlank() &&
        tombstone.conversationId == conversationId &&
        when (tombstone.sourceKind) {
            SOURCE_TOMBSTONE_CONVERSATION -> tombstone.sourceId == conversationId
            SOURCE_TOMBSTONE_MESSAGE -> tombstone.sourceId == messageId &&
                (tombstone.sourceDigest.isEmpty() ||
                    tombstone.sourceDigest == consumedTextDigest)
            else -> false
        }
}

private val RELATION_RESOLVABLE_CANDIDATE_STATUSES = setOf(
    MemoryCandidateStatus.AUTO_APPLIED.name,
    MemoryCandidateStatus.ACCEPTED.name,
    MemoryCandidateStatus.SUPERSEDED.name,
)

private val RELATION_REVIEW_REQUIRED_TRUTH_STATUSES = setOf(
    MemoryTruthStatus.DISPUTED.name,
    MemoryTruthStatus.SUPERSEDED.name,
)

private val RELATION_TRUTH_BEARING_TYPES = setOf(
    MemoryRelationType.CORRECTS.name,
    MemoryRelationType.SUPERSEDES.name,
    MemoryRelationType.UPDATES.name,
    MemoryRelationType.CONTRADICTS.name,
)
private const val RELATION_SOURCE_RECONCILIATION_CODE =
    "RELATION_SOURCE_INVALIDATED_REVIEW_REQUIRED"
private const val RELATION_REVIEW_SOURCE = "__relation_user_review__"

private const val MAX_STORED_ERROR_CHARS = 1_000
private const val MAX_STORED_REASON_CHARS = 2_000
private const val MAX_RELATION_DESCRIPTION_CHARS = 500
private const val MAX_DERIVATION_GRAPH_VISITS = 256
private const val MAX_DREAM_PRIVACY_TARGETS = 4_096
private const val MAX_EXPIRY_MATERIALIZATION_BATCH = 100
private const val PROCESSED_CAPTURE_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
private const val FAILED_CAPTURE_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
private const val SOURCE_TOMBSTONE_CONVERSATION = "CONVERSATION"
private const val SOURCE_TOMBSTONE_MESSAGE = "MESSAGE"

package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryRelationCandidateEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryMutationCommand
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationResult
import me.rerere.rikkahub.memory.MemorySourceVersion
import me.rerere.rikkahub.memory.MemoryScopeSourceInvalidation
import me.rerere.rikkahub.memory.MemorySourceInvalidationBatch
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryExpiryUpdate
import me.rerere.rikkahub.memory.MemoryQueryRecord
import me.rerere.rikkahub.memory.MemoryWriteInput
import me.rerere.rikkahub.utils.JsonInstant

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val retriever: MemoryRetriever,
    private val mutationCoordinator: MemoryMutationCoordinator,
    private val memoryV2Dao: MemoryV2Dao? = null,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId, System.currentTimeMillis())
            .map { entities ->
                entities.map(MemoryEntity::toAssistantMemory)
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId, System.currentTimeMillis())
            .map(MemoryEntity::toAssistantMemory)
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID, System.currentTimeMillis())
            .map { entities ->
                entities.map(MemoryEntity::toAssistantMemory)
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID, System.currentTimeMillis())
            .map(MemoryEntity::toAssistantMemory)
    }

    /**
     * Returns a bounded, redacted view of pending relation reviews in exactly one effective
     * Memory scope. The caller chooses the host-authoritative scope; rows are filtered again here
     * even though the DAO query is already scoped, so a malformed adapter cannot leak another
     * assistant's candidates.
     */
    suspend fun getPendingRelationReviews(
        scopeId: String,
        limit: Int = DEFAULT_RELATION_REVIEW_LIMIT,
    ): List<MemoryRelationReviewRecord> {
        if (!isValidRelationReviewScope(scopeId)) return emptyList()
        val dao = memoryV2Dao ?: return emptyList()
        val boundedLimit = limit.coerceIn(1, MAX_RELATION_REVIEW_LIMIT)
        return pendingRelationReviewRecords(
            rows = dao.observePendingRelationCandidates(scopeId).first(),
            expectedScopeId = scopeId,
            limit = boundedLimit,
        )
    }

    suspend fun getUserApprovedStandingMemories(
        assistantId: kotlin.uuid.Uuid?,
        includeGlobal: Boolean,
        limit: Int = 16,
        frozenNowMs: Long = System.currentTimeMillis(),
    ): List<AssistantMemory> {
        val scopeId = when {
            includeGlobal -> GLOBAL_MEMORY_ID
            assistantId != null -> assistantId.toString()
            else -> return emptyList()
        }
        return memoryDAO.getUserApprovedStandingMemories(
            scopeId = scopeId,
            nowMs = frozenNowMs,
            limit = limit.coerceIn(1, 32),
        ).map(MemoryEntity::toAssistantMemory)
    }

    suspend fun queryRelevant(
        assistantId: kotlin.uuid.Uuid?,
        query: String,
        includeGlobal: Boolean,
        limit: Int = DEFAULT_MEMORY_TOP_K,
        maxChars: Int = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
        excludeMemoryIds: Set<Int> = emptySet(),
        frozenNowMs: Long = System.currentTimeMillis(),
    ): List<MemoryMatch> = retrieveRelevant(
        assistantId = assistantId,
        query = query,
        includeGlobal = includeGlobal,
        limit = limit,
        maxChars = maxChars,
        excludeMemoryIds = excludeMemoryIds,
        frozenNowMs = frozenNowMs,
    ).matches

    suspend fun retrieveRelevant(
        assistantId: kotlin.uuid.Uuid?,
        query: String,
        includeGlobal: Boolean,
        limit: Int = DEFAULT_MEMORY_TOP_K,
        maxChars: Int = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
        excludeMemoryIds: Set<Int> = emptySet(),
        frozenNowMs: Long,
        querySource: MemoryRetrievalQuerySource = MemoryRetrievalQuerySource.UNSPECIFIED,
    ): MemoryRetrievalResult = retriever.retrieve(
        MemoryRetrievalRequest(
            assistantId = assistantId,
            query = query,
            includeGlobal = includeGlobal,
            limit = limit,
            maxChars = maxChars,
            excludeMemoryIds = excludeMemoryIds,
            frozenNowMs = frozenNowMs,
            querySource = querySource,
        ),
    )

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        mutationCoordinator.purgeScope(assistantId)
    }

    suspend fun updateContent(
        scopeId: String,
        id: Int,
        content: String,
        expectedRevision: Int? = null,
    ): AssistantMemory {
        return updateMemory(
            scopeId = scopeId,
            id = id,
            input = MemoryWriteInput(content = content),
            expectedRevision = expectedRevision,
        )
    }

    suspend fun updateMemory(
        scopeId: String,
        id: Int,
        input: MemoryWriteInput,
        expectedRevision: Int? = null,
    ): AssistantMemory {
        val result = mutationCoordinator.mutate(
            MemoryMutationCommand.Update(
                memoryId = id,
                expectedScopeId = scopeId,
                expectedRevision = expectedRevision,
                title = input.title,
                content = input.content,
                kind = input.kind,
                tags = input.tags,
                importance = input.importance,
                expiryUpdate = input.expiresAtMs?.let(MemoryExpiryUpdate::Set)
                    ?: input.expiryUpdate,
                approvalSource = MemoryApprovalSource.MEMORY_TOOL,
            ),
        )
        return result.toAssistantMemory(scopeId, id)
    }

    suspend fun addMemory(
        scopeId: String,
        content: String,
        originAssistantId: String,
    ): AssistantMemory {
        return addMemory(scopeId, MemoryWriteInput(content = content), originAssistantId)
    }

    suspend fun addMemory(
        scopeId: String,
        input: MemoryWriteInput,
        originAssistantId: String,
    ): AssistantMemory {
        val result = mutationCoordinator.mutate(
            MemoryMutationCommand.Create(
                scopeId = scopeId,
                title = input.title,
                content = input.content,
                kind = input.kind ?: MemoryKind.OTHER,
                tags = input.tags.orEmpty(),
                importance = input.importance ?: 0.5f,
                confidence = input.confidence ?: 1f,
                expiresAtMs = input.expiresAtMs,
                approvalSource = MemoryApprovalSource.MEMORY_TOOL,
                sourceType = "MEMORY_TOOL",
                originAssistantId = originAssistantId,
            ),
        )
        return result.toAssistantMemory(scopeId)
    }

    /** Archives a memory and returns the exact revision committed by the mutation transaction. */
    suspend fun deleteMemory(scopeId: String, id: Int, expectedRevision: Int? = null): Int {
        return when (val result = mutationCoordinator.mutate(
            MemoryMutationCommand.Archive(
                memoryId = id,
                expectedScopeId = scopeId,
                expectedRevision = expectedRevision,
                approvalSource = MemoryApprovalSource.MEMORY_TOOL,
            ),
        )) {
            is MemoryMutationResult.Applied -> result.revision
            MemoryMutationResult.NotFound -> error("Memory record #$id not found")
            MemoryMutationResult.Conflict -> error("Memory record #$id changed")
            is MemoryMutationResult.Rejected -> error("Memory archive rejected")
        }
    }

    suspend fun restoreMemory(scopeId: String, id: Int, expectedRevision: Int? = null) {
        when (mutationCoordinator.mutate(
            MemoryMutationCommand.Restore(
                memoryId = id,
                expectedScopeId = scopeId,
                expectedRevision = expectedRevision,
                approvalSource = MemoryApprovalSource.MEMORY_TOOL,
            ),
        )) {
            is MemoryMutationResult.Applied -> Unit
            MemoryMutationResult.NotFound -> error("Memory record #$id not found")
            MemoryMutationResult.Conflict -> error("Memory record #$id changed")
            is MemoryMutationResult.Rejected -> error("Memory restore rejected")
        }
    }

    suspend fun getMemoryEntity(scopeId: String, id: Int): MemoryEntity? =
        memoryDAO.getMemoryById(id, scopeId)

    suspend fun markLastAccessed(
        scopeId: String,
        memoryIds: Set<Int>,
        accessedAtMs: Long,
        frozenNowMs: Long,
    ): Int = if (memoryIds.isEmpty()) {
        0
    } else {
        memoryDAO.markLastAccessed(memoryIds.toList(), scopeId, accessedAtMs, frozenNowMs)
    }

    suspend fun invalidateSourceConversation(scopeId: String, conversationId: String): Int =
        mutationCoordinator.invalidateSourceConversation(scopeId, conversationId)

    suspend fun invalidateSourceConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int = mutationCoordinator.invalidateSourceConversation(scopeId, conversationId, nowMs)

    /**
     * Invalidates one source in several exclusive memory scopes using one frozen timestamp.
     * A caller that also mutates source rows should wrap this call in its Room transaction.
     */
    suspend fun invalidateSourceConversation(
        scopeIds: Set<String>,
        conversationId: String,
        nowMs: Long,
    ): Int = mutationCoordinator.invalidateSources(
        MemorySourceInvalidationBatch(
            conversationId = conversationId,
            scopes = scopeIds.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .map { scopeId ->
                    MemoryScopeSourceInvalidation(
                        scopeId = scopeId,
                        invalidateWholeConversation = true,
                    )
                }
                .toList(),
        ),
        nowMs,
    )

    suspend fun invalidateSources(
        batch: MemorySourceInvalidationBatch,
        nowMs: Long,
    ): Int = mutationCoordinator.invalidateSources(batch, nowMs)

    suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
    ): Int = mutationCoordinator.invalidateSourceMessages(scopeId, conversationId, messageIds)

    suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
        nowMs: Long,
    ): Int = mutationCoordinator.invalidateSourceMessages(
        scopeId,
        conversationId,
        messageIds,
        nowMs,
    )

    /** Uses the same frozen timestamp for every scope; see the transaction note above. */
    suspend fun invalidateSourceMessages(
        scopeIds: Set<String>,
        conversationId: String,
        messageIds: Set<String>,
        nowMs: Long,
    ): Int {
        val normalizedMessageIds = messageIds.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        if (normalizedMessageIds.isEmpty()) return 0
        return mutationCoordinator.invalidateSources(
            MemorySourceInvalidationBatch(
                conversationId = conversationId,
                scopes = scopeIds.asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .map { scopeId ->
                        MemoryScopeSourceInvalidation(
                            scopeId = scopeId,
                            removedMessageIds = normalizedMessageIds,
                        )
                    }
                    .toList(),
            ),
            nowMs,
        )
    }

    /** Invalidates historical message contents while allowing a later edit with the same ID. */
    suspend fun invalidateSourceVersions(
        scopeIds: Set<String>,
        conversationId: String,
        sourceVersions: Set<MemorySourceVersion>,
        nowMs: Long,
    ): Int {
        val normalizedVersions = sourceVersions.asSequence()
            .map { version ->
                MemorySourceVersion(
                    messageId = version.messageId.trim(),
                    consumedTextDigest = version.consumedTextDigest.trim().lowercase(),
                )
            }
            .filter { version ->
                version.messageId.isNotEmpty() &&
                    version.consumedTextDigest.length == 64 &&
                    version.consumedTextDigest.all { it in '0'..'9' || it in 'a'..'f' }
            }
            .toSet()
        if (normalizedVersions.isEmpty()) return 0
        return mutationCoordinator.invalidateSources(
            MemorySourceInvalidationBatch(
                conversationId = conversationId,
                scopes = scopeIds.asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .map { scopeId ->
                        MemoryScopeSourceInvalidation(
                            scopeId = scopeId,
                            removedSourceVersions = normalizedVersions,
                        )
                    }
                    .toList(),
            ),
            nowMs,
        )
    }

    suspend fun runRetention(): Int = mutationCoordinator.runRetention()

    suspend fun queryDetailed(
        assistantId: kotlin.uuid.Uuid?,
        query: String,
        includeGlobal: Boolean,
        limit: Int = DEFAULT_MEMORY_TOP_K,
        tags: Set<String> = emptySet(),
        kind: MemoryKind? = null,
        includeArchived: Boolean = false,
        frozenNowMs: Long = System.currentTimeMillis(),
    ): List<MemoryQueryRecord> {
        if (includeArchived) {
            val scopeId = if (includeGlobal) GLOBAL_MEMORY_ID else assistantId?.toString()
                ?: return emptyList()
            return memoryDAO.searchIncludingArchived(
                scopeId = scopeId,
                query = query.trim(),
                limit = (limit.coerceIn(1, 20) * 3).coerceAtMost(64),
            ).mapNotNull { entity ->
                val entityTags = runCatching {
                    JsonInstant.decodeFromString<List<String>>(entity.tagsJson)
                }.getOrDefault(emptyList())
                if (kind != null && entity.memoryKind != kind.name) return@mapNotNull null
                if (tags.isNotEmpty() && entityTags.none { it in tags }) return@mapNotNull null
                MemoryQueryRecord(
                    id = entity.id,
                    title = entity.title,
                    content = entity.content,
                    kind = runCatching { MemoryKind.valueOf(entity.memoryKind) }
                        .getOrDefault(MemoryKind.OTHER),
                    tags = entityTags,
                    sourceType = entity.sourceType,
                    updatedAtMs = entity.updatedAtMs,
                    importance = entity.importance,
                score = 0.0,
                matchedTerms = memoryQueryTerms(query),
                reason = "including_archived_lexical_match",
                originAssistantId = entity.originAssistantId,
                revision = entity.revision,
                )
            }.take(limit.coerceIn(1, 20))
        }
        val matches = queryRelevant(
            assistantId = assistantId,
            query = query,
            includeGlobal = includeGlobal,
            limit = (limit.coerceIn(1, 20) * 3).coerceAtMost(64),
            maxChars = 20_000,
            frozenNowMs = frozenNowMs,
        )
        return matches.mapNotNull { match ->
            val expectedScopeId = if (includeGlobal) GLOBAL_MEMORY_ID else assistantId?.toString()
                ?: return@mapNotNull null
            val entity = memoryDAO.getActiveConfirmedMemoryById(
                id = match.memory.id,
                scopeId = expectedScopeId,
                nowMs = frozenNowMs,
            )
                ?: return@mapNotNull null
            val entityTags = runCatching {
                JsonInstant.decodeFromString<List<String>>(entity.tagsJson)
            }.getOrDefault(emptyList())
            if (kind != null && entity.memoryKind != kind.name) return@mapNotNull null
            if (tags.isNotEmpty() && entityTags.none { it in tags }) return@mapNotNull null
            MemoryQueryRecord(
                id = entity.id,
                title = entity.title,
                content = entity.content,
                kind = runCatching { MemoryKind.valueOf(entity.memoryKind) }.getOrDefault(MemoryKind.OTHER),
                tags = entityTags,
                sourceType = entity.sourceType,
                updatedAtMs = entity.updatedAtMs,
                importance = entity.importance,
                score = match.score,
                matchedTerms = match.matchedTerms,
                reason = match.reason,
                originAssistantId = entity.originAssistantId,
                revision = entity.revision,
            )
        }.take(limit.coerceIn(1, 20))
    }

    private suspend fun MemoryMutationResult.toAssistantMemory(
        scopeId: String,
        fallbackId: Int? = null,
    ): AssistantMemory {
        val id = when (this) {
            is MemoryMutationResult.Applied -> memoryId
            MemoryMutationResult.NotFound -> error("Memory record #${fallbackId ?: "?"} not found")
            MemoryMutationResult.Conflict -> error("Memory record changed or is duplicated")
            is MemoryMutationResult.Rejected -> error("Memory mutation rejected: $code")
        }
        val memory = memoryDAO.getMemoryById(id, scopeId) ?: error("Memory record #$id not found")
        return memory.toAssistantMemory()
    }
}

data class MemoryRelationReviewRecord(
    val relationCandidateId: String,
    val relationType: String,
    val description: String,
    val source: MemoryRelationReviewEndpoint,
    val target: MemoryRelationReviewEndpoint,
    val evidenceCount: Int,
    val status: String,
    val createdAtMs: Long,
)

data class MemoryRelationReviewEndpoint(
    val memoryId: Int?,
    val candidateId: String?,
    val expectedRevision: Int?,
)

internal fun pendingRelationReviewRecords(
    rows: List<MemoryRelationCandidateEntity>,
    expectedScopeId: String,
    limit: Int,
): List<MemoryRelationReviewRecord> = rows.asSequence()
    .filter { row -> row.scopeId == expectedScopeId && row.status == PENDING_RELATION_STATUS }
    .filter { row -> OWNER_IDENTIFIER_PATTERN.matches(row.id) }
    .take(limit.coerceIn(1, MAX_RELATION_REVIEW_LIMIT))
    .map { row ->
        MemoryRelationReviewRecord(
            relationCandidateId = row.id.safeOwnerIdentifier(),
            relationType = row.relationType.takeIf { RELATION_TYPE_PATTERN.matches(it) }
                ?: UNKNOWN_RELATION_TYPE,
            description = row.description.toOwnerReviewText(MAX_RELATION_REVIEW_DESCRIPTION_CHARS),
            source = MemoryRelationReviewEndpoint(
                memoryId = row.sourceMemoryId?.takeIf { it > 0 },
                candidateId = row.sourceCandidateId?.safeOwnerIdentifier()?.takeIf(String::isNotEmpty),
                expectedRevision = row.sourceExpectedRevision?.takeIf { it > 0 },
            ),
            target = MemoryRelationReviewEndpoint(
                memoryId = row.targetMemoryId?.takeIf { it > 0 },
                candidateId = row.targetCandidateId?.safeOwnerIdentifier()?.takeIf(String::isNotEmpty),
                expectedRevision = row.targetExpectedRevision?.takeIf { it > 0 },
            ),
            evidenceCount = runCatching {
                JsonInstant.decodeFromString<List<String>>(row.evidenceMessageIdsJson)
                    .asSequence()
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(MAX_RELATION_REVIEW_EVIDENCE_COUNT)
                    .count()
            }.getOrDefault(0),
            status = PENDING_RELATION_STATUS,
            createdAtMs = row.createdAtMs,
        )
    }
    .toList()

private fun String.safeOwnerIdentifier(): String = takeIf { OWNER_IDENTIFIER_PATTERN.matches(it) }.orEmpty()

private fun String.toOwnerReviewText(maxChars: Int): String = asSequence()
    .filterNot(Char::isISOControl)
    .take(maxChars)
    .joinToString(separator = "")

private const val DEFAULT_RELATION_REVIEW_LIMIT = 20
internal const val MAX_RELATION_REVIEW_LIMIT = 50
private const val MAX_RELATION_REVIEW_DESCRIPTION_CHARS = 480
private const val MAX_RELATION_REVIEW_EVIDENCE_COUNT = 64
private const val PENDING_RELATION_STATUS = "PENDING"
private const val UNKNOWN_RELATION_TYPE = "UNKNOWN"
private val OWNER_IDENTIFIER_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
private val RELATION_TYPE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
private fun isValidRelationReviewScope(scopeId: String): Boolean =
    scopeId == MemoryRepository.GLOBAL_MEMORY_ID ||
        runCatching { kotlin.uuid.Uuid.parse(scopeId).toString() == scopeId }.getOrDefault(false)

private fun MemoryEntity.toAssistantMemory(): AssistantMemory = AssistantMemory(
    id = id,
    content = content,
    title = title,
    kind = runCatching { MemoryKind.valueOf(memoryKind) }.getOrDefault(MemoryKind.OTHER),
    approvalSource = runCatching { MemoryApprovalSource.valueOf(approvalSource) }
        .getOrDefault(MemoryApprovalSource.LEGACY),
    scopeId = assistantId,
    revision = revision,
)

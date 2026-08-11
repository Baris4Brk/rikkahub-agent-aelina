package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryMutationCommand
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationResult
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryExpiryUpdate
import me.rerere.rikkahub.memory.MemoryQueryRecord
import me.rerere.rikkahub.memory.MemoryWriteInput
import me.rerere.rikkahub.utils.JsonInstant

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val retriever: MemoryRetriever,
    private val mutationCoordinator: MemoryMutationCoordinator,
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

    suspend fun deleteMemory(scopeId: String, id: Int, expectedRevision: Int? = null) {
        when (mutationCoordinator.mutate(
            MemoryMutationCommand.Archive(
                memoryId = id,
                expectedScopeId = scopeId,
                expectedRevision = expectedRevision,
                approvalSource = MemoryApprovalSource.MEMORY_TOOL,
            ),
        )) {
            is MemoryMutationResult.Applied -> Unit
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

    suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
    ): Int = mutationCoordinator.invalidateSourceMessages(scopeId, conversationId, messageIds)

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

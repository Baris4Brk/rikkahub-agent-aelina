package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val retriever: MemoryRetriever,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun queryRelevant(
        assistantId: kotlin.uuid.Uuid?,
        query: String,
        includeGlobal: Boolean,
        limit: Int = DEFAULT_MEMORY_TOP_K,
        maxChars: Int = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
    ): List<MemoryMatch> = retriever.queryRelevant(
        assistantId = assistantId,
        query = query,
        includeGlobal = includeGlobal,
        limit = limit,
        maxChars = maxChars,
    )

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content,
            updatedAtMs = System.currentTimeMillis(),
        )
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        val memory = AssistantMemory(
            id = 0,
            content = content,
        )
        val newMemory = memory.copy(
            id = memoryDAO.insertMemory(
                MemoryEntity(
                    assistantId = assistantId,
                    content = memory.content,
                    updatedAtMs = System.currentTimeMillis(),
                )
            ).toInt()
        )
        return newMemory
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
    }
}

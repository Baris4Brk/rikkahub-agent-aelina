package me.rerere.rikkahub.data.repository

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.memory.MemoryMutationCommand
import me.rerere.rikkahub.memory.MemoryMutationCoordinator
import me.rerere.rikkahub.memory.MemoryMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryArchiveRevisionTest {
    @Test
    fun `delete returns revision applied by the archive transaction`() = runBlocking {
        var observedCommand: MemoryMutationCommand? = null
        val coordinator = object : MemoryMutationCoordinator {
            override suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult {
                observedCommand = command
                return MemoryMutationResult.Applied(memoryId = 41, revision = 8)
            }

            override suspend fun invalidateSourceConversation(scopeId: String, conversationId: String) = 0
            override suspend fun invalidateSourceConversation(
                scopeId: String,
                conversationId: String,
                nowMs: Long,
            ) = 0

            override suspend fun invalidateSourceMessages(
                scopeId: String,
                conversationId: String,
                messageIds: Set<String>,
            ) = 0

            override suspend fun invalidateSourceMessages(
                scopeId: String,
                conversationId: String,
                messageIds: Set<String>,
                nowMs: Long,
            ) = 0

            override suspend fun runRetention() = 0
            override suspend fun purgeScope(scopeId: String) = 0
        }
        val unusedDao = Proxy.newProxyInstance(
            MemoryDAO::class.java.classLoader,
            arrayOf(MemoryDAO::class.java),
        ) { _, method, _ -> error("Unexpected DAO call: ${method.name}") } as MemoryDAO
        val unusedIndex = object : MemorySearchIndex {
            override suspend fun search(
                scopeId: String,
                query: String,
                limit: Int,
            ) = emptyList<MemorySearchCandidate>()
        }
        val repository = MemoryRepository(
            memoryDAO = unusedDao,
            retriever = MemoryRetriever(unusedIndex),
            mutationCoordinator = coordinator,
        )

        val appliedRevision = repository.deleteMemory(
            scopeId = "assistant-scope",
            id = 41,
            expectedRevision = 7,
        )

        assertEquals(8, appliedRevision)
        val archive = observedCommand as MemoryMutationCommand.Archive
        assertEquals("assistant-scope", archive.expectedScopeId)
        assertEquals(7, archive.expectedRevision)
        assertTrue(archive.memoryId == 41)
    }
}

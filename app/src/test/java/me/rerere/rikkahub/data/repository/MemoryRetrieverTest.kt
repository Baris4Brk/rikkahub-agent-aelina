package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryRetrieverTest {
    @Test
    fun `empty queries skip the index and index failures degrade to no memory`() = runBlocking {
        var calls = 0
        val retriever = MemoryRetriever(
            index = MemorySearchIndex { _, _, _ ->
                calls++
                error("fts unavailable")
            },
        )

        assertTrue(
            retriever.queryRelevant(Uuid.random(), "   ", includeGlobal = false).isEmpty(),
        )
        assertEquals(0, calls)
        assertTrue(
            retriever.queryRelevant(Uuid.random(), "coffee", includeGlobal = false).isEmpty(),
        )
        assertEquals(1, calls)
    }

    @Test
    fun `ranking removes duplicate content and only admits whole records within budget`() =
        runBlocking {
            val assistantId = Uuid.random()
            val candidates = listOf(
                MemorySearchCandidate(1, null, "same", 0L, 0.1f, -1.0),
                MemorySearchCandidate(2, "coffee", "same", 10_000L, 1f, -2.0),
                MemorySearchCandidate(3, null, "abcdefghijklmnop", 0L, 0.5f, -0.5),
                MemorySearchCandidate(4, null, "ignored", 0L, 0.5f, -0.1),
            )
            val retriever = MemoryRetriever(
                index = MemorySearchIndex { _, _, _ -> candidates },
                nowMs = { 10_000L },
            )

            val matches = retriever.queryRelevant(
                assistantId = assistantId,
                query = "coffee",
                includeGlobal = false,
                limit = 2,
                maxChars = 12,
            )

            assertEquals(listOf(2, 4), matches.map { it.memory.id })
            assertEquals(11, matches.sumOf { it.memory.content.length })
            assertEquals("ignored", matches.last().memory.content)
        }

    @Test
    fun `explicit retrieval forwards one frozen clock and returns a fail closed trace`() =
        runBlocking {
            val assistantId = Uuid.random()
            val requests = mutableListOf<MemoryIndexSearchRequest>()
            val index = object : MemorySearchIndex {
                override suspend fun search(
                    scopeId: String,
                    query: String,
                    limit: Int,
                ): List<MemorySearchCandidate> = error("legacy search must not be used")

                override suspend fun search(
                    request: MemoryIndexSearchRequest,
                ): List<MemorySearchCandidate> {
                    requests += request
                    error("index unavailable")
                }
            }
            val retriever = MemoryRetriever(index = index, nanoTime = { 0L })

            val result = retriever.retrieve(
                MemoryRetrievalRequest(
                    assistantId = assistantId,
                    query = "咖啡",
                    includeGlobal = false,
                    frozenNowMs = 123_456L,
                ),
            )

            assertTrue(result.matches.isEmpty())
            assertEquals(MemoryRetrievalStatus.INDEX_UNAVAILABLE, result.trace.status)
            assertEquals(MemoryRetrievalScopeKind.ASSISTANT, result.trace.scopeKind)
            assertEquals(123_456L, requests.single().frozenNowMs)
            assertEquals(assistantId.toString(), requests.single().scopeId)
        }

    @Test
    fun `query construction is bounded and never splits a surrogate pair`() = runBlocking {
        val assistantId = Uuid.random()
        val requests = mutableListOf<MemoryIndexSearchRequest>()
        val index = object : MemorySearchIndex {
            override suspend fun search(
                scopeId: String,
                query: String,
                limit: Int,
            ): List<MemorySearchCandidate> = emptyList()

            override suspend fun search(
                request: MemoryIndexSearchRequest,
            ): List<MemorySearchCandidate> {
                requests += request
                return emptyList()
            }
        }
        val query = "汉".repeat(MAX_MEMORY_QUERY_CHARS - 1) + "😀" + "尾".repeat(10_000)

        val result = MemoryRetriever(index, nanoTime = { 0L }).retrieve(
            MemoryRetrievalRequest(
                assistantId = assistantId,
                query = query,
                includeGlobal = false,
                frozenNowMs = 1L,
            ),
        )

        val effectiveQuery = requests.single().query
        assertTrue(result.trace.queryTruncated)
        assertTrue(effectiveQuery.length <= MAX_MEMORY_QUERY_CHARS)
        assertFalse(effectiveQuery.last().isHighSurrogate())
        assertTrue(result.trace.queryTermCount <= MAX_MEMORY_QUERY_TERMS)
    }

    @Test
    fun `every raw candidate receives one deterministic terminal decision`() = runBlocking {
        val assistantId = Uuid.random()
        val candidates = listOf(
            MemorySearchCandidate(1, "coffee", "selected", 1L, 1f, -4.0),
            MemorySearchCandidate(2, null, "selected", 1L, 1f, -3.0),
            MemorySearchCandidate(3, null, "too-long-for-budget", 1L, 1f, -2.0),
            MemorySearchCandidate(4, null, "excluded", 1L, 1f, -1.0),
        )
        val retriever = MemoryRetriever(
            index = MemorySearchIndex { _, _, _ -> candidates },
            nanoTime = { 0L },
        )

        val result = retriever.retrieve(
            MemoryRetrievalRequest(
                assistantId = assistantId,
                query = "coffee",
                includeGlobal = false,
                limit = 2,
                maxChars = 10,
                excludeMemoryIds = setOf(4),
                frozenNowMs = 1L,
            ),
        )

        assertEquals(candidates.size, result.trace.candidates.size)
        assertEquals(candidates.indices.map { it + 1 }, result.trace.candidates.map { it.candidateOrdinal })
        assertEquals(
            listOf(
                MemoryRetrievalDecision.SELECTED,
                MemoryRetrievalDecision.DUPLICATE_CONTENT,
                MemoryRetrievalDecision.BUDGET_EXCEEDED,
                MemoryRetrievalDecision.EXCLUDED_ID,
            ),
            result.trace.candidates.map { it.decision },
        )
        assertEquals(listOf(1), result.matches.map { it.memory.id })
    }

    @Test
    fun `assistant and global scopes stay exclusive while Chinese and English matches are explained`() =
        runBlocking {
            val assistantId = Uuid.random()
            val index = FakeMemorySearchIndex(
                byScope = mapOf(
                    assistantId.toString() to listOf(
                        MemorySearchCandidate(
                            id = 1,
                            title = "咖啡偏好",
                            content = "用户喜欢手冲咖啡和浅烘豆",
                            updatedAtMs = 1_000L,
                            importance = 0.8f,
                            ftsRank = -2.0,
                        ),
                    ),
                    MemoryRepository.GLOBAL_MEMORY_ID to listOf(
                        MemorySearchCandidate(
                            id = 2,
                            title = "Coffee equipment",
                            content = "Use the steel hand grinder for travel.",
                            updatedAtMs = 1_000L,
                            importance = 0.5f,
                            ftsRank = -1.0,
                        ),
                    ),
                ),
            )
            val retriever = MemoryRetriever(index, nowMs = { 1_000L })

            val assistantMatches = retriever.queryRelevant(
                assistantId = assistantId,
                query = "我喜欢什么咖啡？",
                includeGlobal = false,
            )
            val globalMatches = retriever.queryRelevant(
                assistantId = assistantId,
                query = "coffee grinder",
                includeGlobal = true,
            )

            assertEquals(listOf(1), assistantMatches.map { it.memory.id })
            assertTrue(assistantMatches.single().matchedTerms.contains("咖啡"))
            assertEquals(listOf(2), globalMatches.map { it.memory.id })
            assertTrue(globalMatches.single().matchedTerms.contains("coffee"))
            assertEquals(
                listOf(assistantId.toString(), MemoryRepository.GLOBAL_MEMORY_ID),
                index.scopes,
            )
        }

    @Test
    fun `standing memories can be excluded without consuming contextual top k`() = runBlocking {
        val assistantId = Uuid.random()
        val candidates = (1..12).map { id ->
            MemorySearchCandidate(
                id = id,
                title = "coffee $id",
                content = "coffee memory $id",
                updatedAtMs = id.toLong(),
                importance = 0.5f,
                ftsRank = -id.toDouble(),
            )
        }
        val retriever = MemoryRetriever(
            index = MemorySearchIndex { _, _, limit -> candidates.take(limit) },
            nowMs = { 20L },
        )

        val matches = retriever.queryRelevant(
            assistantId = assistantId,
            query = "coffee",
            includeGlobal = false,
            limit = 4,
            excludeMemoryIds = (9..12).toSet(),
        )

        assertEquals(4, matches.size)
        assertTrue(matches.none { it.memory.id in 9..12 })
    }

    private class FakeMemorySearchIndex(
        private val byScope: Map<String, List<MemorySearchCandidate>>,
    ) : MemorySearchIndex {
        val scopes = mutableListOf<String>()

        override suspend fun search(
            scopeId: String,
            query: String,
            limit: Int,
        ): List<MemorySearchCandidate> {
            scopes += scopeId
            return byScope[scopeId].orEmpty().take(limit)
        }
    }
}

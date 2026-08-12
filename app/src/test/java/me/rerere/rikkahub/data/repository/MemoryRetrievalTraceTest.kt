package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class MemoryRetrievalTraceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `persistable trace contains policy outcomes but no query memory or scope identifiers`() =
        runBlocking {
            val secretQuery = "private-query-723184"
            val secretContent = "private-memory-884295"
            val assistantId = Uuid.random()
            val retriever = MemoryRetriever(
                index = MemorySearchIndex { _, _, _ ->
                    listOf(
                        MemorySearchCandidate(
                            id = 918_273,
                            title = "private-title-551122",
                            content = secretContent,
                            updatedAtMs = 10L,
                            importance = 0.5f,
                            ftsRank = -1.0,
                        ),
                    )
                },
                nanoTime = { 0L },
            )

            val result = retriever.retrieve(
                MemoryRetrievalRequest(
                    assistantId = assistantId,
                    query = secretQuery,
                    includeGlobal = false,
                    frozenNowMs = 10L,
                ),
            )
            val encoded = Json.encodeToString(result.trace)

            assertFalse(encoded.contains(secretQuery))
            assertFalse(encoded.contains(secretContent))
            assertFalse(encoded.contains("private-title-551122"))
            assertFalse(encoded.contains(assistantId.toString()))
            assertFalse(encoded.contains("918273"))
        }

    @Test
    fun `trace decisions are deterministic when the timing source is frozen`() = runBlocking {
        val assistantId = Uuid.random()
        val candidates = listOf(
            MemorySearchCandidate(5, "咖啡", "喜欢浅烘咖啡", 100L, 0.8f, -2.0),
            MemorySearchCandidate(6, null, "旅行使用手摇磨豆机", 90L, 0.5f, -1.0),
        )
        val retriever = MemoryRetriever(
            index = MemorySearchIndex { _, _, _ -> candidates },
            nanoTime = { 42L },
        )
        val request = MemoryRetrievalRequest(
            assistantId = assistantId,
            query = "咖啡磨豆机",
            includeGlobal = false,
            frozenNowMs = 100L,
        )

        val traces = List(100) { retriever.retrieve(request).trace }

        traces.drop(1).forEach { trace -> assertEquals(traces.first(), trace) }
    }

    @Test
    fun `malformed numeric projection values cannot poison trace serialization`() = runBlocking {
        val retriever = MemoryRetriever(
            index = MemorySearchIndex { _, _, _ ->
                listOf(
                    MemorySearchCandidate(
                        id = 1,
                        title = "numeric",
                        content = "numeric memory",
                        updatedAtMs = Long.MIN_VALUE,
                        importance = Float.NaN,
                        ftsRank = Double.NEGATIVE_INFINITY,
                    ),
                )
            },
            nanoTime = { 0L },
        )

        val result = retriever.retrieve(
            MemoryRetrievalRequest(
                assistantId = Uuid.random(),
                query = "numeric",
                includeGlobal = false,
                frozenNowMs = Long.MAX_VALUE,
            ),
        )

        assertTrue(result.matches.single().score.isFinite())
        val encoded = Json.encodeToString(result.trace)
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun `diagnostics store is atomic bounded and cannot persist retrieval payloads`() = runBlocking {
        val secretQuery = "never-persist-query-44017"
        val secretMemory = "never-persist-memory-55028"
        val trace = MemoryRetriever(
            index = MemorySearchIndex { _, _, _ ->
                listOf(MemorySearchCandidate(772_299, null, secretMemory, 1L, 0.5f, -1.0))
            },
            nanoTime = { 0L },
        ).retrieve(
            MemoryRetrievalRequest(
                assistantId = Uuid.random(),
                query = secretQuery,
                includeGlobal = false,
                frozenNowMs = 1L,
            ),
        ).trace
        val store = MemoryRetrievalDiagnosticsStore(
            filesDir = temporaryFolder.root,
            maxEntries = 100,
            nowMs = { 123L },
        )

        val handles = List(40) { store.record(trace) }

        assertEquals(MAX_MEMORY_RETRIEVAL_DIAGNOSTIC_ENTRIES, store.entries.value.size)
        assertEquals(40, handles.distinct().size)
        assertTrue(handles.all(::isValidMemoryRetrievalTraceHandle))
        assertTrue(handles.all { handle -> handle.startsWith("mrt_") })
        val invalidUuid = Uuid.random().toString()
        val invalidEntryFailure = runCatching {
            store.entries.value.first().copy(opaqueTraceId = invalidUuid)
        }.exceptionOrNull()
        assertTrue(invalidEntryFailure is IllegalArgumentException)
        val destination = MemoryRetrievalDiagnosticsStore.outputFile(temporaryFolder.root)
        val payload = destination.readText()
        assertTrue(destination.isFile)
        assertTrue(payload.contains("\"schema_version\": 2"))
        assertTrue(payload.contains("\"max_entries\": 32"))
        assertFalse(payload.contains(secretQuery))
        assertFalse(payload.contains(secretMemory))
        assertFalse(payload.contains("772299"))
        assertFalse(payload.contains(invalidUuid))
        assertTrue(payload.contains("mrt_"))
        assertTrue(
            destination.parentFile.listFiles().orEmpty().none { file -> file.extension == "tmp" },
        )
        val reloaded = MemoryRetrievalDiagnosticsStore(
            filesDir = temporaryFolder.root,
            maxEntries = 32,
        )
        assertEquals(32, reloaded.entries.value.size)
    }
}

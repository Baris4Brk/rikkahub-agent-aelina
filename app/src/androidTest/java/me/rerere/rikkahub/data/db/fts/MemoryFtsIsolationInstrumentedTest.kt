package me.rerere.rikkahub.data.db.fts

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.repository.MAX_MEMORY_QUERY_CHARS
import me.rerere.rikkahub.data.repository.MAX_MEMORY_QUERY_TERMS
import me.rerere.rikkahub.data.repository.MemoryIndexSearchRequest
import me.rerere.rikkahub.data.repository.MemoryRetrievalRequest
import me.rerere.rikkahub.data.repository.MemoryRetrievalStatus
import me.rerere.rikkahub.data.repository.MemoryRetriever
import me.rerere.rikkahub.data.repository.MemorySearchCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class MemoryFtsIsolationInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var memoryFts: MemoryFtsManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            // Use the production SQLite runtime: framework SQLite does not provide the app's
            // FTS5 simple tokenizer or jieba_query function on every Android device.
            .openHelperFactory(createAppSQLiteOpenHelperFactory(context))
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictionary = SimpleDictManager.extractDict(context)
                    db.query("SELECT jieba_dict(?)", arrayOf(dictionary.absolutePath)).use { cursor ->
                        check(cursor.moveToFirst()) { "jieba_dictionary_result_missing" }
                        check(
                            cursor.getString(0)?.trimEnd('/') ==
                                dictionary.absolutePath.trimEnd('/'),
                        ) { "jieba_dictionary_initialization_failed" }
                    }
                    ensureMemoryFtsSchema(db)
                }
            })
            .build()
        // Force Room/open callbacks before any seed write so every row is maintained by the real
        // memory_fts triggers rather than by a test-only backfill.
        database.openHelper.writableDatabase
        memoryFts = MemoryFtsManager(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun searchUsesAuthoritativeScopeLifecycleTruthExpiryAndRevision() = runBlocking {
        val assistantActive = insertMemory(
            scopeId = ASSISTANT_A,
            title = "Assistant A authoritative title",
            revision = 7,
        )
        val assistantFutureExpiry = insertMemory(
            scopeId = ASSISTANT_A,
            title = "Assistant A future expiry",
            expiresAtMs = FROZEN_NOW_MS + 1,
        )
        val assistantB = insertMemory(scopeId = ASSISTANT_B, title = "Assistant B")
        val global = insertMemory(scopeId = GLOBAL_SCOPE, title = "Global")
        insertMemory(
            scopeId = ASSISTANT_A,
            title = "Archived",
            lifecycleStatus = "ARCHIVED",
        )
        insertMemory(
            scopeId = ASSISTANT_A,
            title = "Expired at frozen boundary",
            expiresAtMs = FROZEN_NOW_MS,
        )
        insertMemory(
            scopeId = ASSISTANT_A,
            title = "Disputed",
            truthStatus = "DISPUTED",
        )
        insertMemory(
            scopeId = ASSISTANT_A,
            title = "Superseded",
            truthStatus = "SUPERSEDED",
        )

        val assistantAHits = search(ASSISTANT_A, SHARED_TERM)
        assertEquals(
            setOf(assistantActive.id, assistantFutureExpiry.id),
            assistantAHits.mapTo(mutableSetOf(), MemorySearchCandidate::id),
        )
        assertEquals(setOf(assistantB.id), search(ASSISTANT_B, SHARED_TERM).ids())
        assertEquals(setOf(global.id), search(GLOBAL_SCOPE, SHARED_TERM).ids())

        val authoritative = assistantAHits.single { it.id == assistantActive.id }
        assertEquals("Assistant A authoritative title", authoritative.title)
        assertEquals(7, authoritative.revision)
    }

    @Test
    fun insertUpdateAndDeleteTriggersKeepTheRealFtsProjectionSynchronized() = runBlocking {
        val inserted = insertMemory(
            scopeId = ASSISTANT_A,
            title = "Inserted title",
            content = "$INSERT_TERM original body",
            revision = 2,
        )
        assertEquals(setOf(inserted.id), search(ASSISTANT_A, INSERT_TERM).ids())

        val updated = inserted.copy(
            title = "Updated authoritative title",
            content = "$UPDATE_TERM replacement body",
            revision = 9,
            updatedAtMs = FROZEN_NOW_MS + 10,
        )
        assertEquals(1, database.memoryDao().updateMemory(updated))
        assertTrue(search(ASSISTANT_A, INSERT_TERM).isEmpty())
        val updatedHit = search(ASSISTANT_A, UPDATE_TERM).single()
        assertEquals("Updated authoritative title", updatedHit.title)
        assertEquals(9, updatedHit.revision)

        assertEquals(1, database.memoryDao().deleteMemory(updated.id, ASSISTANT_A))
        assertTrue(search(ASSISTANT_A, UPDATE_TERM).isEmpty())
    }

    @Test
    fun chineseAndHostileQueriesUseTheBoundedRetrieverPayloadAgainstRealSqlite() = runBlocking {
        val assistantA = insertMemory(
            scopeId = ASSISTANT_A,
            title = "咖啡偏好",
            content = "喜欢手冲咖啡和无糖拿铁",
        )
        insertMemory(scopeId = ASSISTANT_B, title = "咖啡偏好", content = "也喜欢咖啡")
        insertMemory(scopeId = GLOBAL_SCOPE, title = "咖啡知识", content = "全局咖啡记录")
        val retriever = MemoryRetriever(index = memoryFts, nanoTime = { 0L })

        val chinese = retriever.retrieve(
            MemoryRetrievalRequest(
                assistantId = Uuid.parse(ASSISTANT_A),
                query = "咖啡",
                includeGlobal = false,
                frozenNowMs = FROZEN_NOW_MS,
            ),
        )
        assertEquals(MemoryRetrievalStatus.SUCCESS, chinese.trace.status)
        assertEquals(setOf(assistantA.id), chinese.matches.mapTo(mutableSetOf()) { it.memory.id })

        val hostile = buildString(MAX_MEMORY_QUERY_CHARS * 3) {
            append("\" OR 1=1 -- \u0000 咖啡 ")
            repeat(MAX_MEMORY_QUERY_CHARS * 2) { index ->
                append((0x4E00 + index % 512).toChar())
            }
        }
        val bounded = retriever.retrieve(
            MemoryRetrievalRequest(
                assistantId = Uuid.parse(ASSISTANT_A),
                query = hostile,
                includeGlobal = false,
                frozenNowMs = FROZEN_NOW_MS,
            ),
        )
        assertNotEquals(MemoryRetrievalStatus.INDEX_UNAVAILABLE, bounded.trace.status)
        assertTrue(bounded.trace.queryTruncated)
        assertTrue(bounded.trace.querySanitized)
        assertTrue(bounded.trace.effectiveQueryChars <= MAX_MEMORY_QUERY_CHARS)
        assertEquals(MAX_MEMORY_QUERY_TERMS, bounded.trace.queryTermCount)
        assertFalse(bounded.matches.any { it.memory.id != assistantA.id })
    }

    private suspend fun insertMemory(
        scopeId: String,
        title: String,
        content: String = "$SHARED_TERM searchable body",
        revision: Int = 1,
        lifecycleStatus: String = "ACTIVE",
        truthStatus: String = "CONFIRMED",
        expiresAtMs: Long? = null,
    ): MemoryEntity {
        val memory = MemoryEntity(
            assistantId = scopeId,
            title = title,
            content = content,
            revision = revision,
            lifecycleStatus = lifecycleStatus,
            truthStatus = truthStatus,
            expiresAtMs = expiresAtMs,
            createdAtMs = 1L,
            updatedAtMs = 2L,
        )
        val id = database.memoryDao().insertMemory(memory).toInt()
        return memory.copy(id = id)
    }

    private suspend fun search(
        scopeId: String,
        query: String,
    ): List<MemorySearchCandidate> = memoryFts.search(
        MemoryIndexSearchRequest(
            scopeId = scopeId,
            query = query,
            limit = 64,
            frozenNowMs = FROZEN_NOW_MS,
        ),
    )

    private fun List<MemorySearchCandidate>.ids(): Set<Int> =
        mapTo(mutableSetOf(), MemorySearchCandidate::id)

    private companion object {
        const val ASSISTANT_A = "00000000-0000-0000-0000-00000000000a"
        const val ASSISTANT_B = "00000000-0000-0000-0000-00000000000b"
        const val GLOBAL_SCOPE = "__global__"
        const val FROZEN_NOW_MS = 1_000_000L
        const val SHARED_TERM = "sharedscopekeyword"
        const val INSERT_TERM = "inserttriggerkeyword"
        const val UPDATE_TERM = "updatetriggerkeyword"
    }
}

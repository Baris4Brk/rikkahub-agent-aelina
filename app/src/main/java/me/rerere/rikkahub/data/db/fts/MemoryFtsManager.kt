package me.rerere.rikkahub.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.MemoryIndexSearchRequest
import me.rerere.rikkahub.data.repository.MemorySearchCandidate
import me.rerere.rikkahub.data.repository.MemorySearchIndex
import me.rerere.rikkahub.data.repository.MAX_MEMORY_INDEX_CANDIDATES

const val MEMORY_FTS_SIMPLE_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
        title,
        content,
        outcome,
        tags_search,
        memory_id UNINDEXED,
        assistant_id UNINDEXED,
        updated_at_ms UNINDEXED,
        importance UNINDEXED,
        lifecycle_status UNINDEXED,
        expires_at_ms UNINDEXED,
        tokenize = 'simple'
    )
"""

/** Portable migration form; the on-open reconciler replaces it with the simple tokenizer. */
const val MEMORY_FTS_PORTABLE_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
        title,
        content,
        outcome,
        tags_search,
        memory_id UNINDEXED,
        assistant_id UNINDEXED,
        updated_at_ms UNINDEXED,
        importance UNINDEXED,
        lifecycle_status UNINDEXED,
        expires_at_ms UNINDEXED,
        tokenize = 'unicode61'
    )
"""

/**
 * The version-30 projection predates Memory V2 metadata. Keep this separate from the current
 * projection so a 29 -> 30 migration never asks SQLite for fields introduced by 30 -> 31.
 */
const val MEMORY_FTS_V30_PORTABLE_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
        title,
        content,
        memory_id UNINDEXED,
        assistant_id UNINDEXED,
        updated_at_ms UNINDEXED,
        importance UNINDEXED,
        tokenize = 'unicode61'
    )
"""

val MEMORY_FTS_V30_TRIGGER_SQL: List<String> = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_ai AFTER INSERT ON MemoryEntity BEGIN
        INSERT INTO memory_fts(
            rowid, title, content, memory_id, assistant_id, updated_at_ms, importance
        ) VALUES (
            new.id, new.title, new.content, new.id, new.assistant_id, new.updated_at_ms,
            new.importance
        );
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_au AFTER UPDATE ON MemoryEntity BEGIN
        DELETE FROM memory_fts WHERE rowid = old.id;
        INSERT INTO memory_fts(
            rowid, title, content, memory_id, assistant_id, updated_at_ms, importance
        ) VALUES (
            new.id, new.title, new.content, new.id, new.assistant_id, new.updated_at_ms,
            new.importance
        );
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_ad AFTER DELETE ON MemoryEntity BEGIN
        DELETE FROM memory_fts WHERE rowid = old.id;
    END
    """.trimIndent(),
)

const val MEMORY_FTS_V30_BACKFILL_SQL = """
    INSERT INTO memory_fts(
        rowid, title, content, memory_id, assistant_id, updated_at_ms, importance
    )
    SELECT id, title, content, id, assistant_id, updated_at_ms, importance
    FROM MemoryEntity
"""

val MEMORY_FTS_TRIGGER_SQL: List<String> = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_ai AFTER INSERT ON MemoryEntity BEGIN
        INSERT INTO memory_fts(
            rowid, title, content, outcome, tags_search, memory_id, assistant_id, updated_at_ms,
            importance, lifecycle_status, expires_at_ms
        ) VALUES (
            new.id, new.title, new.content, new.outcome, new.tags_search, new.id, new.assistant_id,
            new.updated_at_ms, new.importance, new.lifecycle_status, new.expires_at_ms
        );
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_au
    AFTER UPDATE OF title, content, outcome, tags_search ON MemoryEntity BEGIN
        DELETE FROM memory_fts WHERE rowid = old.id;
        INSERT INTO memory_fts(
            rowid, title, content, outcome, tags_search, memory_id, assistant_id, updated_at_ms,
            importance, lifecycle_status, expires_at_ms
        ) VALUES (
            new.id, new.title, new.content, new.outcome, new.tags_search, new.id, new.assistant_id,
            new.updated_at_ms, new.importance, new.lifecycle_status, new.expires_at_ms
        );
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_ad AFTER DELETE ON MemoryEntity BEGIN
        DELETE FROM memory_fts WHERE rowid = old.id;
    END
    """.trimIndent(),
)

const val MEMORY_FTS_BACKFILL_SQL = """
    INSERT INTO memory_fts(
        rowid, title, content, outcome, tags_search, memory_id, assistant_id, updated_at_ms,
        importance, lifecycle_status, expires_at_ms
    )
    SELECT id, title, content, outcome, tags_search, id, assistant_id, updated_at_ms, importance,
           lifecycle_status, expires_at_ms
    FROM MemoryEntity
"""

/** Projection used while a 30 -> 31 -> 32 migration chain is between its two steps. */
val MEMORY_FTS_V31_PORTABLE_CREATE_SQL = MEMORY_FTS_PORTABLE_CREATE_SQL.replace("        outcome,\n", "")
val MEMORY_FTS_V31_TRIGGER_SQL = MEMORY_FTS_TRIGGER_SQL.map { sql ->
    sql.replace("content, outcome, tags_search", "content, tags_search")
        .replace("new.content, new.outcome, new.tags_search", "new.content, new.tags_search")
}
val MEMORY_FTS_V31_BACKFILL_SQL = MEMORY_FTS_BACKFILL_SQL
    .replace("content, outcome, tags_search", "content, tags_search")
    .replace("content, outcome, tags_search", "content, tags_search")

internal val MEMORY_FTS_SEARCH_SQL = """
    SELECT m.id, m.title, m.content, m.updated_at_ms, m.importance,
           bm25(memory_fts, 5.0, 1.0, 1.5, 2.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
               AS fts_rank,
           m.revision
    FROM memory_fts
    INNER JOIN MemoryEntity AS m ON m.id = memory_fts.rowid
    WHERE memory_fts MATCH jieba_query(?)
      AND m.assistant_id = ?
      AND m.lifecycle_status = 'ACTIVE'
      AND m.truth_status = 'CONFIRMED'
      AND (m.expires_at_ms IS NULL OR m.expires_at_ms > ?)
    ORDER BY fts_rank ASC, m.updated_at_ms DESC, m.id ASC
    LIMIT ?
""".trimIndent()

private val MEMORY_FTS_EXPECTED_COLUMNS = setOf(
    "title",
    "content",
    "outcome",
    "tags_search",
    "memory_id",
    "assistant_id",
    "updated_at_ms",
    "importance",
    "lifecycle_status",
    "expires_at_ms",
)

/**
 * Makes the memory projection deterministic after migration, import or an interrupted rebuild.
 * A healthy projection is verified but never rewritten on open. This matters because a DELETE +
 * backfill retokenizes every memory, adds startup latency and needlessly consumes battery.
 */
fun ensureMemoryFtsSchema(db: SupportSQLiteDatabase) {
    try {
        val schemaSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='memory_fts'",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
        val columns = readMemoryFtsColumns(db)
        if (!isCompatibleMemoryFtsSchema(schemaSql, columns)) {
            rebuildMemoryFtsProjection(db)
            return
        }

        ensureCurrentMemoryFtsTriggers(db)
        if (memoryFtsProjectionHasDrift(db)) {
            rebuildMemoryFtsRows(db)
        }
    } catch (_: Throwable) {
        // A malformed/corrupt virtual table can make even PRAGMA or the drift query fail. The
        // recovery path is intentionally deterministic and runs before Room exposes the DB.
        rebuildMemoryFtsProjection(db)
    }
}

internal fun isCompatibleMemoryFtsSchema(
    schemaSql: String,
    columns: Set<String>,
): Boolean {
    val usesSimple = Regex(
        pattern = "tokenize\\s*=\\s*['\"]simple['\"]",
        option = RegexOption.IGNORE_CASE,
    ).containsMatchIn(schemaSql)
    return usesSimple && columns == MEMORY_FTS_EXPECTED_COLUMNS
}

internal fun memoryFtsTriggerDefinitionsAreCompatible(
    definitions: Map<String, String>,
): Boolean {
    val expected = MEMORY_FTS_TRIGGER_SQL.associateBy(
        keySelector = { sql ->
            Regex("CREATE\\s+TRIGGER(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+(\\w+)", RegexOption.IGNORE_CASE)
                .find(sql)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        },
        valueTransform = ::normalizeMemoryFtsSql,
    )
    val actual = definitions.mapValues { (_, sql) -> normalizeMemoryFtsSql(sql) }
    return actual == expected
}

private fun readMemoryFtsColumns(db: SupportSQLiteDatabase): Set<String> =
    db.query("PRAGMA table_info(memory_fts)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        buildSet {
            if (nameIndex >= 0) {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
    }

private fun ensureCurrentMemoryFtsTriggers(db: SupportSQLiteDatabase) {
    val definitions = db.query(
        "SELECT name, sql FROM sqlite_master " +
            "WHERE type='trigger' AND name IN ('memory_fts_ai','memory_fts_au','memory_fts_ad')",
    ).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                put(cursor.getString(0), cursor.getString(1).orEmpty())
            }
        }
    }
    if (memoryFtsTriggerDefinitionsAreCompatible(definitions)) return
    memoryFtsTransaction(db) {
        dropMemoryFtsTriggers(db)
        MEMORY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    }
}

/**
 * Drift is limited to row identity and the four fields tokenized by FTS. Scope, lifecycle, truth,
 * expiry, ranking metadata and timestamps are read from the authoritative MemoryEntity join at
 * query time. Comparing their intentionally stale UNINDEXED snapshots here would turn an archive,
 * last-access update or expiry materialization into a full-table retokenization on next open.
 */
internal val MEMORY_FTS_DRIFT_SQL = """
    SELECT 1
    FROM MemoryEntity AS m
    LEFT JOIN memory_fts AS f ON f.rowid = m.id
    WHERE f.rowid IS NULL
       OR f.title IS NOT m.title
       OR f.content IS NOT m.content
       OR f.outcome IS NOT m.outcome
       OR f.tags_search IS NOT m.tags_search
    UNION ALL
    SELECT 1
    FROM memory_fts AS f
    LEFT JOIN MemoryEntity AS m ON m.id = f.rowid
    WHERE m.id IS NULL
    LIMIT 1
""".trimIndent()

private fun memoryFtsProjectionHasDrift(db: SupportSQLiteDatabase): Boolean =
    db.query(MEMORY_FTS_DRIFT_SQL).use { cursor -> cursor.moveToFirst() }

private fun rebuildMemoryFtsRows(db: SupportSQLiteDatabase) {
    memoryFtsTransaction(db) {
        db.execSQL(MEMORY_FTS_SIMPLE_CREATE_SQL.trimIndent())
        db.execSQL("DELETE FROM memory_fts")
        db.execSQL(MEMORY_FTS_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    }
}

private fun rebuildMemoryFtsProjection(db: SupportSQLiteDatabase) {
    memoryFtsTransaction(db) {
        dropMemoryFtsProjection(db)
        db.execSQL(MEMORY_FTS_SIMPLE_CREATE_SQL.trimIndent())
        db.execSQL(MEMORY_FTS_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    }
}

private fun dropMemoryFtsProjection(db: SupportSQLiteDatabase) {
    dropMemoryFtsTriggers(db)
    db.execSQL("DROP TABLE IF EXISTS memory_fts")
}

private fun dropMemoryFtsTriggers(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ai")
    db.execSQL("DROP TRIGGER IF EXISTS memory_fts_au")
    db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ad")
}

private inline fun memoryFtsTransaction(db: SupportSQLiteDatabase, block: () -> Unit) {
    val ownsTransaction = !db.inTransaction()
    if (ownsTransaction) db.beginTransaction()
    try {
        block()
        if (ownsTransaction) db.setTransactionSuccessful()
    } finally {
        if (ownsTransaction) db.endTransaction()
    }
}

private fun normalizeMemoryFtsSql(sql: String): String = sql
    .lowercase()
    .replace(Regex("if\\s+not\\s+exists"), "")
    .replace(Regex("[`\"\\[\\]\\s;]+"), "")

class MemoryFtsManager(
    private val database: AppDatabase,
) : MemorySearchIndex {
    /** Compatibility entry point for callers that have not adopted a frozen request clock. */
    override suspend fun search(
        scopeId: String,
        query: String,
        limit: Int,
    ): List<MemorySearchCandidate> = search(
        MemoryIndexSearchRequest(
            scopeId = scopeId,
            query = query,
            limit = limit,
            frozenNowMs = System.currentTimeMillis(),
        ),
    )

    override suspend fun search(
        request: MemoryIndexSearchRequest,
    ): List<MemorySearchCandidate> = withContext(Dispatchers.IO) {
        val results = arrayListOf<MemorySearchCandidate>()
        database.openHelper.readableDatabase.query(
            MEMORY_FTS_SEARCH_SQL,
            arrayOf<Any?>(
                request.query,
                request.scopeId,
                request.frozenNowMs,
                request.limit.coerceIn(1, MAX_MEMORY_INDEX_CANDIDATES),
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results += MemorySearchCandidate(
                    id = cursor.getInt(0),
                    title = if (cursor.isNull(1)) null else cursor.getString(1),
                    content = cursor.getString(2).orEmpty(),
                    updatedAtMs = cursor.getLong(3),
                    importance = cursor.getFloat(4),
                    ftsRank = cursor.getDouble(5),
                    revision = cursor.getInt(6),
                )
            }
        }
        results
    }
}

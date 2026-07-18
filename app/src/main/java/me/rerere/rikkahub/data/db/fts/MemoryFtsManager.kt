package me.rerere.rikkahub.data.db.fts

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.repository.MemorySearchCandidate
import me.rerere.rikkahub.data.repository.MemorySearchIndex

const val MEMORY_FTS_SIMPLE_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
        title,
        content,
        memory_id UNINDEXED,
        assistant_id UNINDEXED,
        updated_at_ms UNINDEXED,
        importance UNINDEXED,
        tokenize = 'simple'
    )
"""

/** Portable migration form; the on-open reconciler replaces it with the simple tokenizer. */
const val MEMORY_FTS_PORTABLE_CREATE_SQL = """
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

val MEMORY_FTS_TRIGGER_SQL: List<String> = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_ai AFTER INSERT ON MemoryEntity BEGIN
        INSERT INTO memory_fts(
            rowid, title, content, memory_id, assistant_id, updated_at_ms, importance
        ) VALUES (
            new.id, new.title, new.content, new.id, new.assistant_id,
            new.updated_at_ms, new.importance
        );
    END
    """.trimIndent(),
    """
    CREATE TRIGGER IF NOT EXISTS memory_fts_au AFTER UPDATE ON MemoryEntity BEGIN
        DELETE FROM memory_fts WHERE rowid = old.id;
        INSERT INTO memory_fts(
            rowid, title, content, memory_id, assistant_id, updated_at_ms, importance
        ) VALUES (
            new.id, new.title, new.content, new.id, new.assistant_id,
            new.updated_at_ms, new.importance
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
        rowid, title, content, memory_id, assistant_id, updated_at_ms, importance
    )
    SELECT id, title, content, id, assistant_id, updated_at_ms, importance
    FROM MemoryEntity
"""

/**
 * Makes the memory projection deterministic after migration, import or an interrupted rebuild.
 * The bundled `simple` tokenizer is loaded before this callback runs; rebuilding every open is
 * cheap for preference-sized memory collections and repairs any missed trigger update.
 */
fun ensureMemoryFtsSchema(db: SupportSQLiteDatabase) {
    val schemaSql = db.query(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='memory_fts'",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
    val usesSimple = schemaSql.contains("tokenize = 'simple'", ignoreCase = true) ||
        schemaSql.contains("tokenize='simple'", ignoreCase = true)
    if (!usesSimple) {
        dropMemoryFtsProjection(db)
    }
    try {
        db.execSQL(MEMORY_FTS_SIMPLE_CREATE_SQL.trimIndent())
        db.execSQL("DELETE FROM memory_fts")
        db.execSQL(MEMORY_FTS_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    } catch (_: Throwable) {
        dropMemoryFtsProjection(db)
        db.execSQL(MEMORY_FTS_SIMPLE_CREATE_SQL.trimIndent())
        db.execSQL(MEMORY_FTS_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    }
}

private fun dropMemoryFtsProjection(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ai")
    db.execSQL("DROP TRIGGER IF EXISTS memory_fts_au")
    db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ad")
    db.execSQL("DROP TABLE IF EXISTS memory_fts")
}

class MemoryFtsManager(
    private val database: AppDatabase,
) : MemorySearchIndex {
    override suspend fun search(
        scopeId: String,
        query: String,
        limit: Int,
    ): List<MemorySearchCandidate> = withContext(Dispatchers.IO) {
        val results = arrayListOf<MemorySearchCandidate>()
        database.openHelper.readableDatabase.query(
            """
            SELECT memory_id, title, content, updated_at_ms, importance,
                   bm25(memory_fts, 5.0, 1.0, 0.0, 0.0, 0.0, 0.0) AS fts_rank
            FROM memory_fts
            WHERE memory_fts MATCH jieba_query(?) AND assistant_id = ?
            ORDER BY fts_rank ASC, updated_at_ms DESC, memory_id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf<Any?>(query, scopeId, limit.coerceIn(1, 64)),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results += MemorySearchCandidate(
                    id = cursor.getInt(0),
                    title = if (cursor.isNull(1)) null else cursor.getString(1),
                    content = cursor.getString(2).orEmpty(),
                    updatedAtMs = cursor.getLong(3),
                    importance = cursor.getFloat(4),
                    ftsRank = cursor.getDouble(5),
                )
            }
        }
        results
    }
}

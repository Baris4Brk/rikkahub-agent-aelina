package me.rerere.rikkahub.data.db.fts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFtsSchemaReconcilePolicyTest {
    private val expectedColumns = setOf(
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

    @Test
    fun `healthy simple projection is compatible without a rebuild`() {
        assertTrue(
            isCompatibleMemoryFtsSchema(
                schemaSql = MEMORY_FTS_SIMPLE_CREATE_SQL,
                columns = expectedColumns,
            ),
        )
    }

    @Test
    fun `portable tokenizer or incomplete columns require a rebuild`() {
        assertFalse(
            isCompatibleMemoryFtsSchema(
                schemaSql = MEMORY_FTS_PORTABLE_CREATE_SQL,
                columns = expectedColumns,
            ),
        )
        assertFalse(
            isCompatibleMemoryFtsSchema(
                schemaSql = MEMORY_FTS_SIMPLE_CREATE_SQL,
                columns = expectedColumns - "expires_at_ms",
            ),
        )
    }

    @Test
    fun `trigger comparison ignores formatting but rejects stale definitions`() {
        val healthy = mapOf(
            "memory_fts_ai" to MEMORY_FTS_TRIGGER_SQL[0].replace("IF NOT EXISTS", ""),
            "memory_fts_au" to MEMORY_FTS_TRIGGER_SQL[1],
            "memory_fts_ad" to MEMORY_FTS_TRIGGER_SQL[2],
        )
        assertTrue(memoryFtsTriggerDefinitionsAreCompatible(healthy))

        val stale = healthy.toMutableMap().apply {
            this["memory_fts_au"] = getValue("memory_fts_au").replace(
                "new.lifecycle_status",
                "old.lifecycle_status",
            )
        }
        assertFalse(memoryFtsTriggerDefinitionsAreCompatible(stale))
    }

    @Test
    fun `search policy filters only through the authoritative memory row`() {
        val sql = MEMORY_FTS_SEARCH_SQL.lowercase().replace(Regex("\\s+"), " ")

        assertTrue(sql.contains("inner join memoryentity as m on m.id = memory_fts.rowid"))
        assertTrue(sql.contains("m.assistant_id = ?"))
        assertTrue(sql.contains("m.lifecycle_status = 'active'"))
        assertTrue(sql.contains("m.truth_status = 'confirmed'"))
        assertTrue(sql.contains("m.expires_at_ms > ?"))
        assertTrue(sql.contains("select m.id, m.title, m.content"))
    }
}

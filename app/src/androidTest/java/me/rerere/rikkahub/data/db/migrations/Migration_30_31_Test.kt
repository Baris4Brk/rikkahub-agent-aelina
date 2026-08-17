package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_30_31_Test {
    private val testDb = "migration-30-31-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ),
    )

    @Test
    fun migrate30To31_preservesLegacyMemoryAndCreatesV2Ledger() {
        helper.createDatabase(testDb, 30).apply {
            execSQL(
                """
                INSERT INTO MemoryEntity(
                    id, assistant_id, content, title, updated_at_ms, importance
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(7, "__global__", "legacy coffee preference", "Coffee", 1_234L, 0.8f),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 31, false, MIGRATION_30_31)
        db.query(
            """
            SELECT id, assistant_id, content, title, updated_at_ms, importance,
                   created_at_ms, memory_kind, confidence, tags_json, tags_search,
                   content_hash, source_type, source_message_ids_json,
                   lifecycle_status, approval_source, revision
            FROM MemoryEntity WHERE id=7
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7, cursor.getInt(0))
            assertEquals("__global__", cursor.getString(1))
            assertEquals("legacy coffee preference", cursor.getString(2))
            assertEquals("Coffee", cursor.getString(3))
            assertEquals(1_234L, cursor.getLong(4))
            assertEquals(0.8f, cursor.getFloat(5), 0.0001f)
            assertEquals(1_234L, cursor.getLong(6))
            assertEquals("OTHER", cursor.getString(7))
            assertEquals(1f, cursor.getFloat(8), 0.0001f)
            assertEquals("[]", cursor.getString(9))
            assertEquals("", cursor.getString(10))
            assertEquals("", cursor.getString(11))
            assertEquals("LEGACY", cursor.getString(12))
            assertEquals("[]", cursor.getString(13))
            assertEquals("ACTIVE", cursor.getString(14))
            assertEquals("LEGACY", cursor.getString(15))
            assertEquals(1, cursor.getInt(16))
        }

        for (table in listOf("memory_captures", "memory_candidates", "memory_revisions")) {
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use { cursor -> assertTrue("table $table should exist", cursor.moveToFirst()) }
        }

        db.query("PRAGMA table_info(`memory_captures`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var hasCaptureSource = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "capture_source") {
                    hasCaptureSource = true
                    break
                }
            }
            assertTrue("memory captures must retain their automatic/manual source", hasCaptureSource)
        }

        db.query(
            "SELECT memory_id, assistant_id, title, content, tags_search FROM memory_fts WHERE rowid=7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7, cursor.getInt(0))
            assertEquals("__global__", cursor.getString(1))
            assertEquals("Coffee", cursor.getString(2))
            assertEquals("legacy coffee preference", cursor.getString(3))
            assertEquals("", cursor.getString(4))
        }

        db.query("PRAGMA table_info(`memory_fts`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertEquals(
                setOf(
                    "title",
                    "content",
                    "tags_search",
                    "memory_id",
                    "assistant_id",
                    "updated_at_ms",
                    "importance",
                    "lifecycle_status",
                    "expires_at_ms",
                ),
                columns,
            )
        }

        db.execSQL(
            "INSERT INTO MemoryEntity(id, assistant_id, content) VALUES (?, ?, ?)",
            arrayOf<Any?>(8, "__global__", "inserted memory"),
        )
        db.query("SELECT content FROM memory_fts WHERE rowid=8").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("inserted memory", cursor.getString(0))
        }
        db.execSQL(
            "UPDATE MemoryEntity SET content=?, tags_search=? WHERE id=7",
            arrayOf<Any?>("updated legacy memory", "updated"),
        )
        db.query("SELECT content, tags_search FROM memory_fts WHERE rowid=7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("updated legacy memory", cursor.getString(0))
            assertEquals("updated", cursor.getString(1))
        }
        db.execSQL("DELETE FROM MemoryEntity WHERE id=8")
        db.query("SELECT 1 FROM memory_fts WHERE rowid=8").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
    }
}

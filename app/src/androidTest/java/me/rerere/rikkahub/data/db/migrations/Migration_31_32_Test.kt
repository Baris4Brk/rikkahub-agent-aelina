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
class Migration_31_32_Test {
    private val testDb = "migration-31-32-test"

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
    fun migrate31To32BackfillsCurrentProjectionAndKeepsAllTriggersLive() {
        helper.createDatabase(testDb, 31).apply {
            execSQL(
                """
                INSERT INTO MemoryEntity(
                    id, assistant_id, content, title, updated_at_ms, importance, tags_search
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(7, "__global__", "legacy narrative", "Narrative", 1_234L, 0.8f, "legacy"),
            )
            close()
        }

        // memory_fts is an intentionally unmanaged projection verified explicitly below.
        val db = helper.runMigrationsAndValidate(testDb, 32, false, MIGRATION_31_32)
        db.query(
            "SELECT content, outcome, tags_search, memory_id FROM memory_fts WHERE rowid=7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy narrative", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals("legacy", cursor.getString(2))
            assertEquals(7, cursor.getInt(3))
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
                    "outcome",
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
            arrayOf<Any?>(8, "__global__", "inserted narrative"),
        )
        db.query("SELECT content FROM memory_fts WHERE rowid=8").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("inserted narrative", cursor.getString(0))
        }

        db.execSQL(
            "UPDATE MemoryEntity SET content=?, outcome=?, tags_search=? WHERE id=7",
            arrayOf<Any?>("updated narrative", "resolved", "updated"),
        )
        db.query("SELECT content, outcome, tags_search FROM memory_fts WHERE rowid=7")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("updated narrative", cursor.getString(0))
                assertEquals("resolved", cursor.getString(1))
                assertEquals("updated", cursor.getString(2))
            }

        db.execSQL("DELETE FROM MemoryEntity WHERE id=8")
        db.query("SELECT 1 FROM memory_fts WHERE rowid=8").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
    }
}

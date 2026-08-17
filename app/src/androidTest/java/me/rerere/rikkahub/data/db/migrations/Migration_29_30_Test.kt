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
class Migration_29_30_Test {
    private val testDb = "migration-29-30-test"

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
    fun migrate29To30_preservesMemoriesAndBuildsAProjectionSupportedByV30Columns() {
        helper.createDatabase(testDb, 29).apply {
            execSQL(
                "INSERT INTO MemoryEntity(id, assistant_id, content) VALUES (?, ?, ?)",
                arrayOf<Any?>(7, "__global__", "旧的咖啡记忆"),
            )
            close()
        }

        // memory_fts is an intentionally unmanaged projection that is verified explicitly below.
        val db = helper.runMigrationsAndValidate(testDb, 30, false, MIGRATION_29_30)
        db.query(
            "SELECT content, title, updated_at_ms, importance FROM MemoryEntity WHERE id=7",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧的咖啡记忆", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals(0.5f, cursor.getFloat(3), 0.0001f)
        }
        db.query("SELECT memory_id, assistant_id, content FROM memory_fts WHERE rowid=7")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
                assertEquals("__global__", cursor.getString(1))
                assertEquals("旧的咖啡记忆", cursor.getString(2))
            }
        // A version-30 MemoryEntity has no tags/lifecycle/expiry columns. The projection and
        // its update trigger must therefore rely only on the columns introduced by this step.
        db.query("PRAGMA table_info(`memory_fts`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
            assertEquals(
                setOf(
                    "title",
                    "content",
                    "memory_id",
                    "assistant_id",
                    "updated_at_ms",
                    "importance",
                ),
                columns,
            )
        }

        db.execSQL("UPDATE MemoryEntity SET content=? WHERE id=7", arrayOf("updated memory"))
        db.query("SELECT content FROM memory_fts WHERE rowid=7").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("updated memory", cursor.getString(0))
        }
        db.execSQL(
            "INSERT INTO MemoryEntity(id, assistant_id, content) VALUES (?, ?, ?)",
            arrayOf<Any?>(8, "__global__", "inserted memory"),
        )
        db.query("SELECT content FROM memory_fts WHERE rowid=8").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("inserted memory", cursor.getString(0))
        }
        db.execSQL("DELETE FROM MemoryEntity WHERE id=8")
        db.query("SELECT 1 FROM memory_fts WHERE rowid=8").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
    }
}

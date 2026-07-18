package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
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
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate29To30_preservesMemoriesAndBackfillsFtsProjection() {
        helper.createDatabase(testDb, 29).apply {
            execSQL(
                "INSERT INTO MemoryEntity(id, assistant_id, content) VALUES (?, ?, ?)",
                arrayOf<Any?>(7, "__global__", "旧的咖啡记忆"),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 30, true, MIGRATION_29_30)
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
        db.close()
    }
}

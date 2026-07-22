package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_32_33_Test {
    private val testDb = "migration-32-33-test"

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
    fun migrate32To33_keepsCaptureAndFreezesDefaultConversationWindow() {
        helper.createDatabase(testDb, 32).apply {
            execSQL(
                """
                INSERT INTO memory_captures(
                    id, assistant_id, scope_id, conversation_id, user_message_id,
                    assistant_message_id, origin, auto_save_mode, user_text, assistant_text,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "capture-1", "assistant", "assistant", "conversation", "user-message",
                    "assistant-message", "APP_UI", "SAFE_NEW_ONLY", "user text", "assistant text",
                    1_000L, 1_000L,
                ),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 33, false, MIGRATION_32_33)
        db.query("SELECT context_turn_limit FROM memory_captures WHERE id='capture-1'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(12, cursor.getInt(0))
        }
        db.close()
    }
}

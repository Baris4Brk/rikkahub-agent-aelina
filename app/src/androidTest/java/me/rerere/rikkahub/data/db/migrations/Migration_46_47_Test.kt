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

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class Migration_46_47_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate46To47_preservesStreamAndAddsExplicitRewardAuthority() {
        val old = helper.createDatabase("migration-46-47-test", 46)
        old.execSQL(
            "INSERT INTO learning_outbox(stream_id, event_id, event_type, " +
                "event_schema_version, created_at_ms) VALUES(" +
                "'10000000-0000-0000-0000-000000000001', " +
                "'$LEARNING_V46_STREAM_INIT_EVENT_ID', 'STREAM_INIT', 1, 1)",
        )
        old.close()

        val db = helper.runMigrationsAndValidate(
            "migration-46-47-test",
            47,
            true,
            MIGRATION_46_47,
        )

        assertEquals(
            LEARNING_V47_OUTBOX_COLUMNS.map { it.first }.toSet(),
            tableColumns(db, "learning_outbox").intersect(
                LEARNING_V47_OUTBOX_COLUMNS.map { it.first }.toSet(),
            ),
        )
        assertTrue(tableExists(db, "learning_reward_feedback_authority"))
        assertTrue(tableExists(db, "learning_reward_feedback_revisions"))
        db.query(
            "SELECT reward_dimension, reward_signal_kind, reward_value_milli, " +
                "execution_verification_state FROM learning_outbox " +
                "WHERE event_type = 'STREAM_INIT'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            repeat(4) { assertTrue(cursor.isNull(it)) }
            assertFalse(cursor.moveToNext())
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        db.close()
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun tableColumns(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Set<String> = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val name = cursor.getColumnIndexOrThrow("name")
        buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
    }
}

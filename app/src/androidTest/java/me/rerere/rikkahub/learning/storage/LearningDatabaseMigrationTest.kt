package me.rerere.rikkahub.learning.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningDatabaseMigrationTest {
    private val testDb = "learning-migration-1-3-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LearningDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationOneToTwoToThreePreservesTransportAndAddsOnlyFrozenTables() {
        val db = helper.createDatabase(testDb, 1)
        db.execSQL(
            "INSERT INTO learning_stream_checkpoints(stream_id, last_contiguous_seq, " +
                "last_seen_head_seq, replay_generation, reset_reason, bootstrap_state, " +
                "bootstrap_head_seq, coverage_start_ms, command_coverage_start_ms, " +
                "execution_coverage_start_ms, updated_at_ms) VALUES(" +
                "'00000000-0000-0000-0000-000000000001', 0, 1, 0, 'NEW_STREAM', " +
                "'REQUIRED', 1, NULL, NULL, NULL, 1)",
        )

        LEARNING_MIGRATION_1_2.migrate(db)
        assertEquals(
            setOf(
                "learning_episodes",
                "learning_trace_features",
                "learning_episode_lessons",
                "learning_reward_windows",
                "learning_source_validity",
            ),
            tablesWithPrefix(db, listOf("learning_episodes", "learning_trace", "learning_episode_lessons", "learning_reward", "learning_source")),
        )
        assertFalse(tableExists(db, "learning_policies"))
        db.execSQL("PRAGMA user_version = 2")

        LEARNING_MIGRATION_2_3.migrate(db)
        assertTrue(tableExists(db, "learning_policies"))
        assertTrue(tableExists(db, "policy_evidence"))
        assertTrue(tableExists(db, "policy_revisions"))
        assertTrue(tableExists(db, "policy_lineage"))
        db.query("SELECT COUNT(*) FROM learning_stream_checkpoints").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        db.execSQL("PRAGMA user_version = 3")
        db.close()

        helper.runMigrationsAndValidate(testDb, 3, true).close()
    }
}

private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
    db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(table),
    ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }

private fun tablesWithPrefix(
    db: androidx.sqlite.db.SupportSQLiteDatabase,
    prefixes: List<String>,
): Set<String> = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
    buildSet {
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            if (prefixes.any(name::startsWith)) add(name)
        }
    }
}

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
class Migration_47_48_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate47To48_preservesAuthorityAndAddsContentFreePolicyGrants() {
        val old = helper.createDatabase("migration-47-48-test", 47)
        old.execSQL(
            "INSERT INTO learning_outbox(stream_id, event_id, event_type, " +
                "event_schema_version, created_at_ms) VALUES(" +
                "'10000000-0000-0000-0000-000000000001', " +
                "'$LEARNING_V46_STREAM_INIT_EVENT_ID', 'STREAM_INIT', 1, 1)",
        )
        old.close()

        val db = helper.runMigrationsAndValidate(
            "migration-47-48-test",
            48,
            true,
            MIGRATION_47_48,
        )

        assertTrue(tableExists(db, "learning_policy_grants"))
        assertTrue(tableExists(db, "learning_policy_grant_revisions"))
        assertEquals(
            setOf(
                "grant_id", "source_stream_id", "policy_id", "policy_revision",
                "artifact_sha256", "scope_kind", "scope_id", "consuming_assistant_id",
                "actor", "state",
                "state_version", "granted_at_ms", "revoked_at_ms", "reason_code",
                "created_at_ms", "updated_at_ms",
            ),
            tableColumns(db, "learning_policy_grants"),
        )
        db.query("SELECT COUNT(*) FROM learning_outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
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

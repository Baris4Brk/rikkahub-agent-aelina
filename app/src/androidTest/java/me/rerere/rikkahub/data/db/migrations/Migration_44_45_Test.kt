package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
class Migration_44_45_Test {
    private val testDb = "migration-44-45-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate44To45_addsOnlyDormantObserverLedgerWithForeignKeysAndCoalescedReceipts() {
        helper.createDatabase(testDb, 44).close()

        val db = helper.runMigrationsAndValidate(testDb, 45, true, MIGRATION_44_45)
        // MigrationTestHelper's custom SupportSQLite factory does not run Room's normal
        // open-time foreign-key configuration. Exercise the declared constraints under the
        // same PRAGMA that Room enables for the production AppDatabase connection.
        db.execSQL("PRAGMA foreign_keys = ON")

        assertEquals(
            setOf("memory_scope_state", "memory_scope_changes", "dream_runs"),
            observerTables(db),
        )
        listOf(
            "index_memory_scope_state_active_run_id",
            "index_memory_scope_state_active_run_lease_until_ms",
            "index_memory_scope_changes_scope_id_memory_epoch_entity_kind_entity_id",
            "index_memory_scope_changes_scope_id_memory_epoch_change_id",
            "index_dream_runs_scope_id_status_started_at_ms",
            "index_dream_runs_scope_id_created_at_ms",
            "index_dream_runs_status_lease_until_ms",
        ).forEach { assertIndexExists(db, it) }

        db.execSQL(
            "INSERT INTO memory_scope_state(scope_id, updated_at_ms) VALUES('scope-a', 10)",
        )
        db.query(
            "SELECT memory_epoch, observer_checkpoint_epoch, active_run_id " +
                "FROM memory_scope_state WHERE scope_id = 'scope-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
        }

        insertChange(db, epoch = 1, revision = 1, operation = "UPDATE")
        assertTrue(
            "operation/revision must not weaken the per-entity receipt key",
            runCatching {
                insertChange(db, epoch = 1, revision = 2, operation = "ARCHIVE")
            }.isFailure,
        )
        insertChange(db, epoch = 2, revision = 2, operation = "ARCHIVE")

        db.execSQL(
            "INSERT INTO dream_runs(" +
                "run_id, scope_id, mode, base_memory_epoch, " +
                "base_observer_checkpoint_epoch, created_at_ms, updated_at_ms) " +
                "VALUES('run-a', 'scope-a', 'OBSERVER_REPLAY', 2, 0, 20, 20)",
        )
        db.query(
            "SELECT status, attempt, checkpoint_epoch FROM dream_runs WHERE run_id = 'run-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PENDING", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals(0L, cursor.getLong(2))
        }

        assertForeignKeyToScopeState(db, "memory_scope_changes")
        assertForeignKeyToScopeState(db, "dream_runs")
        db.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }

        db.execSQL("DELETE FROM memory_scope_state WHERE scope_id = 'scope-a'")
        assertEquals(0, count(db, "memory_scope_changes"))
        assertEquals(0, count(db, "dream_runs"))
        db.close()
    }

    private fun insertChange(
        db: SupportSQLiteDatabase,
        epoch: Long,
        revision: Long,
        operation: String,
    ) {
        db.execSQL(
            "INSERT INTO memory_scope_changes(" +
                "scope_id, memory_epoch, entity_kind, entity_id, entity_revision, " +
                "operation, reason_code, created_at_ms) VALUES(" +
                "'scope-a', $epoch, 'MEMORY', 'memory-1', $revision, " +
                "'$operation', 'AUTHORITY_CHANGED', 20)",
        )
    }

    private fun observerTables(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('memory_scope_state', 'memory_scope_changes', 'dream_runs')",
        ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun assertIndexExists(db: SupportSQLiteDatabase, index: String) {
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(index),
        ).use { cursor -> assertTrue("missing index $index", cursor.moveToFirst()) }
    }

    private fun assertForeignKeyToScopeState(db: SupportSQLiteDatabase, table: String) {
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val targetTable = cursor.getColumnIndexOrThrow("table")
            val from = cursor.getColumnIndexOrThrow("from")
            val to = cursor.getColumnIndexOrThrow("to")
            val onDelete = cursor.getColumnIndexOrThrow("on_delete")
            assertTrue("missing $table scope FK", cursor.moveToFirst())
            assertEquals("memory_scope_state", cursor.getString(targetTable))
            assertEquals("scope_id", cursor.getString(from))
            assertEquals("scope_id", cursor.getString(to))
            assertEquals("CASCADE", cursor.getString(onDelete))
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}

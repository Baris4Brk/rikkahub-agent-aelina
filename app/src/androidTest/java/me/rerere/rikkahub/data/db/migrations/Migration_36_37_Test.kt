package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.createAppSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_36_37_Test {
    private val testDb = "migration-36-37-test"

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
    fun migrate36To37_preservesRowsAndInitializesVerificationHonestly() {
        helper.createDatabase(testDb, 36).apply {
            execSQL(
                """
                INSERT INTO execution_records(
                    id, trace_id, subject_id, subject_type, origin, capability_keys,
                    resource_summary, runtime, status, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "running", "trace", "subject", "LOCAL_SECOND_USER", "APP_UI", "linux.run",
                    "shell:managed", "WORKSPACE", "running", 1_000L, 2_000L,
                ),
            )
            execSQL(
                """
                INSERT INTO execution_records(
                    id, trace_id, subject_id, subject_type, origin, capability_keys,
                    resource_summary, runtime, status, created_at_ms, updated_at_ms, finished_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "done", "trace", "subject", "LOCAL_SECOND_USER", "APP_UI", "file.read",
                    "file:managed", "LOCAL_TOOL", "timed_out", 1_000L, 2_000L, 2_000L,
                ),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 37, false, MIGRATION_36_37)
        db.query(
            "SELECT execution_kind, state_version, verification_state, completion_policy, " +
                "runtime_instance_marker, requested_terminal_outcome " +
                "FROM execution_records WHERE id='running'",
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("TOOL_CALL", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals("RECONCILING", cursor.getString(2))
            assertEquals("WAIT_FOR_CHILDREN", cursor.getString(3))
            assertNull(cursor.getString(4))
            assertEquals("NONE", cursor.getString(5))
        }
        db.query("SELECT verification_state FROM execution_records WHERE id='done'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("DATABASE_CONFIRMED", cursor.getString(0))
        }
        db.close()
    }
}

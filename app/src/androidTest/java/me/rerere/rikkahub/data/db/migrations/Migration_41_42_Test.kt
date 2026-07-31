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

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class Migration_41_42_Test {
    private val testDb = "migration-41-42-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate41To42_createsOwnerOperationAndServiceLedger() {
        helper.createDatabase(testDb, 41).close()
        val db = helper.runMigrationsAndValidate(testDb, 42, true, MIGRATION_41_42)

        listOf("host_operations", "host_operation_events", "host_local_services").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                assertEquals("missing $table", true, cursor.moveToFirst())
            }
        }
        db.execSQL(
            "INSERT INTO host_operations(request_id,authority_subject_id,authority_epoch,assistant_id,conversation_id,model_id,provider_id,tool_family,action_summary_json,state,state_version,recovery_code,result_code,created_at_ms,updated_at_ms,completed_at_ms) " +
                "VALUES('request-1','subject',1,'assistant','conversation',NULL,NULL,'DOCTOR','[]','VALIDATING',0,NULL,NULL,1,1,NULL)",
        )
        db.execSQL(
            "INSERT INTO host_operation_events(event_id,request_id,sequence,previous_state,next_state,action_index,action_type,reason_code,created_at_ms) " +
                "VALUES('event-1','request-1',0,NULL,'VALIDATING',NULL,NULL,'ACCEPTED',1)",
        )
        val duplicateSequenceRejected = runCatching {
            db.execSQL(
                "INSERT INTO host_operation_events(event_id,request_id,sequence,previous_state,next_state,action_index,action_type,reason_code,created_at_ms) " +
                    "VALUES('event-2','request-1',0,'VALIDATING','APPLYING',NULL,NULL,'DUPLICATE',2)",
            )
        }.isFailure
        assertEquals(true, duplicateSequenceRejected)
        db.close()
    }
}

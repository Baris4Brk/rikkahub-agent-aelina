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
class Migration_39_40_Test {
    private val testDb = "migration-39-40-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate39To40_createsRedactedAuthorityScopedExperienceTables() {
        helper.createDatabase(testDb, 39).close()

        val db = helper.runMigrationsAndValidate(testDb, 40, true, MIGRATION_39_40)
        listOf("tool_experiences", "tool_experience_evidence", "tool_experience_revisions").forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                assertEquals("missing $table", true, cursor.moveToFirst())
            }
        }
        db.execSQL(
            "INSERT INTO tool_experiences(experience_id,authority_subject_id,primary_tool_name,tool_names_json,category_path,schema_fingerprint,title,body,tags_json,state,confidence,state_version,created_at_ms,updated_at_ms,last_observed_at_ms) VALUES('experience','subject','tool','[\"tool\"]','Device and apps','fingerprint','title','body','[]','ACTIVE','OBSERVED',0,1,1,1)",
        )
        db.execSQL(
            "INSERT INTO tool_experience_evidence(evidence_id,experience_id,execution_id,tool_name,schema_fingerprint,outcome_kind,created_at_ms) VALUES('evidence-one','experience','execution','tool','fingerprint','HOST_COMPLETED',1)",
        )
        val duplicateFailed = runCatching {
            db.execSQL(
                "INSERT INTO tool_experience_evidence(evidence_id,experience_id,execution_id,tool_name,schema_fingerprint,outcome_kind,created_at_ms) VALUES('evidence-two','experience','execution','tool','fingerprint','HOST_COMPLETED',1)",
            )
        }.isFailure
        assertEquals(true, duplicateFailed)
        db.close()
    }
}

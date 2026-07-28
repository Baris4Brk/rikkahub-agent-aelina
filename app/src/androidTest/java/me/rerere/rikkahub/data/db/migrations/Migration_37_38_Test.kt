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
class Migration_37_38_Test {
    private val testDb = "migration-37-38-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate37To38_createsPetSchemaAndEnforcesSingleActiveOwner() {
        helper.createDatabase(testDb, 37).close()
        val db = helper.runMigrationsAndValidate(testDb, 38, true, MIGRATION_37_38)
        db.execSQL(
            "INSERT INTO pet_dialogue_sessions(sessionId,assistantId,privilegedConversationId,localDate,zoneId,activeOwnerKey,status,title,summary,notes,tagsJson,summaryState,stateVersion,createdAtMs,updatedAtMs) VALUES('one','a','c','2026-07-29','Asia/Shanghai','a:c','ACTIVE','','','','[]','NONE',0,1,1)",
        )
        val duplicateFailed = runCatching {
            db.execSQL(
                "INSERT INTO pet_dialogue_sessions(sessionId,assistantId,privilegedConversationId,localDate,zoneId,activeOwnerKey,status,title,summary,notes,tagsJson,summaryState,stateVersion,createdAtMs,updatedAtMs) VALUES('two','a','c','2026-07-29','Asia/Shanghai','a:c','ACTIVE','','','','[]','NONE',0,1,1)",
            )
        }.isFailure
        assertEquals(true, duplicateFailed)
        db.close()
    }
}

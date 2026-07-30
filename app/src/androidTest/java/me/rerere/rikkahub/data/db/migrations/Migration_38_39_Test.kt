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
class Migration_38_39_Test {
    private val testDb = "migration-38-39-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate38To39_addsNullableAuthoritySnapshotAndIndex() {
        helper.createDatabase(testDb, 38).use { db ->
            db.execSQL(
                """
                INSERT INTO pending_chat_commands(
                    id,schemaVersion,conversationId,type,payloadJson,state,priority,sequence,
                    idempotencyKey,attempt,createdAt
                ) VALUES('legacy-command',1,'conversation','SEND','{}','PENDING',0,0,'legacy-key',0,1)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 39, true, MIGRATION_38_39)
        db.query(
            "SELECT authoritySubjectId FROM pending_chat_commands WHERE id='legacy-command'",
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(true, cursor.isNull(0))
        }
        db.query("PRAGMA index_list(`pending_chat_commands`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                found = found || cursor.getString(nameIndex) ==
                    "index_pending_chat_commands_authoritySubjectId"
            }
            assertEquals(true, found)
        }
        db.close()
    }
}

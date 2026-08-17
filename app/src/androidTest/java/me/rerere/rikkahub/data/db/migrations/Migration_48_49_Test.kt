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
class Migration_48_49_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate48To49_preservesRowsAndBackfillsUserAuthorityWithoutJson1() {
        val old = helper.createDatabase("migration-48-49-test", 48)
        val legacy = """{"id":"w1","name":"Legacy","enabled":true,"trigger":{"type":"manual"},"actions":[{"tool":"show_toast","args":{"text":"hi"}}],"authoring_assistant_id":"assistant-1"}"""
        old.execSQL(
            "INSERT INTO workflows(id,name,enabled,definitionJson,createdAtMs,updatedAtMs) " +
                "VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("w1", "Legacy", 1, legacy, 10L, 11L),
        )
        old.close()

        val db = helper.runMigrationsAndValidate(
            "migration-48-49-test",
            49,
            true,
            MIGRATION_48_49,
        )
        val required = WORKFLOW_V49_COLUMNS.map { it.first }.toSet()
        assertTrue(tableColumns(db, "workflows").containsAll(required))
        db.query(
            "SELECT stateVersion,origin,sourceCandidateId,sourceArtifactHash,grantDigest," +
                "authoringAssistantId,capabilitySnapshotJson,toolSchemaFingerprintsJson," +
                "staleReason,definitionJson FROM workflows WHERE id='w1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("USER", cursor.getString(1))
            assertTrue(cursor.isNull(2) && cursor.isNull(3) && cursor.isNull(4))
            assertEquals("assistant-1", cursor.getString(5))
            assertTrue(cursor.getString(6).startsWith("["))
            assertEquals("[]", cursor.getString(7))
            assertTrue(cursor.isNull(8))
            assertTrue(cursor.getString(9).contains("\"origin\":\"USER\""))
            assertFalse(cursor.moveToNext())
        }
        db.close()
    }

    private fun tableColumns(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Set<String> = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val name = cursor.getColumnIndexOrThrow("name")
        buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
    }
}

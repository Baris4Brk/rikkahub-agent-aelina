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
class Migration_43_44_Test {
    private val testDb = "migration-43-44-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate43To44_addsScopedCaptureIdentityEvidenceGroupsAndSourceTombstones() {
        val old = helper.createDatabase(testDb, 43)
        old.execSQL(
            "INSERT INTO `MemoryEntity` (`id`, `assistant_id`, `content`) " +
                "VALUES (1, 'scope-a', 'legacy memory')",
        )
        old.execSQL(
            "INSERT INTO `memory_captures` (" +
                "`id`, `assistant_id`, `scope_id`, `conversation_id`, `user_message_id`, " +
                "`assistant_message_id`, `origin`, `auto_save_mode`, `user_text`, " +
                "`assistant_text`, `created_at_ms`, `updated_at_ms`) VALUES (" +
                "'capture-a', 'assistant-a', 'scope-a', 'conversation-1', 'user-1', " +
                "'assistant-1', 'CHAT', 'AUTO', 'user text', 'assistant text', 10, 10)",
        )
        old.execSQL(
            "INSERT INTO `memory_evidence` (" +
                "`id`, `memory_id`, `conversation_id`, `message_id`, `role`, `excerpt`, " +
                "`content_hash`, `captured_at_ms`, `quality`) VALUES (" +
                "'evidence-1', 1, 'conversation-1', 'message-deleted', 'USER', '', '', " +
                "20, 'SOURCE_DELETED')",
        )
        old.execSQL(
            "INSERT INTO `memory_revisions` (" +
                "`id`, `memory_id`, `revision`, `operation`, `actor`, " +
                "`source_conversation_id`, `created_at_ms`, `reason_code`) VALUES (" +
                "'revision-1', 1, 1, 'ARCHIVE', 'SYSTEM', 'conversation-1', 30, " +
                "'SOURCE_CONVERSATION_DELETED')",
        )
        old.close()

        val db = helper.runMigrationsAndValidate(testDb, 44, true, MIGRATION_43_44)

        db.query(
            "SELECT `source_identities_json` FROM `memory_captures` WHERE `id` = 'capture-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        db.query(
            "SELECT `source_identities_json` FROM `MemoryEntity` WHERE `id` = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        db.query(
            "SELECT `source_identities_json` FROM `memory_revisions` WHERE `id` = 'revision-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("[]", cursor.getString(0))
        }
        db.query(
            "SELECT `evidence_group_id`, `source_digest`, `source_kind` " +
                "FROM `memory_evidence` WHERE `id` = 'evidence-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("evidence-1", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertEquals("TEXT", cursor.getString(2))
        }

        assertIndexExists(
            db,
            "index_memory_captures_scope_id_conversation_id_assistant_message_id_capture_source",
        )
        assertIndexDoesNotExist(
            db,
            "index_memory_captures_conversation_id_assistant_message_id_capture_source",
        )
        listOf(
            "index_memory_evidence_conversation_id_message_id_source_digest",
            "index_memory_evidence_link_id_evidence_group_id",
            "index_memory_evidence_memory_id_evidence_group_id",
        ).forEach { assertIndexExists(db, it) }

        db.query(
            "SELECT `scope_id`, `conversation_id`, `source_kind`, `source_id`, " +
                "`source_digest`, `reason_code`, `tombstoned_at_ms` " +
                "FROM `memory_source_tombstones` ORDER BY `source_kind`",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("scope-a", cursor.getString(0))
            assertEquals("conversation-1", cursor.getString(1))
            assertEquals("CONVERSATION", cursor.getString(2))
            assertEquals("conversation-1", cursor.getString(3))
            assertEquals("", cursor.getString(4))
            assertEquals("SOURCE_CONVERSATION_DELETED", cursor.getString(5))
            assertEquals(30L, cursor.getLong(6))

            assertTrue(cursor.moveToNext())
            assertEquals("scope-a", cursor.getString(0))
            assertEquals("conversation-1", cursor.getString(1))
            assertEquals("MESSAGE", cursor.getString(2))
            assertEquals("message-deleted", cursor.getString(3))
            assertEquals("", cursor.getString(4))
            assertEquals("MIGRATED_SOURCE_DELETED", cursor.getString(5))
            assertEquals(20L, cursor.getLong(6))
            assertFalse(cursor.moveToNext())
        }

        // The new key permits the same captured turn in another scope.
        insertCapture(db, id = "capture-b", assistantId = "assistant-b", scopeId = "scope-b")
        db.query(
            "SELECT COUNT(*) FROM `memory_captures` " +
                "WHERE `conversation_id` = 'conversation-1' " +
                "AND `assistant_message_id` = 'assistant-1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        // Idempotency is still enforced inside one scope.
        assertTrue(
            runCatching {
                insertCapture(
                    db,
                    id = "capture-a-duplicate",
                    assistantId = "assistant-a",
                    scopeId = "scope-a",
                )
            }.isFailure,
        )

        // A message ID can carry a version-specific tombstone alongside the legacy wildcard.
        db.execSQL(
            "INSERT INTO `memory_source_tombstones` (" +
                "`scope_id`, `conversation_id`, `source_kind`, `source_id`, `source_digest`, " +
                "`reason_code`, `tombstoned_at_ms`) VALUES (" +
                "'scope-a', 'conversation-1', 'MESSAGE', 'message-deleted', 'digest-v1', " +
                "'SOURCE_MESSAGE_DELETED', 40)",
        )
        db.query(
            "SELECT COUNT(*) FROM `memory_source_tombstones` " +
                "WHERE `source_kind` = 'MESSAGE' AND `source_id` = 'message-deleted'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        assertTombstonePrimaryKey(db)
        db.query("PRAGMA foreign_key_list(`memory_source_tombstones`)").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        db.close()
    }

    private fun insertCapture(
        db: SupportSQLiteDatabase,
        id: String,
        assistantId: String,
        scopeId: String,
    ) {
        db.execSQL(
            "INSERT INTO `memory_captures` (" +
                "`id`, `assistant_id`, `scope_id`, `conversation_id`, `user_message_id`, " +
                "`assistant_message_id`, `origin`, `auto_save_mode`, `user_text`, " +
                "`assistant_text`, `created_at_ms`, `updated_at_ms`) VALUES (" +
                "'$id', '$assistantId', '$scopeId', 'conversation-1', 'user-1', " +
                "'assistant-1', 'CHAT', 'AUTO', 'user text', 'assistant text', 50, 50)",
        )
    }

    private fun assertIndexExists(db: SupportSQLiteDatabase, index: String) {
        db.query(
            "SELECT 1 FROM `sqlite_master` WHERE `type` = 'index' AND `name` = ?",
            arrayOf(index),
        ).use { cursor -> assertTrue("missing index $index", cursor.moveToFirst()) }
    }

    private fun assertIndexDoesNotExist(db: SupportSQLiteDatabase, index: String) {
        db.query(
            "SELECT 1 FROM `sqlite_master` WHERE `type` = 'index' AND `name` = ?",
            arrayOf(index),
        ).use { cursor -> assertFalse("unexpected legacy index $index", cursor.moveToFirst()) }
    }

    private fun assertTombstonePrimaryKey(db: SupportSQLiteDatabase) {
        val expected = mapOf(
            "scope_id" to 1,
            "conversation_id" to 2,
            "source_kind" to 3,
            "source_id" to 4,
            "source_digest" to 5,
        )
        val actual = mutableMapOf<String, Int>()
        db.query("PRAGMA table_info(`memory_source_tombstones`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val primaryKeyPosition = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                if (cursor.getInt(primaryKeyPosition) > 0) {
                    actual[cursor.getString(name)] = cursor.getInt(primaryKeyPosition)
                }
            }
        }
        assertEquals(expected, actual)
    }
}

package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the explicit durable-queue schema migration from v28 to v29. */
@RunWith(AndroidJUnit4::class)
class Migration_28_29_Test {
    private val testDb = "migration-28-29-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate28To29_createsDurableCommandTableAndIndexes() {
        helper.createDatabase(testDb, 28).apply { close() }

        val db = helper.runMigrationsAndValidate(testDb, 29, true, MIGRATION_28_29)
        val cursor = db.query("SELECT * FROM pending_chat_commands LIMIT 0")
        val columns = cursor.columnNames.toSet()
        cursor.close()

        assertEquals(
            setOf(
                "id", "schemaVersion", "conversationId", "type", "payloadJson", "state",
                "priority", "sequence", "expectedTargetVersion", "expectedBranchHeadMessageId",
                "dedupeKey", "idempotencyKey", "attempt", "claimedBy", "leaseUntil", "createdAt",
                "startedAt", "finishedAt", "expiresAt", "lastErrorCode", "lastErrorMessage",
            ),
            columns,
        )

        for (index in listOf(
            "index_pending_chat_commands_conversationId",
            "index_pending_chat_commands_conversationId_state_priority_sequence",
            "index_pending_chat_commands_leaseUntil",
            "index_pending_chat_commands_dedupeKey",
            "index_pending_chat_commands_idempotencyKey",
        )) {
            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(index),
            ).use { result ->
                assertTrue("index $index should exist", result.moveToFirst())
            }
        }
        db.close()
    }

    @Test
    fun migrate28To29_acceptsUpstreamFolderColumnAndPreservesConversationData() {
        val conversationId = "conversation-with-folder"
        val messageNodeId = "message-node-with-folder"
        helper.createDatabase(testDb, 28).apply {
            execSQL(
                "ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''"
            )
            execSQL(
                """
                INSERT INTO `ConversationEntity` (
                    `id`, `assistant_id`, `title`, `nodes`, `create_at`, `update_at`,
                    `suggestions`, `is_pinned`, `custom_system_prompt`,
                    `mode_injection_ids`, `lorebook_ids`, `workspace_cwd`, `folder_id`
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    conversationId,
                    "0950e2dc-9bd5-4801-afa3-aa887aa36b4e",
                    "folder migration conversation",
                    "[]",
                    1_000L,
                    2_000L,
                    "[]",
                    0,
                    "",
                    "[]",
                    "[]",
                    "",
                    "upstream-folder",
                ),
            )
            execSQL(
                """
                INSERT INTO `message_node` (
                    `id`, `conversation_id`, `node_index`, `messages`, `select_index`
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(messageNodeId, conversationId, 0, "[]", 0),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 29, true, MIGRATION_28_29)
        db.query(
            "SELECT title, folder_id FROM ConversationEntity WHERE id=?",
            arrayOf(conversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("folder migration conversation", cursor.getString(0))
            assertEquals("upstream-folder", cursor.getString(1))
        }
        db.query(
            "SELECT conversation_id FROM message_node WHERE id=?",
            arrayOf(messageNodeId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(conversationId, cursor.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate28To29_preservesExistingRows() {
        val scheduledJobId = "job-before-v29"
        helper.createDatabase(testDb, 28).apply {
            val values = ContentValues().apply {
                put("id", scheduledJobId)
                put("name", "migration-test")
                putNull("prompt")
                put("assistantId", "assistant")
                put("scheduleType", "interval")
                putNull("atUnixMs")
                put("intervalSeconds", 60L)
                put("enabled", 1)
                put("createdAtMs", 1_000L)
                putNull("lastRunAtMs")
                putNull("nextRunAtMs")
                put("mode", "llm")
                putNull("actionsJson")
                putNull("cronExpression")
                putNull("timezone")
                putNull("startAtUnixMs")
                putNull("endAtUnixMs")
                putNull("maxRuns")
                put("runsSoFar", 0)
                put("catchup", "fire_once")
                putNull("description")
                putNull("tags")
                putNull("targetConversationId")
            }
            insert("scheduled_jobs", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 29, true, MIGRATION_28_29)
        db.query(
            "SELECT name, intervalSeconds FROM scheduled_jobs WHERE id=?",
            arrayOf(scheduledJobId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("migration-test", cursor.getString(0))
            assertEquals(60L, cursor.getLong(1))
        }
        db.close()
    }
}

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
class Migration_42_43_Test {
    private val testDb = "migration-42-43-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate42To43_addsScopedRelationProvenanceAndRetentionState() {
        val old = helper.createDatabase(testDb, 42)
        old.execSQL(
            "INSERT INTO MemoryEntity(id,assistant_id,content,title,updated_at_ms,importance," +
                "created_at_ms,last_accessed_at_ms,expires_at_ms,memory_kind,confidence,tags_json," +
                "tags_search,content_hash,source_type,source_conversation_id,source_message_ids_json," +
                "lifecycle_status,approval_source,revision,origin_assistant_id,attribution," +
                "truth_status,occurred_at_ms,participants_json,outcome) VALUES(" +
                "1,'scope','source',NULL,1,0.5,1,NULL,NULL,'OTHER',1.0,'[]','','hash-1'," +
                "'LEGACY',NULL,'[]','ACTIVE','LEGACY',1,NULL,'UNKNOWN','CONFIRMED',NULL,'[]',NULL)",
        )
        old.execSQL(
            "INSERT INTO MemoryEntity(id,assistant_id,content,title,updated_at_ms,importance," +
                "created_at_ms,last_accessed_at_ms,expires_at_ms,memory_kind,confidence,tags_json," +
                "tags_search,content_hash,source_type,source_conversation_id,source_message_ids_json," +
                "lifecycle_status,approval_source,revision,origin_assistant_id,attribution," +
                "truth_status,occurred_at_ms,participants_json,outcome) VALUES(" +
                "2,'scope','target',NULL,1,0.5,1,NULL,NULL,'OTHER',1.0,'[]','','hash-2'," +
                "'LEGACY',NULL,'[]','ACTIVE','LEGACY',1,NULL,'UNKNOWN','CONFIRMED',NULL,'[]',NULL)",
        )
        old.execSQL(
            "INSERT INTO memory_links(id,source_memory_id,target_memory_id,relation_type,weight," +
                "description,evidence_message_ids_json,created_by_assistant_id,created_at_ms,revision) " +
                "VALUES('legacy-link',1,2,'RELATED_TO',1.0,'legacy','[]','assistant',10,1)",
        )
        old.close()

        val db = helper.runMigrationsAndValidate(testDb, 43, true, MIGRATION_42_43)
        db.query(
            "SELECT scope_id,lifecycle_status,invalidation_reason FROM memory_links " +
                "WHERE id='legacy-link'",
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("scope", cursor.getString(0))
            assertEquals("INVALIDATED", cursor.getString(1))
            assertEquals("MIGRATION_UNVERIFIED", cursor.getString(2))
        }
        listOf("payload_purged_at_ms", "batch_id", "relation_candidate_id", "link_id").forEach { column ->
            val table = when (column) {
                "payload_purged_at_ms" -> "memory_captures"
                "batch_id" -> "memory_candidates"
                else -> "memory_evidence"
            }
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(name) == column) found = true
                assertEquals("missing $table.$column", true, found)
            }
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='memory_link_revisions'",
        ).use { cursor -> assertEquals(true, cursor.moveToFirst()) }
        db.close()
    }
}

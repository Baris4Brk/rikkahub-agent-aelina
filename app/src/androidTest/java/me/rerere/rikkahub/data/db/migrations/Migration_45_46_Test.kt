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
import java.util.UUID

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class Migration_45_46_Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        createAppSQLiteOpenHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrate45To46_preservesObserverRowsAndAddsFinalP1AuthoritySchema() {
        val old = helper.createDatabase("migration-45-46-test", 45)
        old.execSQL(
            "INSERT INTO memory_scope_state(" +
                "scope_id, memory_epoch, observer_checkpoint_epoch, updated_at_ms) " +
                "VALUES('scope-a', 3, 2, 10)",
        )
        old.execSQL(
            "INSERT INTO memory_scope_changes(" +
                "scope_id, memory_epoch, entity_kind, entity_id, operation, reason_code, " +
                "created_at_ms) VALUES('scope-a', 3, 'MEMORY', '1', 'UPDATE', " +
                "'AUTHORITY_CHANGED', 11)",
        )
        old.execSQL(
            "INSERT INTO dream_runs(run_id, scope_id, mode, base_memory_epoch, " +
                "base_observer_checkpoint_epoch, created_at_ms, updated_at_ms) " +
                "VALUES('run-a', 'scope-a', 'INCREMENTAL', 3, 2, 12, 12)",
        )
        old.execSQL(
            "INSERT INTO MemoryEntity(id, assistant_id, content, revision, content_hash) " +
                "VALUES(1, 'scope-a', 'private source text', 1, 'memory-hash')",
        )
        old.execSQL(
            "INSERT INTO memory_revisions(id, memory_id, revision, operation, actor, " +
                "created_at_ms) VALUES('memory-revision-1', 1, 1, 'CREATE', 'SYSTEM', 9)",
        )
        old.execSQL(
            "INSERT INTO pending_chat_commands(id, schemaVersion, conversationId, type, " +
                "payloadJson, state, priority, sequence, idempotencyKey, attempt, createdAt) " +
                "VALUES('command-a', 1, 'conversation-a', 'send_message', '{}', 'PENDING', " +
                "0, 1, 'command-a', 0, 8)",
        )
        old.execSQL(
            "INSERT INTO execution_records(id, trace_id, subject_id, subject_type, origin, " +
                "capability_keys, resource_summary, runtime, status, created_at_ms, " +
                "updated_at_ms) VALUES('execution-a', 'trace-a', 'subject-a', " +
                "'LOCAL_ASSISTANT', 'MODEL_TOOL', '', 'tool', 'LOCAL_TOOL', 'queued', 7, 7)",
        )
        old.close()

        val db = helper.runMigrationsAndValidate(
            "migration-45-46-test",
            46,
            true,
            MIGRATION_45_46,
        )

        assertEquals(
            setOf(
                "dream_claims",
                "dream_claim_versions",
                "dream_claim_version_sources",
                "dream_snapshots",
            ),
            synthesisTables(db),
        )
        db.query(
            "SELECT memory_epoch, observer_checkpoint_epoch, dream_state_revision, " +
                "last_applied_memory_epoch, active_snapshot_id, last_full_rebuild_at_ms " +
                "FROM memory_scope_state WHERE scope_id = 'scope-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
            assertEquals(2L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals(0L, cursor.getLong(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
        }
        db.query(
            "SELECT base_dream_revision, source_timezone_id, model_identity_digest, input_memory_count, " +
                "output_manifest_hash FROM dream_runs WHERE run_id = 'run-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        listOf(
            "index_memory_scope_state_active_snapshot_id",
            "index_dream_claims_scope_id_claim_key",
            "index_dream_claims_scope_id_state_updated_at_ms",
            "index_dream_claims_scope_id_last_validated_memory_epoch",
            "index_dream_claim_version_sources_memory_id_memory_revision",
            "index_dream_claim_version_sources_claim_id_claim_revision",
            "index_dream_claim_version_sources_memory_evidence_id",
            "index_dream_snapshots_scope_id_snapshot_revision",
            "index_dream_snapshots_scope_id_status_created_at_ms",
            "index_learning_outbox_event_id",
            "index_learning_outbox_stream_id_seq",
            "index_learning_outbox_event_type",
            "index_learning_outbox_source_type_source_id",
            "index_pending_chat_commands_completionKind_finishedAt",
            "index_pending_chat_commands_resultAssistantMessageId_resultAssistantMessageRevision",
            "idx_execution_records_tool_call",
            "idx_execution_records_tool_schema",
            "idx_execution_records_owning_message",
            "idx_learning_conversation_source_id",
            "idx_learning_conversation_source_scope_state_updated",
            "idx_learning_conversation_source_branch_head",
            "idx_learning_conversation_source_scope_scan",
            "idx_learning_message_source_id",
            "idx_learning_message_source_conversation_state",
            "idx_learning_message_source_conversation_revision",
            "idx_learning_message_source_conversation_scan",
        ).forEach { assertIndexExists(db, it) }

        assertEquals(
            setOf(
                "learning_conversation_source_authority",
                "learning_message_source_authority",
            ),
            learningSourceAuthorityTables(db),
        )

        assertLearningSchema(db)
        db.query(
            "SELECT assistantIdSnapshot, lineageId, parentCommandId, branchAnchorMessageId, " +
                "branchAnchorMessageRevision, conversationSourceRevision, completionKind, resultAssistantMessageId, " +
                "resultAssistantMessageRevision, stateVersion FROM pending_chat_commands " +
                "WHERE id = 'command-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertTrue(cursor.isNull(8))
            assertEquals(0L, cursor.getLong(9))
        }
        db.query(
            "SELECT learning_scope_kind, learning_scope_id, tool_call_id, tool_name, " +
                "tool_schema_fingerprint, owning_assistant_message_id, " +
                "owning_assistant_message_revision FROM execution_records " +
                "WHERE id = 'execution-a'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
        }

        // The additive migration is safe to retry without rotating or duplicating the stream.
        val originalStream = learningStreamId(db)
        MIGRATION_45_46.migrate(db)
        assertEquals(originalStream, learningStreamId(db))
        assertEquals(1, count(db, "learning_outbox"))

        insertDerivedRows(db)
        assertTrue(
            "an exact authority revision is pinned until the derived source row is removed",
            runCatching {
                db.execSQL(
                    "DELETE FROM memory_revisions " +
                        "WHERE memory_id = 1 AND revision = 1",
                )
            }.isFailure,
        )
        assertTrue(
            "physical Memory deletion cannot bypass child-first privacy scrubbing",
            runCatching { db.execSQL("DELETE FROM MemoryEntity WHERE id = 1") }.isFailure,
        )
        assertNoForeignKey(db, "memory_scope_state", "dream_snapshots")
        assertNoForeignKeys(db, "learning_conversation_source_authority")
        assertNoForeignKeys(db, "learning_message_source_authority")
        assertForeignKey(db, "dream_claims", "memory_scope_state", "CASCADE")
        assertForeignKey(db, "dream_claim_versions", "dream_claims", "CASCADE")
        assertForeignKey(db, "dream_claim_version_sources", "MemoryEntity", "RESTRICT")
        assertForeignKey(db, "dream_claim_version_sources", "memory_revisions", "RESTRICT")
        assertForeignKey(db, "dream_snapshots", "memory_scope_state", "CASCADE")
        db.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }

        db.execSQL("DELETE FROM dream_claims WHERE claim_id = 'claim-a'")
        assertEquals(0, count(db, "dream_claim_versions"))
        assertEquals(0, count(db, "dream_claim_version_sources"))
        db.execSQL("DELETE FROM memory_revisions WHERE memory_id = 1 AND revision = 1")
        db.execSQL("DELETE FROM MemoryEntity WHERE id = 1")
        db.execSQL("DELETE FROM memory_scope_state WHERE scope_id = 'scope-a'")
        assertEquals(0, count(db, "dream_snapshots"))
        assertEquals(0, count(db, "dream_runs"))
        assertEquals(0, count(db, "memory_scope_changes"))
        db.close()
    }

    @Test
    fun migrationChains_43To44To45To46_and44To45To46_validate() {
        helper.createDatabase("migration-43-46-chain-test", 43).close()
        helper.runMigrationsAndValidate(
            "migration-43-46-chain-test",
            46,
            true,
            MIGRATION_43_44,
            MIGRATION_44_45,
            MIGRATION_45_46,
        ).close()

        helper.createDatabase("migration-44-46-chain-test", 44).close()
        helper.runMigrationsAndValidate(
            "migration-44-46-chain-test",
            46,
            true,
            MIGRATION_44_45,
            MIGRATION_45_46,
        ).close()
    }

    @Test
    fun migrate45To46_refusesBusinessRowsWithoutSentinel() {
        val old = helper.createDatabase("migration-45-46-missing-sentinel-test", 45)
        old.execSQL(LEARNING_V46_OUTBOX_TABLE_SQL)
        old.execSQL(
            "INSERT INTO learning_outbox(stream_id, event_id, event_type, " +
                "event_schema_version, created_at_ms) VALUES(" +
                "'10000000-0000-0000-0000-000000000001', 'event-a', " +
                "'COMMAND_ADMITTED', 1, 1)",
        )
        old.close()

        assertTrue(
            runCatching {
                helper.runMigrationsAndValidate(
                    "migration-45-46-missing-sentinel-test",
                    46,
                    true,
                    MIGRATION_45_46,
                )
            }.isFailure,
        )
    }

    @Test
    fun migrate45To46_refusesMultipleSentinelsAndMixedStreams() {
        val old = helper.createDatabase("migration-45-46-mixed-stream-test", 45)
        old.execSQL(LEARNING_V46_OUTBOX_TABLE_SQL)
        old.execSQL(
            "INSERT INTO learning_outbox(stream_id, event_id, event_type, " +
                "event_schema_version, created_at_ms) VALUES(" +
                "'10000000-0000-0000-0000-000000000001', " +
                "'$LEARNING_V46_STREAM_INIT_EVENT_ID', 'STREAM_INIT', 1, 1)",
        )
        old.execSQL(
            "INSERT INTO learning_outbox(stream_id, event_id, event_type, " +
                "event_schema_version, created_at_ms) VALUES(" +
                "'20000000-0000-0000-0000-000000000002', 'second-stream-init', " +
                "'STREAM_INIT', 1, 1)",
        )
        old.close()

        assertTrue(
            runCatching {
                helper.runMigrationsAndValidate(
                    "migration-45-46-mixed-stream-test",
                    46,
                    true,
                    MIGRATION_45_46,
                )
            }.isFailure,
        )
    }

    private fun insertDerivedRows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO dream_claims(claim_id, scope_id, claim_revision, claim_key, " +
                "storage_class, epistemic_type, title, statement, state, confidence, " +
                "temporal_state, learned_at_ms, source_timezone, claim_hash, " +
                "created_by_run_id, last_validated_memory_epoch, created_at_ms, updated_at_ms) " +
                "VALUES('claim-a', 'scope-a', 1, 'project/a', 'EPISODIC', 'PROJECT_STATE', " +
                "'title', 'derived private text', 'ACTIVE_CONTEXTUAL', 0.9, 'CURRENT', 10, " +
                "'Asia/Shanghai', 'claim-hash', 'run-a', 3, 13, 13)",
        )
        db.execSQL(
            "INSERT INTO dream_claim_versions(claim_id, claim_revision, " +
                "canonical_claim_json, content_hash, source_manifest_hash, reason_code, " +
                "created_by_run_id, created_at_ms) VALUES('claim-a', 1, " +
                "'{\"statement\":\"derived private text\"}', 'version-hash', " +
                "'source-manifest-hash', 'CREATED', 'run-a', 13)",
        )
        db.execSQL(
            "INSERT INTO dream_claim_version_sources(claim_id, claim_revision, memory_id, " +
                "memory_revision, memory_semantic_hash, support_type, created_at_ms) " +
                "VALUES('claim-a', 1, 1, 1, 'memory-hash', 'SUPPORTS', 13)",
        )
        db.execSQL(
            "INSERT INTO dream_snapshots(snapshot_id, scope_id, snapshot_revision, " +
                "source_memory_epoch, committed_dream_revision, status, canonical_payload_json, " +
                "payload_sha256, compiler_revision, estimated_tokens, claim_count, " +
                "created_by_run_id, created_at_ms, reason_code) VALUES('snapshot-a', 'scope-a', " +
                "1, 3, 1, 'ACTIVE', '{\"claims\":[\"claim-a:1\"]}', 'payload-hash', " +
                "'compiler-v1', 10, 1, 'run-a', 13, 'COMMITTED')",
        )
    }

    private fun synthesisTables(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN (" +
                "'dream_claims', 'dream_claim_versions', " +
                "'dream_claim_version_sources', 'dream_snapshots')",
        ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun learningSourceAuthorityTables(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN (" +
                "'learning_conversation_source_authority', " +
                "'learning_message_source_authority')",
        ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun assertIndexExists(db: SupportSQLiteDatabase, index: String) {
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(index),
        ).use { cursor -> assertTrue("missing index $index", cursor.moveToFirst()) }
    }

    private fun assertLearningSchema(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT event_id, event_type, event_schema_version, stream_id, source_id, " +
                "previous_source_revision, source_state, branch_anchor_message_revision, " +
                "conversation_source_revision, completion_kind, tool_name, " +
                "tool_schema_fingerprint, message_revision, " +
                "occurred_at_ms FROM learning_outbox",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(LEARNING_V46_STREAM_INIT_EVENT_ID, cursor.getString(0))
            assertEquals("STREAM_INIT", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            UUID.fromString(cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
            assertTrue(cursor.isNull(7))
            assertTrue(cursor.isNull(8))
            assertTrue(cursor.isNull(9))
            assertTrue(cursor.isNull(10))
            assertTrue(cursor.isNull(11))
            assertTrue(cursor.isNull(12))
            assertTrue(cursor.isNull(13))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun learningStreamId(db: SupportSQLiteDatabase): String =
        db.query("SELECT stream_id FROM learning_outbox WHERE event_type = 'STREAM_INIT'").use {
            assertTrue(it.moveToFirst())
            it.getString(0)
        }

    private fun assertForeignKey(
        db: SupportSQLiteDatabase,
        childTable: String,
        parentTable: String,
        onDelete: String,
    ) {
        var found = false
        db.query("PRAGMA foreign_key_list(`$childTable`)").use { cursor ->
            val table = cursor.getColumnIndexOrThrow("table")
            val action = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) {
                if (cursor.getString(table) == parentTable && cursor.getString(action) == onDelete) {
                    found = true
                }
            }
        }
        assertTrue("missing $childTable -> $parentTable ON DELETE $onDelete", found)
    }

    private fun assertNoForeignKey(
        db: SupportSQLiteDatabase,
        childTable: String,
        parentTable: String,
    ) {
        db.query("PRAGMA foreign_key_list(`$childTable`)").use { cursor ->
            val table = cursor.getColumnIndexOrThrow("table")
            while (cursor.moveToNext()) {
                assertFalse(cursor.getString(table) == parentTable)
            }
        }
    }

    private fun assertNoForeignKeys(db: SupportSQLiteDatabase, table: String) {
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            assertFalse("$table must retain tombstones without a parent FK", cursor.moveToFirst())
        }
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
}

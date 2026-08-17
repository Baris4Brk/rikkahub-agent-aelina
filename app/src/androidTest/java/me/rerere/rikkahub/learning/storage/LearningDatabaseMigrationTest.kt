package me.rerere.rikkahub.learning.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningDatabaseMigrationTest {
    private val testDb = "learning-migration-1-9-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LearningDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationOneThroughNinePreservesTransportAndAddsCuratorThenUtilityLedgers() {
        val db = helper.createDatabase(testDb, 1)
        db.execSQL(
            "INSERT INTO learning_stream_checkpoints(stream_id, last_contiguous_seq, " +
                "last_seen_head_seq, replay_generation, reset_reason, bootstrap_state, " +
                "bootstrap_head_seq, coverage_start_ms, command_coverage_start_ms, " +
                "execution_coverage_start_ms, updated_at_ms) VALUES(" +
                "'00000000-0000-0000-0000-000000000001', 0, 1, 0, 'NEW_STREAM', " +
                "'REQUIRED', 1, NULL, NULL, NULL, 1)",
        )

        LEARNING_MIGRATION_1_2.migrate(db)
        assertEquals(
            setOf(
                "learning_episodes",
                "learning_trace_features",
                "learning_episode_lessons",
                "learning_reward_windows",
                "learning_source_validity",
            ),
            tablesWithPrefix(db, listOf("learning_episodes", "learning_trace", "learning_episode_lessons", "learning_reward", "learning_source")),
        )
        assertFalse(tableExists(db, "learning_policies"))
        db.execSQL("PRAGMA user_version = 2")

        LEARNING_MIGRATION_2_3.migrate(db)
        assertTrue(tableExists(db, "learning_policies"))
        assertTrue(tableExists(db, "policy_evidence"))
        assertTrue(tableExists(db, "policy_revisions"))
        assertTrue(tableExists(db, "policy_lineage"))
        db.execSQL(
            "INSERT INTO learning_episodes(id, stream_id, replay_generation, scope_kind, scope_id, " +
                "conversation_id, conversation_revision, root_command_id, root_command_revision, " +
                "final_command_id, final_command_revision, lineage_id, branch_anchor_message_id, " +
                "branch_anchor_message_revision, result_assistant_message_id, " +
                "result_assistant_message_revision, generation_run_id, execution_id, task_signature, " +
                "status, boundary_reason, revision, started_at_ms, finalized_at_ms, created_at_ms, " +
                "updated_at_ms) VALUES('legacy-episode', " +
                "'00000000-0000-0000-0000-000000000001', 0, 'ASSISTANT', " +
                "'00000000-0000-0000-0000-000000000002', 'conversation', NULL, 'command', 1, " +
                "'command', 1, 'lineage', 'message', 1, 'message', 1, NULL, NULL, " +
                "'task-signature-v1', 'SUCCESS', 'FINAL_SAVED', 1, 1, 1, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO learning_reward_windows(id, episode_id, scope_kind, scope_id, opened_at_ms, " +
                "close_after_ms, state, goal_knowledge, goal_value, goal_unknown_reason, " +
                "goal_evidence_sha256, process_knowledge, process_value, process_unknown_reason, " +
                "process_evidence_sha256, user_knowledge, user_value, user_unknown_reason, " +
                "user_evidence_sha256, weak_label, reward_config_identity, closed_at_ms, " +
                "updated_at_ms) VALUES('legacy-window', 'legacy-episode', 'ASSISTANT', " +
                "'00000000-0000-0000-0000-000000000002', 1, 2, 'CLOSED', 'UNKNOWN', NULL, " +
                "'NO_SIGNAL', NULL, 'UNKNOWN', NULL, 'NO_SIGNAL', NULL, 'UNKNOWN', NULL, " +
                "'NO_SIGNAL', NULL, NULL, 'reward-config-v1', 2, 2)",
        )
        // A v3 provider job does not contain enough information to fabricate a v4 request/runtime
        // manifest. It must be cancelled rather than becoming remotely executable after upgrade.
        db.execSQL(
            "INSERT INTO learning_jobs(id, job_type, job_schema_version, dedupe_key, stream_id, " +
                "source_event_id, scope_kind, scope_id, state, priority, attempts, max_attempts, " +
                "not_before_ms, lease_process_session_id, lease_worker_id, lease_generation, " +
                "lease_until_ms, last_error_code, created_at_ms, updated_at_ms, finished_at_ms, " +
                "replay_generation, algorithm_identity, prompt_identity, provider_kind_identity, " +
                "model_identity, provider_identity, provider_configuration_identity, " +
                "provider_config_generation, source_schema_identity, toolset_identity, " +
                "output_schema_identity) VALUES(" +
                "'legacy-reflect', 'REFLECT_EPISODE_V1', 1, 'legacy-reflect-dedupe', " +
                "'00000000-0000-0000-0000-000000000001', 'legacy-event', 'ASSISTANT', " +
                "'00000000-0000-0000-0000-000000000002', 'PENDING', 0, 0, 5, 1, NULL, " +
                "NULL, 0, NULL, NULL, 1, 1, NULL, 0, 'algorithm-v1', 'prompt-v1', " +
                "'local_litert', '${"a".repeat(64)}', '${"b".repeat(64)}', " +
                "'${"c".repeat(64)}', 0, 'source-v1', 'tools-v1', 'output-v1')",
        )
        db.query("SELECT COUNT(*) FROM learning_stream_checkpoints").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        db.execSQL("PRAGMA user_version = 3")

        LEARNING_MIGRATION_3_4.migrate(db)
        listOf(
            "learning_provider_config_cohorts",
            "learning_provider_job_manifests",
            "learning_provider_attempts",
            "learning_reward_signals",
            "policy_reward_evidence",
        ).forEach { table -> assertTrue(tableExists(db, table)) }
        db.query(
            "SELECT state, last_error_code, lease_process_session_id, lease_worker_id, " +
                "lease_until_ms, finished_at_ms FROM learning_jobs WHERE id = 'legacy-reflect'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CANCELLED", cursor.getString(0))
            assertEquals("INVALID_JOB_SPEC", cursor.getString(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertEquals(1L, cursor.getLong(5))
        }
        db.query(
            "SELECT revision, signal_set_sha256, authority_outcome, last_signal_at_ms " +
                "FROM learning_reward_windows LIMIT 1",
        ).use { cursor ->
            // Empty databases are valid; defaults are additionally asserted by JVM contract and
            // Room's exported v4 schema.
            if (cursor.moveToFirst()) {
                assertEquals(1L, cursor.getLong(0))
                assertEquals(EMPTY_REWARD_SIGNAL_SET_SHA256, cursor.getString(1))
                assertEquals("UNKNOWN", cursor.getString(2))
                assertTrue(cursor.isNull(3))
            }
        }
        db.query(
            "SELECT source_authority_coverage_start_ms, feedback_coverage_start_ms " +
                "FROM learning_stream_checkpoints LIMIT 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.execSQL("PRAGMA user_version = 4")

        LEARNING_MIGRATION_4_5.migrate(db)
        db.query("PRAGMA table_info(`learning_stream_checkpoints`)").use { cursor ->
            var foundCursor = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) ==
                    "reconciliation_cursor_v1_json"
                ) {
                    foundCursor = true
                    assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("notnull")))
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue(foundCursor)
        }
        db.query(
            "SELECT reconciliation_cursor_v1_json FROM learning_stream_checkpoints LIMIT 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        db.execSQL(
            "INSERT INTO learning_policies(id, scope_kind, scope_id, task_signature, policy_type, " +
                "trigger_summary, procedure_summary, verification_summary, boundary_summary, " +
                "failure_mode_summary, state_version, artifact_sha256, compiler_abi, status, " +
                "source_valid, schema_valid, stale_reason, distinct_episode_support, " +
                "positive_episode_count, negative_episode_count, usage_count, confidence, " +
                "observed_utility_delta, utility_uncertainty, producer_model_identity, " +
                "producer_provider_identity, producer_provider_kind, " +
                "producer_configuration_identity, producer_config_generation, " +
                "producer_prompt_identity, producer_template_identity, producer_schema_identity, " +
                "created_at_ms, updated_at_ms, last_used_at_ms) VALUES(" +
                "'legacy-stale-policy', 'ASSISTANT', " +
                "'00000000-0000-0000-0000-000000000002', 'task-signature-v1', 'PROCEDURE', " +
                "'trigger', 'procedure', 'verification', 'boundary', 'failure', 1, " +
                "'${"f".repeat(64)}', 'policy-compiler-v1', 'STALE', 0, 1, " +
                "'SOURCE_INVALIDATED', 1, 1, 0, 0, 0.5, NULL, NULL, '${"1".repeat(64)}', " +
                "'${"2".repeat(64)}', 'local_litert', '${"3".repeat(64)}', 1, " +
                "'prompt-v1', 'template-v1', 'schema-v1', 1, 1, NULL)",
        )
        db.execSQL("PRAGMA user_version = 5")

        LEARNING_MIGRATION_5_6.migrate(db)
        assertTrue(tableExists(db, "learning_policy_exposures"))
        assertTrue(tableExists(db, "learning_policy_exposure_items"))
        db.query(
            "SELECT status, content_revision, schema_valid, stale_reason, " +
                "applicable_tool_schemas_wire, applicable_model_identity_wire, " +
                "applicable_provider_identity_wire FROM learning_policies " +
                "WHERE id = 'legacy-stale-policy'",
        ).use {
            cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("STALE_SCHEMA", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals(POLICY_APPLICABILITY_UNPROVEN_V5_REASON, cursor.getString(3))
            assertEquals(POLICY_TOOL_APPLICABILITY_UNPROVEN_V5, cursor.getString(4))
            assertEquals(POLICY_IDENTITY_APPLICABILITY_ANY, cursor.getString(5))
            assertEquals(POLICY_IDENTITY_APPLICABILITY_ANY, cursor.getString(6))
        }
        assertTrue(
            indexExists(
                db,
                "index_learning_policy_exposures_stream_id_episode_id_logical_run_id_" +
                    "attempt_ordinal_policy_set_digest",
                unique = true,
            ),
        )
        assertTrue(
            indexExists(
                db,
                "index_learning_policy_exposures_scope_kind_scope_id_task_signature_" +
                    "furthest_state",
                unique = false,
            ),
        )
        db.execSQL(
            "INSERT INTO learning_policy_exposures(id, stream_id, replay_generation, episode_id, " +
                "logical_run_id, attempt_ordinal, scope_kind, scope_id, task_signature, " +
                "policy_set_digest, treatment_arm, model_identity, provider_identity, " +
                "provider_generation, toolset_fingerprint, context_compiler_abi, state_version, " +
                "furthest_state, retrieved_at_ms, compiled_at_ms, injected_at_ms, " +
                "host_dispatched_at_ms, first_progress_at_ms, response_finished_at_ms, " +
                "outcome_linked_at_ms, terminal_outcome, terminal_at_ms, outcome_source_type, " +
                "outcome_source_id, outcome_source_revision, attribution_state, created_at_ms, " +
                "updated_at_ms) VALUES('exposure-v1:${"d".repeat(64)}', " +
                "'00000000-0000-0000-0000-000000000001', 0, 'legacy-episode', " +
                "'00000000-0000-0000-0000-000000000003', 1, 'ASSISTANT', " +
                "'00000000-0000-0000-0000-000000000002', 'task-signature-v1', " +
                "'${"a".repeat(64)}', 'TREATMENT', 'model-v1', 'provider-v1', 1, " +
                "'${"b".repeat(64)}', 'recall-compiler-v1', 0, 'RETRIEVED', 2, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'UNKNOWN', 2, 2)",
        )
        db.execSQL(
            "INSERT INTO learning_policy_exposure_items(exposure_id, policy_id, policy_revision, " +
                "artifact_sha256, rank, estimated_tokens, drop_reason, retrieved_at_ms, " +
                "compiled_at_ms, injected_at_ms) VALUES('exposure-v1:${"d".repeat(64)}', " +
                "'policy-v1:${"e".repeat(64)}', 1, '${"c".repeat(64)}', 1, 32, NULL, 2, " +
                "NULL, NULL)",
        )
        db.query(
            "SELECT COUNT(*), MIN(state_version), MIN(attribution_state) " +
                "FROM learning_policy_exposures",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals("UNKNOWN", cursor.getString(2))
        }
        assertEquals(
            "RESTRICT",
            foreignKeyDeleteAction(
                db = db,
                table = "learning_policy_exposures",
                referencedTable = "learning_episodes",
            ),
        )
        assertEquals(
            "CASCADE",
            foreignKeyDeleteAction(
                db = db,
                table = "learning_policy_exposure_items",
                referencedTable = "learning_policy_exposures",
            ),
        )
        db.execSQL("PRAGMA user_version = 6")

        LEARNING_MIGRATION_6_7.migrate(db)
        assertTrue(tableExists(db, "learned_workflow_candidates"))
        assertTrue(tableExists(db, "learned_workflow_candidate_revisions"))
        assertEquals(
            "RESTRICT",
            foreignKeyDeleteAction(
                db = db,
                table = "learned_workflow_candidates",
                referencedTable = "learning_policies",
            ),
        )
        assertEquals(
            "CASCADE",
            foreignKeyDeleteAction(
                db = db,
                table = "learned_workflow_candidate_revisions",
                referencedTable = "learned_workflow_candidates",
            ),
        )
        db.execSQL("PRAGMA user_version = 7")

        LEARNING_MIGRATION_7_8.migrate(db)
        listOf(
            "learning_policy_shadow_observations",
            "learning_policy_shadow_observation_items",
            "curator_delta_candidates",
            "curator_delta_revisions",
            "curator_delta_lineage",
        ).forEach { table -> assertTrue(tableExists(db, table)) }
        assertEquals(
            "CASCADE",
            foreignKeyDeleteAction(
                db = db,
                table = "learning_policy_shadow_observation_items",
                referencedTable = "learning_policy_shadow_observations",
            ),
        )
        assertFalse(tableExists(db, "learning_observed_utility_assignments"))
        assertFalse(tableExists(db, "learning_observed_utility_outcomes"))
        assertFalse(tableExists(db, "learning_observed_utility_evaluation_receipts"))
        assertEquals(
            "CASCADE",
            foreignKeyDeleteAction(
                db = db,
                table = "learning_policy_shadow_observation_items",
                referencedTable = "learning_policies",
            ),
        )
        assertEquals(
            "CASCADE",
            foreignKeyDeleteAction(
                db = db,
                table = "curator_delta_revisions",
                referencedTable = "curator_delta_candidates",
            ),
        )
        assertEquals(
            "CASCADE",
            foreignKeyDeleteAction(
                db = db,
                table = "curator_delta_lineage",
                referencedTable = "curator_delta_candidates",
            ),
        )
        db.query("SELECT COUNT(*) FROM curator_delta_candidates").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
        db.execSQL("PRAGMA user_version = 8")
        db.close()

        // Let Room execute the final production migration so MigrationTestHelper also installs the
        // v9 room_master_table identity. Manually setting user_version to 9 would retain the v1
        // identity written by createDatabase(), bypassing the production onUpgrade path.
        helper.runMigrationsAndValidate(
            testDb,
            9,
            true,
            LEARNING_MIGRATION_8_9,
        ).use { migrated ->
            listOf(
                "learning_observed_utility_assignments",
                "learning_observed_utility_outcomes",
                "learning_observed_utility_evaluation_receipts",
            ).forEach { table -> assertTrue(tableExists(migrated, table)) }
            assertEquals(
                "RESTRICT",
                foreignKeyDeleteAction(
                    db = migrated,
                    table = "learning_observed_utility_assignments",
                    referencedTable = "learning_episodes",
                ),
            )
            assertEquals(
                "CASCADE",
                foreignKeyDeleteAction(
                    db = migrated,
                    table = "learning_observed_utility_outcomes",
                    referencedTable = "learning_observed_utility_assignments",
                ),
            )
        }
    }

    @Test
    fun exportedVersionTwoSchemaValidatesThenRunsTheRegisteredChainToNine() {
        val name = "learning-migration-exported-2-9-test"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO learning_stream_checkpoints(stream_id, last_contiguous_seq, " +
                    "last_seen_head_seq, replay_generation, reset_reason, bootstrap_state, " +
                    "bootstrap_head_seq, coverage_start_ms, command_coverage_start_ms, " +
                    "execution_coverage_start_ms, updated_at_ms) VALUES(" +
                    "'00000000-0000-0000-0000-000000000011', 0, 1, 0, 'NEW_STREAM', " +
                    "'REQUIRED', 1, NULL, NULL, NULL, 11)",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            2,
            true,
            LEARNING_MIGRATION_1_2,
        ).use { db ->
            assertTrue(tableExists(db, "learning_episodes"))
            assertTrue(tableExists(db, "learning_reward_windows"))
            assertFalse(tableExists(db, "learning_policies"))
            db.query("SELECT updated_at_ms FROM learning_stream_checkpoints").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11L, cursor.getLong(0))
                assertFalse(cursor.moveToNext())
            }
        }

        helper.runMigrationsAndValidate(
            name,
            9,
            true,
            LEARNING_MIGRATION_2_3,
            LEARNING_MIGRATION_3_4,
            LEARNING_MIGRATION_4_5,
            LEARNING_MIGRATION_5_6,
            LEARNING_MIGRATION_6_7,
            LEARNING_MIGRATION_7_8,
            LEARNING_MIGRATION_8_9,
        ).use { db ->
            assertTrue(tableExists(db, "learning_policies"))
            assertTrue(tableExists(db, "learning_policy_shadow_observations"))
            assertTrue(tableExists(db, "curator_delta_candidates"))
            assertTrue(tableExists(db, "learning_observed_utility_assignments"))
            assertTrue(tableExists(db, "learning_observed_utility_outcomes"))
            assertTrue(tableExists(db, "learning_observed_utility_evaluation_receipts"))
            db.query("SELECT updated_at_ms FROM learning_stream_checkpoints").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(11L, cursor.getLong(0))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun directSevenToEightValidatesTheFrozenPreCuratorSchemaAndPreservesRows() {
        val name = "learning-migration-7-8-direct-test"
        helper.createDatabase(name, 7).use { db ->
            db.execSQL(
                "INSERT INTO learning_stream_checkpoints(stream_id, last_contiguous_seq, " +
                    "last_seen_head_seq, replay_generation, reset_reason, bootstrap_state, " +
                    "bootstrap_head_seq, coverage_start_ms, command_coverage_start_ms, " +
                    "execution_coverage_start_ms, source_authority_coverage_start_ms, " +
                    "feedback_coverage_start_ms, reconciliation_cursor_v1_json, updated_at_ms) " +
                    "VALUES('00000000-0000-0000-0000-000000000017', 7, 7, 0, " +
                    "'CONTIGUOUS', 'COMPLETE', 7, 1, 1, 1, 1, 1, NULL, 17)",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            8,
            true,
            LEARNING_MIGRATION_7_8,
        ).use { db ->
            db.query(
                "SELECT last_contiguous_seq, updated_at_ms FROM learning_stream_checkpoints " +
                    "WHERE stream_id = '00000000-0000-0000-0000-000000000017'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(17L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
            listOf(
                "learning_policy_shadow_observations",
                "learning_policy_shadow_observation_items",
                "curator_delta_candidates",
                "curator_delta_revisions",
                "curator_delta_lineage",
            ).forEach { table -> assertTrue(tableExists(db, table)) }
        }
    }

    @Test
    fun directEightToNinePreservesFrozenV8RowsAndAddsOnlyObservedUtilityTables() {
        val name = "learning-migration-8-9-direct-test"
        helper.createDatabase(name, 8).use { db ->
            db.execSQL(
                "INSERT INTO learning_stream_checkpoints(stream_id, last_contiguous_seq, " +
                    "last_seen_head_seq, replay_generation, reset_reason, bootstrap_state, " +
                    "bootstrap_head_seq, coverage_start_ms, command_coverage_start_ms, " +
                    "execution_coverage_start_ms, source_authority_coverage_start_ms, " +
                    "feedback_coverage_start_ms, reconciliation_cursor_v1_json, updated_at_ms) " +
                    "VALUES('00000000-0000-0000-0000-000000000018', 8, 8, 0, " +
                    "'CONTIGUOUS', 'COMPLETE', 8, 1, 1, 1, 1, 1, NULL, 18)",
            )
            assertFalse(tableExists(db, "learning_observed_utility_assignments"))
        }

        helper.runMigrationsAndValidate(
            name,
            9,
            true,
            LEARNING_MIGRATION_8_9,
        ).use { db ->
            db.query(
                "SELECT last_contiguous_seq, updated_at_ms FROM learning_stream_checkpoints " +
                    "WHERE stream_id = '00000000-0000-0000-0000-000000000018'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(8L, cursor.getLong(0))
                assertEquals(18L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
            listOf(
                "learning_observed_utility_assignments",
                "learning_observed_utility_outcomes",
                "learning_observed_utility_evaluation_receipts",
            ).forEach { table -> assertTrue(tableExists(db, table)) }
            assertEquals(
                "RESTRICT",
                foreignKeyDeleteAction(
                    db,
                    "learning_observed_utility_assignments",
                    "learning_episodes",
                ),
            )
            assertEquals(
                "CASCADE",
                foreignKeyDeleteAction(
                    db,
                    "learning_observed_utility_outcomes",
                    "learning_observed_utility_assignments",
                ),
            )
        }
    }
}

private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
    db.query(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(table),
    ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }

private fun indexExists(
    db: androidx.sqlite.db.SupportSQLiteDatabase,
    name: String,
    unique: Boolean,
): Boolean = db.query(
    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=? " +
        "AND sql ${if (unique) "LIKE '%CREATE UNIQUE INDEX%'" else "LIKE '%CREATE INDEX%'"}",
    arrayOf(name),
).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }

private fun foreignKeyDeleteAction(
    db: androidx.sqlite.db.SupportSQLiteDatabase,
    table: String,
    referencedTable: String,
): String? = db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
    val referencedTableColumn = cursor.getColumnIndexOrThrow("table")
    val onDeleteColumn = cursor.getColumnIndexOrThrow("on_delete")
    while (cursor.moveToNext()) {
        if (cursor.getString(referencedTableColumn) == referencedTable) {
            return@use cursor.getString(onDeleteColumn)
        }
    }
    null
}

private fun tablesWithPrefix(
    db: androidx.sqlite.db.SupportSQLiteDatabase,
    prefixes: List<String>,
): Set<String> = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
    buildSet {
        while (cursor.moveToNext()) {
            val name = cursor.getString(0)
            if (prefixes.any(name::startsWith)) add(name)
        }
    }
}

package me.rerere.rikkahub.learning.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val LEARNING_V2_SCHEMA_SQL: List<String> = listOf(
    "ALTER TABLE `learning_jobs` ADD COLUMN `algorithm_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `prompt_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `provider_kind_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `model_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `provider_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `provider_configuration_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `provider_config_generation` INTEGER",
    "ALTER TABLE `learning_jobs` ADD COLUMN `source_schema_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `toolset_identity` TEXT",
    "ALTER TABLE `learning_jobs` ADD COLUMN `output_schema_identity` TEXT",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `previous_source_revision` INTEGER",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `source_state` TEXT",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `conversation_source_revision` INTEGER",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `branch_anchor_message_revision` INTEGER",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `completion_kind` TEXT",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `tool_name` TEXT",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `tool_schema_fingerprint` TEXT",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `message_revision` INTEGER",
    "CREATE TABLE IF NOT EXISTS `learning_episodes` (" +
        "`id` TEXT NOT NULL, `stream_id` TEXT NOT NULL, `replay_generation` INTEGER NOT NULL, " +
        "`scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, " +
        "`conversation_revision` INTEGER, `root_command_id` TEXT NOT NULL, " +
        "`root_command_revision` INTEGER NOT NULL, `final_command_id` TEXT, " +
        "`final_command_revision` INTEGER, `lineage_id` TEXT NOT NULL, " +
        "`branch_anchor_message_id` TEXT NOT NULL, `branch_anchor_message_revision` INTEGER NOT NULL, " +
        "`result_assistant_message_id` TEXT, `result_assistant_message_revision` INTEGER, " +
        "`generation_run_id` TEXT, `execution_id` TEXT, `task_signature` TEXT NOT NULL, " +
        "`status` TEXT NOT NULL, `boundary_reason` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
        "`started_at_ms` INTEGER NOT NULL, `finalized_at_ms` INTEGER, " +
        "`created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_episodes_stream_id_replay_generation_lineage_id_branch_anchor_message_id` " +
        "ON `learning_episodes` (`stream_id`, `replay_generation`, `lineage_id`, `branch_anchor_message_id`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_episodes_scope_kind_scope_id_status_updated_at_ms` " +
        "ON `learning_episodes` (`scope_kind`, `scope_id`, `status`, `updated_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_episodes_scope_kind_scope_id_task_signature_status` " +
        "ON `learning_episodes` (`scope_kind`, `scope_id`, `task_signature`, `status`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_episodes_conversation_id_branch_anchor_message_id` " +
        "ON `learning_episodes` (`conversation_id`, `branch_anchor_message_id`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_episodes_root_command_id_root_command_revision` " +
        "ON `learning_episodes` (`root_command_id`, `root_command_revision`)",
    "CREATE TABLE IF NOT EXISTS `learning_trace_features` (" +
        "`episode_id` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `source_ordinal` INTEGER NOT NULL, " +
        "`source_type` TEXT NOT NULL, " +
        "`source_id` TEXT NOT NULL, `source_revision` INTEGER, `missing_revision_reason` TEXT, " +
        "`action_type` TEXT NOT NULL, `action_name` TEXT, `tool_schema_fingerprint` TEXT, " +
        "`outcome_class` TEXT NOT NULL, `error_code` TEXT, `state_summary` TEXT, " +
        "`observation_summary` TEXT, `input_token_count` INTEGER, `output_token_count` INTEGER, " +
        "`tool_count` INTEGER, `retry_count` INTEGER, `duration_ms` INTEGER, `alpha` REAL, " +
        "`quality` REAL, `feature_schema_identity` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`episode_id`, `sequence`, `source_ordinal`), FOREIGN KEY(`episode_id`) " +
        "REFERENCES `learning_episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_trace_features_episode_id_source_type_source_id_source_revision` " +
        "ON `learning_trace_features` (`episode_id`, `source_type`, `source_id`, `source_revision`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_trace_features_source_type_source_id_source_revision` " +
        "ON `learning_trace_features` (`source_type`, `source_id`, `source_revision`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_trace_features_outcome_class_error_code` " +
        "ON `learning_trace_features` (`outcome_class`, `error_code`)",
    "CREATE TABLE IF NOT EXISTS `learning_episode_lessons` (" +
        "`episode_id` TEXT NOT NULL, `lesson_version` INTEGER NOT NULL, `scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, `lesson_type` TEXT NOT NULL, `trigger_summary` TEXT NOT NULL, " +
        "`observation_summary` TEXT NOT NULL, `lesson_summary` TEXT NOT NULL, " +
        "`boundary_summary` TEXT NOT NULL, `evidence_manifest_sha256` TEXT NOT NULL, " +
        "`artifact_sha256` TEXT NOT NULL, `producer_provider_identity` TEXT NOT NULL, " +
        "`producer_provider_kind` TEXT NOT NULL, `producer_model_identity` TEXT NOT NULL, " +
        "`producer_configuration_identity` TEXT NOT NULL, " +
        "`producer_config_generation` INTEGER NOT NULL, " +
        "`algorithm_identity` TEXT NOT NULL, `prompt_identity` TEXT NOT NULL, " +
        "`template_identity` TEXT NOT NULL, `schema_identity` TEXT NOT NULL, " +
        "`input_token_count` INTEGER, `output_token_count` INTEGER, `estimated_cost_micros` INTEGER, " +
        "`remote_provider` INTEGER, `state` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`episode_id`, `lesson_version`), " +
        "FOREIGN KEY(`episode_id`) REFERENCES `learning_episodes`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS `index_learning_episode_lessons_episode_id_state_created_at_ms` " +
        "ON `learning_episode_lessons` (`episode_id`, `state`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_episode_lessons_scope_kind_scope_id_state_created_at_ms` " +
        "ON `learning_episode_lessons` (`scope_kind`, `scope_id`, `state`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_episode_lessons_artifact_sha256` " +
        "ON `learning_episode_lessons` (`artifact_sha256`)",
    "CREATE TABLE IF NOT EXISTS `learning_reward_windows` (" +
        "`id` TEXT NOT NULL, `episode_id` TEXT NOT NULL, `scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, `opened_at_ms` INTEGER NOT NULL, `close_after_ms` INTEGER NOT NULL, " +
        "`state` TEXT NOT NULL, `goal_knowledge` TEXT NOT NULL, `goal_value` REAL, " +
        "`goal_unknown_reason` TEXT, `goal_evidence_sha256` TEXT, " +
        "`process_knowledge` TEXT NOT NULL, `process_value` REAL, `process_unknown_reason` TEXT, " +
        "`process_evidence_sha256` TEXT, `user_knowledge` TEXT NOT NULL, `user_value` REAL, " +
        "`user_unknown_reason` TEXT, `user_evidence_sha256` TEXT, `weak_label` REAL, " +
        "`reward_config_identity` TEXT NOT NULL, `closed_at_ms` INTEGER, " +
        "`updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`episode_id`) " +
        "REFERENCES `learning_episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_reward_windows_episode_id` " +
        "ON `learning_reward_windows` (`episode_id`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_reward_windows_state_close_after_ms` " +
        "ON `learning_reward_windows` (`state`, `close_after_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_reward_windows_scope_kind_scope_id_state_close_after_ms` " +
        "ON `learning_reward_windows` (`scope_kind`, `scope_id`, `state`, `close_after_ms`)",
    "CREATE TABLE IF NOT EXISTS `learning_source_validity` (" +
        "`stream_id` TEXT NOT NULL, `scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL, " +
        "`source_type` TEXT NOT NULL, `source_id` TEXT NOT NULL, `source_revision` INTEGER NOT NULL, " +
        "`previous_source_revision` INTEGER, `state` TEXT NOT NULL, `integrity_sha256` TEXT, " +
        "`invalidation_reason` TEXT, `authority_event_id` TEXT NOT NULL, " +
        "`replay_generation` INTEGER NOT NULL, `occurred_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`stream_id`, `replay_generation`, " +
        "`scope_kind`, `scope_id`, `source_type`, `source_id`, " +
        "`source_revision`))",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_source_validity_scope_kind_scope_id_source_type_source_id_state` " +
        "ON `learning_source_validity` (`scope_kind`, `scope_id`, `source_type`, `source_id`, `state`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_source_validity_source_type_source_id_source_revision_state` " +
        "ON `learning_source_validity` (`source_type`, `source_id`, `source_revision`, `state`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_source_validity_state_updated_at_ms` " +
        "ON `learning_source_validity` (`state`, `updated_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_source_validity_authority_event_id` " +
        "ON `learning_source_validity` (`authority_event_id`)",
)

internal val LEARNING_V3_SCHEMA_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `learning_policies` (" +
        "`id` TEXT NOT NULL, `scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL, " +
        "`task_signature` TEXT NOT NULL, `policy_type` TEXT NOT NULL, " +
        "`trigger_summary` TEXT NOT NULL, `procedure_summary` TEXT NOT NULL, " +
        "`verification_summary` TEXT NOT NULL, `boundary_summary` TEXT NOT NULL, " +
        "`failure_mode_summary` TEXT NOT NULL, `state_version` INTEGER NOT NULL, " +
        "`artifact_sha256` TEXT NOT NULL, `compiler_abi` TEXT NOT NULL, `status` TEXT NOT NULL, " +
        "`source_valid` INTEGER NOT NULL, `schema_valid` INTEGER NOT NULL, `stale_reason` TEXT, " +
        "`distinct_episode_support` INTEGER NOT NULL, " +
        "`positive_episode_count` INTEGER NOT NULL, `negative_episode_count` INTEGER NOT NULL, " +
        "`usage_count` INTEGER NOT NULL, `confidence` REAL NOT NULL, `observed_utility_delta` REAL, " +
        "`utility_uncertainty` REAL, `producer_model_identity` TEXT NOT NULL, " +
        "`producer_provider_identity` TEXT NOT NULL, `producer_provider_kind` TEXT NOT NULL, " +
        "`producer_configuration_identity` TEXT NOT NULL, " +
        "`producer_config_generation` INTEGER NOT NULL, " +
        "`producer_prompt_identity` TEXT NOT NULL, `producer_template_identity` TEXT NOT NULL, " +
        "`producer_schema_identity` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, `last_used_at_ms` INTEGER, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_policies_scope_kind_scope_id_status_source_valid_schema_valid_task_signature_updated_at_ms` " +
        "ON `learning_policies` (`scope_kind`, `scope_id`, `status`, `source_valid`, " +
        "`schema_valid`, `task_signature`, `updated_at_ms`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_policies_scope_kind_scope_id_task_signature_artifact_sha256` " +
        "ON `learning_policies` (`scope_kind`, `scope_id`, `task_signature`, `artifact_sha256`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_policies_status_updated_at_ms` " +
        "ON `learning_policies` (`status`, `updated_at_ms`)",
    "CREATE TABLE IF NOT EXISTS `policy_evidence` (" +
        "`policy_id` TEXT NOT NULL, `episode_id` TEXT NOT NULL, `evidence_kind` TEXT NOT NULL, " +
        "`polarity` TEXT NOT NULL, `quality` REAL, `lesson_version` INTEGER NOT NULL, " +
        "`source_type` TEXT NOT NULL, `source_id` TEXT NOT NULL, `source_revision` INTEGER NOT NULL, " +
        "`source_integrity_sha256` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`policy_id`, `episode_id`), " +
        "FOREIGN KEY(`policy_id`) REFERENCES `learning_policies`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`episode_id`) " +
        "REFERENCES `learning_episodes`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS `index_policy_evidence_episode_id` " +
        "ON `policy_evidence` (`episode_id`)",
    "CREATE INDEX IF NOT EXISTS `index_policy_evidence_source_type_source_id_source_revision` " +
        "ON `policy_evidence` (`source_type`, `source_id`, `source_revision`)",
    "CREATE INDEX IF NOT EXISTS `index_policy_evidence_policy_id_polarity_episode_id` " +
        "ON `policy_evidence` (`policy_id`, `polarity`, `episode_id`)",
    "CREATE TABLE IF NOT EXISTS `policy_revisions` (" +
        "`policy_id` TEXT NOT NULL, `revision` INTEGER NOT NULL, `before_snapshot` TEXT, " +
        "`after_snapshot` TEXT NOT NULL, `before_artifact_sha256` TEXT, " +
        "`after_artifact_sha256` TEXT NOT NULL, `reason_code` TEXT NOT NULL, `actor` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`policy_id`, `revision`), " +
        "FOREIGN KEY(`policy_id`) REFERENCES `learning_policies`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS `index_policy_revisions_policy_id_created_at_ms` " +
        "ON `policy_revisions` (`policy_id`, `created_at_ms`)",
    "CREATE TABLE IF NOT EXISTS `policy_lineage` (" +
        "`child_policy_id` TEXT NOT NULL, `parent_policy_id` TEXT NOT NULL, " +
        "`relation_type` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`child_policy_id`, `parent_policy_id`, `relation_type`), " +
        "FOREIGN KEY(`child_policy_id`) REFERENCES `learning_policies`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`parent_policy_id`) " +
        "REFERENCES `learning_policies`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS `index_policy_lineage_child_policy_id` " +
        "ON `policy_lineage` (`child_policy_id`)",
    "CREATE INDEX IF NOT EXISTS `index_policy_lineage_parent_policy_id` " +
        "ON `policy_lineage` (`parent_policy_id`)",
)

val LEARNING_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V2_SCHEMA_SQL.forEach(db::execSQL)
    }
}

val LEARNING_MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V3_SCHEMA_SQL.forEach(db::execSQL)
    }
}

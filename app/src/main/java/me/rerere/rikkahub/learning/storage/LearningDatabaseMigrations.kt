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

/**
 * P1 provider-operation and reward-authority closure.
 *
 * P2 exposure/retrieval accounting was intentionally moved to Learning DB v6: changing that
 * future slice must not weaken the provider side-effect or reward lineage guarantees established
 * here. The migration never fabricates manifests for v3 jobs because their provider-visible bytes
 * and runtime attestation were not durably knowable.
 */
internal val LEARNING_V4_SCHEMA_SQL: List<String> = listOf(
    "ALTER TABLE `learning_stream_checkpoints` ADD COLUMN " +
        "`source_authority_coverage_start_ms` INTEGER",
    "ALTER TABLE `learning_stream_checkpoints` ADD COLUMN " +
        "`feedback_coverage_start_ms` INTEGER",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `reward_dimension` TEXT",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `reward_signal_kind` TEXT",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `reward_value_milli` INTEGER",
    "ALTER TABLE `learning_inbox_events` ADD COLUMN `execution_verification_state` TEXT",
    "ALTER TABLE `learning_reward_windows` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE `learning_reward_windows` ADD COLUMN `signal_set_sha256` TEXT NOT NULL " +
        "DEFAULT '$EMPTY_REWARD_SIGNAL_SET_SHA256'",
    "ALTER TABLE `learning_reward_windows` ADD COLUMN `authority_outcome` TEXT NOT NULL " +
        "DEFAULT 'UNKNOWN'",
    "ALTER TABLE `learning_reward_windows` ADD COLUMN `last_signal_at_ms` INTEGER",
    "ALTER TABLE `learning_reward_windows` ADD COLUMN `goal_signal_kind` TEXT",
    "ALTER TABLE `learning_reward_windows` ADD COLUMN `process_signal_kind` TEXT",
    "ALTER TABLE `learning_reward_windows` ADD COLUMN `user_signal_kind` TEXT",
    "UPDATE `learning_reward_windows` SET `authority_outcome` = 'PENDING' WHERE `state` = 'OPEN'",
    "CREATE TABLE IF NOT EXISTS `learning_provider_config_cohorts` (" +
        "`id` TEXT NOT NULL, `provider_kind` TEXT NOT NULL, " +
        "`provider_identity_sha256` TEXT NOT NULL, `model_identity_sha256` TEXT NOT NULL, " +
        "`configuration_identity_sha256` TEXT NOT NULL, " +
        "`configuration_generation` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`))",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_provider_config_cohorts_provider_kind_provider_identity_sha256_model_identity_sha256_configuration_identity_sha256` " +
        "ON `learning_provider_config_cohorts` (`provider_kind`, `provider_identity_sha256`, " +
        "`model_identity_sha256`, `configuration_identity_sha256`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_provider_config_cohorts_configuration_generation` " +
        "ON `learning_provider_config_cohorts` (`configuration_generation`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_provider_config_cohorts_created_at_ms_id` " +
        "ON `learning_provider_config_cohorts` (`created_at_ms`, `id`)",
    "CREATE TABLE IF NOT EXISTS `learning_provider_job_manifests` (" +
        "`job_id` TEXT NOT NULL, `cohort_id` TEXT NOT NULL, `manifest_schema_version` INTEGER NOT NULL, " +
        "`request_hmac_sha256` TEXT NOT NULL, `input_identity_sha256` TEXT NOT NULL, " +
        "`runtime_attestation_sha256` TEXT NOT NULL, `redaction_policy_identity` TEXT NOT NULL, " +
        "`field_categories_identity` TEXT NOT NULL, `token_estimator_identity` TEXT NOT NULL, " +
        "`provider_request_key` TEXT NOT NULL, " +
        "`input_utf8_bytes` INTEGER NOT NULL, `max_input_utf8_bytes` INTEGER NOT NULL, " +
        "`estimated_input_tokens` INTEGER NOT NULL, `max_output_tokens` INTEGER NOT NULL, " +
        "`max_output_utf8_bytes` INTEGER NOT NULL, `max_provider_calls` INTEGER NOT NULL, " +
        "`max_cost_micros` INTEGER NOT NULL, `timeout_ms` INTEGER NOT NULL, " +
        "`frozen_at_ms` INTEGER NOT NULL, PRIMARY KEY(`job_id`), " +
        "FOREIGN KEY(`job_id`) REFERENCES `learning_jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(`cohort_id`) REFERENCES `learning_provider_config_cohorts`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS `index_learning_provider_job_manifests_cohort_id_frozen_at_ms_job_id` " +
        "ON `learning_provider_job_manifests` (`cohort_id`, `frozen_at_ms`, `job_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_provider_job_manifests_provider_request_key` " +
        "ON `learning_provider_job_manifests` (`provider_request_key`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_provider_job_manifests_request_hmac_sha256` " +
        "ON `learning_provider_job_manifests` (`request_hmac_sha256`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_provider_job_manifests_runtime_attestation_sha256` " +
        "ON `learning_provider_job_manifests` (`runtime_attestation_sha256`)",
    "CREATE TABLE IF NOT EXISTS `learning_provider_attempts` (" +
        "`job_id` TEXT NOT NULL, `attempt_ordinal` INTEGER NOT NULL, " +
        "`attempt_identity_sha256` TEXT NOT NULL, `state` TEXT NOT NULL, " +
        "`dispatch_knowledge` TEXT NOT NULL, `budget_state` TEXT NOT NULL, " +
        "`budget_authorization_sha256` TEXT NOT NULL, `budget_window_start_ms` INTEGER NOT NULL, " +
        "`budget_window_end_ms` INTEGER NOT NULL, `reserved_provider_calls` INTEGER NOT NULL, " +
        "`reserved_input_tokens` INTEGER NOT NULL, `reserved_output_tokens` INTEGER NOT NULL, " +
        "`reserved_cost_micros` INTEGER NOT NULL, `actual_provider_calls` INTEGER, " +
        "`actual_input_tokens` INTEGER, `actual_output_tokens` INTEGER, " +
        "`actual_cost_micros` INTEGER, `terminal_outcome` TEXT, " +
        "`lease_process_session_id` TEXT NOT NULL, `lease_worker_id` TEXT NOT NULL, " +
        "`lease_generation` INTEGER NOT NULL, `lease_until_ms` INTEGER NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, `dispatch_started_at_ms` INTEGER, " +
        "`terminal_observed_at_ms` INTEGER, `updated_at_ms` INTEGER NOT NULL, " +
        "`finished_at_ms` INTEGER, PRIMARY KEY(`job_id`, `attempt_ordinal`), " +
        "FOREIGN KEY(`job_id`) REFERENCES `learning_provider_job_manifests`(`job_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_provider_attempts_attempt_identity_sha256` " +
        "ON `learning_provider_attempts` (`attempt_identity_sha256`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_provider_attempts_state_updated_at_ms_job_id` " +
        "ON `learning_provider_attempts` (`state`, `updated_at_ms`, `job_id`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_provider_attempts_budget_window_start_ms_budget_window_end_ms_budget_state` " +
        "ON `learning_provider_attempts` (`budget_window_start_ms`, `budget_window_end_ms`, `budget_state`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_provider_attempts_lease_process_session_id_lease_worker_id_lease_generation` " +
        "ON `learning_provider_attempts` (`lease_process_session_id`, `lease_worker_id`, `lease_generation`)",
    "CREATE TABLE IF NOT EXISTS `learning_reward_signals` (" +
        "`id` TEXT NOT NULL, `episode_id` TEXT NOT NULL, `stream_id` TEXT NOT NULL, " +
        "`replay_generation` INTEGER NOT NULL, `scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL, " +
        "`authority_event_id` TEXT NOT NULL, `source_type` TEXT NOT NULL, `source_id` TEXT NOT NULL, " +
        "`source_revision` INTEGER NOT NULL, `source_integrity_sha256` TEXT NOT NULL, " +
        "`dimension` TEXT NOT NULL, `signal_kind` TEXT NOT NULL, `knowledge` TEXT NOT NULL, " +
        "`value_milli` INTEGER, `unknown_reason` TEXT, `occurred_at_ms` INTEGER NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`episode_id`) " +
        "REFERENCES `learning_episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_reward_signals_episode_id_id` " +
        "ON `learning_reward_signals` (`episode_id`, `id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_reward_signals_stream_id_replay_generation_scope_kind_scope_id_source_type_source_id_source_revision_dimension` " +
        "ON `learning_reward_signals` (`stream_id`, `replay_generation`, `scope_kind`, `scope_id`, " +
        "`source_type`, `source_id`, `source_revision`, `dimension`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_reward_signals_episode_id_dimension_signal_kind_occurred_at_ms_id` " +
        "ON `learning_reward_signals` (`episode_id`, `dimension`, `signal_kind`, `occurred_at_ms`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_reward_signals_source_type_source_id_source_revision` " +
        "ON `learning_reward_signals` (`source_type`, `source_id`, `source_revision`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_reward_signals_authority_event_id` " +
        "ON `learning_reward_signals` (`authority_event_id`)",
    "CREATE TABLE IF NOT EXISTS `policy_reward_evidence` (" +
        "`policy_id` TEXT NOT NULL, `episode_id` TEXT NOT NULL, `reward_signal_id` TEXT NOT NULL, " +
        "`source_type` TEXT NOT NULL, `source_id` TEXT NOT NULL, `source_revision` INTEGER NOT NULL, " +
        "`source_integrity_sha256` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`policy_id`, `episode_id`, `reward_signal_id`), " +
        "FOREIGN KEY(`policy_id`, `episode_id`) REFERENCES `policy_evidence`(`policy_id`, `episode_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`episode_id`, `reward_signal_id`) " +
        "REFERENCES `learning_reward_signals`(`episode_id`, `id`) " +
        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS `index_policy_reward_evidence_episode_id_reward_signal_id` " +
        "ON `policy_reward_evidence` (`episode_id`, `reward_signal_id`)",
    "CREATE INDEX IF NOT EXISTS `index_policy_reward_evidence_source_type_source_id_source_revision` " +
        "ON `policy_reward_evidence` (`source_type`, `source_id`, `source_revision`)",
    "CREATE INDEX IF NOT EXISTS `index_policy_reward_evidence_policy_id_episode_id` " +
        "ON `policy_reward_evidence` (`policy_id`, `episode_id`)",
    // v3 could not prove provider-visible bytes/runtime attestation. Never let those historical
    // active side-effect jobs become executable merely because v4 readiness is enabled later.
    "UPDATE `learning_jobs` SET `state` = 'CANCELLED', `lease_process_session_id` = NULL, " +
        "`lease_worker_id` = NULL, `lease_generation` = `lease_generation` + 1, " +
        "`lease_until_ms` = NULL, `last_error_code` = 'INVALID_JOB_SPEC', " +
        "`finished_at_ms` = `updated_at_ms` WHERE `job_type` IN " +
        "('REFLECT_EPISODE_V1', 'DISTILL_POLICY_V1') AND `state` IN " +
        "('PENDING', 'RETRY', 'RUNNING')",
)

/** Durable bounded-reconciliation progress. P2 exposure remains reserved for v6. */
internal val LEARNING_V5_SCHEMA_SQL: List<String> = listOf(
    "ALTER TABLE `learning_stream_checkpoints` ADD COLUMN " +
        "`reconciliation_cursor_v1_json` TEXT",
)

/** Content-free P2 Policy pipeline observation and actual-exposure ledger. */
internal val LEARNING_V6_SCHEMA_SQL: List<String> = listOf(
    "ALTER TABLE `learning_policies` ADD COLUMN `content_revision` INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_tool_schemas_wire` TEXT NOT NULL " +
        "DEFAULT '$POLICY_TOOL_APPLICABILITY_UNPROVEN_V5'",
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_model_identity_wire` TEXT NOT NULL " +
        "DEFAULT '$POLICY_IDENTITY_APPLICABILITY_ANY'",
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_provider_identity_wire` TEXT NOT NULL " +
        "DEFAULT '$POLICY_IDENTITY_APPLICABILITY_ANY'",
    "CREATE TABLE IF NOT EXISTS `learning_policy_exposures` (" +
        "`id` TEXT NOT NULL, `stream_id` TEXT NOT NULL, `replay_generation` INTEGER NOT NULL, " +
        "`episode_id` TEXT NOT NULL, `logical_run_id` TEXT NOT NULL, " +
        "`attempt_ordinal` INTEGER NOT NULL, `scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, `task_signature` TEXT NOT NULL, " +
        "`policy_set_digest` TEXT NOT NULL, `treatment_arm` TEXT NOT NULL, " +
        "`model_identity` TEXT NOT NULL, `provider_identity` TEXT NOT NULL, " +
        "`provider_generation` INTEGER NOT NULL, `toolset_fingerprint` TEXT NOT NULL, " +
        "`context_compiler_abi` TEXT NOT NULL, `state_version` INTEGER NOT NULL, " +
        "`furthest_state` TEXT NOT NULL, `retrieved_at_ms` INTEGER, " +
        "`compiled_at_ms` INTEGER, `injected_at_ms` INTEGER, " +
        "`host_dispatched_at_ms` INTEGER, `first_progress_at_ms` INTEGER, " +
        "`response_finished_at_ms` INTEGER, `outcome_linked_at_ms` INTEGER, " +
        "`terminal_outcome` TEXT, `terminal_at_ms` INTEGER, `outcome_source_type` TEXT, " +
        "`outcome_source_id` TEXT, `outcome_source_revision` INTEGER, " +
        "`attribution_state` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`episode_id`) REFERENCES `learning_episodes`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_policy_exposures_stream_id_episode_id_logical_run_id_attempt_ordinal_policy_set_digest` " +
        "ON `learning_policy_exposures` (`stream_id`, `episode_id`, `logical_run_id`, " +
        "`attempt_ordinal`, `policy_set_digest`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_policy_exposures_scope_kind_scope_id_task_signature_furthest_state` " +
        "ON `learning_policy_exposures` (`scope_kind`, `scope_id`, `task_signature`, " +
        "`furthest_state`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_policy_exposures_episode_id_logical_run_id_attempt_ordinal` " +
        "ON `learning_policy_exposures` (`episode_id`, `logical_run_id`, `attempt_ordinal`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_policy_exposures_scope_kind_scope_id_updated_at_ms_id` " +
        "ON `learning_policy_exposures` (`scope_kind`, `scope_id`, `updated_at_ms`, `id`)",
    "CREATE TABLE IF NOT EXISTS `learning_policy_exposure_items` (" +
        "`exposure_id` TEXT NOT NULL, `policy_id` TEXT NOT NULL, " +
        "`policy_revision` INTEGER NOT NULL, `artifact_sha256` TEXT NOT NULL, " +
        "`rank` INTEGER NOT NULL, `estimated_tokens` INTEGER NOT NULL, `drop_reason` TEXT, " +
        "`retrieved_at_ms` INTEGER NOT NULL, `compiled_at_ms` INTEGER, " +
        "`injected_at_ms` INTEGER, PRIMARY KEY(`exposure_id`, `policy_id`), " +
        "FOREIGN KEY(`exposure_id`) REFERENCES `learning_policy_exposures`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    // v6 splits the old aggregate STALE state into deterministic drift categories. Historical
    // rows cannot prove a narrower category, so they fail closed as source-stale.
    "UPDATE `learning_policies` SET `status` = 'STALE_SOURCE' WHERE `status` = 'STALE'",
    // No v5 row persisted PolicyCandidateDraft.applicableToolSchemas. An empty exact set would
    // falsely claim tool-independent applicability, so every legacy Policy is explicitly marked
    // schema-stale while retaining a distinct, non-live UNPROVEN sentinel for audit/rebuild.
    "UPDATE `learning_policies` SET `status` = 'STALE_SCHEMA', `schema_valid` = 0, " +
        "`stale_reason` = '$POLICY_APPLICABILITY_UNPROVEN_V5_REASON' " +
        "WHERE `applicable_tool_schemas_wire` = '$POLICY_TOOL_APPLICABILITY_UNPROVEN_V5'",
)

/** P4 isolated learned Workflow candidate artifacts; no row is executable from this database. */
internal val LEARNING_V7_SCHEMA_SQL: List<String> = listOf(
    "CREATE TABLE IF NOT EXISTS `learned_workflow_candidates` (" +
        "`id` TEXT NOT NULL, `candidate_version` INTEGER NOT NULL, " +
        "`state_version` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
        "`assistant_id` TEXT NOT NULL, `authority_subject_id` TEXT, " +
        "`source_policy_id` TEXT NOT NULL, `source_policy_revision` INTEGER NOT NULL, " +
        "`source_policy_artifact_sha256` TEXT NOT NULL, `source_grant_digest` TEXT NOT NULL, " +
        "`positive_anchor_evidence_id` TEXT NOT NULL, `evidence_ids_wire` TEXT NOT NULL, " +
        "`canonical_template_json` TEXT NOT NULL, `typed_slots_wire` TEXT NOT NULL, " +
        "`capability_snapshot_wire` TEXT NOT NULL, " +
        "`tool_schema_fingerprints_wire` TEXT NOT NULL, " +
        "`producer_provider_identity` TEXT NOT NULL, `producer_model_identity` TEXT NOT NULL, " +
        "`producer_configuration_identity` TEXT NOT NULL, " +
        "`producer_config_generation` INTEGER NOT NULL, `compiler_version` TEXT NOT NULL, " +
        "`prompt_version` TEXT NOT NULL, `template_version` TEXT NOT NULL, " +
        "`validator_version` TEXT NOT NULL, `verifier_version` TEXT NOT NULL, " +
        "`max_output_utf8_bytes` INTEGER NOT NULL, `artifact_sha256` TEXT NOT NULL, " +
        "`verification_report_wire` TEXT, `verified_at_ms` INTEGER, " +
        "`archived_at_ms` INTEGER, `created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`source_policy_id`) REFERENCES `learning_policies`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS `index_learned_workflow_candidates_source_policy_id` " +
        "ON `learned_workflow_candidates` (`source_policy_id`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learned_workflow_candidates_assistant_id_authority_subject_id_state_updated_at_ms_id` " +
        "ON `learned_workflow_candidates` (`assistant_id`, `authority_subject_id`, `state`, " +
        "`updated_at_ms`, `id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learned_workflow_candidates_source_policy_id_source_policy_revision_assistant_id` " +
        "ON `learned_workflow_candidates` (`source_policy_id`, `source_policy_revision`, `assistant_id`)",
    "CREATE INDEX IF NOT EXISTS `index_learned_workflow_candidates_state_updated_at_ms_id` " +
        "ON `learned_workflow_candidates` (`state`, `updated_at_ms`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_learned_workflow_candidates_artifact_sha256` " +
        "ON `learned_workflow_candidates` (`artifact_sha256`)",
    "CREATE TABLE IF NOT EXISTS `learned_workflow_candidate_revisions` (" +
        "`candidate_id` TEXT NOT NULL, `candidate_version` INTEGER NOT NULL, " +
        "`state_version` INTEGER NOT NULL, `previous_state_version` INTEGER, " +
        "`state` TEXT NOT NULL, `artifact_sha256` TEXT NOT NULL, " +
        "`previous_artifact_sha256` TEXT, `snapshot_wire` TEXT NOT NULL, " +
        "`reason_code` TEXT NOT NULL, `actor` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`candidate_id`, `state_version`), " +
        "FOREIGN KEY(`candidate_id`) REFERENCES `learned_workflow_candidates`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learned_workflow_candidate_revisions_candidate_id_created_at_ms` " +
        "ON `learned_workflow_candidate_revisions` (`candidate_id`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learned_workflow_candidate_revisions_created_at_ms_candidate_id_state_version` " +
        "ON `learned_workflow_candidate_revisions` (`created_at_ms`, `candidate_id`, `state_version`)",
)

/**
 * Unreleased v8 closure: content-free P1 Stage-D observations plus the P5 Curator v1 durable
 * review/apply ledger. Curator candidate/plan wires are never executable by themselves and have a
 * fixed destructive redaction path keyed by the exact source Policy set.
 */
private val LEARNING_V8_AND_V9_SCHEMA_SQL: List<String> = listOf(
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_template_identity` TEXT",
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_configuration_identity` TEXT",
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_configuration_generation` INTEGER",
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_capability_digest` TEXT",
    "ALTER TABLE `learning_policies` ADD COLUMN `applicable_authority_digest` TEXT",
    "ALTER TABLE `learning_policy_exposure_items` ADD COLUMN " +
        "`applicability_cohort_digest` TEXT NOT NULL DEFAULT '${"0".repeat(64)}'",
    // v7 ANY_V1 rows have no provable model/provider/config applicability. Retain them for
    // review/audit only and fail closed before every shadow/active query.
    "UPDATE `learning_policies` SET `status` = 'STALE_SCHEMA', `schema_valid` = 0, " +
        "`stale_reason` = '$POLICY_APPLICABILITY_UNPROVEN_V7_REASON' WHERE `status` IN " +
        "('CANDIDATE', 'SHADOW', 'PROBATION', 'ACTIVE') AND " +
        "(`applicable_model_identity_wire` = '$POLICY_IDENTITY_APPLICABILITY_ANY' OR " +
        "`applicable_provider_identity_wire` = '$POLICY_IDENTITY_APPLICABILITY_ANY' OR " +
        "`applicable_template_identity` IS NULL OR " +
        "`applicable_configuration_identity` IS NULL OR " +
        "`applicable_configuration_generation` IS NULL OR " +
        "`applicable_configuration_generation` <= 0)",
    // P1 Stage-D was not production-reachable when v8 was first drafted. v8 has not shipped;
    // include its content-free request/idempotency ledger here instead of inventing a v9 identity.
    "CREATE TABLE IF NOT EXISTS `learning_policy_shadow_observations` (" +
        "`request_identity` TEXT NOT NULL, `scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, `task_signature` TEXT NOT NULL, " +
        "`gate_identity` TEXT NOT NULL, `query_term_count` INTEGER NOT NULL, " +
        "`exact_candidate_count` INTEGER NOT NULL, `lexical_candidate_count` INTEGER NOT NULL, " +
        "`selected_count` INTEGER NOT NULL, `estimated_tokens` INTEGER NOT NULL, " +
        "`latency_micros` INTEGER NOT NULL, `drop_reason_counts_wire` TEXT NOT NULL, " +
        "`observed_at_ms` INTEGER NOT NULL, PRIMARY KEY(`request_identity`))",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_policy_shadow_observations_scope_kind_scope_id_observed_at_ms_request_identity` " +
        "ON `learning_policy_shadow_observations` (`scope_kind`, `scope_id`, " +
        "`observed_at_ms`, `request_identity`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_policy_shadow_observations_task_signature_observed_at_ms` " +
        "ON `learning_policy_shadow_observations` (`task_signature`, `observed_at_ms`)",
    "CREATE TABLE IF NOT EXISTS `learning_policy_shadow_observation_items` (" +
        "`request_identity` TEXT NOT NULL, `policy_id` TEXT NOT NULL, " +
        "`policy_state_version` INTEGER NOT NULL, `policy_content_revision` INTEGER NOT NULL, " +
        "`artifact_sha256` TEXT NOT NULL, `lifecycle_status` TEXT NOT NULL, " +
        "`rank` INTEGER NOT NULL, `exact_task_match` INTEGER NOT NULL, " +
        "`lexical_score_micros` INTEGER NOT NULL, `estimated_tokens` INTEGER NOT NULL, " +
        "PRIMARY KEY(`request_identity`, `policy_id`), " +
        "FOREIGN KEY(`request_identity`) REFERENCES `learning_policy_shadow_observations`" +
        "(`request_identity`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(`policy_id`) REFERENCES `learning_policies`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS `index_learning_policy_shadow_observation_items_policy_id` " +
        "ON `learning_policy_shadow_observation_items` (`policy_id`)",
    // P2-006A has no reliable pre-v9 assignment/baseline ledger to backfill. New rows are
    // append-only and begin only after the pre-treatment production port commits.
    "CREATE TABLE IF NOT EXISTS `learning_observed_utility_assignments` (" +
        "`id` TEXT NOT NULL, `contract_version` INTEGER NOT NULL, `stream_id` TEXT NOT NULL, " +
        "`replay_generation` INTEGER NOT NULL, `episode_id` TEXT NOT NULL, " +
        "`logical_run_id` TEXT NOT NULL, `attempt_ordinal` INTEGER NOT NULL, " +
        "`scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL, " +
        "`target_policy_id` TEXT NOT NULL, `target_policy_state_version` INTEGER NOT NULL, " +
        "`target_policy_content_revision` INTEGER NOT NULL, " +
        "`target_policy_artifact_sha256` TEXT NOT NULL, `policy_set_digest` TEXT NOT NULL, " +
        "`design_digest` TEXT NOT NULL, `cohort_digest` TEXT NOT NULL, `arm` TEXT NOT NULL, " +
        "`assignment_method` TEXT NOT NULL, `selection_method` TEXT NOT NULL, " +
        "`pre_registered_design_digest` TEXT, `exposure_recording_reliable` INTEGER NOT NULL, " +
        "`exposure_contract_version` INTEGER NOT NULL, " +
        "`eligibility_before_treatment` INTEGER NOT NULL, " +
        "`assignment_before_compile_or_injection` INTEGER NOT NULL, " +
        "`fixed_outcome_window` INTEGER NOT NULL, `randomized_assignment` INTEGER NOT NULL, " +
        "`factorial_isolation` INTEGER NOT NULL, `attribution_unit` TEXT NOT NULL, " +
        "`match_key_digest` TEXT, `propensity` REAL, `expected_exposure_id` TEXT, " +
        "`expected_exposure_state_version` INTEGER, `expected_exposure_receipt_digest` TEXT, " +
        "`task_signature` TEXT NOT NULL, `task_signature_version` INTEGER NOT NULL, " +
        "`model_identity` TEXT NOT NULL, `model_version` TEXT NOT NULL, " +
        "`provider_identity` TEXT NOT NULL, `provider_version` TEXT NOT NULL, " +
        "`provider_configuration_generation` INTEGER NOT NULL, " +
        "`toolset_fingerprint` TEXT NOT NULL, `tool_schema_version` TEXT NOT NULL, " +
        "`producer_model_identity` TEXT NOT NULL, `producer_provider_identity` TEXT NOT NULL, " +
        "`producer_configuration_identity` TEXT NOT NULL, " +
        "`producer_configuration_generation` INTEGER NOT NULL, " +
        "`outcome_definition_version` TEXT NOT NULL, `outcome_window_identity` TEXT NOT NULL, " +
        "`source_window_start_ms` INTEGER NOT NULL, `source_window_end_ms` INTEGER NOT NULL, " +
        "`eligibility_determined_at_ms` INTEGER NOT NULL, `assigned_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`id`), FOREIGN KEY(`episode_id`) REFERENCES `learning_episodes`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
    "CREATE INDEX IF NOT EXISTS `index_learning_observed_utility_assignments_episode_id` " +
        "ON `learning_observed_utility_assignments` (`episode_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_observed_utility_assignments_stream_id_replay_generation_logical_run_id_attempt_ordinal_design_digest` " +
        "ON `learning_observed_utility_assignments` (`stream_id`, `replay_generation`, " +
        "`logical_run_id`, `attempt_ordinal`, `design_digest`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_observed_utility_assignments_scope_kind_scope_id_target_policy_id_policy_set_digest_design_digest_cohort_digest_source_window_end_ms_id` " +
        "ON `learning_observed_utility_assignments` (`scope_kind`, `scope_id`, " +
        "`target_policy_id`, `policy_set_digest`, `design_digest`, `cohort_digest`, " +
        "`source_window_end_ms`, `id`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_observed_utility_assignments_expected_exposure_id` " +
        "ON `learning_observed_utility_assignments` (`expected_exposure_id`)",
    "CREATE TABLE IF NOT EXISTS `learning_observed_utility_outcomes` (" +
        "`assignment_id` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
        "`authority_source_kind` TEXT, `authority_source_id` TEXT, " +
        "`authority_source_revision` INTEGER, `authority_evidence_digest` TEXT, " +
        "`baseline_host_dispatched` INTEGER NOT NULL, " +
        "`baseline_progress_or_response` INTEGER NOT NULL, `exposure_state_version` INTEGER, " +
        "`exposure_receipt_digest` TEXT, `window_closed_at_ms` INTEGER NOT NULL, " +
        "`recorded_at_ms` INTEGER NOT NULL, `outcome_receipt_digest` TEXT NOT NULL, " +
        "PRIMARY KEY(`assignment_id`), FOREIGN KEY(`assignment_id`) REFERENCES " +
        "`learning_observed_utility_assignments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_learning_observed_utility_outcomes_outcome_receipt_digest` " +
        "ON `learning_observed_utility_outcomes` (`outcome_receipt_digest`)",
    "CREATE TABLE IF NOT EXISTS `learning_observed_utility_evaluation_receipts` (" +
        "`receipt_digest` TEXT NOT NULL, `contract_version` INTEGER NOT NULL, " +
        "`scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL, `policy_id` TEXT NOT NULL, " +
        "`expected_state_version` INTEGER NOT NULL, `expected_content_revision` INTEGER NOT NULL, " +
        "`expected_artifact_sha256` TEXT NOT NULL, `design_digest` TEXT NOT NULL, " +
        "`target_policy_set_digest` TEXT NOT NULL, `source_window_start_ms` INTEGER NOT NULL, " +
        "`source_window_end_ms` INTEGER NOT NULL, `source_watermark_digest` TEXT NOT NULL, " +
        "`source_watermark_status` TEXT NOT NULL, `cohort_digest` TEXT NOT NULL, " +
        "`observed_cohort_digest` TEXT, `status` TEXT NOT NULL, `result_code` TEXT NOT NULL, " +
        "`assignment_method` TEXT NOT NULL, `selection_method` TEXT NOT NULL, " +
        "`metric_name` TEXT NOT NULL, `interpretation_name` TEXT NOT NULL, " +
        "`observed_utility_delta` REAL, `utility_uncertainty` REAL, " +
        "`confidence_level` REAL, `confidence_lower` REAL, `confidence_upper` REAL, " +
        "`causal_interpretation` TEXT NOT NULL, `scalar_projection_policy_id` TEXT, " +
        "`sample_size` INTEGER NOT NULL, `exposed_sample_size` INTEGER NOT NULL, " +
        "`non_exposure_sample_size` INTEGER NOT NULL, `unknown_count` INTEGER NOT NULL, " +
        "`censored_count` INTEGER NOT NULL, `evaluated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`receipt_digest`))",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_observed_utility_evaluation_receipts_scope_kind_scope_id_policy_id_expected_state_version_expected_content_revision_expected_artifact_sha256_design_digest_cohort_digest_source_window_start_ms_source_window_end_ms` " +
        "ON `learning_observed_utility_evaluation_receipts` (`scope_kind`, `scope_id`, " +
        "`policy_id`, `expected_state_version`, `expected_content_revision`, " +
        "`expected_artifact_sha256`, `design_digest`, `cohort_digest`, " +
        "`source_window_start_ms`, `source_window_end_ms`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_learning_observed_utility_evaluation_receipts_evaluated_at_ms_receipt_digest` " +
        "ON `learning_observed_utility_evaluation_receipts` (`evaluated_at_ms`, `receipt_digest`)",
    "CREATE TABLE IF NOT EXISTS `curator_delta_candidates` (" +
        "`id` TEXT NOT NULL, `operation` TEXT NOT NULL, `state_version` INTEGER NOT NULL, " +
        "`state` TEXT NOT NULL, `scope_kind` TEXT NOT NULL, `scope_id` TEXT NOT NULL, " +
        "`source_policy_ids_key` TEXT NOT NULL, `candidate_wire` TEXT NOT NULL, " +
        "`candidate_sha256` TEXT NOT NULL, `input_set_sha256` TEXT NOT NULL, " +
        "`producer_identity_sha256` TEXT NOT NULL, `curator_schema_identity` TEXT NOT NULL, " +
        "`apply_plan_id` TEXT, `apply_plan_wire` TEXT, `apply_plan_sha256` TEXT, " +
        "`conflict_code` TEXT, `redacted_at_ms` INTEGER, `created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_curator_delta_candidates_scope_kind_scope_id_state_updated_at_ms_id` " +
        "ON `curator_delta_candidates` (`scope_kind`, `scope_id`, `state`, `updated_at_ms`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_curator_delta_candidates_state_updated_at_ms_id` " +
        "ON `curator_delta_candidates` (`state`, `updated_at_ms`, `id`)",
    "CREATE INDEX IF NOT EXISTS `index_curator_delta_candidates_candidate_sha256` " +
        "ON `curator_delta_candidates` (`candidate_sha256`)",
    "CREATE TABLE IF NOT EXISTS `curator_delta_revisions` (" +
        "`candidate_id` TEXT NOT NULL, `state_version` INTEGER NOT NULL, " +
        "`previous_state_version` INTEGER, `state` TEXT NOT NULL, " +
        "`candidate_sha256` TEXT NOT NULL, `apply_plan_id` TEXT, " +
        "`reason_code` TEXT NOT NULL, `actor` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`candidate_id`, `state_version`), " +
        "FOREIGN KEY(`candidate_id`) REFERENCES `curator_delta_candidates`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_curator_delta_revisions_candidate_id_created_at_ms` " +
        "ON `curator_delta_revisions` (`candidate_id`, `created_at_ms`)",
    "CREATE TABLE IF NOT EXISTS `curator_delta_lineage` (" +
        "`candidate_id` TEXT NOT NULL, `apply_plan_id` TEXT NOT NULL, " +
        "`parent_policy_id` TEXT NOT NULL, `parent_revision` INTEGER NOT NULL, " +
        "`parent_artifact_sha256` TEXT NOT NULL, `child_policy_id` TEXT NOT NULL, " +
        "`child_revision` INTEGER NOT NULL, `child_artifact_sha256` TEXT NOT NULL, " +
        "`relation_type` TEXT NOT NULL, `active` INTEGER NOT NULL, " +
        "`state_version` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`candidate_id`, `parent_policy_id`, `child_policy_id`, `relation_type`), " +
        "FOREIGN KEY(`candidate_id`) REFERENCES `curator_delta_candidates`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(`parent_policy_id`) REFERENCES `learning_policies`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(`child_policy_id`) REFERENCES `learning_policies`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_curator_delta_lineage_candidate_id_active_updated_at_ms` " +
        "ON `curator_delta_lineage` (`candidate_id`, `active`, `updated_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_curator_delta_lineage_parent_policy_id` " +
        "ON `curator_delta_lineage` (`parent_policy_id`)",
    "CREATE INDEX IF NOT EXISTS `index_curator_delta_lineage_child_policy_id` " +
        "ON `curator_delta_lineage` (`child_policy_id`)",
)

/** Frozen v8: exact applicability, Stage-D receipts, and the Curator ledger only. */
internal val LEARNING_V8_SCHEMA_SQL: List<String> =
    LEARNING_V8_AND_V9_SCHEMA_SQL.filterNot { sql ->
        "`learning_observed_utility_" in sql
    }

/** v9 is deliberately additive and contains only the append-only observed-utility ledger. */
internal val LEARNING_V9_SCHEMA_SQL: List<String> =
    LEARNING_V8_AND_V9_SCHEMA_SQL.filter { sql ->
        "`learning_observed_utility_" in sql
    }.also { statements ->
        check(statements.size == 10) { "Unexpected observed-utility v9 schema surface" }
    }

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

val LEARNING_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V4_SCHEMA_SQL.forEach(db::execSQL)
    }
}

val LEARNING_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V5_SCHEMA_SQL.forEach(db::execSQL)
    }
}

val LEARNING_MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V6_SCHEMA_SQL.forEach(db::execSQL)
    }
}

val LEARNING_MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V7_SCHEMA_SQL.forEach(db::execSQL)
    }
}

val LEARNING_MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V8_SCHEMA_SQL.forEach(db::execSQL)
    }
}

val LEARNING_MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        LEARNING_V9_SCHEMA_SQL.forEach(db::execSQL)
    }
}

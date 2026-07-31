package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Owner host-operation ledger and redacted local-service supervision projection. */
val MIGRATION_41_42 = object : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `host_operations` (" +
                "`request_id` TEXT NOT NULL, `authority_subject_id` TEXT NOT NULL, " +
                "`authority_epoch` INTEGER NOT NULL, `assistant_id` TEXT NOT NULL, " +
                "`conversation_id` TEXT NOT NULL, `model_id` TEXT, `provider_id` TEXT, " +
                "`tool_family` TEXT NOT NULL, `action_summary_json` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, `state_version` INTEGER NOT NULL, " +
                "`recovery_code` TEXT, `result_code` TEXT, `created_at_ms` INTEGER NOT NULL, " +
                "`updated_at_ms` INTEGER NOT NULL, `completed_at_ms` INTEGER, " +
                "PRIMARY KEY(`request_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_operations_authority_state` ON `host_operations` (`authority_subject_id`, `state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_operations_conversation_updated` ON `host_operations` (`conversation_id`, `updated_at_ms`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_operations_state_updated` ON `host_operations` (`state`, `updated_at_ms`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `host_operation_events` (" +
                "`event_id` TEXT NOT NULL, `request_id` TEXT NOT NULL, `sequence` INTEGER NOT NULL, " +
                "`previous_state` TEXT, `next_state` TEXT NOT NULL, `action_index` INTEGER, " +
                "`action_type` TEXT, `reason_code` TEXT, `created_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`event_id`), FOREIGN KEY(`request_id`) REFERENCES `host_operations`(`request_id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_operation_events_request` ON `host_operation_events` (`request_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_host_operation_events_request_sequence` ON `host_operation_events` (`request_id`, `sequence`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_operation_events_created` ON `host_operation_events` (`created_at_ms`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `host_local_services` (" +
                "`service_id` TEXT NOT NULL, `authority_subject_id` TEXT NOT NULL, " +
                "`authority_epoch` INTEGER NOT NULL, `manifest_json` TEXT NOT NULL, " +
                "`manifest_hash` TEXT NOT NULL, `execution_id` TEXT, `health_state` TEXT NOT NULL, " +
                "`restart_policy` TEXT NOT NULL, `restart_count` INTEGER NOT NULL, " +
                "`next_probe_at_ms` INTEGER, `last_probe_at_ms` INTEGER, `last_reason_code` TEXT, " +
                "`enabled` INTEGER NOT NULL, `state_version` INTEGER NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`service_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_local_services_authority_enabled` ON `host_local_services` (`authority_subject_id`, `enabled`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_local_services_execution` ON `host_local_services` (`execution_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_host_local_services_health` ON `host_local_services` (`health_state`, `next_probe_at_ms`)")
    }
}

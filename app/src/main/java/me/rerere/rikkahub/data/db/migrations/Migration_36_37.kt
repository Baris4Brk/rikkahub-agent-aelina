package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the versioned execution journal and redacted second-user approval projection. */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `execution_records` ADD COLUMN `execution_kind` " +
                "TEXT NOT NULL DEFAULT 'TOOL_CALL'",
        )
        db.execSQL(
            "ALTER TABLE `execution_records` ADD COLUMN `state_version` " +
                "INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `execution_records` ADD COLUMN `last_state_source` " +
                "TEXT NOT NULL DEFAULT 'LEGACY'",
        )
        db.execSQL("ALTER TABLE `execution_records` ADD COLUMN `last_reason_code` TEXT")
        db.execSQL(
            "ALTER TABLE `execution_records` ADD COLUMN `verification_state` " +
                "TEXT NOT NULL DEFAULT 'UNKNOWN'",
        )
        db.execSQL("ALTER TABLE `execution_records` ADD COLUMN `last_probe_at_ms` INTEGER")
        db.execSQL(
            "ALTER TABLE `execution_records` ADD COLUMN `completion_policy` " +
                "TEXT NOT NULL DEFAULT 'WAIT_FOR_CHILDREN'",
        )
        db.execSQL("ALTER TABLE `execution_records` ADD COLUMN `runtime_instance_marker` TEXT")
        db.execSQL(
            "ALTER TABLE `execution_records` ADD COLUMN `cancellation_requested_at_ms` INTEGER",
        )
        db.execSQL(
            """
            UPDATE `execution_records`
            SET `verification_state` = CASE
                WHEN `status` IN ('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown')
                    THEN 'DATABASE_CONFIRMED'
                ELSE 'RECONCILING'
            END
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `execution_events` (
                `event_id` TEXT NOT NULL,
                `execution_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `previous_status` TEXT,
                `next_status` TEXT NOT NULL,
                `previous_verification` TEXT,
                `next_verification` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `reason_code` TEXT,
                `created_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`event_id`),
                FOREIGN KEY(`execution_id`) REFERENCES `execution_records`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_tool_approvals` (
                `approval_id` TEXT NOT NULL,
                `execution_id` TEXT NOT NULL,
                `trace_id` TEXT,
                `tool_call_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `subject_type` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `capability_key` TEXT NOT NULL,
                `resource_category` TEXT NOT NULL,
                `requested_at_ms` INTEGER NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'PENDING',
                `state_version` INTEGER NOT NULL DEFAULT 0,
                `resolved_at_ms` INTEGER,
                `resolution_reason` TEXT,
                `resolution_request_id` TEXT,
                PRIMARY KEY(`approval_id`)
            )
            """.trimIndent(),
        )

        listOf(
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_conversation_status_updated` " +
                "ON `execution_records` (`conversation_id`, `status`, `updated_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_subject_status_updated` " +
                "ON `execution_records` (`subject_id`, `status`, `updated_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_parent_status` " +
                "ON `execution_records` (`parent_execution_id`, `status`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_runtime_handle` " +
                "ON `execution_records` (`runtime`, `runtime_handle_summary`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_records_heartbeat_status` " +
                "ON `execution_records` (`heartbeat_at_ms`, `status`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_events_execution` " +
                "ON `execution_events` (`execution_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_execution_events_execution_sequence` " +
                "ON `execution_events` (`execution_id`, `sequence`)",
            "CREATE INDEX IF NOT EXISTS `idx_execution_events_created` " +
                "ON `execution_events` (`created_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_tool_approvals_execution` " +
                "ON `pending_tool_approvals` (`execution_id`)",
            "CREATE INDEX IF NOT EXISTS `idx_tool_approvals_conversation_status_requested` " +
                "ON `pending_tool_approvals` (`conversation_id`, `status`, `requested_at_ms`)",
            "CREATE INDEX IF NOT EXISTS `idx_tool_approvals_resolved` " +
                "ON `pending_tool_approvals` (`resolved_at_ms`)",
        ).forEach(db::execSQL)
    }
}

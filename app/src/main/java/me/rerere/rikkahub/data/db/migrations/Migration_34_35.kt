package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive execution ledger migration. No existing run or conversation data is rewritten. */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `workspaces` ADD COLUMN `storage_mode` TEXT NOT NULL DEFAULT 'PRIVATE'",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `execution_records` (
                `id` TEXT NOT NULL,
                `trace_id` TEXT NOT NULL,
                `parent_execution_id` TEXT,
                `command_id` TEXT,
                `conversation_id` TEXT,
                `subject_id` TEXT NOT NULL,
                `subject_type` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `capability_keys` TEXT NOT NULL,
                `resource_summary` TEXT NOT NULL,
                `runtime` TEXT NOT NULL,
                `idempotency_key` TEXT,
                `runtime_handle_summary` TEXT,
                `status` TEXT NOT NULL,
                `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                `started_at_ms` INTEGER,
                `heartbeat_at_ms` INTEGER,
                `finished_at_ms` INTEGER,
                `cancellation_result` TEXT,
                `terminal_detail` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_execution_records_status` ON `execution_records` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_execution_records_trace` ON `execution_records` (`trace_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_execution_records_parent` ON `execution_records` (`parent_execution_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_execution_records_idempotency` ON `execution_records` (`idempotency_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_execution_records_updated` ON `execution_records` (`updated_at_ms`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `capability_grants` (
                `id` TEXT NOT NULL,
                `subject_id` TEXT NOT NULL,
                `subject_type` TEXT NOT NULL,
                `capability_key` TEXT NOT NULL,
                `resource_kind` TEXT NOT NULL,
                `resource_identifier` TEXT NOT NULL,
                `allowed_origins` TEXT NOT NULL,
                `scope` TEXT NOT NULL,
                `expires_at_ms` INTEGER,
                `revoked` INTEGER NOT NULL,
                `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_capability_grants_subject` ON `capability_grants` (`subject_id`, `subject_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_capability_grants_active` ON `capability_grants` (`revoked`, `expires_at_ms`)")
    }
}

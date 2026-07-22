package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_V31_BACKFILL_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_V31_PORTABLE_CREATE_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_V31_TRIGGER_SQL

/** Adds the durable Memory V2 metadata, capture queue, review ledger and revision history. */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val migrationTimeMs = System.currentTimeMillis()

        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `created_at_ms` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `last_accessed_at_ms` INTEGER")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `expires_at_ms` INTEGER")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `memory_kind` TEXT NOT NULL DEFAULT 'OTHER'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `confidence` REAL NOT NULL DEFAULT 1.0")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `tags_json` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `tags_search` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `content_hash` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `source_type` TEXT NOT NULL DEFAULT 'LEGACY'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `source_conversation_id` TEXT")
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `source_message_ids_json` " +
                "TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `lifecycle_status` " +
                "TEXT NOT NULL DEFAULT 'ACTIVE'",
        )
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `approval_source` " +
                "TEXT NOT NULL DEFAULT 'LEGACY'",
        )
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            "UPDATE `MemoryEntity` SET `created_at_ms` = " +
                "CASE WHEN `updated_at_ms` > 0 THEN `updated_at_ms` ELSE ? END",
            arrayOf(migrationTimeMs),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_captures` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `scope_id` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `user_message_id` TEXT NOT NULL,
                `assistant_message_id` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `capture_source` TEXT NOT NULL DEFAULT 'AUTOMATIC_TURN',
                `auto_save_mode` TEXT NOT NULL,
                `user_text` TEXT NOT NULL,
                `assistant_text` TEXT NOT NULL,
                `state` TEXT NOT NULL DEFAULT 'PENDING',
                `retry_count` INTEGER NOT NULL DEFAULT 0,
                `last_error_code` TEXT,
                `last_error_message` TEXT,
                `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                `lease_owner` TEXT,
                `lease_until_ms` INTEGER,
                `processed_at_ms` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_memory_captures_conversation_id_assistant_message_id_capture_source` " +
                "ON `memory_captures` (`conversation_id`, `assistant_message_id`, `capture_source`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_captures_scope_id_state_created_at_ms` " +
                "ON `memory_captures` (`scope_id`, `state`, `created_at_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_captures_lease_until_ms` " +
                "ON `memory_captures` (`lease_until_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_captures_conversation_id` " +
                "ON `memory_captures` (`conversation_id`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_candidates` (
                `id` TEXT NOT NULL,
                `scope_id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `source_conversation_id` TEXT NOT NULL,
                `capture_ids_json` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `target_memory_ids_json` TEXT NOT NULL,
                `expected_revisions_json` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `memory_kind` TEXT NOT NULL,
                `tags_json` TEXT NOT NULL,
                `importance` REAL NOT NULL,
                `confidence` REAL NOT NULL,
                `expires_at_ms` INTEGER,
                `risk_flags_json` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `evidence_message_ids_json` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'PENDING_REVIEW',
                `applied_memory_id` INTEGER,
                `resolution_error` TEXT,
                `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_candidates_scope_id_status_created_at_ms` " +
                "ON `memory_candidates` (`scope_id`, `status`, `created_at_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_candidates_source_conversation_id` " +
                "ON `memory_candidates` (`source_conversation_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_candidates_applied_memory_id` " +
                "ON `memory_candidates` (`applied_memory_id`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_revisions` (
                `id` TEXT NOT NULL,
                `memory_id` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `operation` TEXT NOT NULL,
                `before_snapshot_json` TEXT,
                `after_snapshot_json` TEXT,
                `actor` TEXT NOT NULL,
                `candidate_id` TEXT,
                `source_conversation_id` TEXT,
                `source_message_ids_json` TEXT NOT NULL DEFAULT '[]',
                `created_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_revisions_memory_id_revision` " +
                "ON `memory_revisions` (`memory_id`, `revision`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_revisions_memory_id_created_at_ms` " +
                "ON `memory_revisions` (`memory_id`, `created_at_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_revisions_candidate_id` " +
                "ON `memory_revisions` (`candidate_id`)",
        )

        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ai")
        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_au")
        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ad")
        db.execSQL("DROP TABLE IF EXISTS memory_fts")
        db.execSQL(MEMORY_FTS_V31_PORTABLE_CREATE_SQL.trimIndent())
        db.execSQL(MEMORY_FTS_V31_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_V31_TRIGGER_SQL.forEach(db::execSQL)
    }
}

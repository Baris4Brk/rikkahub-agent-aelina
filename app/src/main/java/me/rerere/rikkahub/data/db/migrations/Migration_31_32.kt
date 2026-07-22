package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_BACKFILL_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_PORTABLE_CREATE_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_TRIGGER_SQL

/** Adds narrative memory metadata, evidence capsules, light relations and manual backfill state. */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `origin_assistant_id` TEXT")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `attribution` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `truth_status` TEXT NOT NULL DEFAULT 'CONFIRMED'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `occurred_at_ms` INTEGER")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `participants_json` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `outcome` TEXT")
        db.execSQL("ALTER TABLE `memory_candidates` ADD COLUMN `proposal_key` TEXT")
        db.execSQL("ALTER TABLE `memory_candidates` ADD COLUMN `attribution` TEXT NOT NULL DEFAULT 'UNKNOWN'")
        db.execSQL("ALTER TABLE `memory_candidates` ADD COLUMN `truth_status` TEXT NOT NULL DEFAULT 'CONFIRMED'")
        db.execSQL("ALTER TABLE `memory_candidates` ADD COLUMN `occurred_at_ms` INTEGER")
        db.execSQL("ALTER TABLE `memory_candidates` ADD COLUMN `participants_json` TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE `memory_candidates` ADD COLUMN `outcome` TEXT")
        db.execSQL("ALTER TABLE `memory_captures` ADD COLUMN `processing_outcome` TEXT")
        db.execSQL("ALTER TABLE `memory_captures` ADD COLUMN `candidate_count` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_captures` ADD COLUMN `supersedes_capture_id` TEXT")
        db.execSQL("ALTER TABLE `memory_captures` ADD COLUMN `narrative_events_enabled` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_captures` ADD COLUMN `insights_theories_enabled` INTEGER NOT NULL DEFAULT 0")

        db.execSQL("""CREATE TABLE IF NOT EXISTS `memory_evidence` (`id` TEXT NOT NULL, `memory_id` INTEGER, `candidate_id` TEXT, `conversation_id` TEXT NOT NULL, `message_id` TEXT NOT NULL, `role` TEXT NOT NULL, `excerpt` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `captured_at_ms` INTEGER NOT NULL, `quality` TEXT NOT NULL DEFAULT 'ORIGINAL_MESSAGE', PRIMARY KEY(`id`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_evidence_memory_id` ON `memory_evidence` (`memory_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_evidence_candidate_id` ON `memory_evidence` (`candidate_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_evidence_message_id` ON `memory_evidence` (`message_id`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `memory_links` (`id` TEXT NOT NULL, `source_memory_id` INTEGER NOT NULL, `target_memory_id` INTEGER NOT NULL, `relation_type` TEXT NOT NULL, `weight` REAL NOT NULL, `description` TEXT NOT NULL, `evidence_message_ids_json` TEXT NOT NULL, `created_by_assistant_id` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `revision` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_links_source_memory_id_target_memory_id_relation_type` ON `memory_links` (`source_memory_id`, `target_memory_id`, `relation_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_links_target_memory_id` ON `memory_links` (`target_memory_id`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `memory_relation_candidates` (`id` TEXT NOT NULL, `batch_id` TEXT NOT NULL, `source_proposal_key` TEXT, `source_memory_id` INTEGER, `target_proposal_key` TEXT, `target_memory_id` INTEGER, `relation_type` TEXT NOT NULL, `weight` REAL NOT NULL, `description` TEXT NOT NULL, `evidence_message_ids_json` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'PENDING', `created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_batch_id` ON `memory_relation_candidates` (`batch_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_status` ON `memory_relation_candidates` (`status`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `memory_backfill_runs` (`id` TEXT NOT NULL, `assistant_id` TEXT NOT NULL, `scope_id` TEXT NOT NULL, `selection_json` TEXT NOT NULL, `total_turns` INTEGER NOT NULL, `processed_turns` INTEGER NOT NULL DEFAULT 0, `failed_turns` INTEGER NOT NULL DEFAULT 0, `status` TEXT NOT NULL DEFAULT 'PENDING', `last_error` TEXT, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_backfill_runs_assistant_id` ON `memory_backfill_runs` (`assistant_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_backfill_runs_status` ON `memory_backfill_runs` (`status`)")

        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ai")
        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_au")
        db.execSQL("DROP TRIGGER IF EXISTS memory_fts_ad")
        db.execSQL("DROP TABLE IF EXISTS memory_fts")
        db.execSQL(MEMORY_FTS_PORTABLE_CREATE_SQL.trimIndent())
        db.execSQL(MEMORY_FTS_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    }
}

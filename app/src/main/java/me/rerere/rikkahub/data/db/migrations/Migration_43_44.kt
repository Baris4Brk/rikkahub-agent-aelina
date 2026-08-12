package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val MEMORY_V44_OLD_CAPTURE_UNIQUE_INDEX =
    "index_memory_captures_conversation_id_assistant_message_id_capture_source"

internal const val MEMORY_V44_SCOPED_CAPTURE_UNIQUE_INDEX_SQL =
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_memory_captures_scope_id_conversation_id_assistant_message_id_capture_source` " +
        "ON `memory_captures` (`scope_id`, `conversation_id`, `assistant_message_id`, `capture_source`)"

internal const val MEMORY_V44_SOURCE_TOMBSTONES_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `memory_source_tombstones` (" +
        "`scope_id` TEXT NOT NULL, " +
        "`conversation_id` TEXT NOT NULL, " +
        "`source_kind` TEXT NOT NULL, " +
        "`source_id` TEXT NOT NULL, " +
        "`source_digest` TEXT NOT NULL DEFAULT '', " +
        "`reason_code` TEXT NOT NULL, " +
        "`tombstoned_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`scope_id`, `conversation_id`, `source_kind`, `source_id`, `source_digest`))"

internal val MEMORY_V44_EVIDENCE_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS `index_memory_evidence_conversation_id_message_id_source_digest` " +
        "ON `memory_evidence` (`conversation_id`, `message_id`, `source_digest`)",
    "CREATE INDEX IF NOT EXISTS `index_memory_evidence_link_id_evidence_group_id` " +
        "ON `memory_evidence` (`link_id`, `evidence_group_id`)",
    "CREATE INDEX IF NOT EXISTS `index_memory_evidence_memory_id_evidence_group_id` " +
        "ON `memory_evidence` (`memory_id`, `evidence_group_id`)",
)

/**
 * Best-effort conversion of source invalidations that predate durable tombstones.
 *
 * Evidence is polymorphic, so scope is recovered from its owning memory/candidate/relation/link,
 * with a still-present conversation as the final fallback. Rows whose scope cannot be proven are
 * deliberately skipped rather than being assigned to another assistant.
 */
internal val MEMORY_V44_SOURCE_TOMBSTONE_BACKFILL_SQL = listOf(
    "INSERT OR IGNORE INTO `memory_source_tombstones` (" +
        "`scope_id`, `conversation_id`, `source_kind`, `source_id`, `source_digest`, " +
        "`reason_code`, `tombstoned_at_ms`) " +
        "SELECT `resolved_scope_id`, `conversation_id`, 'MESSAGE', `message_id`, " +
        "`source_digest`, 'MIGRATED_SOURCE_DELETED', `captured_at_ms` FROM (" +
        "SELECT COALESCE(" +
        "NULLIF((SELECT `assistant_id` FROM `MemoryEntity` WHERE `id` = e.`memory_id`), ''), " +
        "NULLIF((SELECT `scope_id` FROM `memory_candidates` WHERE `id` = e.`candidate_id`), ''), " +
        "NULLIF((SELECT `scope_id` FROM `memory_relation_candidates` " +
        "WHERE `id` = e.`relation_candidate_id`), ''), " +
        "NULLIF((SELECT `scope_id` FROM `memory_links` WHERE `id` = e.`link_id`), ''), " +
        "NULLIF((SELECT `assistant_id` FROM `ConversationEntity` " +
        "WHERE `id` = e.`conversation_id`), '')" +
        ") AS `resolved_scope_id`, e.`conversation_id`, e.`message_id`, e.`source_digest`, " +
        "e.`captured_at_ms` FROM `memory_evidence` e " +
        "WHERE e.`quality` = 'SOURCE_DELETED' " +
        "AND e.`conversation_id` <> '' AND e.`message_id` <> '') AS `legacy_deleted` " +
        "WHERE `resolved_scope_id` IS NOT NULL AND `resolved_scope_id` <> ''",
    "INSERT OR IGNORE INTO `memory_source_tombstones` (" +
        "`scope_id`, `conversation_id`, `source_kind`, `source_id`, `source_digest`, " +
        "`reason_code`, `tombstoned_at_ms`) " +
        "SELECT m.`assistant_id`, r.`source_conversation_id`, 'CONVERSATION', " +
        "r.`source_conversation_id`, '', 'SOURCE_CONVERSATION_DELETED', r.`created_at_ms` " +
        "FROM `memory_revisions` r " +
        "INNER JOIN `MemoryEntity` m ON m.`id` = r.`memory_id` " +
        "WHERE r.`reason_code` = 'SOURCE_CONVERSATION_DELETED' " +
        "AND r.`source_conversation_id` IS NOT NULL " +
        "AND r.`source_conversation_id` <> '' AND m.`assistant_id` <> ''",
)

/**
 * v44 makes capture idempotency scope-aware and introduces immutable source identities,
 * grouped evidence identity, and durable deletion tombstones.
 */
val MIGRATION_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `memory_captures` ADD COLUMN " +
                "`source_identities_json` TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN " +
                "`source_identities_json` TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL(
            "ALTER TABLE `memory_revisions` ADD COLUMN " +
                "`source_identities_json` TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL("DROP INDEX IF EXISTS `$MEMORY_V44_OLD_CAPTURE_UNIQUE_INDEX`")
        db.execSQL(MEMORY_V44_SCOPED_CAPTURE_UNIQUE_INDEX_SQL)

        db.execSQL(
            "ALTER TABLE `memory_evidence` ADD COLUMN " +
                "`evidence_group_id` TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE `memory_evidence` ADD COLUMN " +
                "`source_digest` TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE `memory_evidence` ADD COLUMN " +
                "`source_kind` TEXT NOT NULL DEFAULT 'TEXT'",
        )
        db.execSQL(
            "UPDATE `memory_evidence` SET `evidence_group_id` = `id` " +
                "WHERE `evidence_group_id` = ''",
        )
        MEMORY_V44_EVIDENCE_INDEX_SQL.forEach(db::execSQL)

        db.execSQL(MEMORY_V44_SOURCE_TOMBSTONES_TABLE_SQL)
        MEMORY_V44_SOURCE_TOMBSTONE_BACKFILL_SQL.forEach(db::execSQL)
    }
}

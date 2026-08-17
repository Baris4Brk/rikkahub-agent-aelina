package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val LEARNING_V47_OUTBOX_COLUMNS = listOf(
    "reward_dimension" to "TEXT",
    "reward_signal_kind" to "TEXT",
    "reward_value_milli" to "INTEGER",
    "execution_verification_state" to "TEXT",
)

internal val LEARNING_V47_SENTINEL_PAYLOAD_COLUMNS =
    LEARNING_V46_SENTINEL_PAYLOAD_COLUMNS + LEARNING_V47_OUTBOX_COLUMNS.map { it.first }

internal const val LEARNING_V47_REWARD_FEEDBACK_AUTHORITY_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `learning_reward_feedback_authority` (" +
        "`feedback_id` TEXT NOT NULL, " +
        "`scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`conversation_id` TEXT NOT NULL, " +
        "`conversation_source_revision` INTEGER NOT NULL, " +
        "`command_id` TEXT NOT NULL, " +
        "`command_revision` INTEGER NOT NULL, " +
        "`lineage_id` TEXT NOT NULL, " +
        "`branch_anchor_message_id` TEXT NOT NULL, " +
        "`branch_anchor_message_revision` INTEGER NOT NULL, " +
        "`target_assistant_message_id` TEXT NOT NULL, " +
        "`target_assistant_message_revision` INTEGER NOT NULL, " +
        "`dimension` TEXT NOT NULL, " +
        "`signal_kind` TEXT NOT NULL, " +
        "`value_milli` INTEGER, " +
        "`source_state` TEXT NOT NULL, " +
        "`source_revision` INTEGER NOT NULL, " +
        "`previous_source_revision` INTEGER, " +
        "`integrity_sha256` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`feedback_id`))"

internal const val LEARNING_V47_REWARD_FEEDBACK_REVISIONS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `learning_reward_feedback_revisions` (" +
        "`feedback_id` TEXT NOT NULL, " +
        "`scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`conversation_id` TEXT NOT NULL, " +
        "`conversation_source_revision` INTEGER NOT NULL, " +
        "`command_id` TEXT NOT NULL, " +
        "`command_revision` INTEGER NOT NULL, " +
        "`lineage_id` TEXT NOT NULL, " +
        "`branch_anchor_message_id` TEXT NOT NULL, " +
        "`branch_anchor_message_revision` INTEGER NOT NULL, " +
        "`target_assistant_message_id` TEXT NOT NULL, " +
        "`target_assistant_message_revision` INTEGER NOT NULL, " +
        "`dimension` TEXT NOT NULL, " +
        "`signal_kind` TEXT NOT NULL, " +
        "`value_milli` INTEGER, " +
        "`source_state` TEXT NOT NULL, " +
        "`source_revision` INTEGER NOT NULL, " +
        "`previous_source_revision` INTEGER, " +
        "`integrity_sha256` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`feedback_id`, `source_revision`), " +
        "FOREIGN KEY(`feedback_id`) REFERENCES `learning_reward_feedback_authority`(`feedback_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal val LEARNING_V47_REWARD_FEEDBACK_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `idx_learning_reward_feedback_target_dimension` " +
        "ON `learning_reward_feedback_authority` " +
        "(`scope_kind`, `scope_id`, `target_assistant_message_id`, " +
        "`target_assistant_message_revision`, `dimension`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_reward_feedback_command_revision` " +
        "ON `learning_reward_feedback_authority` (`command_id`, `command_revision`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_reward_feedback_updated` " +
        "ON `learning_reward_feedback_authority` (`updated_at_ms`, `feedback_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_reward_feedback_revision_feedback` " +
        "ON `learning_reward_feedback_revisions` (`feedback_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_reward_feedback_revision_target` " +
        "ON `learning_reward_feedback_revisions` " +
        "(`scope_kind`, `scope_id`, `target_assistant_message_id`, " +
        "`target_assistant_message_revision`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_reward_feedback_revision_scan` " +
        "ON `learning_reward_feedback_revisions` " +
        "(`updated_at_ms`, `feedback_id`, `source_revision`)",
)

/** v47 adds explicit reward authority; historical v46 DDL remains byte-for-byte unchanged. */
val MIGRATION_46_47 = object : Migration(46, 47) {
    override fun migrate(db: SupportSQLiteDatabase) {
        requireHealthyLearningOutbox(db)
        ensureV47Columns(db, "learning_outbox", LEARNING_V47_OUTBOX_COLUMNS)
        db.execSQL(LEARNING_V47_REWARD_FEEDBACK_AUTHORITY_TABLE_SQL)
        db.execSQL(LEARNING_V47_REWARD_FEEDBACK_REVISIONS_TABLE_SQL)
        LEARNING_V47_REWARD_FEEDBACK_INDEX_SQL.forEach(db::execSQL)
        requireHealthyLearningOutboxV47(db)
    }
}

private fun ensureV47Columns(
    db: SupportSQLiteDatabase,
    table: String,
    additions: List<Pair<String, String>>,
) {
    val existing = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        buildSet {
            if (nameIndex >= 0) while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
    additions.forEach { (name, declaration) ->
        if (name !in existing) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$name` $declaration")
    }
}

internal fun requireHealthyLearningOutboxV47(db: SupportSQLiteDatabase) {
    val payloadProjection = LEARNING_V47_SENTINEL_PAYLOAD_COLUMNS.joinToString(", ") { "`$it`" }
    db.query(
        "SELECT " + payloadProjection + " FROM `learning_outbox` " +
            "WHERE `event_type` = 'STREAM_INIT' LIMIT 2",
    ).use { cursor ->
        check(cursor.count == 1 && cursor.moveToFirst()) { "Invalid v47 Learning stream sentinel" }
        repeat(cursor.columnCount) { column ->
            check(cursor.isNull(column)) { "Learning stream sentinel contains v47 payload" }
        }
    }
}

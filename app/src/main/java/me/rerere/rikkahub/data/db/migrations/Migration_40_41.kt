package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Durable, redacted model-confirmed shortcut metadata for the active second user. */
val MIGRATION_40_41 = object : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tool_shortcuts` (" +
                "`shortcut_id` TEXT NOT NULL, " +
                "`authority_subject_id` TEXT NOT NULL, " +
                "`tool_name` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`category_path` TEXT NOT NULL, " +
                "`risk` TEXT NOT NULL, " +
                "`schema_fingerprint` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`state_version` INTEGER NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, " +
                "`updated_at_ms` INTEGER NOT NULL, " +
                "`last_used_at_ms` INTEGER, " +
                "`use_count` INTEGER NOT NULL, " +
                "`model_confirmed_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`shortcut_id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_shortcuts_authority_subject_id_state_updated_at_ms` " +
                "ON `tool_shortcuts` (`authority_subject_id`, `state`, `updated_at_ms`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_tool_shortcuts_authority_subject_id_tool_name_schema_fingerprint` " +
                "ON `tool_shortcuts` (`authority_subject_id`, `tool_name`, `schema_fingerprint`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_shortcuts_tool_name_state` " +
                "ON `tool_shortcuts` (`tool_name`, `state`)",
        )
    }
}

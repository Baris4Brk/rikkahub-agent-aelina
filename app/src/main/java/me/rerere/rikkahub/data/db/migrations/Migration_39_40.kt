package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Durable, redacted procedural memories for the exact active second-user authority. */
val MIGRATION_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tool_experiences` (" +
                "`experience_id` TEXT NOT NULL, " +
                "`authority_subject_id` TEXT NOT NULL, " +
                "`primary_tool_name` TEXT NOT NULL, " +
                "`tool_names_json` TEXT NOT NULL, " +
                "`category_path` TEXT NOT NULL, " +
                "`schema_fingerprint` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`tags_json` TEXT NOT NULL, " +
                "`state` TEXT NOT NULL, " +
                "`confidence` TEXT NOT NULL, " +
                "`state_version` INTEGER NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, " +
                "`updated_at_ms` INTEGER NOT NULL, " +
                "`last_observed_at_ms` INTEGER NOT NULL, " +
                "`last_verified_at_ms` INTEGER, " +
                "`deleted_at_ms` INTEGER, " +
                "PRIMARY KEY(`experience_id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_experiences_authority_subject_id_state_updated_at_ms` " +
                "ON `tool_experiences` (`authority_subject_id`, `state`, `updated_at_ms`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_experiences_authority_subject_id_primary_tool_name_schema_fingerprint` " +
                "ON `tool_experiences` (`authority_subject_id`, `primary_tool_name`, `schema_fingerprint`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_experiences_primary_tool_name_state` " +
                "ON `tool_experiences` (`primary_tool_name`, `state`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_experiences_deleted_at_ms` " +
                "ON `tool_experiences` (`deleted_at_ms`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tool_experience_evidence` (" +
                "`evidence_id` TEXT NOT NULL, " +
                "`experience_id` TEXT NOT NULL, " +
                "`execution_id` TEXT NOT NULL, " +
                "`tool_name` TEXT NOT NULL, " +
                "`schema_fingerprint` TEXT NOT NULL, " +
                "`outcome_kind` TEXT NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`evidence_id`), " +
                "FOREIGN KEY(`experience_id`) REFERENCES `tool_experiences`(`experience_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_experience_evidence_experience_id_created_at_ms` " +
                "ON `tool_experience_evidence` (`experience_id`, `created_at_ms`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_experience_evidence_execution_id` " +
                "ON `tool_experience_evidence` (`execution_id`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tool_experience_revisions` (" +
                "`revision_id` TEXT NOT NULL, " +
                "`experience_id` TEXT NOT NULL, " +
                "`revision` INTEGER NOT NULL, " +
                "`actor` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`tags_json` TEXT NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`revision_id`), " +
                "FOREIGN KEY(`experience_id`) REFERENCES `tool_experiences`(`experience_id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tool_experience_revisions_experience_id_revision` " +
                "ON `tool_experience_revisions` (`experience_id`, `revision`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tool_experience_revisions_created_at_ms` " +
                "ON `tool_experience_revisions` (`created_at_ms`)",
        )
    }
}

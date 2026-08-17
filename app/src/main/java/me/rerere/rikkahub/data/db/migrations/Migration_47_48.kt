package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** `policy_revision` binds immutable policy content; lifecycle CAS versions are never grants. */
internal const val LEARNING_V48_GRANT_POLICY_REVISION_SEMANTICS =
    "LEARNING_POLICY_CONTENT_REVISION"

internal const val LEARNING_V48_POLICY_GRANTS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `learning_policy_grants` (" +
        "`grant_id` TEXT NOT NULL, " +
        "`source_stream_id` TEXT NOT NULL, " +
        "`policy_id` TEXT NOT NULL, " +
        "`policy_revision` INTEGER NOT NULL, " +
        "`artifact_sha256` TEXT NOT NULL, " +
        "`scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`consuming_assistant_id` TEXT NOT NULL, " +
        "`actor` TEXT NOT NULL, " +
        "`state` TEXT NOT NULL, " +
        "`state_version` INTEGER NOT NULL, " +
        "`granted_at_ms` INTEGER NOT NULL, " +
        "`revoked_at_ms` INTEGER, " +
        "`reason_code` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`grant_id`))"

internal const val LEARNING_V48_POLICY_GRANT_REVISIONS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `learning_policy_grant_revisions` (" +
        "`grant_id` TEXT NOT NULL, " +
        "`source_stream_id` TEXT NOT NULL, " +
        "`policy_id` TEXT NOT NULL, " +
        "`policy_revision` INTEGER NOT NULL, " +
        "`artifact_sha256` TEXT NOT NULL, " +
        "`scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`consuming_assistant_id` TEXT NOT NULL, " +
        "`actor` TEXT NOT NULL, " +
        "`state` TEXT NOT NULL, " +
        "`state_version` INTEGER NOT NULL, " +
        "`granted_at_ms` INTEGER NOT NULL, " +
        "`revoked_at_ms` INTEGER, " +
        "`reason_code` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "`previous_state_version` INTEGER, " +
        "`changed_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`grant_id`, `state_version`), " +
        "FOREIGN KEY(`grant_id`) REFERENCES `learning_policy_grants`(`grant_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal val LEARNING_V48_POLICY_GRANT_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `idx_learning_policy_grants_stream_scope_policy` " +
        "ON `learning_policy_grants` " +
        "(`source_stream_id`, `scope_kind`, `scope_id`, `consuming_assistant_id`, `policy_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_policy_grants_scope_state` " +
        "ON `learning_policy_grants` " +
        "(`source_stream_id`, `scope_kind`, `scope_id`, `consuming_assistant_id`, `state`, `updated_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_policy_grants_updated` " +
        "ON `learning_policy_grants` (`updated_at_ms`, `grant_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_policy_grant_revisions_policy` " +
        "ON `learning_policy_grant_revisions` " +
        "(`source_stream_id`, `policy_id`, `policy_revision`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_policy_grant_revisions_scope` " +
        "ON `learning_policy_grant_revisions` " +
        "(`source_stream_id`, `scope_kind`, `scope_id`, `consuming_assistant_id`, `state`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_policy_grant_revisions_changed` " +
        "ON `learning_policy_grant_revisions` " +
        "(`changed_at_ms`, `grant_id`, `state_version`)",
)

/** v48 adds only content-free user-review authority; LearningDatabase remains separately owned. */
val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(db: SupportSQLiteDatabase) {
        requireHealthyLearningOutboxV47(db)
        db.execSQL(LEARNING_V48_POLICY_GRANTS_TABLE_SQL)
        db.execSQL(LEARNING_V48_POLICY_GRANT_REVISIONS_TABLE_SQL)
        LEARNING_V48_POLICY_GRANT_INDEX_SQL.forEach(db::execSQL)
        requireHealthyLearningOutboxV47(db)
    }
}

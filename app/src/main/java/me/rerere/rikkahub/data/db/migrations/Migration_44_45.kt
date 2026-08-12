package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal const val MEMORY_V45_SCOPE_STATE_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `memory_scope_state` (" +
        "`scope_id` TEXT NOT NULL, " +
        "`memory_epoch` INTEGER NOT NULL DEFAULT 0, " +
        "`observer_checkpoint_epoch` INTEGER NOT NULL DEFAULT 0, " +
        "`active_run_id` TEXT, " +
        "`active_run_lease_until_ms` INTEGER, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "`last_reason_code` TEXT, " +
        "PRIMARY KEY(`scope_id`))"

internal const val MEMORY_V45_SCOPE_STATE_ACTIVE_RUN_INDEX_SQL =
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_scope_state_active_run_id` " +
        "ON `memory_scope_state` (`active_run_id`)"

internal const val MEMORY_V45_SCOPE_STATE_LEASE_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_memory_scope_state_active_run_lease_until_ms` " +
        "ON `memory_scope_state` (`active_run_lease_until_ms`)"

internal const val MEMORY_V45_SCOPE_CHANGES_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `memory_scope_changes` (" +
        "`change_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`memory_epoch` INTEGER NOT NULL, " +
        "`entity_kind` TEXT NOT NULL, " +
        "`entity_id` TEXT NOT NULL, " +
        "`entity_revision` INTEGER, " +
        "`operation` TEXT NOT NULL, " +
        "`reason_code` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "FOREIGN KEY(`scope_id`) REFERENCES `memory_scope_state`(`scope_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal val MEMORY_V45_SCOPE_CHANGES_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS " +
        "`index_memory_scope_changes_scope_id_memory_epoch_entity_kind_entity_id` " +
        "ON `memory_scope_changes` (`scope_id`, `memory_epoch`, `entity_kind`, `entity_id`)",
    "CREATE INDEX IF NOT EXISTS `index_memory_scope_changes_scope_id_memory_epoch_change_id` " +
        "ON `memory_scope_changes` (`scope_id`, `memory_epoch`, `change_id`)",
)

internal const val MEMORY_V45_DREAM_RUNS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `dream_runs` (" +
        "`run_id` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`mode` TEXT NOT NULL, " +
        "`status` TEXT NOT NULL DEFAULT 'PENDING', " +
        "`base_memory_epoch` INTEGER NOT NULL, " +
        "`base_observer_checkpoint_epoch` INTEGER NOT NULL, " +
        "`attempt` INTEGER NOT NULL DEFAULT 0, " +
        "`lease_owner` TEXT, " +
        "`lease_until_ms` INTEGER, " +
        "`checkpoint_epoch` INTEGER NOT NULL DEFAULT 0, " +
        "`failure_code` TEXT, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`started_at_ms` INTEGER, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "`finished_at_ms` INTEGER, " +
        "PRIMARY KEY(`run_id`), " +
        "FOREIGN KEY(`scope_id`) REFERENCES `memory_scope_state`(`scope_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal val MEMORY_V45_DREAM_RUNS_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS `index_dream_runs_scope_id_status_started_at_ms` " +
        "ON `dream_runs` (`scope_id`, `status`, `started_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_dream_runs_scope_id_created_at_ms` " +
        "ON `dream_runs` (`scope_id`, `created_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `index_dream_runs_status_lease_until_ms` " +
        "ON `dream_runs` (`status`, `lease_until_ms`)",
)

internal val MEMORY_V45_OBSERVER_SCHEMA_SQL = buildList {
    add(MEMORY_V45_SCOPE_STATE_TABLE_SQL)
    add(MEMORY_V45_SCOPE_STATE_ACTIVE_RUN_INDEX_SQL)
    add(MEMORY_V45_SCOPE_STATE_LEASE_INDEX_SQL)
    add(MEMORY_V45_SCOPE_CHANGES_TABLE_SQL)
    addAll(MEMORY_V45_SCOPE_CHANGES_INDEX_SQL)
    add(MEMORY_V45_DREAM_RUNS_TABLE_SQL)
    addAll(MEMORY_V45_DREAM_RUNS_INDEX_SQL)
}

/** v45 installs the dormant Observer ledger. No worker, model, or runtime path is enabled. */
val MIGRATION_44_45 = object : Migration(44, 45) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MEMORY_V45_OBSERVER_SCHEMA_SQL.forEach(db::execSQL)
    }
}

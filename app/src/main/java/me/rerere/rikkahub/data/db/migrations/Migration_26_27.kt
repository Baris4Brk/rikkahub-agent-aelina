package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v26 → v27: add alarms table.
 *
 * The table is created WITHOUT DEFAULT values and WITHOUT the index
 * so the resulting schema matches what Room expects from AlarmEntity
 * (Kotlin defaults and Room indices are compile-time metadata, not SQL-level).
 * If a prior Reconciler pass left a stale alarms table with defaults+index,
 * the rename‑based rebuild below corrects it so Room validation passes.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Rebuild alarms so it matches the canonical v29 schema.
        // A plain CREATE TABLE IF NOT EXISTS would skip an already‑existing
        // table that may have been created by an older Reconciler with
        // DEFAULT clauses + index that the current entity doesn't declare.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `alarms_new` (
                `id` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `note` TEXT,
                `scheduleType` TEXT NOT NULL,
                `time` TEXT,
                `hour` INTEGER,
                `minute` INTEGER,
                `daysOfWeek` TEXT,
                `timezone` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `vibrate` INTEGER NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                `lastFiredAtMs` INTEGER,
                `nextFireAtMs` INTEGER,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        // If a legacy alarms table already exists (from an older
        // ImportedDatabaseReconciler or the original migration), migrate
        // its data — every column name matches, so a simple INSERT …
        // SELECT works. Drop the old table and index when done.
        val hasOld = db.query("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='alarms'").use { cursor ->
            cursor.moveToFirst(); cursor.getInt(0) > 0
        }
        if (hasOld) {
            db.execSQL("INSERT OR IGNORE INTO alarms_new SELECT * FROM alarms")
            db.execSQL("DROP TABLE IF EXISTS alarms")
        }
        db.execSQL("ALTER TABLE alarms_new RENAME TO alarms")
        // Drop the orphan index if a prior run created it.
        db.execSQL("DROP INDEX IF EXISTS `index_alarms_enabled_nextFireAtMs`")
    }
}

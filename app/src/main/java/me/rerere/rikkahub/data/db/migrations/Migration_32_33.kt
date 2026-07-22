package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Freezes the user-selected conversation context window on every queued memory capture. */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `memory_captures` ADD COLUMN `context_turn_limit` " +
                "INTEGER NOT NULL DEFAULT 12",
        )
    }
}

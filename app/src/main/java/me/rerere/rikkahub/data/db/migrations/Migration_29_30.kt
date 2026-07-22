package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_V30_BACKFILL_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_V30_PORTABLE_CREATE_SQL
import me.rerere.rikkahub.data.db.fts.MEMORY_FTS_V30_TRIGGER_SQL

/** Adds bounded-memory metadata and seeds a portable FTS5 projection for every old row. */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `MemoryEntity` ADD COLUMN `title` TEXT")
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `updated_at_ms` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `MemoryEntity` ADD COLUMN `importance` REAL NOT NULL DEFAULT 0.5",
        )
        db.execSQL(MEMORY_FTS_V30_PORTABLE_CREATE_SQL.trimIndent())
        db.execSQL(MEMORY_FTS_V30_BACKFILL_SQL.trimIndent())
        MEMORY_FTS_V30_TRIGGER_SQL.forEach(db::execSQL)
    }
}

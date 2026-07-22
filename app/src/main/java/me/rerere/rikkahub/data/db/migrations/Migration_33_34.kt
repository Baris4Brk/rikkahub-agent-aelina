package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_bookmarks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `normalized_url` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_browser_bookmarks_normalized_url` ON `browser_bookmarks` (`normalized_url`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `browser_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `normalized_url` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `visited_at_ms` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_history_normalized_url` ON `browser_history` (`normalized_url`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_browser_history_visited_at_ms` ON `browser_history` (`visited_at_ms`)",
        )
    }
}

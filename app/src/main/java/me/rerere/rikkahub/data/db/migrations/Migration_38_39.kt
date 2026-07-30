package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Durable command authority snapshot. Null legacy rows deliberately cannot regain elevation. */
val MIGRATION_38_39 = object : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pending_chat_commands` ADD COLUMN `authoritySubjectId` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_authoritySubjectId` " +
                "ON `pending_chat_commands` (`authoritySubjectId`)",
        )
    }
}

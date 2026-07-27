package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Makes reverse chronological windows proportional to the requested page, not chat length. */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                "ON `message_node` (`conversation_id`, `node_index`)",
        )
    }
}

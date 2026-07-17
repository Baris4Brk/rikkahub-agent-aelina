package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit v28 -> v29 migration for the durable conversation command queue.
 *
 * Keep this SQL in source rather than relying on generated AutoMigration output so the
 * durable queue schema is reviewable and can be exercised by migration tests.
 *
 * ALSO rebuilds the `alarms` table at this boundary to strip DEFAULT clauses and
 * the orphan index that the original `Migration_26_27` created — Room's KSP compiler
 * does not emit those SQL defaults into the canonical schema, so any database that
 * still carries the v26-era alarms table will fail Room validation on the very next
 * migration (including this one).  Rebuilding here is safe because every column name
 * is identical — only the SQL defaults + index are removed.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Official RikkaHub backups already contain this column, while older fork databases
        // may not. Make the v29 target schema deterministic for both paths.
        ensureConversationFolderColumn(db)

        // ── Step 1: Rebuild alarms to strip DEFAULT clauses + index ─────────────
        rebuildAlarms(db)

        // ── Step 2: Create pending_chat_commands ────────────────────────────────
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_chat_commands` (
                `id` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `conversationId` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `priority` INTEGER NOT NULL,
                `sequence` INTEGER NOT NULL,
                `expectedTargetVersion` INTEGER,
                `expectedBranchHeadMessageId` TEXT,
                `dedupeKey` TEXT,
                `idempotencyKey` TEXT NOT NULL,
                `attempt` INTEGER NOT NULL,
                `claimedBy` TEXT,
                `leaseUntil` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `startedAt` INTEGER,
                `finishedAt` INTEGER,
                `expiresAt` INTEGER,
                `lastErrorCode` TEXT,
                `lastErrorMessage` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_conversationId` " +
                "ON `pending_chat_commands` (`conversationId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_conversationId_state_priority_sequence` " +
                "ON `pending_chat_commands` (`conversationId`, `state`, `priority`, `sequence`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_leaseUntil` " +
                "ON `pending_chat_commands` (`leaseUntil`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_dedupeKey` " +
                "ON `pending_chat_commands` (`dedupeKey`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_chat_commands_idempotencyKey` " +
                "ON `pending_chat_commands` (`idempotencyKey`)"
        )
    }
}

private fun ensureConversationFolderColumn(db: SupportSQLiteDatabase) {
    val hasFolderId = db.query("PRAGMA table_info(`ConversationEntity`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) {
            false
        } else {
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "folder_id") {
                    found = true
                    break
                }
            }
            found
        }
    }
    if (!hasFolderId) {
        db.execSQL(
            "ALTER TABLE `ConversationEntity` ADD COLUMN `folder_id` TEXT NOT NULL DEFAULT ''"
        )
    }
}

/**
 * Rebuild the `alarms` table without DEFAULT clauses and without the orphan index
 * that the original v26→v27 migration left behind.
 *
 * Room's KSP compiler does not turn Kotlin constructor-defaults into SQL DEFAULT,
 * so a table that was created by an older migration (which DID include DEFAULTs)
 * will fail Room validation on every subsequent migration.
 *
 * Strategy (safe against prior reconciler / partial upgrades):
 *  1. Create `alarms_new` with the canonical (DEFAULT-less) schema.
 *  2. If a legacy `alarms` already exists, copy every row — every column name
 *     is identical so INSERT … SELECT works without column lists.
 *  3. Drop the old table + the stale index.
 *  4. Rename `alarms_new` → `alarms`.
 */
private fun rebuildAlarms(db: SupportSQLiteDatabase) {
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
    val hasOld = db.query(
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='alarms'"
    ).use { cursor ->
        cursor.moveToFirst(); cursor.getInt(0) > 0
    }
    if (hasOld) {
        db.execSQL("INSERT OR IGNORE INTO alarms_new SELECT * FROM alarms")
        db.execSQL("DROP TABLE IF EXISTS alarms")
    }
    db.execSQL("ALTER TABLE alarms_new RENAME TO alarms")
    db.execSQL("DROP INDEX IF EXISTS `index_alarms_enabled_nextFireAtMs`")
}

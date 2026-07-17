package me.rerere.rikkahub.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log

/**
 * Reconciles a database file that was just restored from a backup so Room can open it.
 *
 * The fork added several tables (scheduled jobs, workflows, ssh hosts, telegram chats,
 * the agent-run ledger) on top of upstream RikkaHub. A backup exported from *upstream*
 * RikkaHub does not contain those tables, yet upstream and the fork share the same Room
 * schema version number. When such a backup is restored, Room reopens the file at the
 * matching version, runs no migration, and then either fails its integrity check or hits
 * "no such table: scheduled_jobs" at first query — the app crashes on the very first launch
 * after the import (see issue #8).
 *
 * This step runs once, right after the restore writes `rikka_hub.db`, on the raw file before
 * Room touches it:
 *  - It creates any of the fork-only tables that are missing, empty, with the exact v29
 *    schema Room expects (copied verbatim from app/schemas/.../29.json) — so the file looks
 *    like a clean agent install for those tables.
 *  - If the file is already stamped at the current schema version (so Room would run no
 *    migration), it rewrites Room's identity row to the fork's expected hash. Without this,
 *    Room rejects the foreign hash even though every table is now present. The shared tables
 *    already match because the fork tracks upstream's schema, so trusting the hash is sound.
 *  - If the file is at an older version (upgrade scenario, e.g. official v24→agent v29),
 *    it keeps the original user_version so Room runs every real migration, including the
 *    explicit 28→29 migration. The fork-only tables are pre-created only as compatibility
 *    scaffolding for upstream backups.
 *
 * Room then runs its normal migrations up to current and sets the identity itself; the
 *    pre-created tables simply let those migrations find the fork-only schema.
 * Backups newer than the app are left untouched (Room will report the downgrade).
 *
 * Best-effort: any failure here is logged and swallowed so a restore never half-breaks. The
 * worst case is the same pre-existing crash on next open, never data loss — there is no
 * destructive-migration fallback configured, so the restored rows always survive on disk.
 */
object ImportedDatabaseReconciler {

    private const val TAG = "DbReconciler"
    private const val DB_NAME = "rikka_hub"

    /**
     * Room's schema version and identity hash for [AppDatabase]. Both are copied verbatim
     * from app/schemas/me.rerere.rikkahub.data.db.AppDatabase/29.json. When the schema
     * version is bumped, update BOTH constants (and the table DDL below if the fork-only
     * tables changed) or this reconciliation will silently stop matching.
     */
    private const val EXPECTED_VERSION = 29
    private const val EXPECTED_IDENTITY_HASH = "f81c8788e40327589ad93784c2004d16"

    /**
     * Fork-only tables absent from an upstream backup, with their exact v29 create + index
     * statements. Every statement is IF NOT EXISTS so running it against a genuine agent
     * backup (where the tables already exist) is a no-op.
     */
    private val FORK_ONLY_DDL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `scheduled_jobs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT, `assistantId` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `atUnixMs` INTEGER, `intervalSeconds` INTEGER, `enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `nextRunAtMs` INTEGER, `mode` TEXT NOT NULL DEFAULT 'llm', `actionsJson` TEXT, `cronExpression` TEXT, `timezone` TEXT, `startAtUnixMs` INTEGER, `endAtUnixMs` INTEGER, `maxRuns` INTEGER, `runsSoFar` INTEGER NOT NULL DEFAULT 0, `catchup` TEXT NOT NULL DEFAULT 'fire_once', `description` TEXT, `tags` TEXT, `targetConversationId` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `scheduled_job_runs` (`id` TEXT NOT NULL, `jobId` TEXT NOT NULL, `mode` TEXT NOT NULL, `scheduledAtMs` INTEGER NOT NULL, `startedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER, `outcome` TEXT NOT NULL, `conversationId` TEXT, `errorMessage` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `ssh_hosts` (`name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `user` TEXT NOT NULL, `password` TEXT, `privateKey` TEXT, `passphrase` TEXT, `createdAtMs` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        "CREATE TABLE IF NOT EXISTS `telegram_chats` (`chatId` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `lastMessageAtMs` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `workflows` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `enabled` INTEGER NOT NULL DEFAULT 1, `definitionJson` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastRunAtMs` INTEGER, `lastRunStatus` TEXT, `lastRunError` TEXT, `runsTodayCount` INTEGER NOT NULL DEFAULT 0, `runsTodayDate` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `workflow_runs` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workflowId` TEXT NOT NULL, `firedAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `errorMessage` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId_firedAtMs` ON `workflow_runs` (`workflowId`, `firedAtMs`)",
        "CREATE TABLE IF NOT EXISTS `agent_runs` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `domain_id` TEXT NOT NULL, `parent_run_id` TEXT, `status` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `started_at_ms` INTEGER, `finished_at_ms` INTEGER, `last_error` TEXT, `metadata_json` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `idx_runs_status` ON `agent_runs` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_kind_dom` ON `agent_runs` (`kind`, `domain_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_parent` ON `agent_runs` (`parent_run_id`)",
        "CREATE INDEX IF NOT EXISTS `idx_runs_updated_at` ON `agent_runs` (`updated_at_ms`)",
        "CREATE TABLE IF NOT EXISTS `alarms` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `note` TEXT, `scheduleType` TEXT NOT NULL, `time` TEXT, `hour` INTEGER, `minute` INTEGER, `daysOfWeek` TEXT, `timezone` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `vibrate` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `lastFiredAtMs` INTEGER, `nextFireAtMs` INTEGER, PRIMARY KEY(`id`))",
        "DROP INDEX IF EXISTS `index_alarms_enabled_nextFireAtMs`",
        "CREATE TABLE IF NOT EXISTS `pending_chat_commands` (" +
            "`id` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, " +
            "`type` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `state` TEXT NOT NULL, " +
            "`priority` INTEGER NOT NULL, `sequence` INTEGER NOT NULL, " +
            "`expectedTargetVersion` INTEGER, `expectedBranchHeadMessageId` TEXT, `dedupeKey` TEXT, " +
            "`idempotencyKey` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `claimedBy` TEXT, " +
            "`leaseUntil` INTEGER, `createdAt` INTEGER NOT NULL, `startedAt` INTEGER, " +
            "`finishedAt` INTEGER, `expiresAt` INTEGER, `lastErrorCode` TEXT, `lastErrorMessage` TEXT, " +
            "PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_conversationId` " +
            "ON `pending_chat_commands` (`conversationId`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_conversationId_state_priority_sequence` " +
            "ON `pending_chat_commands` (`conversationId`, `state`, `priority`, `sequence`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_leaseUntil` " +
            "ON `pending_chat_commands` (`leaseUntil`)",
        "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_dedupeKey` " +
            "ON `pending_chat_commands` (`dedupeKey`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_chat_commands_idempotencyKey` " +
            "ON `pending_chat_commands` (`idempotencyKey`)",
    )

    private fun ensureConversationFolderV29Column(db: SQLiteDatabase) {
        val hasFolderId = db.rawQuery("PRAGMA table_info(`ConversationEntity`)", null).use { cursor ->
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

    private fun ensureScheduledJobsV29Column(db: SQLiteDatabase) {
        val hasTargetConversationId = db.rawQuery("PRAGMA table_info(`scheduled_jobs`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) {
                false
            } else {
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "targetConversationId") {
                        found = true
                        break
                    }
                }
                found
            }
        }
        if (!hasTargetConversationId) {
            db.execSQL("ALTER TABLE `scheduled_jobs` ADD COLUMN `targetConversationId` TEXT")
        }
    }

    /**
     * Call after a restore has written the database file, and only when the restore actually
     * included the database. Safe to call when the file is a genuine agent backup (every
     * statement is idempotent) or when the file does not exist (no-op).
     */
    fun reconcile(context: Context) {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            Log.i(TAG, "reconcile: no database file at ${dbFile.absolutePath}, skipping")
            return
        }
        try {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                val version = db.version // PRAGMA user_version
                if (version > EXPECTED_VERSION) {
                    Log.w(TAG, "reconcile: backup db version $version is newer than $EXPECTED_VERSION; leaving untouched")
                    return
                }

                db.beginTransaction()
                try {
                    FORK_ONLY_DDL.forEach(db::execSQL)
                    ensureScheduledJobsV29Column(db)
                    ensureConversationFolderV29Column(db)

                    // Older backups must keep their original user_version so Room can run
                    // every real migration (including 28→29). Precreating fork-only tables
                    // makes upstream backups compatible, but stamping v29 here would silently
                    // skip migrations and risk losing schema changes. Only a genuine v29 file
                    // is stamped with the fork identity hash because Room will not run a
                    // migration in that case.
                    if (version == EXPECTED_VERSION) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
                            arrayOf(EXPECTED_IDENTITY_HASH),
                        )
                    } else {
                        Log.i(TAG, "reconcile: kept older user_version=$version for Room migrations")
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                Log.i(TAG, "reconcile: reconciled imported db (version=$version)")
            }
        } catch (t: Throwable) {
            // Never let reconciliation break the restore. Worst case is the pre-existing
            // behaviour (a crash on next open); the user's rows are still on disk.
            Log.w(TAG, "reconcile: failed to reconcile imported db", t)
        }
    }
}

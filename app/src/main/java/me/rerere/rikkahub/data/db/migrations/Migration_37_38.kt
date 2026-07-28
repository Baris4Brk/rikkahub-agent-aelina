package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pet_dialogue_sessions` (
                `sessionId` TEXT NOT NULL,
                `assistantId` TEXT NOT NULL,
                `privilegedConversationId` TEXT NOT NULL,
                `localDate` TEXT NOT NULL,
                `zoneId` TEXT NOT NULL,
                `activeOwnerKey` TEXT,
                `status` TEXT NOT NULL,
                `archiveReason` TEXT,
                `title` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `notes` TEXT NOT NULL,
                `tagsJson` TEXT NOT NULL,
                `summaryState` TEXT NOT NULL,
                `stateVersion` INTEGER NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                `archivedAtMs` INTEGER,
                `deletedAtMs` INTEGER,
                PRIMARY KEY(`sessionId`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_assistantId` ON `pet_dialogue_sessions` (`assistantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_privilegedConversationId` ON `pet_dialogue_sessions` (`privilegedConversationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_assistantId_status` ON `pet_dialogue_sessions` (`assistantId`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_assistantId_localDate` ON `pet_dialogue_sessions` (`assistantId`, `localDate`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_activeOwnerKey` ON `pet_dialogue_sessions` (`activeOwnerKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_sessions_deletedAtMs` ON `pet_dialogue_sessions` (`deletedAtMs`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pet_dialogue_turns` (
                `turnId` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `inputKind` TEXT NOT NULL,
                `userText` TEXT,
                `interactionJson` TEXT,
                `assistantText` TEXT,
                `action` TEXT,
                `handoffRequestId` TEXT,
                `createdAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`turnId`),
                FOREIGN KEY(`sessionId`) REFERENCES `pet_dialogue_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_turns_sessionId` ON `pet_dialogue_turns` (`sessionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pet_dialogue_turns_sessionId_sequence` ON `pet_dialogue_turns` (`sessionId`, `sequence`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_turns_handoffRequestId` ON `pet_dialogue_turns` (`handoffRequestId`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pet_handoff_requests` (
                `requestId` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `turnId` TEXT NOT NULL,
                `assistantId` TEXT NOT NULL,
                `privilegedConversationId` TEXT NOT NULL,
                `mode` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `request` TEXT NOT NULL,
                `targetCommandId` TEXT,
                `stateVersion` INTEGER NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `submittedAtMs` INTEGER,
                `resolvedAtMs` INTEGER,
                `expiresAtMs` INTEGER,
                PRIMARY KEY(`requestId`),
                FOREIGN KEY(`sessionId`) REFERENCES `pet_dialogue_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`turnId`) REFERENCES `pet_dialogue_turns`(`turnId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_sessionId` ON `pet_handoff_requests` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_turnId` ON `pet_handoff_requests` (`turnId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_assistantId_status` ON `pet_handoff_requests` (`assistantId`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_targetCommandId` ON `pet_handoff_requests` (`targetCommandId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_handoff_requests_expiresAtMs` ON `pet_handoff_requests` (`expiresAtMs`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pet_dialogue_revisions` (
                `revisionId` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `actor` TEXT NOT NULL,
                `operation` TEXT NOT NULL,
                `previousTitle` TEXT NOT NULL,
                `previousSummary` TEXT NOT NULL,
                `previousNotes` TEXT NOT NULL,
                `previousTagsJson` TEXT NOT NULL,
                `previousStatus` TEXT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`revisionId`),
                FOREIGN KEY(`sessionId`) REFERENCES `pet_dialogue_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_revisions_sessionId` ON `pet_dialogue_revisions` (`sessionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pet_dialogue_revisions_sessionId_revision` ON `pet_dialogue_revisions` (`sessionId`, `revision`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_dialogue_revisions_createdAtMs` ON `pet_dialogue_revisions` (`createdAtMs`)")
    }
}

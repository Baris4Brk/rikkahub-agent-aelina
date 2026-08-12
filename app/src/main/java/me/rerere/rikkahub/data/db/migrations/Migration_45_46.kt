package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

internal const val LEARNING_V46_STREAM_INIT_EVENT_ID = "learning-stream-init:v1"

internal val LEARNING_V46_P0_COMMAND_AUTHORITY_COLUMNS = listOf(
    "assistantIdSnapshot" to "TEXT",
    "lineageId" to "TEXT",
    "parentCommandId" to "TEXT",
    "branchAnchorMessageId" to "TEXT",
    "stateVersion" to "INTEGER NOT NULL DEFAULT 0",
)

internal val LEARNING_V46_P1_COMMAND_AUTHORITY_COLUMNS = listOf(
    "branchAnchorMessageRevision" to "INTEGER",
    "conversationSourceRevision" to "INTEGER",
    "completionKind" to "TEXT",
    "resultAssistantMessageId" to "TEXT",
    "resultAssistantMessageRevision" to "INTEGER",
)

internal val LEARNING_V46_COMMAND_AUTHORITY_COLUMNS =
    LEARNING_V46_P0_COMMAND_AUTHORITY_COLUMNS + LEARNING_V46_P1_COMMAND_AUTHORITY_COLUMNS

internal val LEARNING_V46_P0_EXECUTION_SCOPE_COLUMNS = listOf(
    "learning_scope_kind" to "TEXT",
    "learning_scope_id" to "TEXT",
)

internal val LEARNING_V46_P1_EXECUTION_AUTHORITY_COLUMNS = listOf(
    "tool_call_id" to "TEXT",
    "tool_name" to "TEXT",
    "tool_schema_fingerprint" to "TEXT",
    "owning_assistant_message_id" to "TEXT",
    "owning_assistant_message_revision" to "INTEGER",
)

internal val LEARNING_V46_EXECUTION_SCOPE_COLUMNS =
    LEARNING_V46_P0_EXECUTION_SCOPE_COLUMNS + LEARNING_V46_P1_EXECUTION_AUTHORITY_COLUMNS

internal val LEARNING_V46_P1_AUTHORITY_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS `index_pending_chat_commands_completionKind_finishedAt` " +
        "ON `pending_chat_commands` (`completionKind`, `finishedAt`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_pending_chat_commands_resultAssistantMessageId_resultAssistantMessageRevision` " +
        "ON `pending_chat_commands` " +
        "(`resultAssistantMessageId`, `resultAssistantMessageRevision`)",
    "CREATE INDEX IF NOT EXISTS `idx_execution_records_tool_call` " +
        "ON `execution_records` (`tool_call_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_execution_records_tool_schema` " +
        "ON `execution_records` (`tool_name`, `tool_schema_fingerprint`)",
    "CREATE INDEX IF NOT EXISTS `idx_execution_records_owning_message` " +
        "ON `execution_records` " +
        "(`owning_assistant_message_id`, `owning_assistant_message_revision`)",
)

internal val LEARNING_V46_OUTBOX_P1_COLUMNS = listOf(
    "previous_source_revision" to "INTEGER",
    "source_state" to "TEXT",
    "conversation_source_revision" to "INTEGER",
    "branch_anchor_message_revision" to "INTEGER",
    "completion_kind" to "TEXT",
    "tool_name" to "TEXT",
    "tool_schema_fingerprint" to "TEXT",
    "message_revision" to "INTEGER",
)

internal const val LEARNING_V46_OUTBOX_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `learning_outbox` (" +
        "`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "`stream_id` TEXT NOT NULL, " +
        "`event_id` TEXT NOT NULL, " +
        "`event_type` TEXT NOT NULL, " +
        "`event_schema_version` INTEGER NOT NULL, " +
        "`terminal_state` TEXT, " +
        "`source_type` TEXT, " +
        "`source_id` TEXT, " +
        "`source_revision` INTEGER, " +
        "`previous_source_revision` INTEGER, " +
        "`source_state` TEXT, " +
        "`missing_revision_reason` TEXT, " +
        "`scope_kind` TEXT, " +
        "`scope_id` TEXT, " +
        "`conversation_id` TEXT, " +
        "`conversation_source_revision` INTEGER, " +
        "`command_id` TEXT, " +
        "`lineage_id` TEXT, " +
        "`parent_command_id` TEXT, " +
        "`branch_anchor_message_id` TEXT, " +
        "`branch_anchor_message_revision` INTEGER, " +
        "`completion_kind` TEXT, " +
        "`generation_run_id` TEXT, " +
        "`execution_id` TEXT, " +
        "`tool_call_id` TEXT, " +
        "`tool_name` TEXT, " +
        "`tool_schema_fingerprint` TEXT, " +
        "`message_id` TEXT, " +
        "`message_revision` INTEGER, " +
        "`occurred_at_ms` INTEGER, " +
        "`created_at_ms` INTEGER NOT NULL)"

internal val LEARNING_V46_OUTBOX_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_learning_outbox_event_id` " +
        "ON `learning_outbox` (`event_id`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_outbox_stream_id_seq` " +
        "ON `learning_outbox` (`stream_id`, `seq`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_outbox_event_type` " +
        "ON `learning_outbox` (`event_type`)",
    "CREATE INDEX IF NOT EXISTS `index_learning_outbox_source_type_source_id` " +
        "ON `learning_outbox` (`source_type`, `source_id`)",
)

internal const val LEARNING_V46_CONVERSATION_SOURCE_AUTHORITY_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `learning_conversation_source_authority` (" +
        "`scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`conversation_id` TEXT NOT NULL, " +
        "`assistant_id_snapshot` TEXT NOT NULL, " +
        "`source_revision` INTEGER NOT NULL, " +
        "`previous_source_revision` INTEGER, " +
        "`source_state` TEXT NOT NULL, " +
        "`change_kind` TEXT NOT NULL, " +
        "`branch_head_message_id` TEXT, " +
        "`branch_head_message_revision` INTEGER, " +
        "`occurred_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`scope_kind`, `scope_id`, `conversation_id`))"

internal const val LEARNING_V46_MESSAGE_SOURCE_AUTHORITY_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `learning_message_source_authority` (" +
        "`scope_kind` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`conversation_id` TEXT NOT NULL, " +
        "`message_id` TEXT NOT NULL, " +
        "`message_role` TEXT NOT NULL, " +
        "`source_revision` INTEGER NOT NULL, " +
        "`previous_source_revision` INTEGER, " +
        "`source_state` TEXT NOT NULL, " +
        "`change_kind` TEXT NOT NULL, " +
        "`payload_integrity_sha256` TEXT, " +
        "`occurred_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`scope_kind`, `scope_id`, `message_id`))"

internal val LEARNING_V46_SOURCE_AUTHORITY_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS `idx_learning_conversation_source_id` " +
        "ON `learning_conversation_source_authority` (`conversation_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_conversation_source_scope_state_updated` " +
        "ON `learning_conversation_source_authority` " +
        "(`scope_kind`, `scope_id`, `source_state`, `updated_at_ms`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_conversation_source_branch_head` " +
        "ON `learning_conversation_source_authority` " +
        "(`branch_head_message_id`, `branch_head_message_revision`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_conversation_source_scope_scan` " +
        "ON `learning_conversation_source_authority` " +
        "(`conversation_id`, `scope_kind`, `scope_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_message_source_id` " +
        "ON `learning_message_source_authority` (`message_id`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_message_source_conversation_state` " +
        "ON `learning_message_source_authority` " +
        "(`scope_kind`, `scope_id`, `conversation_id`, `source_state`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_message_source_conversation_revision` " +
        "ON `learning_message_source_authority` (`conversation_id`, `source_revision`)",
    "CREATE INDEX IF NOT EXISTS `idx_learning_message_source_conversation_scan` " +
        "ON `learning_message_source_authority` " +
        "(`scope_kind`, `scope_id`, `conversation_id`, `message_id`)",
)

internal val LEARNING_V46_SOURCE_AUTHORITY_TABLE_AND_INDEX_SQL = buildList {
    add(LEARNING_V46_CONVERSATION_SOURCE_AUTHORITY_TABLE_SQL)
    add(LEARNING_V46_MESSAGE_SOURCE_AUTHORITY_TABLE_SQL)
    addAll(LEARNING_V46_SOURCE_AUTHORITY_INDEX_SQL)
}

internal val LEARNING_V46_SENTINEL_PAYLOAD_COLUMNS = listOf(
    "terminal_state",
    "source_type",
    "source_id",
    "source_revision",
    "previous_source_revision",
    "source_state",
    "missing_revision_reason",
    "scope_kind",
    "scope_id",
    "conversation_id",
    "conversation_source_revision",
    "command_id",
    "lineage_id",
    "parent_command_id",
    "branch_anchor_message_id",
    "branch_anchor_message_revision",
    "completion_kind",
    "generation_run_id",
    "execution_id",
    "tool_call_id",
    "tool_name",
    "tool_schema_fingerprint",
    "message_id",
    "message_revision",
    "occurred_at_ms",
)

internal fun insertLearningOutboxStreamSentinel(
    db: SupportSQLiteDatabase,
    streamId: String,
    createdAtMs: Long,
) {
    require(runCatching { UUID.fromString(streamId) }.isSuccess) { "Invalid Learning stream ID" }
    require(createdAtMs >= 0L) { "Invalid Learning stream creation time" }
    db.execSQL(
        "INSERT OR IGNORE INTO `learning_outbox` (" +
            "`stream_id`, `event_id`, `event_type`, `event_schema_version`, `created_at_ms`) " +
            "VALUES (?, ?, 'STREAM_INIT', 1, ?)",
        arrayOf<Any>(streamId, LEARNING_V46_STREAM_INIT_EVENT_ID, createdAtMs),
    )
}

internal fun ensureLearningOutboxStreamSentinel(
    db: SupportSQLiteDatabase,
    streamId: String,
    createdAtMs: Long,
) {
    val rowCount = db.query("SELECT COUNT(*) FROM `learning_outbox`").use { cursor ->
        check(cursor.moveToFirst()) { "Learning outbox count query returned no row" }
        cursor.getLong(0)
    }
    if (rowCount == 0L) {
        insertLearningOutboxStreamSentinel(db, streamId, createdAtMs)
    }
    requireHealthyLearningOutbox(db)
}

/** Rejects a missing, duplicated, malformed or mixed database-stream sentinel. */
internal fun requireHealthyLearningOutbox(db: SupportSQLiteDatabase) {
    val summary = db.query(
        "SELECT COUNT(*), " +
            "SUM(CASE WHEN `event_type` = 'STREAM_INIT' THEN 1 ELSE 0 END), " +
            "COUNT(DISTINCT `stream_id`) FROM `learning_outbox`",
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Learning outbox health query returned no row" }
        Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
    }
    check(summary.first > 0L) { "Learning outbox has no stream sentinel" }
    check(summary.second == 1L) { "Learning outbox must have exactly one stream sentinel" }
    check(summary.third == 1L) { "Learning outbox contains mixed streams" }
    val payloadProjection = LEARNING_V46_SENTINEL_PAYLOAD_COLUMNS.joinToString(", ") {
        "`$it`"
    }
    db.query(
        "SELECT `seq`, `stream_id`, `event_id`, `event_schema_version`, " +
            payloadProjection + " " +
            "FROM `learning_outbox` WHERE `event_type` = 'STREAM_INIT' LIMIT 2",
    ).use { cursor ->
        check(cursor.moveToFirst()) { "Learning outbox sentinel disappeared" }
        check(cursor.getLong(0) > 0L) { "Learning outbox sentinel has invalid sequence" }
        check(runCatching { UUID.fromString(cursor.getString(1)) }.isSuccess) {
            "Learning outbox sentinel has invalid stream ID"
        }
        check(cursor.getString(2) == LEARNING_V46_STREAM_INIT_EVENT_ID) {
            "Learning outbox sentinel has invalid event ID"
        }
        check(cursor.getInt(3) == 1) { "Learning outbox sentinel has invalid schema" }
        for (column in 4 until cursor.columnCount) {
            check(cursor.isNull(column)) { "Learning outbox sentinel contains payload fields" }
        }
        check(!cursor.moveToNext()) { "Learning outbox contains multiple sentinels" }
    }
}

private fun ensureColumns(
    db: SupportSQLiteDatabase,
    table: String,
    additions: List<Pair<String, String>>,
) {
    val existing = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        buildSet {
            if (nameIndex >= 0) while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
    additions.forEach { (name, declaration) ->
        if (name !in existing) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$name` $declaration")
    }
}

internal val MEMORY_V46_SCOPE_STATE_COLUMNS = listOf(
    "dream_state_revision" to "INTEGER NOT NULL DEFAULT 0",
    "last_applied_memory_epoch" to "INTEGER NOT NULL DEFAULT 0",
    "active_snapshot_id" to "TEXT",
    "last_full_rebuild_at_ms" to "INTEGER",
)

internal val MEMORY_V46_DREAM_RUN_COLUMNS = listOf(
    "base_dream_revision" to "INTEGER NOT NULL DEFAULT 0",
    "source_timezone_id" to "TEXT",
    "model_identity_digest" to "TEXT",
    "provider_kind" to "TEXT",
    "prompt_contract_version" to "TEXT",
    "validator_version" to "TEXT",
    "input_memory_count" to "INTEGER",
    "input_tokens" to "INTEGER",
    "output_claim_count" to "INTEGER",
    "output_tokens" to "INTEGER",
    "input_manifest_hash" to "TEXT",
    "output_manifest_hash" to "TEXT",
)

internal const val MEMORY_V46_ACTIVE_SNAPSHOT_INDEX_SQL =
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_scope_state_active_snapshot_id` " +
        "ON `memory_scope_state` (`active_snapshot_id`)"

internal const val MEMORY_V46_DREAM_CLAIMS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `dream_claims` (" +
        "`claim_id` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`claim_revision` INTEGER NOT NULL, " +
        "`claim_key` TEXT NOT NULL, " +
        "`storage_class` TEXT NOT NULL, " +
        "`epistemic_type` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, " +
        "`statement` TEXT NOT NULL, " +
        "`state` TEXT NOT NULL, " +
        "`confidence` REAL NOT NULL, " +
        "`temporal_state` TEXT NOT NULL, " +
        "`valid_from_ms` INTEGER, " +
        "`valid_to_ms` INTEGER, " +
        "`learned_at_ms` INTEGER NOT NULL, " +
        "`source_timezone` TEXT NOT NULL, " +
        "`claim_hash` TEXT NOT NULL, " +
        "`created_by_run_id` TEXT NOT NULL, " +
        "`last_validated_memory_epoch` INTEGER NOT NULL, " +
        "`invalidated_at_ms` INTEGER, " +
        "`invalidation_reason` TEXT, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`updated_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`claim_id`), " +
        "FOREIGN KEY(`scope_id`) REFERENCES `memory_scope_state`(`scope_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal val MEMORY_V46_DREAM_CLAIMS_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_dream_claims_scope_id_claim_key` " +
        "ON `dream_claims` (`scope_id`, `claim_key`)",
    "CREATE INDEX IF NOT EXISTS `index_dream_claims_scope_id_state_updated_at_ms` " +
        "ON `dream_claims` (`scope_id`, `state`, `updated_at_ms`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_dream_claims_scope_id_last_validated_memory_epoch` " +
        "ON `dream_claims` (`scope_id`, `last_validated_memory_epoch`)",
)

internal const val MEMORY_V46_DREAM_CLAIM_VERSIONS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `dream_claim_versions` (" +
        "`claim_id` TEXT NOT NULL, " +
        "`claim_revision` INTEGER NOT NULL, " +
        "`canonical_claim_json` TEXT NOT NULL, " +
        "`content_hash` TEXT NOT NULL, " +
        "`source_manifest_hash` TEXT NOT NULL, " +
        "`reason_code` TEXT NOT NULL, " +
        "`created_by_run_id` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`claim_id`, `claim_revision`), " +
        "FOREIGN KEY(`claim_id`) REFERENCES `dream_claims`(`claim_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal const val MEMORY_V46_DREAM_CLAIM_SOURCES_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `dream_claim_version_sources` (" +
        "`claim_id` TEXT NOT NULL, " +
        "`claim_revision` INTEGER NOT NULL, " +
        "`memory_id` INTEGER NOT NULL, " +
        "`memory_revision` INTEGER NOT NULL, " +
        "`memory_semantic_hash` TEXT NOT NULL, " +
        "`memory_evidence_id` TEXT, " +
        "`support_type` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "PRIMARY KEY(`claim_id`, `claim_revision`, `memory_id`, `memory_revision`, " +
        "`support_type`), " +
        "FOREIGN KEY(`claim_id`, `claim_revision`) " +
        "REFERENCES `dream_claim_versions`(`claim_id`, `claim_revision`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
        "FOREIGN KEY(`memory_id`) REFERENCES `MemoryEntity`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
        "FOREIGN KEY(`memory_id`, `memory_revision`) " +
        "REFERENCES `memory_revisions`(`memory_id`, `revision`) " +
        "ON UPDATE NO ACTION ON DELETE RESTRICT)"

internal val MEMORY_V46_DREAM_CLAIM_SOURCES_INDEX_SQL = listOf(
    "CREATE INDEX IF NOT EXISTS " +
        "`index_dream_claim_version_sources_memory_id_memory_revision` " +
        "ON `dream_claim_version_sources` (`memory_id`, `memory_revision`)",
    "CREATE INDEX IF NOT EXISTS " +
        "`index_dream_claim_version_sources_claim_id_claim_revision` " +
        "ON `dream_claim_version_sources` (`claim_id`, `claim_revision`)",
    "CREATE INDEX IF NOT EXISTS `index_dream_claim_version_sources_memory_evidence_id` " +
        "ON `dream_claim_version_sources` (`memory_evidence_id`)",
)

internal const val MEMORY_V46_DREAM_SNAPSHOTS_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS `dream_snapshots` (" +
        "`snapshot_id` TEXT NOT NULL, " +
        "`scope_id` TEXT NOT NULL, " +
        "`snapshot_revision` INTEGER NOT NULL, " +
        "`source_memory_epoch` INTEGER NOT NULL, " +
        "`committed_dream_revision` INTEGER NOT NULL, " +
        "`status` TEXT NOT NULL, " +
        "`canonical_payload_json` TEXT NOT NULL, " +
        "`payload_sha256` TEXT NOT NULL, " +
        "`compiler_revision` TEXT NOT NULL, " +
        "`estimated_tokens` INTEGER NOT NULL, " +
        "`claim_count` INTEGER NOT NULL, " +
        "`created_by_run_id` TEXT NOT NULL, " +
        "`created_at_ms` INTEGER NOT NULL, " +
        "`supersedes_snapshot_id` TEXT, " +
        "`reason_code` TEXT NOT NULL, " +
        "PRIMARY KEY(`snapshot_id`), " +
        "FOREIGN KEY(`scope_id`) REFERENCES `memory_scope_state`(`scope_id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE)"

internal val MEMORY_V46_DREAM_SNAPSHOTS_INDEX_SQL = listOf(
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_dream_snapshots_scope_id_snapshot_revision` " +
        "ON `dream_snapshots` (`scope_id`, `snapshot_revision`)",
    "CREATE INDEX IF NOT EXISTS `index_dream_snapshots_scope_id_status_created_at_ms` " +
        "ON `dream_snapshots` (`scope_id`, `status`, `created_at_ms`)",
)

internal val MEMORY_V46_SYNTHESIS_TABLE_AND_INDEX_SQL = buildList {
    add(MEMORY_V46_ACTIVE_SNAPSHOT_INDEX_SQL)
    add(MEMORY_V46_DREAM_CLAIMS_TABLE_SQL)
    addAll(MEMORY_V46_DREAM_CLAIMS_INDEX_SQL)
    add(MEMORY_V46_DREAM_CLAIM_VERSIONS_TABLE_SQL)
    add(MEMORY_V46_DREAM_CLAIM_SOURCES_TABLE_SQL)
    addAll(MEMORY_V46_DREAM_CLAIM_SOURCES_INDEX_SQL)
    add(MEMORY_V46_DREAM_SNAPSHOTS_TABLE_SQL)
    addAll(MEMORY_V46_DREAM_SNAPSHOTS_INDEX_SQL)
}

/**
 * Final unpublished v46: Dreaming Shadow Synthesis plus content-free Learning authority heads.
 * No policy injection or user-visible Learning behavior is enabled by this schema migration.
 */
val MIGRATION_45_46 = object : Migration(45, 46) {
    override fun migrate(db: SupportSQLiteDatabase) {
        ensureColumns(db, "memory_scope_state", MEMORY_V46_SCOPE_STATE_COLUMNS)
        ensureColumns(db, "dream_runs", MEMORY_V46_DREAM_RUN_COLUMNS)
        ensureColumns(db, "pending_chat_commands", LEARNING_V46_COMMAND_AUTHORITY_COLUMNS)
        ensureColumns(db, "execution_records", LEARNING_V46_EXECUTION_SCOPE_COLUMNS)
        LEARNING_V46_P1_AUTHORITY_INDEX_SQL.forEach(db::execSQL)
        MEMORY_V46_SYNTHESIS_TABLE_AND_INDEX_SQL.forEach(db::execSQL)
        db.execSQL(LEARNING_V46_OUTBOX_TABLE_SQL)
        // Makes a local retry safe if an earlier unpublished v46 created the P0 table first.
        ensureColumns(db, "learning_outbox", LEARNING_V46_OUTBOX_P1_COLUMNS)
        LEARNING_V46_OUTBOX_INDEX_SQL.forEach(db::execSQL)
        LEARNING_V46_SOURCE_AUTHORITY_TABLE_AND_INDEX_SQL.forEach(db::execSQL)
        ensureLearningOutboxStreamSentinel(
            db = db,
            streamId = UUID.randomUUID().toString(),
            createdAtMs = System.currentTimeMillis(),
        )
    }
}

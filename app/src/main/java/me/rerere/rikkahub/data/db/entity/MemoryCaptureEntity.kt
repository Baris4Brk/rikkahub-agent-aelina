package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_captures",
    indices = [
        Index(value = ["conversation_id", "assistant_message_id", "capture_source"], unique = true),
        Index(value = ["scope_id", "state", "created_at_ms"]),
        Index("lease_until_ms"),
        Index("conversation_id"),
    ],
)
data class MemoryCaptureEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("scope_id")
    val scopeId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("user_message_id")
    val userMessageId: String,
    @ColumnInfo("assistant_message_id")
    val assistantMessageId: String,
    val origin: String,
    @ColumnInfo(name = "capture_source", defaultValue = "'AUTOMATIC_TURN'")
    val captureSource: String = "AUTOMATIC_TURN",
    @ColumnInfo("auto_save_mode")
    val autoSaveMode: String,
    @ColumnInfo("user_text")
    val userText: String,
    @ColumnInfo("assistant_text")
    val assistantText: String,
    @ColumnInfo(name = "context_turn_limit", defaultValue = "12")
    val contextTurnLimit: Int = 12,
    @ColumnInfo(defaultValue = "'PENDING'")
    val state: String = "PENDING",
    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo("last_error_code")
    val lastErrorCode: String? = null,
    @ColumnInfo("last_error_message")
    val lastErrorMessage: String? = null,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo("updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo("lease_owner")
    val leaseOwner: String? = null,
    @ColumnInfo("lease_until_ms")
    val leaseUntilMs: Long? = null,
    @ColumnInfo("processed_at_ms")
    val processedAtMs: Long? = null,
    @ColumnInfo(name = "processing_outcome")
    val processingOutcome: String? = null,
    @ColumnInfo(name = "candidate_count", defaultValue = "0")
    val candidateCount: Int = 0,
    @ColumnInfo(name = "supersedes_capture_id")
    val supersedesCaptureId: String? = null,
    @ColumnInfo(name = "narrative_events_enabled", defaultValue = "0")
    val narrativeEventsEnabled: Boolean = false,
    @ColumnInfo(name = "insights_theories_enabled", defaultValue = "0")
    val insightsTheoriesEnabled: Boolean = false,
    @ColumnInfo(name = "payload_purged_at_ms")
    val payloadPurgedAtMs: Long? = null,
)

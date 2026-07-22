package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("title")
    val title: String? = null,
    @ColumnInfo(name = "updated_at_ms", defaultValue = "0")
    val updatedAtMs: Long = 0L,
    @ColumnInfo(name = "importance", defaultValue = "0.5")
    val importance: Float = 0.5f,

    @ColumnInfo(name = "created_at_ms", defaultValue = "0")
    val createdAtMs: Long = 0L,

    @ColumnInfo(name = "last_accessed_at_ms")
    val lastAccessedAtMs: Long? = null,

    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long? = null,

    @ColumnInfo(name = "memory_kind", defaultValue = "'OTHER'")
    val memoryKind: String = "OTHER",

    @ColumnInfo(name = "confidence", defaultValue = "1.0")
    val confidence: Float = 1f,

    @ColumnInfo(name = "tags_json", defaultValue = "'[]'")
    val tagsJson: String = "[]",

    @ColumnInfo(name = "tags_search", defaultValue = "''")
    val tagsSearch: String = "",

    @ColumnInfo(name = "content_hash", defaultValue = "''")
    val contentHash: String = "",

    @ColumnInfo(name = "source_type", defaultValue = "'LEGACY'")
    val sourceType: String = "LEGACY",

    @ColumnInfo(name = "source_conversation_id")
    val sourceConversationId: String? = null,

    @ColumnInfo(name = "source_message_ids_json", defaultValue = "'[]'")
    val sourceMessageIdsJson: String = "[]",

    @ColumnInfo(name = "lifecycle_status", defaultValue = "'ACTIVE'")
    val lifecycleStatus: String = "ACTIVE",

    @ColumnInfo(name = "approval_source", defaultValue = "'LEGACY'")
    val approvalSource: String = "LEGACY",

    @ColumnInfo(name = "revision", defaultValue = "1")
    val revision: Int = 1,

    @ColumnInfo(name = "origin_assistant_id")
    val originAssistantId: String? = null,
    @ColumnInfo(name = "attribution", defaultValue = "'UNKNOWN'")
    val attribution: String = "UNKNOWN",
    @ColumnInfo(name = "truth_status", defaultValue = "'CONFIRMED'")
    val truthStatus: String = "CONFIRMED",
    @ColumnInfo(name = "occurred_at_ms")
    val occurredAtMs: Long? = null,
    @ColumnInfo(name = "participants_json", defaultValue = "'[]'")
    val participantsJson: String = "[]",
    @ColumnInfo(name = "outcome")
    val outcome: String? = null,
)

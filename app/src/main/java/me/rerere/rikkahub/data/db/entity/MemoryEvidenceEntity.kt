package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "memory_evidence", indices = [Index("memory_id"), Index("candidate_id"), Index("message_id")])
data class MemoryEvidenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("memory_id") val memoryId: Int? = null,
    @ColumnInfo("candidate_id") val candidateId: String? = null,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("message_id") val messageId: String,
    val role: String,
    val excerpt: String,
    @ColumnInfo("content_hash") val contentHash: String,
    @ColumnInfo("captured_at_ms") val capturedAtMs: Long,
    @ColumnInfo(defaultValue = "'ORIGINAL_MESSAGE'") val quality: String = "ORIGINAL_MESSAGE",
)

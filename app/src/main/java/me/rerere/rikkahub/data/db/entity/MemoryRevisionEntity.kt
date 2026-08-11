package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_revisions",
    indices = [
        Index(value = ["memory_id", "revision"], unique = true),
        Index(value = ["memory_id", "created_at_ms"]),
        Index("candidate_id"),
    ],
)
data class MemoryRevisionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("memory_id")
    val memoryId: Int,
    val revision: Int,
    val operation: String,
    @ColumnInfo("before_snapshot_json")
    val beforeSnapshotJson: String? = null,
    @ColumnInfo("after_snapshot_json")
    val afterSnapshotJson: String? = null,
    val actor: String,
    @ColumnInfo("candidate_id")
    val candidateId: String? = null,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo(name = "source_message_ids_json", defaultValue = "'[]'")
    val sourceMessageIdsJson: String = "[]",
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo("reason_code")
    val reasonCode: String? = null,
    @ColumnInfo("cause_memory_id")
    val causeMemoryId: Int? = null,
    @ColumnInfo("cause_link_id")
    val causeLinkId: String? = null,
)

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "memory_links", indices = [Index(value = ["source_memory_id", "target_memory_id", "relation_type"], unique = true), Index("target_memory_id")])
data class MemoryLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("source_memory_id") val sourceMemoryId: Int,
    @ColumnInfo("target_memory_id") val targetMemoryId: Int,
    @ColumnInfo("relation_type") val relationType: String,
    val weight: Float,
    val description: String,
    @ColumnInfo("evidence_message_ids_json") val evidenceMessageIdsJson: String = "[]",
    @ColumnInfo("created_by_assistant_id") val createdByAssistantId: String,
    @ColumnInfo("created_at_ms") val createdAtMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Int = 1,
)

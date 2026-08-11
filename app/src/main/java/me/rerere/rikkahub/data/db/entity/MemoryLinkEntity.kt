package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "memory_links",
    indices = [
        Index(value = ["scope_id", "source_memory_id", "target_memory_id", "relation_type"], unique = true),
        Index(value = ["scope_id", "source_memory_id", "lifecycle_status"]),
        Index(value = ["scope_id", "target_memory_id", "lifecycle_status"]),
        Index("relation_candidate_id"),
    ],
)
@Serializable
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
    @ColumnInfo(name = "scope_id", defaultValue = "''") val scopeId: String = "",
    @ColumnInfo(name = "lifecycle_status", defaultValue = "'ACTIVE'")
    val lifecycleStatus: String = "ACTIVE",
    @ColumnInfo(name = "source_revision", defaultValue = "1") val sourceRevision: Int = 1,
    @ColumnInfo(name = "target_revision", defaultValue = "1") val targetRevision: Int = 1,
    @ColumnInfo(name = "source_semantic_hash", defaultValue = "''")
    val sourceSemanticHash: String = "",
    @ColumnInfo(name = "target_semantic_hash", defaultValue = "''")
    val targetSemanticHash: String = "",
    @ColumnInfo("relation_candidate_id") val relationCandidateId: String? = null,
    @ColumnInfo(name = "updated_at_ms", defaultValue = "0") val updatedAtMs: Long = 0L,
    @ColumnInfo("invalidated_at_ms") val invalidatedAtMs: Long? = null,
    @ColumnInfo("invalidation_reason") val invalidationReason: String? = null,
)

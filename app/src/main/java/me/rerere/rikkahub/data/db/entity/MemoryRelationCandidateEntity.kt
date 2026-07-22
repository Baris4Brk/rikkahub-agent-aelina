package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "memory_relation_candidates", indices = [Index("batch_id"), Index("status")])
data class MemoryRelationCandidateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("batch_id") val batchId: String,
    @ColumnInfo("source_proposal_key") val sourceProposalKey: String? = null,
    @ColumnInfo("source_memory_id") val sourceMemoryId: Int? = null,
    @ColumnInfo("target_proposal_key") val targetProposalKey: String? = null,
    @ColumnInfo("target_memory_id") val targetMemoryId: Int? = null,
    @ColumnInfo("relation_type") val relationType: String,
    val weight: Float,
    val description: String,
    @ColumnInfo("evidence_message_ids_json") val evidenceMessageIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "'PENDING'") val status: String = "PENDING",
    @ColumnInfo("created_at_ms") val createdAtMs: Long,
)

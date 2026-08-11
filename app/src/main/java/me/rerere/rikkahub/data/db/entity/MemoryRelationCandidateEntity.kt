package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_relation_candidates",
    indices = [
        Index("batch_id"),
        Index(value = ["scope_id", "status", "created_at_ms"]),
        Index("source_candidate_id"),
        Index("target_candidate_id"),
        Index("resolved_link_id"),
    ],
)
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
    @ColumnInfo(name = "scope_id", defaultValue = "''") val scopeId: String = "",
    @ColumnInfo(name = "created_by_assistant_id", defaultValue = "''")
    val createdByAssistantId: String = "",
    @ColumnInfo("source_candidate_id") val sourceCandidateId: String? = null,
    @ColumnInfo("target_candidate_id") val targetCandidateId: String? = null,
    @ColumnInfo("source_expected_revision") val sourceExpectedRevision: Int? = null,
    @ColumnInfo("target_expected_revision") val targetExpectedRevision: Int? = null,
    @ColumnInfo("resolved_link_id") val resolvedLinkId: String? = null,
    @ColumnInfo("resolution_error") val resolutionError: String? = null,
    @ColumnInfo(name = "updated_at_ms", defaultValue = "0") val updatedAtMs: Long = 0L,
)

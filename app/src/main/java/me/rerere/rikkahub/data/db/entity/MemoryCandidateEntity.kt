package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_candidates",
    indices = [
        Index(value = ["scope_id", "status", "created_at_ms"]),
        Index("source_conversation_id"),
        Index("applied_memory_id"),
    ],
)
data class MemoryCandidateEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("scope_id")
    val scopeId: String,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String,
    @ColumnInfo("capture_ids_json")
    val captureIdsJson: String,
    val action: String,
    @ColumnInfo("target_memory_ids_json")
    val targetMemoryIdsJson: String,
    @ColumnInfo("expected_revisions_json")
    val expectedRevisionsJson: String,
    val title: String,
    val content: String,
    @ColumnInfo("memory_kind")
    val memoryKind: String,
    @ColumnInfo("tags_json")
    val tagsJson: String,
    val importance: Float,
    val confidence: Float,
    @ColumnInfo("expires_at_ms")
    val expiresAtMs: Long? = null,
    @ColumnInfo("risk_flags_json")
    val riskFlagsJson: String,
    val reason: String,
    @ColumnInfo("evidence_message_ids_json")
    val evidenceMessageIdsJson: String,
    @ColumnInfo(defaultValue = "'PENDING_REVIEW'")
    val status: String = "PENDING_REVIEW",
    @ColumnInfo("applied_memory_id")
    val appliedMemoryId: Int? = null,
    @ColumnInfo("resolution_error")
    val resolutionError: String? = null,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo("updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "proposal_key")
    val proposalKey: String? = null,
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

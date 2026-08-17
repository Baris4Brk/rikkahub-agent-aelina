package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Append-only journal used for deterministic replay and reconciliation while handoff is disabled. */
@Entity(
    tableName = "learning_reward_feedback_revisions",
    primaryKeys = ["feedback_id", "source_revision"],
    foreignKeys = [
        ForeignKey(
            entity = RewardFeedbackAuthorityEntity::class,
            parentColumns = ["feedback_id"],
            childColumns = ["feedback_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "idx_learning_reward_feedback_revision_feedback",
            value = ["feedback_id"],
        ),
        Index(
            name = "idx_learning_reward_feedback_revision_target",
            value = [
                "scope_kind",
                "scope_id",
                "target_assistant_message_id",
                "target_assistant_message_revision",
            ],
        ),
        Index(
            name = "idx_learning_reward_feedback_revision_scan",
            value = ["updated_at_ms", "feedback_id", "source_revision"],
        ),
    ],
)
data class RewardFeedbackAuthorityRevisionEntity(
    @ColumnInfo(name = "feedback_id") val feedbackId: String,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "conversation_source_revision") val conversationSourceRevision: Long,
    @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "command_revision") val commandRevision: Long,
    @ColumnInfo(name = "lineage_id") val lineageId: String,
    @ColumnInfo(name = "branch_anchor_message_id") val branchAnchorMessageId: String,
    @ColumnInfo(name = "branch_anchor_message_revision") val branchAnchorMessageRevision: Long,
    @ColumnInfo(name = "target_assistant_message_id") val targetAssistantMessageId: String,
    @ColumnInfo(name = "target_assistant_message_revision")
    val targetAssistantMessageRevision: Long,
    @ColumnInfo(name = "dimension") val dimension: String,
    @ColumnInfo(name = "signal_kind") val signalKind: String,
    @ColumnInfo(name = "value_milli") val valueMilli: Int?,
    @ColumnInfo(name = "source_state") val sourceState: String,
    @ColumnInfo(name = "source_revision") val sourceRevision: Long,
    @ColumnInfo(name = "previous_source_revision") val previousSourceRevision: Long?,
    @ColumnInfo(name = "integrity_sha256") val integritySha256: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    init {
        validateRewardFeedbackAuthorityFields(
            feedbackId, scopeKind,
            listOf(
                scopeId, conversationId, commandId, lineageId, branchAnchorMessageId,
                targetAssistantMessageId,
            ),
            listOf(
                conversationSourceRevision, commandRevision, branchAnchorMessageRevision,
                targetAssistantMessageRevision,
            ),
            dimension, signalKind, valueMilli, sourceState, sourceRevision,
            previousSourceRevision, integritySha256, createdAtMs, updatedAtMs,
        )
    }

    override fun toString(): String =
        "RewardFeedbackAuthorityRevisionEntity(dimension=$dimension, state=$sourceState, " +
            "revision=$sourceRevision, ids=<redacted>)"
}

internal fun RewardFeedbackAuthorityEntity.toRevisionEntity(): RewardFeedbackAuthorityRevisionEntity =
    RewardFeedbackAuthorityRevisionEntity(
        feedbackId, scopeKind, scopeId, conversationId, conversationSourceRevision, commandId,
        commandRevision, lineageId, branchAnchorMessageId, branchAnchorMessageRevision,
        targetAssistantMessageId, targetAssistantMessageRevision, dimension, signalKind, valueMilli,
        sourceState, sourceRevision, previousSourceRevision, integritySha256, createdAtMs, updatedAtMs,
    )

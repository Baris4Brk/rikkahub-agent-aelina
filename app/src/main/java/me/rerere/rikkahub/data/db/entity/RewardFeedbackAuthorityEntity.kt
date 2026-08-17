package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Content-free current head for an explicit user reward/correction.
 *
 * Scope and all source revisions are copied from main-database authorities by the writer. They are
 * never accepted from UI callers and therefore cannot widen a Learning scope or bind feedback to a
 * newer message after process death.
 */
@Entity(
    tableName = "learning_reward_feedback_authority",
    primaryKeys = ["feedback_id"],
    indices = [
        Index(
            name = "idx_learning_reward_feedback_target_dimension",
            value = [
                "scope_kind",
                "scope_id",
                "target_assistant_message_id",
                "target_assistant_message_revision",
                "dimension",
            ],
            unique = true,
        ),
        Index(
            name = "idx_learning_reward_feedback_command_revision",
            value = ["command_id", "command_revision"],
        ),
        Index(
            name = "idx_learning_reward_feedback_updated",
            value = ["updated_at_ms", "feedback_id"],
        ),
    ],
)
data class RewardFeedbackAuthorityEntity(
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
            feedbackId = feedbackId,
            scopeKind = scopeKind,
            ids = listOf(
                scopeId,
                conversationId,
                commandId,
                lineageId,
                branchAnchorMessageId,
                targetAssistantMessageId,
            ),
            revisions = listOf(
                conversationSourceRevision,
                commandRevision,
                branchAnchorMessageRevision,
                targetAssistantMessageRevision,
            ),
            dimension = dimension,
            signalKind = signalKind,
            valueMilli = valueMilli,
            sourceState = sourceState,
            sourceRevision = sourceRevision,
            previousSourceRevision = previousSourceRevision,
            integritySha256 = integritySha256,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
        )
    }

    override fun toString(): String =
        "RewardFeedbackAuthorityEntity(dimension=$dimension, kind=$signalKind, " +
            "state=$sourceState, revision=$sourceRevision, ids=<redacted>)"
}

internal fun validateRewardFeedbackAuthorityFields(
    feedbackId: String,
    scopeKind: String,
    ids: List<String>,
    revisions: List<Long>,
    dimension: String,
    signalKind: String,
    valueMilli: Int?,
    sourceState: String,
    sourceRevision: Long,
    previousSourceRevision: Long?,
    integritySha256: String,
    createdAtMs: Long,
    updatedAtMs: Long,
) {
    require(feedbackId.isRewardAuthorityId()) { "Invalid reward feedback ID" }
    require(scopeKind == "ASSISTANT" || scopeKind == "AUTHORITY_SUBJECT") {
        "Invalid reward feedback scope"
    }
    require(ids.all { it.isRewardAuthorityId() }) { "Invalid reward authority reference" }
    require(revisions.all { it > 0L }) { "Invalid reward authority revision" }
    require(dimension in setOf("GOAL", "PROCESS", "USER")) { "Invalid reward dimension" }
    require(signalKind in setOf("EXPLICIT_USER_FEEDBACK", "EXPLICIT_USER_CORRECTION")) {
        "Invalid reward signal kind"
    }
    require(sourceState == "ACTIVE" || sourceState == "TOMBSTONED") {
        "Invalid reward source state"
    }
    require(
        (sourceState == "ACTIVE" && (valueMilli == -1000 || valueMilli == 1000)) ||
            (sourceState == "TOMBSTONED" && valueMilli == null),
    ) { "Reward value/state mismatch" }
    require(sourceRevision > 0L)
    require(
        (sourceRevision == 1L && previousSourceRevision == null) ||
            (sourceRevision > 1L && previousSourceRevision == sourceRevision - 1L),
    ) { "Reward feedback revisions must be contiguous" }
    require(sourceState != "TOMBSTONED" || sourceRevision > 1L) {
        "Reward feedback cannot begin as a tombstone"
    }
    require(integritySha256.length == 64 && integritySha256.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid reward authority integrity digest"
    }
    require(createdAtMs >= 0L && updatedAtMs >= createdAtMs) {
        "Invalid reward authority time"
    }
}

internal fun String.isRewardAuthorityId(): Boolean =
    length in 1..256 && all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == ':' || char == '@'
    }

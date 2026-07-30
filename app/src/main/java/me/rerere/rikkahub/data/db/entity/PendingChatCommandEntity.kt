package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "pending_chat_commands",
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "state", "priority", "sequence"]),
        Index("leaseUntil"),
        Index("dedupeKey"),
        Index("authoritySubjectId"),
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class PendingChatCommandEntity(
    @androidx.room.PrimaryKey val id: String,
    val schemaVersion: Int,
    val conversationId: String,
    /** Exact global second-user subject at admission; null is never elevated on recovery. */
    val authoritySubjectId: String? = null,
    val type: String,
    val payloadJson: String,
    val state: String,
    val priority: Int,
    val sequence: Long,
    val expectedTargetVersion: Long?,
    val expectedBranchHeadMessageId: String?,
    val dedupeKey: String?,
    val idempotencyKey: String,
    val attempt: Int,
    val claimedBy: String?,
    val leaseUntil: Long?,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val expiresAt: Long?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
)

package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import me.rerere.rikkahub.service.chat.CommandCompletionKind

@Entity(
    tableName = "pending_chat_commands",
    indices = [
        Index("conversationId"),
        Index(value = ["conversationId", "state", "priority", "sequence"]),
        Index("leaseUntil"),
        Index("dedupeKey"),
        Index("authoritySubjectId"),
        Index(value = ["completionKind", "finishedAt"]),
        Index(value = ["resultAssistantMessageId", "resultAssistantMessageRevision"]),
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class PendingChatCommandEntity(
    @androidx.room.PrimaryKey val id: String,
    val schemaVersion: Int,
    val conversationId: String,
    /** Exact global second-user subject at admission; null is never elevated on recovery. */
    val authoritySubjectId: String? = null,
    /** Frozen assistant authority at admission; null marks a legacy row that cannot be elevated. */
    val assistantIdSnapshot: String? = null,
    /** Stable root command identity; null marks a legacy row pending conservative recovery. */
    val lineageId: String? = null,
    val parentCommandId: String? = null,
    val branchAnchorMessageId: String? = null,
    /** Exact authority revision of [branchAnchorMessageId]; null is legacy/ineligible. */
    val branchAnchorMessageRevision: Long? = null,
    /** Exact conversation-source revision committed with this command; never reconstructed later. */
    val conversationSourceRevision: Long? = null,
    /** Typed terminal/suspension meaning. Command state alone cannot prove a model result. */
    val completionKind: String? = null,
    /** Persisted final assistant source; set only after the exact message commit succeeds. */
    val resultAssistantMessageId: String? = null,
    val resultAssistantMessageRevision: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val stateVersion: Long = 0L,
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
) {
    init {
        require(branchAnchorMessageRevision == null || branchAnchorMessageRevision > 0L) {
            "Negative branch anchor message revision"
        }
        require(conversationSourceRevision == null || conversationSourceRevision > 0L) {
            "Negative conversation source revision"
        }
        require(resultAssistantMessageRevision == null || resultAssistantMessageRevision > 0L) {
            "Negative result assistant message revision"
        }
        require(
            (resultAssistantMessageId == null) == (resultAssistantMessageRevision == null),
        ) { "Result assistant source requires an exact ID/revision pair" }
        require(
            completionKind == null || CommandCompletionKind.parseOrNull(completionKind) != null,
        ) { "Unknown Learning completion kind" }
    }
}

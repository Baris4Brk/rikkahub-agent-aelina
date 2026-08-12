package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Content-free head of one Conversation source timeline.
 *
 * There is deliberately no foreign key to ConversationEntity: a privacy deletion must leave a
 * minimal tombstone which prevents an older Learning source revision from becoming valid again.
 */
@Entity(
    tableName = "learning_conversation_source_authority",
    primaryKeys = ["scope_kind", "scope_id", "conversation_id"],
    indices = [
        Index(name = "idx_learning_conversation_source_id", value = ["conversation_id"]),
        Index(
            name = "idx_learning_conversation_source_scope_state_updated",
            value = ["scope_kind", "scope_id", "source_state", "updated_at_ms"],
        ),
        Index(
            name = "idx_learning_conversation_source_branch_head",
            value = ["branch_head_message_id", "branch_head_message_revision"],
        ),
        Index(
            name = "idx_learning_conversation_source_scope_scan",
            value = ["conversation_id", "scope_kind", "scope_id"],
        ),
    ],
)
data class LearningConversationSourceAuthorityEntity(
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "assistant_id_snapshot")
    val assistantIdSnapshot: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long,
    @ColumnInfo(name = "previous_source_revision")
    val previousSourceRevision: Long?,
    @ColumnInfo(name = "source_state")
    val sourceState: String,
    @ColumnInfo(name = "change_kind")
    val changeKind: String,
    @ColumnInfo(name = "branch_head_message_id")
    val branchHeadMessageId: String?,
    @ColumnInfo(name = "branch_head_message_revision")
    val branchHeadMessageRevision: Long?,
    @ColumnInfo(name = "occurred_at_ms")
    val occurredAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
) {
    init {
        require(scopeKind.isLearningAuthorityCode()) { "Invalid Learning source scope kind" }
        listOf(scopeId, conversationId, assistantIdSnapshot).forEach { id ->
            require(id.isLearningAuthorityId()) { "Invalid Learning source authority ID" }
        }
        require(sourceRevision > 0L) { "Learning source revision must be positive" }
        require(
            (sourceRevision == 1L && previousSourceRevision == null) ||
                (sourceRevision > 1L && previousSourceRevision == sourceRevision - 1L),
        ) { "Learning source revisions must be contiguous" }
        require(sourceState.isLearningSourceAuthorityState()) {
            "Invalid Learning source state"
        }
        require(changeKind.isLearningSourceAuthorityChangeKind()) {
            "Invalid Learning source change kind"
        }
        require((branchHeadMessageId == null) == (branchHeadMessageRevision == null)) {
            "Conversation branch head requires an exact ID/revision pair"
        }
        require(branchHeadMessageId == null || branchHeadMessageId.isLearningAuthorityId()) {
            "Invalid Learning branch head ID"
        }
        require(branchHeadMessageRevision == null || branchHeadMessageRevision > 0L) {
            "Invalid Learning branch head revision"
        }
        require(sourceState != "TOMBSTONED" || branchHeadMessageId == null) {
            "Tombstoned Conversation source cannot retain a branch head"
        }
        require(occurredAtMs >= 0L && updatedAtMs >= occurredAtMs) {
            "Invalid Learning source authority time"
        }
    }
}

internal fun String.isLearningSourceAuthorityState(): Boolean =
    this == "ACTIVE" || this == "SUPERSEDED" || this == "TOMBSTONED"

internal fun String.isLearningSourceAuthorityChangeKind(): Boolean = when (this) {
    "CREATED",
    "UPDATED",
    "BRANCH_SELECTED",
    "BRANCH_SUPERSEDED",
    "DELETED",
    "CONVERSATION_DELETED" -> true
    else -> false
}

internal fun String.isLearningAuthorityCode(): Boolean =
    this == "ASSISTANT" || this == "AUTHORITY_SUBJECT"

internal fun String.isLearningAuthorityId(): Boolean =
    length in 1..256 && all { char ->
        char in 'a'..'z' ||
            char in 'A'..'Z' ||
            char in '0'..'9' ||
            char == '-' ||
            char == '_' ||
            char == '.' ||
            char == ':' ||
            char == '@'
    }

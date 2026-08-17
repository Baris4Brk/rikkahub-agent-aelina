package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Content-free authority head for one durable message ID in one Learning scope.
 *
 * [payloadIntegritySha256] detects a corrupt/stale read; it is never a revision token. A selected
 * branch may supersede and later reselect the same message, but every transition increments the
 * monotonic [sourceRevision], so old evidence never revives.
 */
@Entity(
    tableName = "learning_message_source_authority",
    primaryKeys = ["scope_kind", "scope_id", "message_id"],
    indices = [
        Index(name = "idx_learning_message_source_id", value = ["message_id"]),
        Index(
            name = "idx_learning_message_source_conversation_state",
            value = ["scope_kind", "scope_id", "conversation_id", "source_state"],
        ),
        Index(
            name = "idx_learning_message_source_conversation_revision",
            value = ["conversation_id", "source_revision"],
        ),
        Index(
            name = "idx_learning_message_source_conversation_scan",
            value = ["scope_kind", "scope_id", "conversation_id", "message_id"],
        ),
    ],
)
data class LearningMessageSourceAuthorityEntity(
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "message_role")
    val messageRole: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long,
    @ColumnInfo(name = "previous_source_revision")
    val previousSourceRevision: Long?,
    @ColumnInfo(name = "source_state")
    val sourceState: String,
    @ColumnInfo(name = "change_kind")
    val changeKind: String,
    @ColumnInfo(name = "payload_integrity_sha256")
    val payloadIntegritySha256: String?,
    @ColumnInfo(name = "occurred_at_ms")
    val occurredAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
) {
    init {
        require(scopeKind.isLearningAuthorityCode()) { "Invalid Learning source scope kind" }
        listOf(scopeId, conversationId, messageId).forEach { id ->
            require(id.isLearningAuthorityId()) { "Invalid Learning source authority ID" }
        }
        require(messageRole in setOf("USER", "ASSISTANT", "SYSTEM", "TOOL")) {
            "Invalid Learning message role"
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
        require(
            payloadIntegritySha256 == null || payloadIntegritySha256.isLowerHexSha256(),
        ) { "Invalid Learning source integrity digest" }
        require(
            sourceState == "TOMBSTONED" ||
                payloadIntegritySha256 != null,
        ) { "Live Learning source requires an integrity digest" }
        require(occurredAtMs >= 0L && updatedAtMs >= occurredAtMs) {
            "Invalid Learning source authority time"
        }
    }

    override fun toString(): String =
        "LearningMessageSourceAuthorityEntity(role=$messageRole, state=$sourceState, " +
            "change=$changeKind, revision=$sourceRevision, ids-and-digest=<redacted>)"
}

private fun String.isLowerHexSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

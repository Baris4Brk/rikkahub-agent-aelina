package me.rerere.rikkahub.data.execution

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Redacted lifecycle projection for a tool approval.
 *
 * The executable payload remains in the conversation message graph; this row must never contain
 * tool arguments, command text, paths, credentials, output, or message text.
 */
@Entity(
    tableName = "pending_tool_approvals",
    indices = [
        Index(name = "idx_tool_approvals_execution", value = ["execution_id"]),
        Index(
            name = "idx_tool_approvals_conversation_status_requested",
            value = ["conversation_id", "status", "requested_at_ms"],
        ),
        Index(name = "idx_tool_approvals_resolved", value = ["resolved_at_ms"]),
    ],
)
data class PendingToolApprovalRecord(
    @PrimaryKey
    @ColumnInfo(name = "approval_id")
    val approvalId: String,
    @ColumnInfo(name = "execution_id")
    val executionId: String,
    @ColumnInfo(name = "trace_id")
    val traceId: String?,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "subject_type")
    val subjectType: String,
    @ColumnInfo(name = "origin")
    val origin: String,
    @ColumnInfo(name = "capability_key")
    val capabilityKey: String,
    @ColumnInfo(name = "resource_category")
    val resourceCategory: String,
    @ColumnInfo(name = "requested_at_ms")
    val requestedAtMs: Long,
    @ColumnInfo(name = "status", defaultValue = "'PENDING'")
    val status: String = ApprovalStatus.PENDING.name,
    @ColumnInfo(name = "state_version", defaultValue = "0")
    val stateVersion: Long = 0,
    @ColumnInfo(name = "resolved_at_ms")
    val resolvedAtMs: Long? = null,
    @ColumnInfo(name = "resolution_reason")
    val resolutionReason: String? = null,
    @ColumnInfo(name = "resolution_request_id")
    val resolutionRequestId: String? = null,
)

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    DENIED,
    INVALIDATED;

    val isResolved: Boolean
        get() = this != PENDING

    companion object {
        fun fromWire(value: String?): ApprovalStatus = entries.firstOrNull { it.name == value } ?: INVALIDATED
    }
}

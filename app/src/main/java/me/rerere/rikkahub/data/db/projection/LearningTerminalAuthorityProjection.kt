package me.rerere.rikkahub.data.db.projection

import androidx.room.ColumnInfo

/**
 * Content-free authority projection for a terminal durable command.
 *
 * Keep this deliberately narrower than the pending-command entity: command type, payload,
 * idempotency material, errors, and model/user content must never enter reconciliation memory.
 */
data class LearningCommandTerminalAuthorityProjection(
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "command_state")
    val state: String,
    @ColumnInfo(name = "command_state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "command_conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "command_conversation_source_revision")
    val conversationSourceRevision: Long? = null,
    @ColumnInfo(name = "command_authority_subject_id")
    val authoritySubjectId: String?,
    @ColumnInfo(name = "command_assistant_id_snapshot")
    val assistantIdSnapshot: String?,
    @ColumnInfo(name = "command_lineage_id")
    val lineageId: String?,
    @ColumnInfo(name = "command_parent_id")
    val parentCommandId: String?,
    @ColumnInfo(name = "command_branch_anchor_message_id")
    val branchAnchorMessageId: String?,
    @ColumnInfo(name = "command_branch_anchor_message_revision")
    val branchAnchorMessageRevision: Long? = null,
    @ColumnInfo(name = "command_completion_kind")
    val completionKind: String? = null,
    @ColumnInfo(name = "command_result_assistant_message_id")
    val resultAssistantMessageId: String? = null,
    @ColumnInfo(name = "command_result_assistant_message_revision")
    val resultAssistantMessageRevision: Long? = null,
    @ColumnInfo(name = "command_finished_at_ms")
    val finishedAtMs: Long?,
)

/**
 * Content-free proof that the current execution snapshot is backed by its exact final journal
 * transition. Mutable handles, capabilities, resource summaries, reasons, and payloads are
 * intentionally absent.
 */
data class LearningExecutionTerminalAuthorityProjection(
    @ColumnInfo(name = "execution_id")
    val executionId: String,
    @ColumnInfo(name = "execution_trace_id")
    val traceId: String,
    @ColumnInfo(name = "execution_command_id")
    val commandId: String?,
    @ColumnInfo(name = "execution_conversation_id")
    val conversationId: String?,
    @ColumnInfo(name = "execution_learning_scope_kind")
    val learningScopeKind: String?,
    @ColumnInfo(name = "execution_learning_scope_id")
    val learningScopeId: String?,
    @ColumnInfo(name = "execution_tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo(name = "execution_tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "execution_tool_schema_fingerprint")
    val toolSchemaFingerprint: String? = null,
    @ColumnInfo(name = "execution_owning_assistant_message_id")
    val owningAssistantMessageId: String? = null,
    @ColumnInfo(name = "execution_owning_assistant_message_revision")
    val owningAssistantMessageRevision: Long? = null,
    @ColumnInfo(name = "execution_status")
    val status: String,
    @ColumnInfo(name = "execution_state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "execution_verification_state")
    val verificationState: String,
    @ColumnInfo(name = "execution_finished_at_ms")
    val finishedAtMs: Long?,
    @ColumnInfo(name = "execution_updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "terminal_event_id")
    val eventId: String,
    @ColumnInfo(name = "terminal_event_execution_id")
    val eventExecutionId: String,
    @ColumnInfo(name = "terminal_event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "terminal_event_previous_status")
    val eventPreviousStatus: String?,
    @ColumnInfo(name = "terminal_event_next_status")
    val eventNextStatus: String,
    @ColumnInfo(name = "terminal_event_next_verification")
    val eventNextVerification: String,
    @ColumnInfo(name = "terminal_event_created_at_ms")
    val eventCreatedAtMs: Long,
)

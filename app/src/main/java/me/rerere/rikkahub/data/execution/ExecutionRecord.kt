package me.rerere.rikkahub.data.execution

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Authoritative record for one actual tool/runtime execution.
 *
 * [me.rerere.rikkahub.data.agentrun.AgentRun] remains the cross-domain summary ledger. This
 * table is deliberately finer grained: a workflow node, tool call, or managed process gets its
 * own row, with enough non-secret ownership and lifecycle data to state honestly whether it was
 * stopped, finished, or merely lost after a process death.
 */
@Entity(
    tableName = "execution_records",
    indices = [
        Index(name = "idx_execution_records_status", value = ["status"]),
        Index(name = "idx_execution_records_trace", value = ["trace_id"]),
        Index(name = "idx_execution_records_parent", value = ["parent_execution_id"]),
        Index(name = "idx_execution_records_idempotency", value = ["idempotency_key"]),
        Index(name = "idx_execution_records_updated", value = ["updated_at_ms"]),
        Index(
            name = "idx_execution_records_conversation_status_updated",
            value = ["conversation_id", "status", "updated_at_ms"],
        ),
        Index(
            name = "idx_execution_records_subject_status_updated",
            value = ["subject_id", "status", "updated_at_ms"],
        ),
        Index(
            name = "idx_execution_records_parent_status",
            value = ["parent_execution_id", "status"],
        ),
        Index(
            name = "idx_execution_records_runtime_handle",
            value = ["runtime", "runtime_handle_summary"],
        ),
        Index(
            name = "idx_execution_records_heartbeat_status",
            value = ["heartbeat_at_ms", "status"],
        ),
        Index(name = "idx_execution_records_tool_call", value = ["tool_call_id"]),
        Index(
            name = "idx_execution_records_tool_schema",
            value = ["tool_name", "tool_schema_fingerprint"],
        ),
        Index(
            name = "idx_execution_records_owning_message",
            value = ["owning_assistant_message_id", "owning_assistant_message_revision"],
        ),
    ],
)
data class ExecutionRecord(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "trace_id")
    val traceId: String,
    @ColumnInfo(name = "parent_execution_id")
    val parentExecutionId: String? = null,
    @ColumnInfo(name = "command_id")
    val commandId: String? = null,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,
    /** Frozen Learning authorization boundary at execution admission; null is legacy/ineligible. */
    @ColumnInfo(name = "learning_scope_kind")
    val learningScopeKind: String? = null,
    @ColumnInfo(name = "learning_scope_id")
    val learningScopeId: String? = null,
    /** Provider/tool-call identity frozen at admission. Never derived from tool arguments. */
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "tool_schema_fingerprint")
    val toolSchemaFingerprint: String? = null,
    /** Exact assistant source which owns this call; null marks legacy/unrecoverable ownership. */
    @ColumnInfo(name = "owning_assistant_message_id")
    val owningAssistantMessageId: String? = null,
    @ColumnInfo(name = "owning_assistant_message_revision")
    val owningAssistantMessageRevision: Long? = null,
    @ColumnInfo(name = "subject_id")
    val subjectId: String,
    @ColumnInfo(name = "subject_type")
    val subjectType: String,
    @ColumnInfo(name = "origin")
    val origin: String,
    /** Stable action keys only; never raw tool arguments, credentials, or command text. */
    @ColumnInfo(name = "capability_keys")
    val capabilityKeys: String,
    /** Redacted resource class/identifier summary; not a path or a remote command. */
    @ColumnInfo(name = "resource_summary")
    val resourceSummary: String,
    @ColumnInfo(name = "runtime")
    val runtime: String,
    @ColumnInfo(name = "execution_kind", defaultValue = "'TOOL_CALL'")
    val executionKind: String = ExecutionKind.TOOL_CALL.name,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String? = null,
    /** Opaque managed-handle identifier, never a token or a shell command. */
    @ColumnInfo(name = "runtime_handle_summary")
    val runtimeHandleSummary: String? = null,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long? = null,
    @ColumnInfo(name = "heartbeat_at_ms")
    val heartbeatAtMs: Long? = null,
    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long? = null,
    @ColumnInfo(name = "cancellation_result")
    val cancellationResult: String? = null,
    /** Stable, short error/recovery code. Never an exception stack or unredacted output. */
    @ColumnInfo(name = "terminal_detail")
    val terminalDetail: String? = null,
    @ColumnInfo(name = "state_version", defaultValue = "0")
    val stateVersion: Long = 0,
    @ColumnInfo(name = "last_state_source", defaultValue = "'LEGACY'")
    val lastStateSource: String = ExecutionStateSource.LEGACY.name,
    @ColumnInfo(name = "last_reason_code")
    val lastReasonCode: String? = null,
    @ColumnInfo(name = "verification_state", defaultValue = "'UNKNOWN'")
    val verificationState: String = VerificationState.UNKNOWN.name,
    @ColumnInfo(name = "last_probe_at_ms")
    val lastProbeAtMs: Long? = null,
    @ColumnInfo(name = "completion_policy", defaultValue = "'WAIT_FOR_CHILDREN'")
    val completionPolicy: String = CompletionPolicy.WAIT_FOR_CHILDREN.name,
    @ColumnInfo(name = "runtime_instance_marker")
    val runtimeInstanceMarker: String? = null,
    @ColumnInfo(name = "cancellation_requested_at_ms")
    val cancellationRequestedAtMs: Long? = null,
    /** Persisted intent used to choose the honest terminal state after an independent probe. */
    @ColumnInfo(name = "requested_terminal_outcome", defaultValue = "'NONE'")
    val requestedTerminalOutcome: String = RequestedTerminalOutcome.NONE.name,
) {
    init {
        val toolIdentity = listOf(toolCallId, toolName, toolSchemaFingerprint)
        require(toolIdentity.all { it == null } || toolIdentity.all { it != null }) {
            "Execution tool admission identity must be complete"
        }
        require(toolCallId == null || toolCallId.isSafeExecutionAuthorityId()) {
            "Invalid execution tool-call ID"
        }
        require(toolName == null || toolName.isSafeExecutionAuthorityId()) {
            "Invalid execution tool name"
        }
        require(toolSchemaFingerprint == null || toolSchemaFingerprint.isLowerExecutionSha256()) {
            "Invalid execution tool schema fingerprint"
        }
        require(
            (owningAssistantMessageId == null) == (owningAssistantMessageRevision == null),
        ) { "Execution message ownership requires an exact ID/revision pair" }
        require(
            owningAssistantMessageId == null || owningAssistantMessageId.isSafeExecutionAuthorityId(),
        ) { "Invalid execution owning message ID" }
        require(owningAssistantMessageRevision == null || owningAssistantMessageRevision > 0L) {
            "Invalid execution owning message revision"
        }
    }
}

private fun String.isSafeExecutionAuthorityId(): Boolean =
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

private fun String.isLowerExecutionSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

enum class ExecutionKind {
    TOOL_CALL,
    MANAGED_PROCESS;

    companion object {
        fun fromWire(value: String?): ExecutionKind = entries.firstOrNull { it.name == value } ?: TOOL_CALL
    }
}

enum class CompletionPolicy {
    WAIT_FOR_CHILDREN,
    DETACH_BACKGROUND,
    SERVICE_EXPECTED_TO_STAY_ALIVE;

    companion object {
        fun fromWire(value: String?): CompletionPolicy =
            entries.firstOrNull { it.name == value } ?: WAIT_FOR_CHILDREN
    }
}

enum class VerificationState {
    LIVE_CONFIRMED,
    RUNTIME_CONFIRMED,
    DATABASE_CONFIRMED,
    RECONCILING,
    STALE,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): VerificationState = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class RuntimeContinuity {
    SAME_INSTANCE,
    RESTARTED,
    LOST,
    UNKNOWN,
}

enum class RequestedTerminalOutcome {
    NONE,
    CANCELLED,
    TIMED_OUT;

    companion object {
        fun fromWire(value: String?): RequestedTerminalOutcome =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

enum class ExecutionStateSource {
    LIVE_EVENT,
    DATABASE,
    PROBE,
    RECOVERY,
    USER,
    POLICY,
    DOCTOR,
    LEGACY,
}

@Suppress("EnumEntryName")
enum class ExecutionStatus {
    queued,
    waiting_approval,
    starting,
    running,
    cancel_requested,
    terminating,
    succeeded,
    failed,
    cancelled,
    timed_out,
    orphaned,
    unknown;

    val isTerminal: Boolean
        get() = this in TERMINAL

    fun canTransitionTo(next: ExecutionStatus): Boolean {
        if (this == next) return true
        if (isTerminal) return false
        return next in when (this) {
            queued -> setOf(waiting_approval, starting, cancel_requested, failed, cancelled, orphaned, unknown)
            waiting_approval -> setOf(starting, cancel_requested, cancelled, failed, orphaned, unknown)
            starting -> setOf(running, cancel_requested, terminating, succeeded, failed, cancelled, timed_out, orphaned, unknown)
            running -> setOf(cancel_requested, terminating, succeeded, failed, cancelled, timed_out, orphaned, unknown)
            cancel_requested -> setOf(terminating, cancelled, timed_out, failed, orphaned, unknown)
            terminating -> setOf(cancelled, timed_out, failed, orphaned, unknown)
            succeeded, failed, cancelled, timed_out, orphaned, unknown -> emptySet()
        }
    }

    companion object {
        val TERMINAL: Set<ExecutionStatus> = setOf(succeeded, failed, cancelled, timed_out, orphaned, unknown)
        val IN_FLIGHT: Set<ExecutionStatus> = entries.toSet() - TERMINAL

        fun fromWire(value: String?): ExecutionStatus =
            entries.firstOrNull { it.name == value } ?: unknown
    }
}

enum class ExecutionRuntime {
    LOCAL_TOOL,
    TERMUX,
    SSH,
    WORKSPACE,
    SHIZUKU,
    PLUGIN,
    MCP,
    LEGACY,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): ExecutionRuntime =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

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
)

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

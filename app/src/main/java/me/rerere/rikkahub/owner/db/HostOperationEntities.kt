package me.rerere.rikkahub.owner.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One redacted, idempotent Owner host operation. Request arguments are never stored here. */
@Entity(
    tableName = "host_operations",
    indices = [
        Index(name = "idx_host_operations_authority_state", value = ["authority_subject_id", "state"]),
        Index(name = "idx_host_operations_conversation_updated", value = ["conversation_id", "updated_at_ms"]),
        Index(name = "idx_host_operations_state_updated", value = ["state", "updated_at_ms"]),
    ],
)
data class HostOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "authority_subject_id")
    val authoritySubjectId: String,
    @ColumnInfo(name = "authority_epoch")
    val authorityEpoch: Long,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "model_id")
    val modelId: String?,
    @ColumnInfo(name = "provider_id")
    val providerId: String?,
    @ColumnInfo(name = "tool_family")
    val toolFamily: String,
    /** JSON containing an opaque request HMAC plus action type, risk and ordinal only. */
    @ColumnInfo(name = "action_summary_json")
    val actionSummaryJson: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "recovery_code")
    val recoveryCode: String?,
    @ColumnInfo(name = "result_code")
    val resultCode: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "completed_at_ms")
    val completedAtMs: Long?,
)

/** Append-only state history. It contains codes and action types, never values or output. */
@Entity(
    tableName = "host_operation_events",
    foreignKeys = [
        ForeignKey(
            entity = HostOperationEntity::class,
            parentColumns = ["request_id"],
            childColumns = ["request_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_host_operation_events_request", value = ["request_id"]),
        Index(
            name = "idx_host_operation_events_request_sequence",
            value = ["request_id", "sequence"],
            unique = true,
        ),
        Index(name = "idx_host_operation_events_created", value = ["created_at_ms"]),
    ],
)
data class HostOperationEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "request_id")
    val requestId: String,
    @ColumnInfo(name = "sequence")
    val sequence: Long,
    @ColumnInfo(name = "previous_state")
    val previousState: String?,
    @ColumnInfo(name = "next_state")
    val nextState: String,
    @ColumnInfo(name = "action_index")
    val actionIndex: Int?,
    @ColumnInfo(name = "action_type")
    val actionType: String?,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

/** Durable supervision projection. Script/argv/path/header/body values are deliberately absent. */
@Entity(
    tableName = "host_local_services",
    indices = [
        Index(name = "idx_host_local_services_authority_enabled", value = ["authority_subject_id", "enabled"]),
        Index(name = "idx_host_local_services_execution", value = ["execution_id"]),
        Index(name = "idx_host_local_services_health", value = ["health_state", "next_probe_at_ms"]),
    ],
)
data class HostLocalServiceEntity(
    @PrimaryKey
    @ColumnInfo(name = "service_id")
    val serviceId: String,
    @ColumnInfo(name = "authority_subject_id")
    val authoritySubjectId: String,
    @ColumnInfo(name = "authority_epoch")
    val authorityEpoch: Long,
    /** Redacted manifest: runtime kind, stable workspace/process IDs and script hash only. */
    @ColumnInfo(name = "manifest_json")
    val manifestJson: String,
    @ColumnInfo(name = "manifest_hash")
    val manifestHash: String,
    @ColumnInfo(name = "execution_id")
    val executionId: String?,
    @ColumnInfo(name = "health_state")
    val healthState: String,
    @ColumnInfo(name = "restart_policy")
    val restartPolicy: String,
    @ColumnInfo(name = "restart_count")
    val restartCount: Int,
    @ColumnInfo(name = "next_probe_at_ms")
    val nextProbeAtMs: Long?,
    @ColumnInfo(name = "last_probe_at_ms")
    val lastProbeAtMs: Long?,
    @ColumnInfo(name = "last_reason_code")
    val lastReasonCode: String?,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)

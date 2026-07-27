package me.rerere.rikkahub.data.execution

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only, redacted explanation of one accepted execution-state mutation. */
@Entity(
    tableName = "execution_events",
    foreignKeys = [
        ForeignKey(
            entity = ExecutionRecord::class,
            parentColumns = ["id"],
            childColumns = ["execution_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "idx_execution_events_execution", value = ["execution_id"]),
        Index(
            name = "idx_execution_events_execution_sequence",
            value = ["execution_id", "sequence"],
            unique = true,
        ),
        Index(name = "idx_execution_events_created", value = ["created_at_ms"]),
    ],
)
data class ExecutionEventRecord(
    /** Also serves as the producer mutation id for duplicate detection. */
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "execution_id")
    val executionId: String,
    @ColumnInfo(name = "sequence")
    val sequence: Long,
    @ColumnInfo(name = "previous_status")
    val previousStatus: String?,
    @ColumnInfo(name = "next_status")
    val nextStatus: String,
    @ColumnInfo(name = "previous_verification")
    val previousVerification: String?,
    @ColumnInfo(name = "next_verification")
    val nextVerification: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

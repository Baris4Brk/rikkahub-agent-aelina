package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

/** Contiguous handoff position for exactly one authoritative database lineage. */
@Entity(tableName = "learning_stream_checkpoints")
data class LearningStreamCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "stream_id")
    val streamId: String,
    @ColumnInfo(name = "last_contiguous_seq")
    val lastContiguousSeq: Long,
    @ColumnInfo(name = "last_seen_head_seq")
    val lastSeenHeadSeq: Long,
    @ColumnInfo(name = "replay_generation")
    val replayGeneration: Long,
    @ColumnInfo(name = "reset_reason")
    val resetReason: String?,
    @ColumnInfo(name = "bootstrap_state")
    val bootstrapState: String,
    @ColumnInfo(name = "bootstrap_head_seq")
    val bootstrapHeadSeq: Long?,
    @ColumnInfo(name = "coverage_start_ms")
    val coverageStartMs: Long?,
    @ColumnInfo(name = "command_coverage_start_ms")
    val commandCoverageStartMs: Long?,
    @ColumnInfo(name = "execution_coverage_start_ms")
    val executionCoverageStartMs: Long?,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "source_authority_coverage_start_ms")
    val sourceAuthorityCoverageStartMs: Long? = null,
    @ColumnInfo(name = "feedback_coverage_start_ms")
    val feedbackCoverageStartMs: Long? = null,
    @ColumnInfo(name = "reconciliation_cursor_v1_json")
    val reconciliationCursorV1Json: String? = null,
) {
    init {
        require(runCatching { Uuid.parse(streamId) }.isSuccess) { "Invalid learning stream ID" }
        require(lastContiguousSeq >= 0L) { "Negative contiguous learning sequence" }
        require(lastSeenHeadSeq >= lastContiguousSeq) {
            "Learning checkpoint head precedes its contiguous sequence"
        }
        require(replayGeneration >= 0L) { "Negative replay generation" }
        require(
            resetReason == null || LearningStreamResetReason.entries.any { it.name == resetReason }
        ) { "Invalid learning stream reset reason" }
        require(LearningBootstrapState.entries.any { it.name == bootstrapState }) {
            "Invalid learning bootstrap state"
        }
        listOfNotNull(
            bootstrapHeadSeq,
            coverageStartMs,
            commandCoverageStartMs,
            executionCoverageStartMs,
            sourceAuthorityCoverageStartMs,
            feedbackCoverageStartMs,
        ).forEach { value ->
            require(value >= 0L) { "Negative learning checkpoint time or sequence" }
        }
        require(updatedAtMs >= 0L) { "Negative learning checkpoint update time" }
    }

    override fun toString(): String =
        "LearningStreamCheckpointEntity(contiguous=$lastContiguousSeq, " +
            "head=$lastSeenHeadSeq, replayGeneration=$replayGeneration, " +
            "bootstrap=$bootstrapState, stream=<redacted>)"
}

enum class LearningBootstrapState {
    REQUIRED,
    RUNNING,
    COMPLETE,
    DEGRADED,
}

enum class LearningStreamResetReason {
    NEW_STREAM,
    HEAD_REWIND,
    DERIVED_DATABASE_RECREATED,
    RESTORE,
    CORRUPTION,
}

package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Sanitized, bounded feature row. Raw prompts, reasoning, tool arguments and outputs are absent. */
@Entity(
    tableName = "learning_trace_features",
    primaryKeys = ["episode_id", "sequence", "source_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = LearningEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["episode_id", "source_type", "source_id", "source_revision"]),
        Index(value = ["source_type", "source_id", "source_revision"]),
        Index(value = ["outcome_class", "error_code"]),
    ],
)
data class LearningTraceFeatureEntity(
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    val sequence: Long,
    @ColumnInfo(name = "source_ordinal")
    val sourceOrdinal: Int,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long?,
    @ColumnInfo(name = "missing_revision_reason")
    val missingRevisionReason: String?,
    @ColumnInfo(name = "action_type")
    val actionType: String,
    @ColumnInfo(name = "action_name")
    val actionName: String?,
    @ColumnInfo(name = "tool_schema_fingerprint")
    val toolSchemaFingerprint: String?,
    @ColumnInfo(name = "outcome_class")
    val outcomeClass: String,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
    @ColumnInfo(name = "state_summary")
    val stateSummary: String?,
    @ColumnInfo(name = "observation_summary")
    val observationSummary: String?,
    @ColumnInfo(name = "input_token_count")
    val inputTokenCount: Long?,
    @ColumnInfo(name = "output_token_count")
    val outputTokenCount: Long?,
    @ColumnInfo(name = "tool_count")
    val toolCount: Int?,
    @ColumnInfo(name = "retry_count")
    val retryCount: Int?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,
    val alpha: Double?,
    val quality: Double?,
    @ColumnInfo(name = "feature_schema_identity")
    val featureSchemaIdentity: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    init {
        requireLearningStorageId(episodeId, "trace episode ID")
        require(sequence >= 0L) { "Negative trace sequence" }
        require(sourceOrdinal in 0..15) { "Invalid trace source ordinal" }
        requireLearningCode(sourceType, "trace source type")
        requireLearningStorageId(sourceId, "trace source ID")
        require(sourceRevision == null || sourceRevision > 0L) { "Invalid trace source revision" }
        require((sourceRevision == null) == (missingRevisionReason != null)) {
            "Missing trace source revision requires exactly one reason"
        }
        missingRevisionReason?.let { requireLearningCode(it, "missing trace revision reason") }
        requireLearningCode(actionType, "trace action type")
        actionName?.let { requireLearningIdentity(it, "trace action name") }
        toolSchemaFingerprint?.let { requireLearningIdentity(it, "tool schema fingerprint") }
        requireLearningCode(outcomeClass, "trace outcome class")
        errorCode?.let { requireLearningCode(it, "trace error code") }
        requireNullableBoundedRedactedText(stateSummary, "trace state summary")
        requireNullableBoundedRedactedText(observationSummary, "trace observation summary")
        listOfNotNull(inputTokenCount, outputTokenCount, durationMs).forEach {
            require(it >= 0L) { "Negative trace aggregate" }
        }
        listOfNotNull(toolCount, retryCount).forEach {
            require(it >= 0) { "Negative trace count" }
        }
        listOfNotNull(alpha, quality).forEach {
            require(it.isFinite() && it in 0.0..1.0) { "Invalid trace weak score" }
        }
        requireLearningIdentity(featureSchemaIdentity, "trace schema identity")
        require(createdAtMs >= 0L) { "Negative trace creation time" }
    }

    override fun toString(): String =
        "LearningTraceFeatureEntity(sequence=$sequence, outcome=$outcomeClass, summaries=<redacted>, ids=<redacted>)"
}

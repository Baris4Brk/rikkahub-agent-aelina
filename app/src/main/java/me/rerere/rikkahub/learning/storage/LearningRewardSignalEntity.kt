package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

/**
 * Immutable, derived projection of one exact authoritative reward signal.
 *
 * The row contains no feedback text, command detail, tool output or provider judgement. Source
 * invalidation is evaluated against [LearningSourceValidityEntity]; a stale source can therefore
 * never remain usable merely because this immutable projection still exists.
 */
@Entity(
    tableName = "learning_reward_signals",
    foreignKeys = [
        ForeignKey(
            entity = LearningEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["episode_id", "id"], unique = true),
        Index(
            value = [
                "stream_id",
                "replay_generation",
                "scope_kind",
                "scope_id",
                "source_type",
                "source_id",
                "source_revision",
                "dimension",
            ],
            unique = true,
        ),
        Index(value = ["episode_id", "dimension", "signal_kind", "occurred_at_ms", "id"]),
        Index(value = ["source_type", "source_id", "source_revision"]),
        Index(value = ["authority_event_id"]),
    ],
)
data class LearningRewardSignalEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "stream_id")
    val streamId: String,
    @ColumnInfo(name = "replay_generation")
    val replayGeneration: Long,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "authority_event_id")
    val authorityEventId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long,
    @ColumnInfo(name = "source_integrity_sha256")
    val sourceIntegritySha256: String,
    val dimension: String,
    @ColumnInfo(name = "signal_kind")
    val signalKind: String,
    val knowledge: String,
    @ColumnInfo(name = "value_milli")
    val valueMilli: Int?,
    @ColumnInfo(name = "unknown_reason")
    val unknownReason: String?,
    @ColumnInfo(name = "occurred_at_ms")
    val occurredAtMs: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    init {
        requireLearningStorageId(id, "reward signal ID")
        requireLearningStorageId(episodeId, "reward signal episode ID")
        require(runCatching { Uuid.parse(streamId) }.isSuccess) {
            "Invalid reward signal stream ID"
        }
        require(replayGeneration >= 0L) { "Negative reward signal replay generation" }
        requireLearningScope(scopeKind, scopeId)
        requireLearningStorageId(authorityEventId, "reward authority event ID")
        requireLearningCode(sourceType, "reward signal source type")
        requireLearningStorageId(sourceId, "reward signal source ID")
        require(sourceRevision > 0L) { "Reward signal requires an exact positive source revision" }
        requireSha256(sourceIntegritySha256, "reward signal source integrity")
        require(LearningRewardDimension.entries.any { it.name == dimension }) {
            "Invalid reward signal dimension"
        }
        require(LearningRewardSignalKind.entries.any { it.name == signalKind }) {
            "Invalid reward signal kind"
        }
        val parsedKnowledge = requireNotNull(
            LearningRewardKnowledge.entries.firstOrNull { it.name == knowledge },
        ) { "Invalid reward signal knowledge" }
        when (parsedKnowledge) {
            LearningRewardKnowledge.KNOWN -> {
                require(valueMilli != null && valueMilli in -1_000..1_000) {
                    "Known reward signal requires a bounded milli-value"
                }
                require(unknownReason == null) {
                    "Known reward signal cannot retain an unknown reason"
                }
            }

            LearningRewardKnowledge.UNKNOWN,
            LearningRewardKnowledge.CENSORED,
            -> {
                require(valueMilli == null) { "Unknown reward signal is not zero" }
                require(unknownReason != null) { "Unknown reward signal requires a reason" }
            }
        }
        unknownReason?.let { requireLearningCode(it, "reward signal unknown reason") }
        require(occurredAtMs >= 0L && createdAtMs >= occurredAtMs) {
            "Invalid reward signal authority clock"
        }
    }

    override fun toString(): String =
        "LearningRewardSignalEntity(dimension=$dimension, kind=$signalKind, " +
            "knowledge=$knowledge, value=<redacted>, ids=<redacted>)"
}

enum class LearningRewardDimension {
    GOAL,
    PROCESS,
    USER,
}

/** Ordered by the reducer, never by enum ordinal or insertion time. */
enum class LearningRewardSignalKind {
    EXPLICIT_USER_FEEDBACK,
    EXPLICIT_USER_CORRECTION,
    VERIFIED_TOOL_RESULT,
    COMMAND_TERMINAL,
    PROGRAMMATIC_METRIC,
    JUDGE,
}

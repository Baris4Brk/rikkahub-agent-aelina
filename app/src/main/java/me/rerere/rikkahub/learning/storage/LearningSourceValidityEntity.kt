package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlin.uuid.Uuid

/** Monotonic validity ledger for a stable authority revision; it stores no source body. */
@Entity(
    tableName = "learning_source_validity",
    primaryKeys = [
        "stream_id",
        "replay_generation",
        "scope_kind",
        "scope_id",
        "source_type",
        "source_id",
        "source_revision",
    ],
    indices = [
        Index(value = ["scope_kind", "scope_id", "source_type", "source_id", "state"]),
        Index(value = ["source_type", "source_id", "source_revision", "state"]),
        Index(value = ["state", "updated_at_ms"]),
        Index(value = ["authority_event_id"]),
    ],
)
data class LearningSourceValidityEntity(
    @ColumnInfo(name = "stream_id")
    val streamId: String,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long,
    @ColumnInfo(name = "previous_source_revision")
    val previousSourceRevision: Long?,
    val state: String,
    @ColumnInfo(name = "integrity_sha256")
    val integritySha256: String?,
    @ColumnInfo(name = "invalidation_reason")
    val invalidationReason: String?,
    @ColumnInfo(name = "authority_event_id")
    val authorityEventId: String,
    @ColumnInfo(name = "replay_generation")
    val replayGeneration: Long,
    @ColumnInfo(name = "occurred_at_ms")
    val occurredAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
) {
    init {
        require(runCatching { Uuid.parse(streamId) }.isSuccess) { "Invalid validity stream ID" }
        requireLearningScope(scopeKind, scopeId)
        requireLearningCode(sourceType, "validity source type")
        requireLearningStorageId(sourceId, "validity source ID")
        require(sourceRevision > 0L) { "Invalid validity source revision" }
        require(previousSourceRevision == null || previousSourceRevision > 0L) {
            "Invalid previous source revision"
        }
        require(previousSourceRevision == null || previousSourceRevision < sourceRevision) {
            "Source revision did not advance"
        }
        require(LearningSourceValidityState.entries.any { it.name == state }) {
            "Invalid source validity state"
        }
        integritySha256?.let { requireSha256(it, "source integrity") }
        invalidationReason?.let { requireLearningCode(it, "source invalidation reason") }
        require(
            (state == LearningSourceValidityState.VALID.name) == (invalidationReason == null)
        ) { "Source validity reason disagrees with state" }
        requireLearningStorageId(authorityEventId, "validity event ID")
        require(replayGeneration >= 0L) { "Negative validity replay generation" }
        require(occurredAtMs >= 0L && updatedAtMs >= occurredAtMs) {
            "Invalid source validity clock"
        }
    }

    override fun toString(): String =
        "LearningSourceValidityEntity(state=$state, revision=$sourceRevision, scope=$scopeKind, ids=<redacted>)"
}

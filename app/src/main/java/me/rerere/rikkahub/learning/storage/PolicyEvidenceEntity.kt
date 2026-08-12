package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** One distinct-Episode evidence edge. Retry/rollout copies conflict on the same primary key. */
@Entity(
    tableName = "policy_evidence",
    primaryKeys = ["policy_id", "episode_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["policy_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["episode_id"]),
        Index(value = ["source_type", "source_id", "source_revision"]),
        Index(value = ["policy_id", "polarity", "episode_id"]),
    ],
)
data class PolicyEvidenceEntity(
    @ColumnInfo(name = "policy_id")
    val policyId: String,
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "evidence_kind")
    val evidenceKind: String,
    val polarity: String,
    val quality: Double?,
    @ColumnInfo(name = "lesson_version")
    val lessonVersion: Int,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_revision")
    val sourceRevision: Long,
    @ColumnInfo(name = "source_integrity_sha256")
    val sourceIntegritySha256: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    init {
        requireLearningStorageId(policyId, "policy evidence policy ID")
        requireLearningStorageId(episodeId, "policy evidence episode ID")
        requireLearningCode(evidenceKind, "policy evidence kind")
        require(LearningPolicyEvidencePolarity.entries.any { it.name == polarity }) {
            "Invalid policy evidence polarity"
        }
        require(quality == null || (quality.isFinite() && quality in 0.0..1.0)) {
            "Invalid policy evidence quality"
        }
        require(lessonVersion > 0) { "Invalid evidence lesson version" }
        requireLearningCode(sourceType, "policy evidence source type")
        requireLearningStorageId(sourceId, "policy evidence source ID")
        require(sourceRevision > 0L) { "Policy evidence requires a stable source revision" }
        requireSha256(sourceIntegritySha256, "policy evidence source integrity")
        require(createdAtMs >= 0L) { "Negative policy evidence creation time" }
    }

    override fun toString(): String =
        "PolicyEvidenceEntity(kind=$evidenceKind, polarity=$polarity, ids=<redacted>)"
}

package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Exact reward authority that justified one policy-evidence edge. */
@Entity(
    tableName = "policy_reward_evidence",
    primaryKeys = ["policy_id", "episode_id", "reward_signal_id"],
    foreignKeys = [
        ForeignKey(
            entity = PolicyEvidenceEntity::class,
            parentColumns = ["policy_id", "episode_id"],
            childColumns = ["policy_id", "episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningRewardSignalEntity::class,
            parentColumns = ["episode_id", "id"],
            childColumns = ["episode_id", "reward_signal_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["episode_id", "reward_signal_id"]),
        Index(value = ["source_type", "source_id", "source_revision"]),
        Index(value = ["policy_id", "episode_id"]),
    ],
)
data class PolicyRewardEvidenceEntity(
    @ColumnInfo(name = "policy_id")
    val policyId: String,
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "reward_signal_id")
    val rewardSignalId: String,
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
        requireLearningStorageId(policyId, "policy reward evidence policy ID")
        requireLearningStorageId(episodeId, "policy reward evidence episode ID")
        requireLearningStorageId(rewardSignalId, "policy reward evidence signal ID")
        requireLearningCode(sourceType, "policy reward evidence source type")
        requireLearningStorageId(sourceId, "policy reward evidence source ID")
        require(sourceRevision > 0L) { "Policy reward evidence requires an exact revision" }
        requireSha256(sourceIntegritySha256, "policy reward evidence source integrity")
        require(createdAtMs >= 0L) { "Negative policy reward evidence creation time" }
    }

    override fun toString(): String =
        "PolicyRewardEvidenceEntity(sourceType=$sourceType, identities=<redacted>)"
}

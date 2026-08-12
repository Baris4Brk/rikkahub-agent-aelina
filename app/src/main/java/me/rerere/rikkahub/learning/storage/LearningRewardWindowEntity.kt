package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable, idempotent reward window. Unknown and censored components remain distinct from zero. */
@Entity(
    tableName = "learning_reward_windows",
    foreignKeys = [
        ForeignKey(
            entity = LearningEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["episode_id"], unique = true),
        Index(value = ["state", "close_after_ms"]),
        Index(value = ["scope_kind", "scope_id", "state", "close_after_ms"]),
    ],
)
data class LearningRewardWindowEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "opened_at_ms")
    val openedAtMs: Long,
    @ColumnInfo(name = "close_after_ms")
    val closeAfterMs: Long,
    val state: String,
    @ColumnInfo(name = "goal_knowledge")
    val goalKnowledge: String,
    @ColumnInfo(name = "goal_value")
    val goalValue: Double?,
    @ColumnInfo(name = "goal_unknown_reason")
    val goalUnknownReason: String?,
    @ColumnInfo(name = "goal_evidence_sha256")
    val goalEvidenceSha256: String?,
    @ColumnInfo(name = "process_knowledge")
    val processKnowledge: String,
    @ColumnInfo(name = "process_value")
    val processValue: Double?,
    @ColumnInfo(name = "process_unknown_reason")
    val processUnknownReason: String?,
    @ColumnInfo(name = "process_evidence_sha256")
    val processEvidenceSha256: String?,
    @ColumnInfo(name = "user_knowledge")
    val userKnowledge: String,
    @ColumnInfo(name = "user_value")
    val userValue: Double?,
    @ColumnInfo(name = "user_unknown_reason")
    val userUnknownReason: String?,
    @ColumnInfo(name = "user_evidence_sha256")
    val userEvidenceSha256: String?,
    @ColumnInfo(name = "weak_label")
    val weakLabel: Double?,
    @ColumnInfo(name = "reward_config_identity")
    val rewardConfigIdentity: String,
    @ColumnInfo(name = "closed_at_ms")
    val closedAtMs: Long?,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
) {
    init {
        requireLearningStorageId(id, "reward window ID")
        requireLearningStorageId(episodeId, "reward episode ID")
        requireLearningScope(scopeKind, scopeId)
        require(openedAtMs >= 0L && closeAfterMs >= openedAtMs && updatedAtMs >= openedAtMs) {
            "Invalid reward window clock"
        }
        require(LearningRewardWindowState.entries.any { it.name == state }) {
            "Invalid reward window state"
        }
        validateComponent(goalKnowledge, goalValue, goalUnknownReason, goalEvidenceSha256, "goal")
        validateComponent(
            processKnowledge,
            processValue,
            processUnknownReason,
            processEvidenceSha256,
            "process",
        )
        validateComponent(userKnowledge, userValue, userUnknownReason, userEvidenceSha256, "user")
        require(weakLabel == null || (weakLabel.isFinite() && weakLabel in -1.0..1.0)) {
            "Invalid weak reward label"
        }
        requireLearningIdentity(rewardConfigIdentity, "reward config identity")
        require((state == LearningRewardWindowState.OPEN.name) == (closedAtMs == null)) {
            "Reward window state and closure disagree"
        }
        require(closedAtMs == null || closedAtMs >= openedAtMs) { "Reward closes before opening" }
    }

    override fun toString(): String =
        "LearningRewardWindowEntity(state=$state, goal=$goalKnowledge, process=$processKnowledge, user=$userKnowledge, ids=<redacted>)"
}

private fun validateComponent(
    knowledge: String,
    value: Double?,
    unknownReason: String?,
    evidenceSha256: String?,
    label: String,
) {
    val parsed = requireNotNull(LearningRewardKnowledge.entries.firstOrNull { it.name == knowledge }) {
        "Invalid $label reward knowledge"
    }
    when (parsed) {
        LearningRewardKnowledge.KNOWN -> {
            require(value != null && value.isFinite() && value in -1.0..1.0) {
                "Known $label reward requires a bounded value"
            }
            require(unknownReason == null) { "Known $label reward cannot have unknown reason" }
            require(evidenceSha256 != null) { "Known $label reward requires evidence" }
        }

        LearningRewardKnowledge.UNKNOWN,
        LearningRewardKnowledge.CENSORED,
        -> {
            require(value == null) { "Unknown $label reward is not zero" }
            require(unknownReason != null) { "Unknown $label reward requires a reason" }
        }
    }
    unknownReason?.let { requireLearningCode(it, "$label reward unknown reason") }
    evidenceSha256?.let { requireSha256(it, "$label reward evidence") }
}

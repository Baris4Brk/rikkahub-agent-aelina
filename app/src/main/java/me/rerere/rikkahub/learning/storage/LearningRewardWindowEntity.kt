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
    @ColumnInfo(name = "revision", defaultValue = "1")
    val revision: Long = 1L,
    @ColumnInfo(name = "signal_set_sha256", defaultValue = EMPTY_REWARD_SIGNAL_SET_SQL_DEFAULT)
    val signalSetSha256: String = EMPTY_REWARD_SIGNAL_SET_SHA256,
    @ColumnInfo(name = "authority_outcome", defaultValue = UNKNOWN_REWARD_AUTHORITY_SQL_DEFAULT)
    val authorityOutcome: String = if (state == LearningRewardWindowState.OPEN.name) {
        LearningRewardAuthorityOutcome.PENDING.name
    } else {
        LearningRewardAuthorityOutcome.UNKNOWN.name
    },
    @ColumnInfo(name = "last_signal_at_ms")
    val lastSignalAtMs: Long? = null,
    @ColumnInfo(name = "goal_signal_kind")
    val goalSignalKind: String? = null,
    @ColumnInfo(name = "process_signal_kind")
    val processSignalKind: String? = null,
    @ColumnInfo(name = "user_signal_kind")
    val userSignalKind: String? = null,
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
        require(revision > 0L) { "Invalid reward window revision" }
        requireSha256(signalSetSha256, "reward signal set identity")
        require(LearningRewardAuthorityOutcome.entries.any { it.name == authorityOutcome }) {
            "Invalid reward authority outcome"
        }
        require(lastSignalAtMs == null || lastSignalAtMs in 0L..updatedAtMs) {
            "Invalid reward signal clock"
        }
        listOfNotNull(goalSignalKind, processSignalKind, userSignalKind).forEach { kind ->
            require(LearningRewardSignalKind.entries.any { it.name == kind }) {
                "Invalid reward window signal kind"
            }
        }
        if (authorityOutcome == LearningRewardAuthorityOutcome.PENDING.name) {
            require(signalSetSha256 == EMPTY_REWARD_SIGNAL_SET_SHA256 && lastSignalAtMs == null) {
                "Pending reward window cannot claim an authority signal set"
            }
            require(goalSignalKind == null && processSignalKind == null && userSignalKind == null) {
                "Pending reward window cannot claim a signal kind"
            }
        }
        require(
            (authorityOutcome == LearningRewardAuthorityOutcome.PENDING.name) ==
                (
                    state == LearningRewardWindowState.OPEN.name &&
                        signalSetSha256 == EMPTY_REWARD_SIGNAL_SET_SHA256
                    )
        ) { "Only an empty open reward window may have pending authority" }
        require(
            (signalSetSha256 == EMPTY_REWARD_SIGNAL_SET_SHA256) == (lastSignalAtMs == null)
        ) { "Reward signal set identity and last-signal clock disagree" }
        if (
            authorityOutcome == LearningRewardAuthorityOutcome.SUCCESS.name ||
            authorityOutcome == LearningRewardAuthorityOutcome.FAILURE.name
        ) {
            require(
                (
                    goalKnowledge == LearningRewardKnowledge.KNOWN.name &&
                        goalSignalKind != null
                    ) ||
                    (
                        userKnowledge == LearningRewardKnowledge.KNOWN.name &&
                            userSignalKind != null
                        )
            ) { "Success/failure reward authority requires known goal or user evidence" }
            require(signalSetSha256 != EMPTY_REWARD_SIGNAL_SET_SHA256 && lastSignalAtMs != null) {
                "Success/failure reward authority requires a non-empty signal set"
            }
        }
        if (authorityOutcome == LearningRewardAuthorityOutcome.CONFLICT.name) {
            require(
                signalSetSha256 != EMPTY_REWARD_SIGNAL_SET_SHA256 && lastSignalAtMs != null &&
                    (
                        goalKnowledge == LearningRewardKnowledge.KNOWN.name ||
                            userKnowledge == LearningRewardKnowledge.KNOWN.name
                        )
            ) { "Conflicting reward authority requires known goal/user signals" }
        }
    }

    override fun toString(): String =
        "LearningRewardWindowEntity(state=$state, goal=$goalKnowledge, process=$processKnowledge, user=$userKnowledge, ids=<redacted>)"
}

/** Canonical LearningCanonicalId.digest("reward-signal-set-v1", emptyList()). */
const val EMPTY_REWARD_SIGNAL_SET_SHA256: String =
    "fbfce8b1c00064b9a80e735a23b52f9f24cec559e34f824b1cf28c9f0adc4f9f"
private const val EMPTY_REWARD_SIGNAL_SET_SQL_DEFAULT: String =
    "'fbfce8b1c00064b9a80e735a23b52f9f24cec559e34f824b1cf28c9f0adc4f9f'"
private const val UNKNOWN_REWARD_AUTHORITY_SQL_DEFAULT: String = "'UNKNOWN'"

enum class LearningRewardAuthorityOutcome {
    PENDING,
    SUCCESS,
    FAILURE,
    UNKNOWN,
    CONFLICT,
    CENSORED,
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

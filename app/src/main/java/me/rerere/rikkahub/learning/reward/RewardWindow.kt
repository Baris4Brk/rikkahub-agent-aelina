package me.rerere.rikkahub.learning.reward

import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningSourceRef

enum class RewardWindowState {
    OPEN,
    CLOSED,
    EXPIRED,
}

enum class RewardUnknownReason {
    NO_SIGNAL,
    CENSORED,
    SOURCE_UNAVAILABLE,
    SOURCE_STALE,
    CONFLICTING_AUTHORITATIVE_SIGNALS,
    RETENTION_GAP,
}

sealed interface RewardComponent {
    data class Known(
        val value: Double,
        val evidence: List<LearningSourceRef>,
        val signalKind: RewardSignalKind,
    ) : RewardComponent {
        init {
            require(value.isFinite() && value in -1.0..1.0)
            require(evidence.isNotEmpty() && evidence.size <= 16)
            require(evidence.distinct().size == evidence.size)
            require(evidence.all { it.eligibleForPersistentPolicyEvidence })
        }
    }

    data class Unknown(val reason: RewardUnknownReason) : RewardComponent
}

data class RewardWindow(
    val episodeId: EpisodeId,
    val openedAtMs: Long,
    val closeAfterMs: Long,
    val state: RewardWindowState,
    val goal: RewardComponent,
    val process: RewardComponent,
    val user: RewardComponent,
    val rewardConfigVersion: String,
    val closedAtMs: Long?,
    val revision: Long,
) {
    init {
        require(openedAtMs >= 0L)
        require(closeAfterMs >= openedAtMs)
        require(rewardConfigVersion.matches(Regex("[a-z0-9][a-z0-9._-]{0,95}")))
        require(revision > 0L)
        require((state == RewardWindowState.OPEN) == (closedAtMs == null))
        require(closedAtMs == null || closedAtMs >= openedAtMs)
    }

    override fun toString(): String =
        "RewardWindow(state=$state, goal=${goal::class.simpleName}, " +
            "process=${process::class.simpleName}, user=${user::class.simpleName}, id=<redacted>)"
}

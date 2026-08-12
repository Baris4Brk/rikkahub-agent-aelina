package me.rerere.rikkahub.learning.reward

import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningSourceRef

enum class RewardDimension {
    GOAL,
    PROCESS,
    USER,
}

/** Ordered strongest to weakest. LLM_JUDGE can never overwrite a deterministic signal. */
enum class RewardSignalKind(val priority: Int) {
    EXPLICIT_USER_CORRECTION(500),
    EXPLICIT_USER_FEEDBACK(400),
    VERIFIED_TOOL_OUTCOME(300),
    COMMAND_FINAL_STATE(200),
    PROGRAMMATIC_METRIC(100),
    LLM_JUDGE_WEAK_LABEL(10),
}

data class RewardSignal(
    val signalId: String,
    val episodeId: EpisodeId,
    val dimension: RewardDimension,
    val kind: RewardSignalKind,
    val value: Double?,
    val unknownReason: RewardUnknownReason?,
    val evidence: List<LearningSourceRef>,
    val occurredAtMs: Long,
) {
    init {
        require(signalId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
        require((value == null) != (unknownReason == null)) {
            "A reward signal is exactly known or unknown"
        }
        require(value == null || value.isFinite() && value in -1.0..1.0)
        require(evidence.size <= 16 && evidence.distinct().size == evidence.size)
        require(value == null || evidence.isNotEmpty())
        require(evidence.all { it.eligibleForPersistentPolicyEvidence })
        require(occurredAtMs >= 0L)
    }

    override fun toString(): String =
        "RewardSignal(dimension=$dimension, kind=$kind, known=${value != null}, " +
            "evidence=${evidence.size}, ids=<redacted>)"
}

enum class RewardCollectionFailure {
    WRONG_EPISODE,
    DUPLICATE_SIGNAL_IDENTITY_CONFLICT,
    ALREADY_CLOSED,
    CLOSE_BEFORE_DEADLINE,
}

sealed interface RewardCollectionResult {
    data class Updated(val window: RewardWindow) : RewardCollectionResult
    data class Duplicate(val window: RewardWindow) : RewardCollectionResult
    data class Rejected(val failure: RewardCollectionFailure) : RewardCollectionResult
}

object RewardSignalCollector {
    fun open(
        episodeId: EpisodeId,
        openedAtMs: Long,
        closeAfterMs: Long,
        rewardConfigVersion: String,
    ): RewardWindow = RewardWindow(
        episodeId = episodeId,
        openedAtMs = openedAtMs,
        closeAfterMs = closeAfterMs,
        state = RewardWindowState.OPEN,
        goal = RewardComponent.Unknown(RewardUnknownReason.NO_SIGNAL),
        process = RewardComponent.Unknown(RewardUnknownReason.NO_SIGNAL),
        user = RewardComponent.Unknown(RewardUnknownReason.NO_SIGNAL),
        rewardConfigVersion = rewardConfigVersion,
        closedAtMs = null,
        revision = 1L,
    )

    /**
     * Pure deterministic fold. Storage must deduplicate signals by signalId before calling this;
     * watchdog retries therefore cannot multiply evidence or reward.
     */
    fun collect(
        current: RewardWindow,
        signals: List<RewardSignal>,
    ): RewardCollectionResult {
        if (current.state != RewardWindowState.OPEN) {
            return RewardCollectionResult.Rejected(RewardCollectionFailure.ALREADY_CLOSED)
        }
        if (signals.any { it.episodeId != current.episodeId }) {
            return RewardCollectionResult.Rejected(RewardCollectionFailure.WRONG_EPISODE)
        }
        val byId = signals.groupBy { it.signalId }
        if (byId.values.any { duplicates -> duplicates.distinct().size != 1 }) {
            return RewardCollectionResult.Rejected(
                RewardCollectionFailure.DUPLICATE_SIGNAL_IDENTITY_CONFLICT,
            )
        }
        val unique = byId.values.map(List<RewardSignal>::first)
        val next = current.copy(
            goal = choose(unique.filter { it.dimension == RewardDimension.GOAL }),
            process = choose(unique.filter { it.dimension == RewardDimension.PROCESS }),
            user = choose(unique.filter { it.dimension == RewardDimension.USER }),
            revision = Math.addExact(current.revision, 1L),
        )
        return if (
            next.goal == current.goal && next.process == current.process && next.user == current.user
        ) {
            RewardCollectionResult.Duplicate(current)
        } else {
            RewardCollectionResult.Updated(next)
        }
    }

    fun close(
        current: RewardWindow,
        frozenNowMs: Long,
        forceCensored: Boolean = false,
    ): RewardCollectionResult {
        if (current.state != RewardWindowState.OPEN) {
            return RewardCollectionResult.Duplicate(current)
        }
        if (!forceCensored && frozenNowMs < current.closeAfterMs) {
            return RewardCollectionResult.Rejected(RewardCollectionFailure.CLOSE_BEFORE_DEADLINE)
        }
        require(frozenNowMs >= current.openedAtMs)
        fun RewardComponent.censorIfUnknown(): RewardComponent = when (this) {
            is RewardComponent.Known -> this
            is RewardComponent.Unknown -> if (forceCensored) {
                RewardComponent.Unknown(RewardUnknownReason.CENSORED)
            } else {
                this
            }
        }
        return RewardCollectionResult.Updated(
            current.copy(
                state = RewardWindowState.CLOSED,
                goal = current.goal.censorIfUnknown(),
                process = current.process.censorIfUnknown(),
                user = current.user.censorIfUnknown(),
                closedAtMs = frozenNowMs,
                revision = Math.addExact(current.revision, 1L),
            ),
        )
    }

    private fun choose(signals: List<RewardSignal>): RewardComponent {
        if (signals.isEmpty()) return RewardComponent.Unknown(RewardUnknownReason.NO_SIGNAL)
        val strongestPriority = signals.maxOf { it.kind.priority }
        val strongest = signals.filter { it.kind.priority == strongestPriority }
        val known = strongest.filter { it.value != null }
        if (known.isEmpty()) {
            return RewardComponent.Unknown(
                strongest.mapNotNull { it.unknownReason }.sortedBy { it.ordinal }.firstOrNull()
                    ?: RewardUnknownReason.NO_SIGNAL,
            )
        }
        val values = known.mapNotNull { it.value }.distinct()
        if (values.size != 1) {
            return RewardComponent.Unknown(RewardUnknownReason.CONFLICTING_AUTHORITATIVE_SIGNALS)
        }
        return RewardComponent.Known(
            value = values.single(),
            evidence = known.flatMap { it.evidence }.distinct().sortedWith(SOURCE_ORDER),
            signalKind = known.first().kind,
        )
    }

    private val SOURCE_ORDER = compareBy<LearningSourceRef>(
        { it.sourceKind.ordinal },
        { it.sourceId },
        { it.sourceRevision },
    )
}

package me.rerere.rikkahub.learning.policy

import me.rerere.rikkahub.learning.episode.EpisodeId

enum class PolicyEvidencePolarity {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
}

data class PolicyEvidenceObservation(
    val evidenceId: String,
    val episodeId: EpisodeId,
    val polarity: PolicyEvidencePolarity,
    val sourceValid: Boolean,
) {
    init {
        require(evidenceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
    }
}

data class PolicyStatistics(
    val distinctEpisodeSupport: Int,
    val positiveEpisodes: Int,
    val negativeEpisodes: Int,
    val neutralEpisodes: Int,
    val usageCount: Int,
    val observedUtilityDelta: Double?,
    val utilityUncertainty: Double?,
) {
    init {
        require(distinctEpisodeSupport >= 0)
        require(positiveEpisodes >= 0 && negativeEpisodes >= 0 && neutralEpisodes >= 0)
        require(positiveEpisodes + negativeEpisodes + neutralEpisodes <= distinctEpisodeSupport)
        require(usageCount == 0) { "P1 has no actual policy exposure" }
        require(observedUtilityDelta == null && utilityUncertainty == null) {
            "P1 does not estimate utility"
        }
    }
}

object PolicyStatisticsCalculator {
    fun calculate(evidence: List<PolicyEvidenceObservation>): PolicyStatistics {
        val validByEpisode = evidence.filter(PolicyEvidenceObservation::sourceValid)
            .groupBy(PolicyEvidenceObservation::episodeId)
        var positive = 0
        var negative = 0
        var neutral = 0
        validByEpisode.values.forEach { observations ->
            val polarities = observations.map(PolicyEvidenceObservation::polarity).toSet()
            when {
                PolicyEvidencePolarity.POSITIVE in polarities &&
                    PolicyEvidencePolarity.NEGATIVE in polarities -> neutral += 1
                PolicyEvidencePolarity.NEGATIVE in polarities -> negative += 1
                PolicyEvidencePolarity.POSITIVE in polarities -> positive += 1
                else -> neutral += 1
            }
        }
        return PolicyStatistics(
            distinctEpisodeSupport = validByEpisode.size,
            positiveEpisodes = positive,
            negativeEpisodes = negative,
            neutralEpisodes = neutral,
            usageCount = 0,
            observedUtilityDelta = null,
            utilityUncertainty = null,
        )
    }
}

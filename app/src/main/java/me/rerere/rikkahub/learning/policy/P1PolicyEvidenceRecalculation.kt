package me.rerere.rikkahub.learning.policy

/** Content-free evidence signal used for deterministic P1 support/confidence recomputation. */
data class P1PolicyEvidenceSignal(
    val evidenceId: String,
    val polarity: PolicyEvidencePolarity,
    val quality: Double?,
) {
    init {
        require(evidenceId.isNotBlank())
        require(quality == null || (quality.isFinite() && quality in 0.0..1.0))
    }
}

data class P1PolicyEvidenceStatistics(
    val distinctEpisodeSupport: Int,
    val positiveEpisodeCount: Int,
    val negativeEpisodeCount: Int,
    val confidence: Double,
) {
    init {
        require(distinctEpisodeSupport >= 0)
        require(positiveEpisodeCount >= 0 && negativeEpisodeCount >= 0)
        require(positiveEpisodeCount + negativeEpisodeCount <= distinctEpisodeSupport)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

/** Frozen P1 heuristic from the architecture report; gain/utility deliberately remain UNKNOWN. */
object P1PolicyEvidenceRecalculator {
    fun calculate(signals: List<P1PolicyEvidenceSignal>): P1PolicyEvidenceStatistics {
        val distinct = signals.distinctBy(P1PolicyEvidenceSignal::evidenceId)
        if (distinct.isEmpty()) return P1PolicyEvidenceStatistics(0, 0, 0, 0.0)
        val positive = distinct.count { it.polarity == PolicyEvidencePolarity.POSITIVE }
        val negative = distinct.count { it.polarity == PolicyEvidencePolarity.NEGATIVE }
        val evidenceConfidence = distinct.size.toDouble() / (distinct.size + EVIDENCE_PRIOR_STRENGTH)
        val qualityConfidence = distinct.mapNotNull(P1PolicyEvidenceSignal::quality)
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0
        val contradictionRate = (2.0 * minOf(positive, negative) / distinct.size)
            .coerceIn(0.0, 1.0)
        val consistencyConfidence = 1.0 - contradictionRate
        val confidence = (
            EVIDENCE_WEIGHT * evidenceConfidence +
                QUALITY_WEIGHT * qualityConfidence +
                CONSISTENCY_WEIGHT * consistencyConfidence
            ).coerceIn(0.0, 1.0)
        return P1PolicyEvidenceStatistics(
            distinctEpisodeSupport = distinct.size,
            positiveEpisodeCount = positive,
            negativeEpisodeCount = negative,
            confidence = confidence,
        )
    }

    private const val EVIDENCE_PRIOR_STRENGTH = 3.0
    private const val EVIDENCE_WEIGHT = 0.40
    private const val QUALITY_WEIGHT = 0.35
    private const val CONSISTENCY_WEIGHT = 0.25
}

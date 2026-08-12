package me.rerere.rikkahub.learning.provenance

import me.rerere.rikkahub.learning.episode.EpisodeId

enum class SourceDerivedArtifactState {
    VALID,
    STALE_SOURCE,
    REJECTED,
}

data class EpisodeLessonValidity(
    val lessonId: String,
    val episodeId: EpisodeId,
    val sources: List<LearningSourceValidityKey>,
    val state: SourceDerivedArtifactState,
    val revision: Long,
) {
    init {
        require(lessonId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
        require(sources.isNotEmpty() && sources.size <= 16 && sources.distinct().size == sources.size)
        require(revision > 0L)
    }
}

data class SourceInvalidationPlan(
    val updatedValidity: LearningSourceValidity,
    val staleLessonIds: Set<String>,
    val eraseLessonSummaryIds: Set<String>,
) {
    override fun toString(): String =
        "SourceInvalidationPlan(stale=${staleLessonIds.size}, erase=${eraseLessonSummaryIds.size}, " +
            "source=$updatedValidity)"
}

object LearningSourceInvalidator {
    fun plan(
        validity: LearningSourceValidity,
        lessons: List<EpisodeLessonValidity>,
        privacyErase: Boolean,
    ): SourceInvalidationPlan {
        val affected = lessons.filter { lesson -> validity.key in lesson.sources }
        val stale = if (validity.isValid) emptySet() else affected.mapTo(linkedSetOf()) { it.lessonId }
        return SourceInvalidationPlan(
            updatedValidity = validity,
            staleLessonIds = stale,
            eraseLessonSummaryIds = if (privacyErase) stale else emptySet(),
        )
    }

    /** A historical revision can be restored only if the current authority still matches it. */
    fun canRestoreLesson(
        lesson: EpisodeLessonValidity,
        currentValidity: Map<LearningSourceValidityKey, LearningSourceValidity>,
    ): Boolean = lesson.sources.all { key -> currentValidity[key]?.isValid == true }
}

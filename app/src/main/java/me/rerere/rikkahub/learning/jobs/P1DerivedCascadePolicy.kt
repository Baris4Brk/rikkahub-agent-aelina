package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.storage.LearningJobType

internal data class P1EpisodeChildPlan(
    val enqueueRewardClose: Boolean,
    val enqueueReflection: Boolean,
    val attemptPolicyDistillation: Boolean,
)

/** Pure bounded DAG policy: Episode -> {Reward, Reflection} -> Distill; Distill has no child. */
internal object P1DerivedCascadePolicy {
    fun afterEpisode(
        episodeStatus: String,
        captureEnabled: Boolean,
        reflectionEnabled: Boolean,
        policyEnabled: Boolean,
        hasStableSource: Boolean,
        lessonAlreadyExists: Boolean,
        reflectionJobAlreadyExists: Boolean,
        modelConfigured: Boolean,
    ): P1EpisodeChildPlan {
        val terminal = episodeStatus != StoredLearningEpisodeStatus.OPEN.name
        val reflectable = episodeStatus in setOf(
            StoredLearningEpisodeStatus.SUCCESS.name,
            StoredLearningEpisodeStatus.PARTIAL.name,
            StoredLearningEpisodeStatus.FAILURE.name,
        )
        return P1EpisodeChildPlan(
            enqueueRewardClose = captureEnabled && terminal,
            enqueueReflection = captureEnabled && reflectionEnabled && reflectable &&
                hasStableSource && !lessonAlreadyExists && !reflectionJobAlreadyExists &&
                modelConfigured,
            attemptPolicyDistillation = captureEnabled && policyEnabled && terminal,
        )
    }

    fun afterLessonOrReward(
        policyEnabled: Boolean,
        distinctCompleteEvidence: Int,
    ): Boolean = policyEnabled && distinctCompleteEvidence >= 2

    fun afterPolicyDistillation(): Set<LearningJobType> = emptySet()

    /** A trigger creates one job for this newest-first bounded cohort, never one job per prefix. */
    fun <T> singleDistillationCohort(newestFirstEvidence: List<T>, limit: Int): List<T> {
        require(limit in 2..64)
        return newestFirstEvidence.take(limit)
    }
}

package me.rerere.rikkahub.learning.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import me.rerere.rikkahub.learning.storage.curator.CuratorDeltaCandidateEntity
import me.rerere.rikkahub.learning.storage.curator.CuratorDeltaDao
import me.rerere.rikkahub.learning.storage.curator.CuratorDeltaLineageEntity
import me.rerere.rikkahub.learning.storage.curator.CuratorDeltaRevisionEntity
import me.rerere.rikkahub.learning.storage.dao.LearnedWorkflowCandidateDao
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateEntity
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionEntity

/** v9 adds the append-only observed-utility ledger without rewriting the frozen v8 schema. */
const val LEARNING_DATABASE_VERSION: Int = 9

/**
 * Rebuildable, fail-open derived state. It is intentionally separate from the authoritative app
 * database so learning failures cannot block chat or memory commits.
 */
@Database(
    entities = [
        LearningInboxEventEntity::class,
        LearningStreamCheckpointEntity::class,
        LearningJobEntity::class,
        LearningEpisodeEntity::class,
        LearningTraceFeatureEntity::class,
        LearningEpisodeLessonEntity::class,
        LearningRewardWindowEntity::class,
        LearningSourceValidityEntity::class,
        LearningPolicyEntity::class,
        PolicyEvidenceEntity::class,
        PolicyRevisionEntity::class,
        PolicyLineageEntity::class,
        LearningProviderConfigCohortEntity::class,
        LearningProviderJobManifestEntity::class,
        LearningProviderAttemptEntity::class,
        LearningRewardSignalEntity::class,
        PolicyRewardEvidenceEntity::class,
        LearningPolicyShadowObservationEntity::class,
        LearningPolicyShadowObservationItemEntity::class,
        LearningPolicyExposureEntity::class,
        LearningPolicyExposureItemEntity::class,
        LearningObservedUtilityAssignmentEntity::class,
        LearningObservedUtilityOutcomeEntity::class,
        LearningObservedUtilityEvaluationReceiptEntity::class,
        LearnedWorkflowCandidateEntity::class,
        LearnedWorkflowCandidateRevisionEntity::class,
        CuratorDeltaCandidateEntity::class,
        CuratorDeltaRevisionEntity::class,
        CuratorDeltaLineageEntity::class,
    ],
    version = LEARNING_DATABASE_VERSION,
    exportSchema = true,
)
abstract class LearningDatabase : RoomDatabase() {
    abstract fun inboxDao(): LearningInboxDao

    abstract fun checkpointDao(): LearningCheckpointDao

    abstract fun jobDao(): LearningJobDao

    abstract fun episodeDao(): LearningEpisodeDao

    abstract fun policyDao(): LearningPolicyDao

    abstract fun policyShadowObservationDao(): LearningPolicyShadowObservationDao

    abstract fun providerExecutionDao(): LearningProviderExecutionDao

    abstract fun rewardSignalDao(): LearningRewardSignalDao

    abstract fun policyExposureDao(): LearningPolicyExposureDao

    abstract fun observedUtilityDao(): LearningObservedUtilityDao

    abstract fun learnedWorkflowCandidateDao(): LearnedWorkflowCandidateDao

    abstract fun curatorDeltaDao(): CuratorDeltaDao

    companion object {
        const val FILE_NAME: String = "learning_runtime.db"
    }
}

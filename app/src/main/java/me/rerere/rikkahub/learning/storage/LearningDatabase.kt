package me.rerere.rikkahub.learning.storage

import androidx.room.Database
import androidx.room.RoomDatabase

const val LEARNING_DATABASE_VERSION: Int = 3

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

    companion object {
        const val FILE_NAME: String = "learning_runtime.db"
    }
}

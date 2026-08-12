package me.rerere.rikkahub.learning.storage

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.retrieval.PolicyFtsManager
import me.rerere.rikkahub.learning.retention.LearningRetentionDecisionPolicyV1

/** All P1 retention thresholds live here; DAOs receive frozen cutoffs, never policy constants. */
class LearningRetentionPolicyV1(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun freeze(): LearningRetentionCutoffsV1 {
        val nowMs = clock()
        require(nowMs >= 0L) { "Negative retention clock" }
        return LearningRetentionCutoffsV1(
            nowMs = nowMs,
            openEpisodeCutoffMs = subtractFloor(nowMs, OPEN_EPISODE_MAX_AGE_MS),
            traceCutoffMs = subtractFloor(nowMs, TRACE_TTL_MS),
            episodeAndRewardCutoffMs = subtractFloor(nowMs, EPISODE_AND_REWARD_TTL_MS),
            lessonCutoffMs = subtractFloor(nowMs, LESSON_TTL_MS),
            dormantPolicyCutoffMs = subtractFloor(nowMs, DORMANT_POLICY_TTL_MS),
            invalidSourceCutoffMs = subtractFloor(nowMs, INVALID_SOURCE_MIN_TTL_MS),
        )
    }

    companion object {
        const val OPEN_EPISODE_MAX_AGE_MS: Long =
            LearningRetentionDecisionPolicyV1.OPEN_EPISODE_MAX_AGE_MS
        const val TRACE_TTL_MS: Long = LearningRetentionDecisionPolicyV1.TRACE_TTL_MS
        const val EPISODE_AND_REWARD_TTL_MS: Long =
            LearningRetentionDecisionPolicyV1.EPISODE_TTL_MS
        const val LESSON_TTL_MS: Long = LearningRetentionDecisionPolicyV1.LESSON_TTL_MS
        const val DORMANT_POLICY_TTL_MS: Long =
            LearningRetentionDecisionPolicyV1.CANDIDATE_TTL_MS
        const val INVALID_SOURCE_MIN_TTL_MS: Long =
            LearningRetentionDecisionPolicyV1.SOURCE_TOMBSTONE_AUDIT_FLOOR_MS
    }
}

data class LearningRetentionCutoffsV1(
    val nowMs: Long,
    val openEpisodeCutoffMs: Long,
    val traceCutoffMs: Long,
    val episodeAndRewardCutoffMs: Long,
    val lessonCutoffMs: Long,
    val dormantPolicyCutoffMs: Long,
    val invalidSourceCutoffMs: Long,
) {
    init {
        require(nowMs >= 0L)
        listOf(
            openEpisodeCutoffMs,
            traceCutoffMs,
            episodeAndRewardCutoffMs,
            lessonCutoffMs,
            dormantPolicyCutoffMs,
            invalidSourceCutoffMs,
        ).forEach { require(it in 0L..nowMs) }
    }
}

data class LearningRetentionResult(
    val censoredOpenEpisodes: Int,
    val deletedPolicies: Int,
    val deletedPolicyRevisions: Int,
    val deletedLessons: Int,
    val deletedTraceFeatures: Int,
    val deletedRewardWindows: Int,
    val deletedEpisodes: Int,
    val deletedSourceValidityRows: Int,
)

/** One bounded, dependency-ordered maintenance transaction. Retrieval never relies on this sweep. */
class LearningRetentionStore(
    private val database: LearningDatabase,
    private val policy: LearningRetentionPolicyV1 = LearningRetentionPolicyV1(),
    private val batchLimit: Int = DEFAULT_RETENTION_BATCH_LIMIT,
) {
    init {
        require(batchLimit in 1..DEFAULT_RETENTION_BATCH_LIMIT) { "Unsafe retention batch limit" }
    }

    suspend fun sweepOnce(): LearningRetentionResult {
        val cutoffs = policy.freeze()
        return database.withTransaction {
            val episodeDao = database.episodeDao()
            val policyDao = database.policyDao()
            val censored = episodeDao.censorExpiredOpenEpisodes(
                cutoffs.openEpisodeCutoffMs,
                cutoffs.nowMs,
                batchLimit,
            )
            // Policies release their evidence references before lesson/Episode pruning.
            val policies = policyDao.deleteExpiredPolicies(
                cutoffs.dormantPolicyCutoffMs,
                batchLimit,
            )
            val revisions = policyDao.deleteExpiredNonCurrentRevisions(
                cutoffs.lessonCutoffMs,
                batchLimit,
            )
            val lessons = episodeDao.deleteExpiredUnreferencedLessons(
                cutoffs.lessonCutoffMs,
                batchLimit,
            )
            val trace = episodeDao.deleteExpiredUnpinnedTrace(cutoffs.traceCutoffMs, batchLimit)
            val rewards = episodeDao.deleteExpiredRewardWindows(
                cutoffs.episodeAndRewardCutoffMs,
                batchLimit,
            )
            val episodes = episodeDao.deleteExpiredUnreferencedEpisodes(
                cutoffs.episodeAndRewardCutoffMs,
                batchLimit,
            )
            val validity = episodeDao.deleteExpiredUnreferencedSourceValidity(
                cutoffs.invalidSourceCutoffMs,
                batchLimit,
            )
            LearningRetentionResult(
                censoredOpenEpisodes = censored,
                deletedPolicies = policies,
                deletedPolicyRevisions = revisions,
                deletedLessons = lessons,
                deletedTraceFeatures = trace,
                deletedRewardWindows = rewards,
                deletedEpisodes = episodes,
                deletedSourceValidityRows = validity,
            )
        }
    }
}

data class LearningScopeEraseResult(
    val policies: Int,
    val rewards: Int,
    val lessons: Int,
    val traceFeatures: Int,
    val episodes: Int,
    val sourceValidityRows: Int,
    val retainedAuditTombstones: Int,
    val jobs: Int,
    val inboxEvents: Int,
)

/**
 * Irreversible exact-scope erase primitive. UI must supply explicit confirmation and quiesce the
 * runtime; deleting job rows in this transaction also fences any late worker completion.
 */
class LearningDerivedDataEraseStore(
    private val database: LearningDatabase,
) {
    suspend fun eraseScope(
        scope: LearningScope,
        frozenNowMs: Long,
    ): LearningScopeEraseResult {
        require(frozenNowMs >= 0L)
        // FTS is rebuildable and outside Room's entity graph, so remove its text before authority
        // rows. If the subsequent Room transaction fails, a later on-open backfill safely repairs
        // the projection; the privacy-sensitive direction (text surviving erase) never occurs.
        PolicyFtsManager(database).eraseScope(scope)
        return database.withTransaction {
            val kind = scope.kind.name
            val id = scope.storageId
            val policies = database.policyDao().deletePoliciesByScope(kind, id)
            val rewards = database.episodeDao().deleteRewardWindowsByScope(kind, id)
            val lessons = database.episodeDao().deleteLessonsByScope(kind, id)
            val trace = database.episodeDao().deleteTraceByScope(kind, id)
            val episodes = database.episodeDao().deleteEpisodesByScope(kind, id)
            val tombstoneCutoffMs = subtractFloor(
                frozenNowMs,
                LearningRetentionDecisionPolicyV1.SOURCE_TOMBSTONE_AUDIT_FLOOR_MS,
            )
            val retainedTombstones = database.episodeDao()
                .countRetainedSourceAuditTombstonesByScope(kind, id, tombstoneCutoffMs)
            val validity = database.episodeDao()
                .deleteErasableSourceValidityByScope(kind, id, tombstoneCutoffMs)
            val jobs = database.jobDao().deleteByScope(kind, id)
            val inbox = database.inboxDao().deleteByScope(kind, id)
            LearningScopeEraseResult(
                policies = policies,
                rewards = rewards,
                lessons = lessons,
                traceFeatures = trace,
                episodes = episodes,
                sourceValidityRows = validity,
                retainedAuditTombstones = retainedTombstones,
                jobs = jobs,
                inboxEvents = inbox,
            )
        }
    }
}

private fun subtractFloor(value: Long, delta: Long): Long =
    if (value < delta) 0L else value - delta

private const val DEFAULT_RETENTION_BATCH_LIMIT = 128

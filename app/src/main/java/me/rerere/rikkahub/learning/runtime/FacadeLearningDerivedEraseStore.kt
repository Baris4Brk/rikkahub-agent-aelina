package me.rerere.rikkahub.learning.runtime

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.privacy.LearningDerivedEraseStore
import me.rerere.rikkahub.learning.privacy.LearningEraseReceipt
import me.rerere.rikkahub.learning.privacy.LearningEphemeralScopeEraser

/** Typed privacy adapter; the Learning Room instance remains owned by [LearningRuntimeFacade]. */
class FacadeLearningDerivedEraseStore internal constructor(
    private val runtime: LearningRuntimeFacade,
    private val ephemeralEraser: LearningEphemeralScopeEraser,
) : LearningDerivedEraseStore {
    override suspend fun eraseScope(
        scope: LearningScope,
        frozenNowMs: Long,
    ): LearningEraseReceipt {
        val erased = runtime.eraseDerivedScope(scope, frozenNowMs, ephemeralEraser)
        return LearningEraseReceipt(
            erasedEpisodes = erased.episodes,
            erasedTraceFeatures = erased.traceFeatures,
            erasedLessons = erased.lessons,
            erasedRewards = erased.rewards,
            erasedPolicies = erased.policies,
            retainedAuditTombstones = erased.retainedAuditTombstones,
            erasedSourceValidityRows = erased.sourceValidityRows,
            erasedJobs = erased.jobs,
            erasedInboxEvents = erased.inboxEvents,
        )
    }
}

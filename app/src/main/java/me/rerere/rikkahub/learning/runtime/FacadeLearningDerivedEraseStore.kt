package me.rerere.rikkahub.learning.runtime

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.privacy.LearningDerivedEraseStore
import me.rerere.rikkahub.learning.privacy.LearningEraseReceipt
import me.rerere.rikkahub.learning.privacy.LearningEphemeralScopeEraser
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowErasePort
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowPrivacyPort

/** Typed privacy adapter; the Learning Room instance remains owned by [LearningRuntimeFacade]. */
class FacadeLearningDerivedEraseStore internal constructor(
    private val runtime: LearningRuntimeFacade,
    private val ephemeralEraser: LearningEphemeralScopeEraser,
    private val learnedWorkflowErasePort: ExactScopeLearnedWorkflowErasePort,
    private val durableLearnedWorkflowPrivacyPort: DurableLearnedWorkflowPrivacyPort,
) : LearningDerivedEraseStore {
    override suspend fun eraseScope(
        scope: LearningScope,
        frozenNowMs: Long,
    ): LearningEraseReceipt {
        val erased = runtime.eraseDerivedScope(
            scope = scope,
            frozenNowMs = frozenNowMs,
            ephemeralEraser = ephemeralEraser,
            learnedWorkflowErasePort = learnedWorkflowErasePort,
            durableLearnedWorkflowPrivacyPort = durableLearnedWorkflowPrivacyPort,
        )
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
            erasedPolicyExposures = erased.policyExposures,
            erasedPolicyShadowObservations = erased.policyShadowObservations,
            erasedMainDatabaseWorkflows = erased.mainDatabaseWorkflows,
            erasedObservedUtilityEvaluationReceipts = erased.observedUtilityEvaluationReceipts,
            erasedObservedUtilityAssignments = erased.observedUtilityAssignments,
            erasedProviderConfigCohorts = erased.providerConfigCohorts,
        )
    }
}

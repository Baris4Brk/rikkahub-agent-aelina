package me.rerere.rikkahub.learning.storage

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.model.LearningRetentionPreferencesV1
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowErasePort
import me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowPrivacyPort
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowCandidatePageSource
import me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowEraseSaga
import me.rerere.rikkahub.learning.curator.CuratorRetentionArchiveCursor
import me.rerere.rikkahub.learning.curator.CuratorRetentionArchiveRequest
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationResult
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyLifecycleReason
import me.rerere.rikkahub.learning.policy.PolicyMutationActor
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.retrieval.PolicyFtsManager
import me.rerere.rikkahub.learning.retention.LearningRetentionDecisionPolicyV1
import me.rerere.rikkahub.learning.retention.LearningRetentionPlanV1
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionActor
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionReason
import me.rerere.rikkahub.learning.storage.curator.RoomCuratorReviewRuntimeStore
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState

/** All Learning retention thresholds resolve here; DAOs receive one frozen plan, never day values. */
class LearningRetentionPolicyV1(
    private val preferences: LearningRetentionPreferencesV1 = LearningRetentionPreferencesV1(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun freeze(): LearningRetentionPlanV1 {
        val nowMs = clock()
        require(nowMs >= 0L) { "Negative retention clock" }
        return LearningRetentionDecisionPolicyV1.freezePlan(nowMs, preferences)
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

typealias LearningRetentionCutoffsV1 = LearningRetentionPlanV1

data class LearningRetentionResult(
    val censoredOpenEpisodes: Int,
    val archivedWorkflowCandidates: Int,
    val archivedCuratorCandidates: Int,
    val archivedPolicies: Int,
    val deletedPolicyRevisions: Int,
    val deletedLessons: Int,
    val deletedTraceFeatures: Int,
    val deletedRewardWindows: Int,
    val deletedEpisodes: Int,
    val deletedSourceValidityRows: Int,
    val deletedSettledPolicyExposures: Int,
    val deletedUnreferencedRewardSignals: Int,
    val deletedDoneJobs: Int,
    val deletedProviderConfigCohorts: Int,
    val deletedInboxEvents: Int,
    val deletedShadowObservations: Int,
    val deletedWorkflowRevisions: Int = 0,
    val deletedObservedUtilityEvaluationReceipts: Int = 0,
    val deletedObservedUtilityAssignments: Int = 0,
) {
    val deletedWorkflowCandidates: Int get() = archivedWorkflowCandidates
    val deletedPolicies: Int get() = archivedPolicies
}

/**
 * One bounded, dependency-ordered maintenance pass. Lifecycle expiry is fenced row-by-row before
 * the independent derived-artifact pruning transaction; retrieval never relies on this sweep.
 */
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
        val workflowCandidates = archiveExpiredWorkflowCandidates(cutoffs)
        val curatorCandidates = archiveExpiredCuratorCandidates(cutoffs)
        val policies = archiveExpiredPolicyCandidates(cutoffs)
        return database.withTransaction {
            val episodeDao = database.episodeDao()
            val policyDao = database.policyDao()
            val utilityReceipts = database.observedUtilityDao()
                .deleteExpiredEvaluationReceiptsPage(
                    cutoffs.policyExposureCutoffMs,
                    batchLimit,
                )
            val utilityAssignments = database.observedUtilityDao()
                .deleteExpiredAssignmentsPage(
                    cutoffs.policyExposureCutoffMs,
                    batchLimit,
                )
            val settledExposures = database.policyExposureDao().deleteExpiredSettledPage(
                cutoffs.policyExposureCutoffMs,
                batchLimit,
            )
            val shadowObservations = database.policyShadowObservationDao().deleteExpiredPage(
                cutoffs.policyExposureCutoffMs,
                batchLimit,
            )
            val rewardSignals = database.rewardSignalDao().deleteExpiredUnreferencedSignalsPage(
                cutoffs.rewardCutoffMs,
                batchLimit,
            )
            val doneJobs = database.jobDao().deleteDonePage(
                cutoffs.doneJobCutoffMs,
                batchLimit,
            )
            val providerCohorts = database.providerExecutionDao()
                .deleteUnreferencedConfigCohortsPage(batchLimit)
            val checkpoint = database.checkpointDao().listAll().singleOrNull()
            val inbox = if (
                checkpoint != null && checkpoint.bootstrapState == "COMPLETE" &&
                checkpoint.lastContiguousSeq >= 0L
            ) {
                database.inboxDao().deleteExpiredConsumedPage(
                    streamId = checkpoint.streamId,
                    replayGeneration = checkpoint.replayGeneration,
                    throughContiguousSeq = checkpoint.lastContiguousSeq,
                    ingestedBeforeMs = cutoffs.inboxCutoffMs,
                    limit = batchLimit,
                )
            } else {
                0
            }
            val censored = episodeDao.censorExpiredOpenEpisodes(
                cutoffs.openEpisodeCutoffMs,
                cutoffs.nowMs,
                batchLimit,
            )
            val revisions = policyDao.deleteExpiredNonCurrentRevisions(
                cutoffs.revisionCutoffMs,
                batchLimit,
            )
            val workflowRevisions = database.learnedWorkflowCandidateDao()
                .deleteExpiredSupersededMachineRevisions(
                    cutoffs.workflowRevisionCutoffMs,
                    batchLimit,
                )
            val lessons = episodeDao.deleteExpiredUnreferencedLessons(
                cutoffs.lessonCutoffMs,
                batchLimit,
            )
            val trace = episodeDao.deleteExpiredUnpinnedTrace(cutoffs.traceCutoffMs, batchLimit)
            val rewards = episodeDao.deleteExpiredRewardWindows(
                cutoffs.rewardCutoffMs,
                batchLimit,
            )
            val episodes = episodeDao.deleteExpiredUnreferencedEpisodes(
                cutoffs.episodeCutoffMs,
                batchLimit,
            )
            val validity = episodeDao.deleteExpiredUnreferencedSourceValidity(
                cutoffs.invalidSourceCutoffMs,
                batchLimit,
            )
            LearningRetentionResult(
                censoredOpenEpisodes = censored,
                archivedWorkflowCandidates = workflowCandidates,
                archivedCuratorCandidates = curatorCandidates,
                archivedPolicies = policies,
                deletedPolicyRevisions = revisions,
                deletedLessons = lessons,
                deletedTraceFeatures = trace,
                deletedRewardWindows = rewards,
                deletedEpisodes = episodes,
                deletedSourceValidityRows = validity,
                deletedSettledPolicyExposures = settledExposures,
                deletedShadowObservations = shadowObservations,
                deletedUnreferencedRewardSignals = rewardSignals,
                deletedDoneJobs = doneJobs,
                deletedProviderConfigCohorts = providerCohorts,
                deletedInboxEvents = inbox,
                deletedWorkflowRevisions = workflowRevisions,
                deletedObservedUtilityEvaluationReceipts = utilityReceipts,
                deletedObservedUtilityAssignments = utilityAssignments,
            )
        }
    }

    private suspend fun archiveExpiredPolicyCandidates(
        cutoffs: LearningRetentionPlanV1,
    ): Int {
        val candidates = database.policyDao().listExpiredUnreviewedCandidates(
            cutoffMs = cutoffs.candidateCutoffMs,
            limit = batchLimit,
        )
        val mutationStore = RoomPolicyLifecycleMutationStore(database)
        var archived = 0
        candidates.forEach { candidate ->
            val scope = LearningScope.parseOrNull(candidate.scopeKind, candidate.scopeId)
                ?: return@forEach
            val result = mutationStore.mutate(
                PolicyMutationRequest.Transition(
                    fence = PolicyMutationFence(
                        policyId = candidate.id,
                        scope = scope,
                        expectedRevision = candidate.stateVersion,
                        expectedContentRevision = candidate.contentRevision,
                        expectedArtifactHash = candidate.artifactSha256,
                    ),
                    target = LearningPolicyStatus.ARCHIVED,
                    reason = PolicyLifecycleReason.RETENTION_EXPIRED,
                    frozenNowMs = cutoffs.nowMs,
                    actor = PolicyMutationActor.RETENTION,
                ),
            )
            if (result is PolicyMutationResult.Applied) archived += 1
        }
        return archived
    }

    private suspend fun archiveExpiredWorkflowCandidates(
        cutoffs: LearningRetentionPlanV1,
    ): Int {
        val dao = database.learnedWorkflowCandidateDao()
        val candidates = dao.listExpiredArchivable(
            cutoffMs = cutoffs.workflowCandidateCutoffMs,
            limit = batchLimit,
        )
        var archived = 0
        candidates.forEach { candidate ->
            val nextStateVersion = candidate.stateVersion.takeIf { it < Long.MAX_VALUE }
                ?.plus(1L) ?: return@forEach
            val next = candidate.copy(
                stateVersion = nextStateVersion,
                state = LearnedWorkflowCandidateState.ARCHIVED.name,
                archivedAtMs = cutoffs.nowMs,
                updatedAtMs = cutoffs.nowMs,
            )
            if (
                dao.transitionFenced(
                    expected = candidate,
                    next = next,
                    reason = LearnedWorkflowCandidateRevisionReason.RETENTION_EXPIRED,
                    actor = LearnedWorkflowCandidateRevisionActor.RETENTION,
                )
            ) {
                archived += 1
            }
        }
        return archived
    }

    private suspend fun archiveExpiredCuratorCandidates(
        cutoffs: LearningRetentionPlanV1,
    ): Int {
        val store = RoomCuratorReviewRuntimeStore(database)
        val candidates = store.listRetentionArchivable(
            cutoffMs = cutoffs.candidateCutoffMs,
            after = CuratorRetentionArchiveCursor(),
            limit = batchLimit,
        )
        var archived = 0
        candidates.forEach { candidate ->
            val result = store.archiveRetention(
                CuratorRetentionArchiveRequest(
                    candidateId = candidate.candidateId,
                    expectedState = candidate.state,
                    expectedStateVersion = candidate.stateVersion,
                    expectedCandidateSha256 = candidate.candidateSha256,
                    expectedUpdatedAtMs = candidate.updatedAtMs,
                    archivedAtMs = cutoffs.nowMs.coerceAtLeast(candidate.updatedAtMs),
                ),
            )
            if (result is CuratorReviewMutationResult.Applied) archived += 1
        }
        return archived
    }
}

data class LearningScopeEraseResult(
    val observedUtilityEvaluationReceipts: Int,
    val observedUtilityAssignments: Int,
    val policyExposures: Int,
    val policyShadowObservations: Int,
    val mainDatabaseWorkflows: Int,
    val workflowCandidates: Int,
    val policies: Int,
    val rewards: Int,
    val lessons: Int,
    val traceFeatures: Int,
    val episodes: Int,
    val sourceValidityRows: Int,
    val retainedAuditTombstones: Int,
    val jobs: Int,
    val inboxEvents: Int,
    val providerConfigCohorts: Int,
)

/**
 * Irreversible exact-scope erase primitive. UI must supply explicit confirmation and quiesce the
 * runtime; deleting job rows in this transaction also fences any late worker completion.
 */
class LearningDerivedDataEraseStore(
    private val database: LearningDatabase,
    private val learnedWorkflowErasePort: ExactScopeLearnedWorkflowErasePort,
    private val durableLearnedWorkflowPrivacyPort: DurableLearnedWorkflowPrivacyPort,
) {
    suspend fun eraseScope(
        scope: LearningScope,
        frozenNowMs: Long,
    ): LearningScopeEraseResult {
        require(frozenNowMs >= 0L)
        // AppDatabase goes first. Every exact-scope candidate id becomes a permanent disabled
        // tombstone/claim before its LearningDatabase authority row can disappear. A crash or
        // conflict leaves the candidates intact, so replay sees the same bounded id pages and
        // cannot report success until all pages are fenced.
        val durableScopeReceipt = durableLearnedWorkflowPrivacyPort.redactExactScope(
            scope,
            frozenNowMs,
        )
        val candidateMappedWorkflows = fenceMainDatabaseWorkflows(scope, frozenNowMs)
        // The durable AppDB scan is authoritative after candidate loss. The candidate saga then
        // closes in-flight promotion races and may observe the same tombstones, hence max rather
        // than addition avoids double-counting one definition in this content-free receipt.
        val mainDatabaseWorkflows = maxOf(
            durableScopeReceipt.redactedDefinitions,
            candidateMappedWorkflows,
        )
        // FTS is rebuildable and outside Room's entity graph, so remove its text before authority
        // rows. If the subsequent Room transaction fails, a later on-open backfill safely repairs
        // the projection; the privacy-sensitive direction (text surviving erase) never occurs.
        PolicyFtsManager(database).eraseScope(scope)
        return database.withTransaction {
            val kind = scope.kind.name
            val id = scope.storageId
            val policyShadowObservations = database.policyShadowObservationDao()
                .deleteByScope(kind, id)
            var utilityReceipts = 0
            var utilityAssignments = 0
            var utilityDeleted: Int
            do {
                utilityDeleted = database.observedUtilityDao().deleteEvaluationScopePage(
                    scopeKind = kind,
                    scopeId = id,
                    limit = DEFAULT_RETENTION_BATCH_LIMIT,
                )
                utilityReceipts = Math.addExact(utilityReceipts, utilityDeleted)
            } while (utilityDeleted == DEFAULT_RETENTION_BATCH_LIMIT)
            do {
                utilityDeleted = database.observedUtilityDao().deleteAssignmentScopePage(
                    scopeKind = kind,
                    scopeId = id,
                    limit = DEFAULT_RETENTION_BATCH_LIMIT,
                )
                utilityAssignments = Math.addExact(utilityAssignments, utilityDeleted)
            } while (utilityDeleted == DEFAULT_RETENTION_BATCH_LIMIT)
            // Curator wires can contain reviewed Policy summaries. Destroy them, append a
            // content-free privacy receipt, and deactivate their lineage before the canonical
            // Policy rows disappear. Each call removes the earliest still-unredacted page; a
            // full page means another bounded pass is required.
            var curatorHasMore: Boolean
            do {
                val redaction = database.curatorDeltaDao().redactScopeBeforeErase(
                    scopeKind = kind,
                    scopeId = id,
                    redactedAtMs = frozenNowMs,
                    limit = DEFAULT_RETENTION_BATCH_LIMIT,
                )
                check(redaction.redacted == redaction.scanned) {
                    "Curator privacy redaction did not cover the complete page"
                }
                curatorHasMore = redaction.hasMore
            } while (curatorHasMore)
            var policyExposures = 0
            var deleted: Int
            do {
                deleted = database.policyExposureDao().deleteScopePage(
                    scopeKind = kind,
                    scopeId = id,
                    limit = DEFAULT_RETENTION_BATCH_LIMIT,
                )
                policyExposures = Math.addExact(policyExposures, deleted)
            } while (deleted == DEFAULT_RETENTION_BATCH_LIMIT)
            val workflowCandidates = when (scope) {
                is LearningScope.Assistant -> database.learnedWorkflowCandidateDao()
                    .deleteAssistantScope(scope.assistantId.toString())
                is LearningScope.AuthoritySubject -> database.learnedWorkflowCandidateDao()
                    .deleteAuthoritySubjectScope(scope.authoritySubjectId)
            }
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
            var providerConfigCohorts = 0
            var providerCohortsDeleted: Int
            do {
                providerCohortsDeleted = database.providerExecutionDao()
                    .deleteUnreferencedConfigCohortsPage(DEFAULT_RETENTION_BATCH_LIMIT)
                providerConfigCohorts = Math.addExact(
                    providerConfigCohorts,
                    providerCohortsDeleted,
                )
            } while (providerCohortsDeleted == DEFAULT_RETENTION_BATCH_LIMIT)
            val inbox = database.inboxDao().deleteByScope(kind, id)
            LearningScopeEraseResult(
                observedUtilityEvaluationReceipts = utilityReceipts,
                observedUtilityAssignments = utilityAssignments,
                policyExposures = policyExposures,
                policyShadowObservations = policyShadowObservations,
                mainDatabaseWorkflows = mainDatabaseWorkflows,
                workflowCandidates = workflowCandidates,
                policies = policies,
                rewards = rewards,
                lessons = lessons,
                traceFeatures = trace,
                episodes = episodes,
                sourceValidityRows = validity,
                retainedAuditTombstones = retainedTombstones,
                jobs = jobs,
                inboxEvents = inbox,
                providerConfigCohorts = providerConfigCohorts,
            )
        }
    }

    private suspend fun fenceMainDatabaseWorkflows(
        scope: LearningScope,
        frozenNowMs: Long,
    ): Int {
        val candidateSource = ExactScopeLearnedWorkflowCandidatePageSource {
                exactScope, afterIdExclusive, limit ->
            when (exactScope) {
                is LearningScope.Assistant -> database.learnedWorkflowCandidateDao()
                    .listAssistantScopeIdsForErase(
                        assistantId = exactScope.assistantId.toString(),
                        afterIdExclusive = afterIdExclusive,
                        limit = limit,
                    )
                is LearningScope.AuthoritySubject -> database.learnedWorkflowCandidateDao()
                    .listAuthoritySubjectScopeIdsForErase(
                        authoritySubjectId = exactScope.authoritySubjectId,
                        afterIdExclusive = afterIdExclusive,
                        limit = limit,
                    )
            }
        }
        return ExactScopeLearnedWorkflowEraseSaga(
            candidates = candidateSource,
            workflows = learnedWorkflowErasePort,
            batchSize = DEFAULT_RETENTION_BATCH_LIMIT,
        ).fenceBeforeLearningDelete(scope, frozenNowMs).redactedWorkflowDefinitions
    }
}

private fun subtractFloor(value: Long, delta: Long): Long =
    if (value < delta) 0L else value - delta

private const val DEFAULT_RETENTION_BATCH_LIMIT = 128

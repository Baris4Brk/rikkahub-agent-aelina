package me.rerere.rikkahub.learning.exposure

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.policy.ObservedUtilityArm
import me.rerere.rikkahub.learning.policy.ObservedUtilityOutcome
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityLedgerWriteResult
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityOutcomeAuthority
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityOutcomeCommit
import me.rerere.rikkahub.learning.policy.runtime.RoomObservedUtilityLedger
import me.rerere.rikkahub.learning.policy.runtime.observedUtilityExposureReceiptDigest
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus

private const val MAX_TERMINAL_UTILITY_ASSIGNMENTS = 20

/**
 * Projects only an already-committed terminal authority onto assignments frozen before treatment.
 * Missing baseline progress or exposure snapshots remain explicit and later force ABSTAIN.
 */
internal class ObservedUtilityTerminalAuthorityProjector(
    private val database: LearningDatabase,
) {
    suspend fun project(
        plan: PolicyExposureOutcomeLinkPlan,
        episode: LearningEpisodeEntity,
    ): ObservedUtilityTerminalProjectionResult = try {
        val outcome = episode.toObservedUtilityOutcome()
            ?: return ObservedUtilityTerminalProjectionResult.Unavailable
        val assignments = database.observedUtilityDao().listUnclosedAssignmentsForEpisode(
            streamId = plan.streamId,
            replayGeneration = plan.replayGeneration,
            episodeId = plan.episodeId,
            logicalRunId = plan.logicalRunId,
            limit = MAX_TERMINAL_UTILITY_ASSIGNMENTS,
        )
        val ledger = RoomObservedUtilityLedger(database, database.observedUtilityDao())
        var applied = 0
        var duplicates = 0
        var conflicts = 0
        var unavailable = 0
        assignments.forEach { assignment ->
            val exposureSnapshot = assignment.expectedExposureId?.let { exposureId ->
                when (val loaded = RoomPolicyExposureStore(database).load(exposureId)) {
                    is PolicyExposureStoreResult.Available ->
                        loaded.receipt.stateVersion to
                            observedUtilityExposureReceiptDigest(loaded.receipt)
                    is PolicyExposureStoreResult.Conflict -> {
                        conflicts += 1
                        return@forEach
                    }
                    is PolicyExposureStoreResult.Unavailable -> {
                        unavailable += 1
                        return@forEach
                    }
                }
            }
            val isBaseline = assignment.arm == ObservedUtilityArm.NON_EXPOSURE.name
            val result = ledger.commit(
                ObservedUtilityOutcomeCommit(
                    assignmentId = assignment.id,
                    outcome = outcome,
                    authority = ObservedUtilityOutcomeAuthority(
                        sourceKind = plan.authority.sourceKind,
                        sourceId = plan.authority.sourceId,
                        sourceRevision = plan.authority.sourceRevision,
                    ),
                    // A committed result message proves a provider response/progress for the
                    // no-Policy request. Other baseline failures remain ineligible, not negative.
                    baselineHostDispatched = isBaseline &&
                        episode.resultAssistantMessageId != null,
                    baselineProgressOrResponse = isBaseline &&
                        episode.resultAssistantMessageId != null,
                    exposureStateVersion = exposureSnapshot?.first,
                    exposureReceiptDigest = exposureSnapshot?.second,
                    windowClosedAtMs = plan.linkObservedAtMs,
                    recordedAtMs = plan.linkObservedAtMs,
                ),
            )
            when (result) {
                is ObservedUtilityLedgerWriteResult.Applied -> applied += 1
                is ObservedUtilityLedgerWriteResult.Duplicate -> duplicates += 1
                is ObservedUtilityLedgerWriteResult.Conflict -> conflicts += 1
                ObservedUtilityLedgerWriteResult.Unavailable -> unavailable += 1
            }
        }
        ObservedUtilityTerminalProjectionResult.Completed(
            scanned = assignments.size,
            applied = applied,
            duplicates = duplicates,
            conflicts = conflicts,
            unavailable = unavailable,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ObservedUtilityTerminalProjectionResult.Unavailable
    }
}

internal sealed interface ObservedUtilityTerminalProjectionResult {
    data class Completed(
        val scanned: Int,
        val applied: Int,
        val duplicates: Int,
        val conflicts: Int,
        val unavailable: Int,
    ) : ObservedUtilityTerminalProjectionResult {
        init {
            require(scanned in 0..MAX_TERMINAL_UTILITY_ASSIGNMENTS)
            require(applied + duplicates + conflicts + unavailable == scanned)
        }
    }

    data object Unavailable : ObservedUtilityTerminalProjectionResult
}

private fun LearningEpisodeEntity.toObservedUtilityOutcome(): ObservedUtilityOutcome? =
    if (finalizedAtMs == null) null else when (status) {
        StoredLearningEpisodeStatus.SUCCESS.name -> ObservedUtilityOutcome.SUCCESS
        StoredLearningEpisodeStatus.PARTIAL.name,
        StoredLearningEpisodeStatus.FAILURE.name,
        -> ObservedUtilityOutcome.FAILURE
        StoredLearningEpisodeStatus.CENSORED.name,
        StoredLearningEpisodeStatus.SUPERSEDED.name,
        -> ObservedUtilityOutcome.CENSORED
        StoredLearningEpisodeStatus.UNKNOWN.name -> ObservedUtilityOutcome.UNKNOWN
        StoredLearningEpisodeStatus.OPEN.name,
        StoredLearningEpisodeStatus.ABORTED.name,
        StoredLearningEpisodeStatus.TIMEOUT.name,
        -> null
        else -> null
    }

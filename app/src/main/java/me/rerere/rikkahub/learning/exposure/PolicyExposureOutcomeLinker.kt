package me.rerere.rikkahub.learning.exposure

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.episode.LearningCompletionKind
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.policy.runtime.NoOpPolicyOutcomeLinkedObserver
import me.rerere.rikkahub.learning.policy.runtime.PolicyOutcomeLinkedObserver
import me.rerere.rikkahub.learning.policy.runtime.PolicyOutcomeSafetyTrigger
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeBoundaryReason
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus

private const val MAX_OUTCOME_LINK_ATTEMPTS_PER_PASS = 20
private const val COMMAND_TERMINAL_EVENT = "COMMAND_TERMINAL"
private const val COMMAND_EVENT_SCHEMA_VERSION = 2

/** Exact, content-free authority plan derived only from committed durable rows. */
internal data class PolicyExposureOutcomeLinkPlan(
    val streamId: String,
    val replayGeneration: Long,
    val episodeId: String,
    val logicalRunId: String,
    val scopeKind: String,
    val scopeId: String,
    val authority: PolicyExposureOutcomeAuthority,
    val linkObservedAtMs: Long,
)

/**
 * Pure fail-closed mapping from a committed command terminal and its terminal Episode.
 *
 * The mapping deliberately does not decide utility. Failure/censored/superseded terminals remain
 * linkable historical outcomes, while a later estimator must exclude censored/non-result cases
 * from utility attribution. Waiting/fast-path/control/final-save-failure never form a link here.
 */
internal object PolicyExposureOutcomeLinkPolicy {
    fun plan(
        event: LearningInboxEventEntity,
        episode: LearningEpisodeEntity,
    ): PolicyExposureOutcomeLinkPlan? {
        if (
            event.eventTypeCode != COMMAND_TERMINAL_EVENT ||
            event.eventSchemaVersion != COMMAND_EVENT_SCHEMA_VERSION ||
            event.decodeState != LearningEventDecodeState.KNOWN.name ||
            event.sourceType != LearningSourceKind.COMMAND.name ||
            event.missingRevisionReason != null ||
            event.terminalState !in COMMAND_TERMINAL_STATES ||
            episode.status == StoredLearningEpisodeStatus.OPEN.name ||
            episode.finalizedAtMs == null
        ) return null

        val sourceRevision = event.sourceRevision?.takeIf { it > 0L } ?: return null
        val commandId = event.commandId ?: return null
        val logicalRunId = event.generationRunId ?: return null
        val occurredAtMs = event.occurredAtMs ?: return null
        if (
            event.sourceId != commandId ||
            event.streamId != episode.streamId ||
            event.replayGeneration != episode.replayGeneration ||
            event.scopeKind != episode.scopeKind ||
            event.scopeId != episode.scopeId ||
            event.conversationId != episode.conversationId ||
            event.conversationSourceRevision != episode.conversationRevision ||
            event.lineageId != episode.lineageId ||
            event.branchAnchorMessageId != episode.branchAnchorMessageId ||
            event.branchAnchorMessageRevision != episode.branchAnchorMessageRevision ||
            commandId != episode.finalCommandId ||
            sourceRevision != episode.finalCommandRevision ||
            logicalRunId != episode.generationRunId ||
            occurredAtMs != episode.finalizedAtMs ||
            occurredAtMs != episode.updatedAtMs
        ) return null

        val authority = when (LearningCompletionKind.parseOrNull(event.completionKind)) {
            LearningCompletionKind.GENERATION_FINAL_SAVED -> {
                val expectedStatus = expectedFinalSavedStatus(event.terminalState) ?: return null
                if (
                    episode.boundaryReason != LearningEpisodeBoundaryReason.FINAL_SAVED.name ||
                    episode.status != expectedStatus
                ) return null
                val messageId = event.messageId ?: return null
                val messageRevision = event.messageRevision ?: return null
                if (
                    messageId != episode.resultAssistantMessageId ||
                    messageRevision != episode.resultAssistantMessageRevision
                ) return null
                PolicyExposureOutcomeAuthority(
                    sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
                    sourceId = messageId,
                    sourceRevision = messageRevision,
                )
            }

            LearningCompletionKind.FAILED_OTHER -> {
                val expectedStatus = expectedFailedOtherStatus(event.terminalState) ?: return null
                if (
                    episode.boundaryReason != LearningEpisodeBoundaryReason.UNKNOWN.name ||
                    episode.status != expectedStatus ||
                    !hasNoResultAuthority(event, episode)
                ) return null
                PolicyExposureOutcomeAuthority(
                    sourceKind = LearningSourceKind.COMMAND,
                    sourceId = commandId,
                    sourceRevision = sourceRevision,
                )
            }

            LearningCompletionKind.CENSORED_CANCELLED -> {
                if (
                    event.terminalState != "CANCELLED" ||
                    episode.status != StoredLearningEpisodeStatus.CENSORED.name ||
                    episode.boundaryReason != LearningEpisodeBoundaryReason.STOPPED.name ||
                    !hasNoResultAuthority(event, episode)
                ) return null
                PolicyExposureOutcomeAuthority(
                    sourceKind = LearningSourceKind.COMMAND,
                    sourceId = commandId,
                    sourceRevision = sourceRevision,
                )
            }

            LearningCompletionKind.SUPERSEDED_REGENERATE -> {
                if (
                    event.terminalState != "CANCELLED" ||
                    episode.status != StoredLearningEpisodeStatus.SUPERSEDED.name ||
                    episode.boundaryReason !=
                    LearningEpisodeBoundaryReason.REGENERATED_BRANCH.name ||
                    !hasNoResultAuthority(event, episode)
                ) return null
                PolicyExposureOutcomeAuthority(
                    sourceKind = LearningSourceKind.COMMAND,
                    sourceId = commandId,
                    sourceRevision = sourceRevision,
                )
            }

            LearningCompletionKind.GENERATION_WAITING_APPROVAL,
            LearningCompletionKind.FAST_PATH_HANDLED,
            LearningCompletionKind.CONTROL_ONLY,
            LearningCompletionKind.FAILED_FINAL_SAVE,
            null,
            -> return null
        }

        return PolicyExposureOutcomeLinkPlan(
            streamId = episode.streamId,
            replayGeneration = episode.replayGeneration,
            episodeId = episode.id,
            logicalRunId = logicalRunId,
            scopeKind = episode.scopeKind,
            scopeId = episode.scopeId,
            authority = authority,
            linkObservedAtMs = maxOf(event.ingestedAtMs, episode.updatedAtMs),
        )
    }

    private fun expectedFinalSavedStatus(terminalState: String?): String? = when (terminalState) {
        "COMPLETED" -> StoredLearningEpisodeStatus.SUCCESS.name
        "FAILED" -> StoredLearningEpisodeStatus.PARTIAL.name
        else -> null
    }

    private fun expectedFailedOtherStatus(terminalState: String?): String? = when (terminalState) {
        "FAILED" -> StoredLearningEpisodeStatus.FAILURE.name
        "MANUAL_CONFIRMATION" -> StoredLearningEpisodeStatus.UNKNOWN.name
        else -> null
    }

    private fun hasNoResultAuthority(
        event: LearningInboxEventEntity,
        episode: LearningEpisodeEntity,
    ): Boolean = event.messageId == null &&
        event.messageRevision == null &&
        episode.resultAssistantMessageId == null &&
        episode.resultAssistantMessageRevision == null
}

/** Bounded replay result; it never contains Policy, Episode, command, or message IDs. */
internal data class PolicyExposureOutcomeLinkResult(
    val authorityEligible: Boolean,
    val scanned: Int,
    val applied: Int,
    val duplicates: Int,
    val conflicts: Int,
    val unavailable: Int,
) {
    init {
        require(scanned in 0..MAX_OUTCOME_LINK_ATTEMPTS_PER_PASS)
        require(listOf(applied, duplicates, conflicts, unavailable).all { it >= 0 })
        require(applied + duplicates + conflicts + unavailable == scanned)
    }

    companion object {
        val INELIGIBLE = PolicyExposureOutcomeLinkResult(
            authorityEligible = false,
            scanned = 0,
            applied = 0,
            duplicates = 0,
            conflicts = 0,
            unavailable = 0,
        )
    }
}

/**
 * Replays missing outcome links in the same LearningDatabase transaction as Episode projection.
 * The committed inbox row and current terminal Episode are re-read before the bounded attempt
 * query; every link then uses that attempt's current durable stateVersion as its CAS fence.
 */
internal class PolicyExposureOutcomeLinker(
    private val database: LearningDatabase,
    private val store: PolicyExposureMutationPort = RoomPolicyExposureStore(database),
    private val outcomeObserver: PolicyOutcomeLinkedObserver =
        NoOpPolicyOutcomeLinkedObserver,
) {
    suspend fun replayCommittedTerminal(
        event: LearningInboxEventEntity,
        episode: LearningEpisodeEntity,
    ): PolicyExposureOutcomeLinkResult = database.withTransaction {
        val committedEvent = database.inboxDao().find(event.streamId, event.eventId)
            ?.takeIf { it == event }
            ?: return@withTransaction PolicyExposureOutcomeLinkResult.INELIGIBLE
        val committedEpisode = database.episodeDao().findEpisode(episode.id)
            ?.takeIf { it == episode }
            ?: return@withTransaction PolicyExposureOutcomeLinkResult.INELIGIBLE
        val plan = PolicyExposureOutcomeLinkPolicy.plan(committedEvent, committedEpisode)
            ?: return@withTransaction PolicyExposureOutcomeLinkResult.INELIGIBLE
        val attempts = database.policyExposureDao().listUnlinkedTerminalAttempts(
            streamId = plan.streamId,
            replayGeneration = plan.replayGeneration,
            episodeId = plan.episodeId,
            logicalRunId = plan.logicalRunId,
            scopeKind = plan.scopeKind,
            scopeId = plan.scopeId,
            limit = MAX_OUTCOME_LINK_ATTEMPTS_PER_PASS,
        )
        var applied = 0
        var duplicates = 0
        var conflicts = 0
        var unavailable = 0
        attempts.forEach { attempt ->
            val linkAtMs = maxOf(plan.linkObservedAtMs, attempt.updatedAtMs)
            when (
                val result = store.linkOutcome(
                    reservationId = attempt.id,
                    expectedStateVersion = attempt.stateVersion,
                    authority = plan.authority,
                    frozenNowEpochMs = linkAtMs,
                )
            ) {
                is PolicyExposureStoreResult.Available -> {
                    val receipt = result.receipt
                    check(receipt.reservation.key.reservationId == attempt.id)
                    check(receipt.hasObserved(PolicyExposureState.OUTCOME_LINKED))
                    // This callback intentionally remains inside the link transaction. Any
                    // exception rolls back a newly applied link so catch-up can retry rather than
                    // committing an unobserved deterministic safety fact.
                    outcomeObserver.onLinked(
                        PolicyOutcomeSafetyTrigger(
                            reservationId = attempt.id,
                            expectedExposureStateVersion = receipt.stateVersion,
                            outcomeAuthority = plan.authority,
                            frozenNowMs = linkAtMs,
                        ),
                    )
                    when (result.disposition) {
                        PolicyExposureWriteDisposition.APPLIED -> applied += 1
                        PolicyExposureWriteDisposition.DUPLICATE -> duplicates += 1
                    }
                }
                is PolicyExposureStoreResult.Conflict -> conflicts += 1
                is PolicyExposureStoreResult.Unavailable -> unavailable += 1
            }
        }
        // This also covers a pre-treatment NON_EXPOSURE assignment, for which no Policy exposure
        // attempt exists. Projection failures leave the append-only assignment unclosed so a
        // later terminal catch-up or maintenance pass yields ABSTAIN rather than fabricated data.
        ObservedUtilityTerminalAuthorityProjector(database).project(plan, committedEpisode)
        PolicyExposureOutcomeLinkResult(
            authorityEligible = true,
            scanned = attempts.size,
            applied = applied,
            duplicates = duplicates,
            conflicts = conflicts,
            unavailable = unavailable,
        )
    }
}

private val COMMAND_TERMINAL_STATES = setOf(
    "COMPLETED",
    "FAILED",
    "CANCELLED",
    "MANUAL_CONFIRMATION",
)

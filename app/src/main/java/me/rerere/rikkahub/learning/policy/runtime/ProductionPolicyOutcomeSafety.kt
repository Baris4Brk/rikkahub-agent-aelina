package me.rerere.rikkahub.learning.policy.runtime

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.curation.PolicyCuratorQueueDisposition
import me.rerere.rikkahub.learning.curation.PolicyCuratorV0
import me.rerere.rikkahub.learning.curation.PolicyDistillationRequestQueue
import me.rerere.rikkahub.learning.curation.PolicyHarmReviewQueue
import me.rerere.rikkahub.learning.exposure.PolicyExposureOutcomeAuthority
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeTerminalOutcome
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernor
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernorResult
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyExposureAttributionState
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.RoomPolicyLifecycleMutationStore
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus

private const val POLICY_TERMINAL_SAFETY_CONTRACT_VERSION = 1
private const val MAX_POLICY_OUTCOME_SAFETY_HEADS = 20
private const val DETERMINISTIC_SAFETY_REVIEW_REASON =
    "P2_VERSIONED_FAIL_CLOSED_SAFETY_RULE"
// VersionedPolicySafetyRules.SINGLE_POLICY_COMPLETED_RESPONSE_AUTHORITATIVE_FAILURE is the sole
// deterministic production rule admitted by this composition.

/** Creates a database-scoped observer; the derived Room handle never escapes through DI. */
fun interface PolicyOutcomeLinkedObserverFactory {
    fun create(database: LearningDatabase): PolicyOutcomeLinkedObserver
}

object NoOpPolicyOutcomeLinkedObserverFactory : PolicyOutcomeLinkedObserverFactory {
    override fun create(database: LearningDatabase): PolicyOutcomeLinkedObserver =
        NoOpPolicyOutcomeLinkedObserver
}

/**
 * Production composition for the terminal caller. The lifecycle-backed review admission is valid
 * only for the deterministic branch: the immediately following lifecycle revision is itself the
 * durable pending-review queue item. Advisory callers are deliberately not bound until a separate
 * durable queue exists.
 */
object ProductionPolicyOutcomeLinkedObserverFactory : PolicyOutcomeLinkedObserverFactory {
    override fun create(database: LearningDatabase): PolicyOutcomeLinkedObserver {
        val curator = PolicyCuratorV0(
            distillationQueue = PolicyDistillationRequestQueue {
                error("Safety observation must not enqueue Policy distillation")
            },
            harmReviewQueue = LifecycleBackedDeterministicHarmReviewAdmission,
        )
        val governor = PolicySafetyGovernor(
            curator = curator,
            mutationStore = RoomPolicyLifecycleMutationStore(database),
        )
        val runtime = PolicyOutcomeSafetyRuntime(
            source = RoomPolicyOutcomeSafetyMaterialSource(database),
            governor = governor,
        )
        return PolicyOutcomeLinkedObserver { trigger ->
            when (val result = runtime.onOutcomeLinked(trigger)) {
                PolicyOutcomeSafetyRuntimeResult.Conflict,
                PolicyOutcomeSafetyRuntimeResult.Unavailable,
                -> error("Exact linked Policy safety material is unavailable")

                is PolicyOutcomeSafetyRuntimeResult.Evaluated -> {
                    if (result.deterministicHits > 0 && result.governorResults.any { governorResult ->
                            governorResult !is PolicySafetyGovernorResult.SuspendedPendingReview &&
                                governorResult !is PolicySafetyGovernorResult.SuspensionDuplicate
                        }
                    ) {
                        error("Deterministic Policy safety hit was not durably isolated")
                    }
                }
            }
        }
    }
}

/**
 * The canonical lifecycle revision is the durable review queue item; this admission class has no
 * standalone write and is intentionally restricted to the one deterministic safety reason.
 */
internal object LifecycleBackedDeterministicHarmReviewAdmission : PolicyHarmReviewQueue {
    override suspend fun enqueueValidated(
        candidate: me.rerere.rikkahub.learning.curation.PolicyDeltaCandidate,
    ): PolicyCuratorQueueDisposition {
        check(candidate.reasonCode == DETERMINISTIC_SAFETY_REVIEW_REASON &&
            candidate.targetPolicyId != null && candidate.expectedRevision != null &&
            candidate.baseArtifactHash != null && candidate.evidenceIds.size == 1
        ) {
            "Advisory harm review has no durable production queue"
        }
        return PolicyCuratorQueueDisposition.QUEUED
    }
}

/** Rehydrates every fact from the same open LearningDatabase transaction as the outcome link. */
internal class RoomPolicyOutcomeSafetyMaterialSource(
    private val database: LearningDatabase,
) : PolicyOutcomeSafetyMaterialSource {
    override suspend fun loadExact(
        trigger: PolicyOutcomeSafetyTrigger,
    ): PolicyOutcomeSafetyMaterialResult = try {
        val exposure = database.policyExposureDao().findExposure(trigger.reservationId)
            ?: return PolicyOutcomeSafetyMaterialResult.Conflict
        if (exposure.stateVersion != trigger.expectedExposureStateVersion ||
            exposure.outcomeLinkedAtMs == null ||
            exposure.attributionState != LearningPolicyExposureAttributionState.KNOWN.name ||
            exposure.outcomeSourceType != trigger.outcomeAuthority.sourceKind.name ||
            exposure.outcomeSourceId != trigger.outcomeAuthority.sourceId ||
            exposure.outcomeSourceRevision != trigger.outcomeAuthority.sourceRevision
        ) return PolicyOutcomeSafetyMaterialResult.Conflict

        val scope = LearningScope.parseOrNull(exposure.scopeKind, exposure.scopeId)
            ?: return PolicyOutcomeSafetyMaterialResult.Conflict
        val items = database.policyExposureDao().listItems(
            exposureId = exposure.id,
            limit = MAX_POLICY_OUTCOME_SAFETY_HEADS + 1,
        )
        if (items.isEmpty() || items.size > MAX_POLICY_OUTCOME_SAFETY_HEADS ||
            items.any { item ->
                item.exposureId != exposure.id || item.retrievedAtMs != exposure.retrievedAtMs ||
                    item.compiledAtMs != exposure.compiledAtMs ||
                    item.injectedAtMs != exposure.injectedAtMs || item.dropReason != null
            }
        ) return PolicyOutcomeSafetyMaterialResult.Conflict
        val bundle = PolicyExposureBundle.create(
            items.map { item ->
                PolicyExposurePolicyRef(
                    policyId = item.policyId,
                    policyRevision = item.policyRevision,
                    artifactSha256 = item.artifactSha256,
                    scope = scope,
                    rank = item.rank,
                    estimatedTokens = item.estimatedTokens,
                    applicabilityCohortDigest = item.applicabilityCohortDigest,
                )
            },
        )
        if (bundle.policySetDigest != exposure.policySetDigest) {
            return PolicyOutcomeSafetyMaterialResult.Conflict
        }
        val reservation = PolicyExposureReservation(
            key = PolicyExposureReservationKey(
                streamId = kotlin.uuid.Uuid.parse(exposure.streamId),
                episodeId = EpisodeId.parseOrNull(exposure.episodeId)
                    ?: return PolicyOutcomeSafetyMaterialResult.Conflict,
                logicalRunId = kotlin.uuid.Uuid.parse(exposure.logicalRunId),
                attemptOrdinal = exposure.attemptOrdinal,
                policySetDigest = exposure.policySetDigest,
            ),
            bundle = bundle,
        )
        if (reservation.key.reservationId != exposure.id) {
            return PolicyOutcomeSafetyMaterialResult.Conflict
        }
        val states = buildSet {
            add(PolicyExposureState.RETRIEVED)
            if (exposure.compiledAtMs != null) add(PolicyExposureState.COMPILED)
            if (exposure.injectedAtMs != null) add(PolicyExposureState.INJECTED)
            if (exposure.hostDispatchedAtMs != null) add(PolicyExposureState.HOST_DISPATCHED)
            if (exposure.firstProgressAtMs != null) add(PolicyExposureState.FIRST_PROGRESS)
            if (exposure.responseFinishedAtMs != null) add(PolicyExposureState.RESPONSE_FINISHED)
            if (exposure.outcomeLinkedAtMs != null) add(PolicyExposureState.OUTCOME_LINKED)
        }
        val receipt = PolicyExposureReceipt.restore(
            reservation = reservation,
            observedStates = states,
            stateVersion = exposure.stateVersion,
            terminalOutcome = exposure.terminalOutcome?.let { stored ->
                ProviderAttemptTerminalOutcome.entries.singleOrNull { it.name == stored }
                    ?: return PolicyOutcomeSafetyMaterialResult.Conflict
            } ?: return PolicyOutcomeSafetyMaterialResult.Conflict,
        )
        if (receipt.stateVersion != exposure.stateVersion ||
            !receipt.hasObserved(PolicyExposureState.OUTCOME_LINKED) ||
            receipt.reservation.key.episodeId.value != exposure.episodeId ||
            receipt.reservation.key.logicalRunId.toString() != exposure.logicalRunId ||
            receipt.reservation.key.streamId.toString() != exposure.streamId
        ) return PolicyOutcomeSafetyMaterialResult.Conflict

        val episode = database.episodeDao().findEpisode(exposure.episodeId)
            ?: return PolicyOutcomeSafetyMaterialResult.Conflict
        if (episode.streamId != exposure.streamId ||
            episode.replayGeneration != exposure.replayGeneration ||
            episode.scopeKind != exposure.scopeKind || episode.scopeId != exposure.scopeId ||
            episode.generationRunId != exposure.logicalRunId ||
            episode.finalizedAtMs == null ||
            !episode.matchesExactOutcomeAuthority(trigger.outcomeAuthority)
        ) return PolicyOutcomeSafetyMaterialResult.Conflict
        val authoritativeOutcome = episode.status.toAuthoritativeSafetyOutcomeOrNull()
            ?: return PolicyOutcomeSafetyMaterialResult.Conflict

        val refs = receipt.reservation.bundle.policies
        if (refs.isEmpty() || refs.size > MAX_POLICY_OUTCOME_SAFETY_HEADS) {
            return PolicyOutcomeSafetyMaterialResult.Conflict
        }
        val heads = refs.map { ref ->
            val policy = database.policyDao().findPolicy(ref.policyId)
                ?: return PolicyOutcomeSafetyMaterialResult.Conflict
            val status = runCatching { LearningPolicyStatus.valueOf(policy.status) }.getOrNull()
                ?: return PolicyOutcomeSafetyMaterialResult.Conflict
            if (policy.scopeKind != scope.kind.name || policy.scopeId != scope.storageId ||
                policy.contentRevision != ref.policyRevision ||
                policy.artifactSha256 != ref.artifactSha256 || ref.scope != scope ||
                PolicyApplicabilityWire.decodeToolSchemasOrNull(
                    policy.applicableToolSchemasWire,
                ) == null
            ) return PolicyOutcomeSafetyMaterialResult.Conflict
            ExactSafetyPolicyHead(
                fence = PolicyMutationFence(
                    policyId = policy.id,
                    scope = scope,
                    expectedRevision = policy.stateVersion,
                    expectedContentRevision = policy.contentRevision,
                    expectedArtifactHash = policy.artifactSha256,
                ),
                status = status,
            )
        }
        PolicyOutcomeSafetyMaterialResult.Ready(
            DurablePolicyOutcomeSafetyMaterial(
                receipt = receipt,
                terminal = AuthoritativeTerminalSafetyFact(
                    authority = trigger.outcomeAuthority,
                    outcome = authoritativeOutcome,
                    terminalContractVersion = POLICY_TERMINAL_SAFETY_CONTRACT_VERSION,
                ),
                policyHeads = heads,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        PolicyOutcomeSafetyMaterialResult.Conflict
    } catch (_: Throwable) {
        PolicyOutcomeSafetyMaterialResult.Unavailable
    }
}

private fun me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
    .matchesExactOutcomeAuthority(authority: PolicyExposureOutcomeAuthority): Boolean =
    when (authority.sourceKind) {
        LearningSourceKind.CONVERSATION_MESSAGE ->
            resultAssistantMessageId == authority.sourceId &&
                resultAssistantMessageRevision == authority.sourceRevision
        LearningSourceKind.COMMAND -> {
            val expectedId = finalCommandId ?: rootCommandId
            val expectedRevision = finalCommandRevision ?: rootCommandRevision
            expectedId == authority.sourceId && expectedRevision == authority.sourceRevision
        }
        else -> false
    }

private fun String.toAuthoritativeSafetyOutcomeOrNull(): PolicyAuthoritativeTerminalOutcome? =
    when (this) {
        StoredLearningEpisodeStatus.SUCCESS.name -> PolicyAuthoritativeTerminalOutcome.SUCCESS
        StoredLearningEpisodeStatus.PARTIAL.name,
        StoredLearningEpisodeStatus.FAILURE.name,
        -> PolicyAuthoritativeTerminalOutcome.FAILURE
        StoredLearningEpisodeStatus.CENSORED.name,
        StoredLearningEpisodeStatus.SUPERSEDED.name,
        -> PolicyAuthoritativeTerminalOutcome.CENSORED
        StoredLearningEpisodeStatus.UNKNOWN.name -> PolicyAuthoritativeTerminalOutcome.UNKNOWN
        StoredLearningEpisodeStatus.OPEN.name,
        StoredLearningEpisodeStatus.ABORTED.name,
        StoredLearningEpisodeStatus.TIMEOUT.name,
        -> null
        else -> null
    }

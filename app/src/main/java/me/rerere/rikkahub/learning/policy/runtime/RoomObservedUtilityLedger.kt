package me.rerere.rikkahub.learning.policy.runtime

import androidx.room.withTransaction
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.ObservedUtilityArm
import me.rerere.rikkahub.learning.policy.ObservedUtilityAttributionUnit
import me.rerere.rikkahub.learning.policy.ObservedUtilityOutcome
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeTerminalOutcome
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningObservedUtilityAssignmentEntity
import me.rerere.rikkahub.learning.storage.LearningObservedUtilityDao
import me.rerere.rikkahub.learning.storage.LearningObservedUtilityEvaluationReceiptEntity
import me.rerere.rikkahub.learning.storage.LearningObservedUtilityOutcomeEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyExposureEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyExposureItemEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.storage.toAuthoritativeOutcome

private const val MAX_UTILITY_EXPOSURE_ITEMS = 20
private val ROOM_UTILITY_SHA256 = Regex("[0-9a-f]{64}")

/**
 * Production Room boundary for pre-treatment assignments, authority outcome closure, bounded
 * durable observation reads and append-only evaluation receipts.
 */
class RoomObservedUtilityLedger(
    private val database: LearningDatabase,
    private val observedUtilityDao: LearningObservedUtilityDao,
) : ObservedUtilityPreTreatmentAssignmentPort,
    ObservedUtilityOutcomeCommitPort,
    DurableObservedUtilitySource,
    ObservedUtilityEvaluationStore,
    ObservedUtilityMaintenanceCandidateSource {

    override suspend fun reserve(
        assignment: ObservedUtilityPreTreatmentAssignment,
    ): ObservedUtilityLedgerWriteResult = safeLedgerWrite {
        database.withTransaction {
            val dao = observedUtilityDao
            dao.findAssignment(assignment.assignmentId)?.let { current ->
                return@withTransaction if (current ==
                    LearningObservedUtilityAssignmentEntity.from(
                        assignment,
                        current.expectedExposureStateVersion,
                        current.expectedExposureReceiptDigest,
                    )
                ) {
                    ObservedUtilityLedgerWriteResult.Duplicate(assignment.assignmentId)
                } else {
                    ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.ASSIGNMENT_CONFLICT,
                    )
                }
            }
            val episode = database.episodeDao().findEpisode(assignment.episodeId.value)
                ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.EPISODE_NOT_FOUND,
                )
            if (episode.streamId != assignment.streamId.toString() ||
                episode.replayGeneration != assignment.replayGeneration ||
                episode.scopeKind != assignment.fence.scope.kind.name ||
                episode.scopeId != assignment.fence.scope.storageId ||
                episode.status != StoredLearningEpisodeStatus.OPEN.name ||
                episode.generationRunId != assignment.logicalRunId.toString() ||
                episode.taskSignature != assignment.cohort.taskSignature
            ) {
                return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.EPISODE_IDENTITY_MISMATCH,
                )
            }
            val policy = dao.findExactPolicyFence(
                policyId = assignment.fence.policyId,
                scopeKind = assignment.fence.scope.kind.name,
                scopeId = assignment.fence.scope.storageId,
                stateVersion = assignment.fence.expectedRevision,
                contentRevision = assignment.fence.expectedContentRevision,
                artifactSha256 = assignment.fence.expectedArtifactHash,
            ) ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                ObservedUtilityLedgerConflict.POLICY_FENCE_CHANGED,
            )
            if (!policy.isOperationalUtilityHead()) {
                return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.POLICY_NOT_ACTIVE,
                )
            }
            val exposureSnapshot = assignment.expectedExposureId?.let { exposureId ->
                val entity = database.policyExposureDao().findExposure(exposureId)
                    ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EXPOSURE_MISSING,
                    )
                val receipt = restoreExposureReceipt(entity)
                    ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EXPOSURE_IDENTITY_MISMATCH,
                    )
                if (!entity.matchesPreTreatmentAssignment(assignment, receipt)) {
                    return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EXPOSURE_IDENTITY_MISMATCH,
                    )
                }
                receipt.stateVersion to observedUtilityExposureReceiptDigest(receipt)
            }
            val entity = LearningObservedUtilityAssignmentEntity.from(
                value = assignment,
                expectedExposureStateVersion = exposureSnapshot?.first,
                expectedExposureReceiptDigest = exposureSnapshot?.second,
            )
            dao.insertAssignment(entity)
            check(dao.findAssignment(entity.id) == entity)
            ObservedUtilityLedgerWriteResult.Applied(entity.id)
        }
    }

    override suspend fun commit(
        outcome: ObservedUtilityOutcomeCommit,
    ): ObservedUtilityLedgerWriteResult = safeLedgerWrite {
        database.withTransaction {
            val dao = observedUtilityDao
            val assignment = dao.findAssignment(outcome.assignmentId)
                ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.ASSIGNMENT_IDENTITY_MISMATCH,
                )
            if (outcome.authority == null &&
                outcome.windowClosedAtMs < assignment.sourceWindowEndMs
            ) {
                return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.OUTCOME_WINDOW_OPEN,
                )
            }
            assignment.expectedExposureId?.let { exposureId ->
                val entity = database.policyExposureDao().findExposure(exposureId)
                    ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EXPOSURE_MISSING,
                    )
                val receipt = restoreExposureReceipt(entity)
                    ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EXPOSURE_IDENTITY_MISMATCH,
                    )
                if (!entity.matchesFrozenAssignment(assignment, receipt)) {
                    return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EXPOSURE_IDENTITY_MISMATCH,
                    )
                }
                if (!receipt.hasObserved(PolicyExposureState.INJECTED) ||
                    !receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED)
                ) return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.EXPOSURE_IDENTITY_MISMATCH,
                )
                if (outcome.authority != null) {
                    if (!receipt.canAttributeOutcome ||
                        entity.outcomeSourceType != outcome.authority.sourceKind.name ||
                        entity.outcomeSourceId != outcome.authority.sourceId ||
                        entity.outcomeSourceRevision != outcome.authority.sourceRevision
                    ) return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.OUTCOME_AUTHORITY_MISMATCH,
                    )
                    val episode = database.episodeDao().findEpisode(entity.episodeId)
                        ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                            ObservedUtilityLedgerConflict.EPISODE_NOT_FOUND,
                        )
                    if (!episode.matchesObservedUtilityAssignment(assignment) ||
                        !episode.matchesOutcomeAuthority(outcome.authority) ||
                        episode.toObservedUtilityOutcome() != outcome.outcome
                    ) return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.OUTCOME_AUTHORITY_MISMATCH,
                    )
                } else if (outcome.outcome != ObservedUtilityOutcome.UNKNOWN) {
                    return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.OUTCOME_AUTHORITY_MISMATCH,
                    )
                }
                val receiptDigest = observedUtilityExposureReceiptDigest(receipt)
                if (outcome.exposureStateVersion != receipt.stateVersion ||
                    outcome.exposureReceiptDigest != receiptDigest
                ) {
                    return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EXPOSURE_IDENTITY_MISMATCH,
                    )
                }
                Unit
            }
            if (assignment.expectedExposureId == null &&
                (outcome.exposureStateVersion != null || outcome.exposureReceiptDigest != null)
            ) {
                return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.EXPOSURE_IDENTITY_MISMATCH,
                )
            }
            if (assignment.arm == ObservedUtilityArm.NON_EXPOSURE.name &&
                outcome.outcome in setOf(ObservedUtilityOutcome.SUCCESS, ObservedUtilityOutcome.FAILURE) &&
                (!outcome.baselineHostDispatched || !outcome.baselineProgressOrResponse)
            ) {
                return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.OUTCOME_AUTHORITY_MISMATCH,
                )
            }
            if (assignment.arm == ObservedUtilityArm.NON_EXPOSURE.name &&
                outcome.authority != null
            ) {
                val episode = database.episodeDao().findEpisode(assignment.episodeId)
                    ?: return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.EPISODE_NOT_FOUND,
                    )
                if (!episode.matchesObservedUtilityAssignment(assignment) ||
                    !episode.matchesOutcomeAuthority(outcome.authority) ||
                    episode.toObservedUtilityOutcome() != outcome.outcome
                ) return@withTransaction ObservedUtilityLedgerWriteResult.Conflict(
                    ObservedUtilityLedgerConflict.OUTCOME_AUTHORITY_MISMATCH,
                )
            }
            val entity = LearningObservedUtilityOutcomeEntity.from(outcome)
            dao.findOutcome(outcome.assignmentId)?.let { current ->
                return@withTransaction if (current == entity) {
                    ObservedUtilityLedgerWriteResult.Duplicate(entity.outcomeReceiptDigest)
                } else {
                    ObservedUtilityLedgerWriteResult.Conflict(
                        ObservedUtilityLedgerConflict.OUTCOME_CONFLICT,
                    )
                }
            }
            dao.insertOutcome(entity)
            check(dao.findOutcome(entity.assignmentId) == entity)
            ObservedUtilityLedgerWriteResult.Applied(entity.outcomeReceiptDigest)
        }
    }

    override suspend fun loadExact(
        request: ObservedUtilityRuntimeRequest,
    ): DurableObservedUtilityBatchResult = safeRead {
        val dao = observedUtilityDao
        val entities = dao.listExactAssignments(
            scopeKind = request.fence.scope.kind.name,
            scopeId = request.fence.scope.storageId,
            policyId = request.fence.policyId,
            policyStateVersion = request.fence.expectedRevision,
            policyContentRevision = request.fence.expectedContentRevision,
            policyArtifactSha256 = request.fence.expectedArtifactHash,
            policySetDigest = request.design.targetPolicySetDigest,
            designDigest = observedUtilityDesignDigest(request.design),
            cohortDigest = request.expectedCohortDigest,
            sourceWindowStartMs = request.sourceWindowStartMs,
            sourceWindowEndMs = request.sourceWindowEndMs,
            limit = request.limit + 1,
        )
        val assignmentPageComplete = entities.size <= request.limit
        val bounded = entities.take(request.limit)
        val outcomes = if (bounded.isEmpty()) {
            emptyMap()
        } else {
            dao.listOutcomes(bounded.map { it.id }).associateBy { it.assignmentId }
        }
        val complete = assignmentPageComplete && outcomes.size == bounded.size
        val rows = mutableListOf<DurableObservedUtilityRow>()
        for (assignment in bounded) {
            val outcome = outcomes[assignment.id] ?: continue
            val domainAssignment = assignment.toDomain()
            val exposureReceipt = assignment.expectedExposureId?.let { exposureId ->
                val exposure = database.policyExposureDao().findExposure(exposureId)
                    ?: return@safeRead DurableObservedUtilityBatchResult.Unavailable
                val receipt = restoreExposureReceipt(exposure)
                    ?: return@safeRead DurableObservedUtilityBatchResult.Unavailable
                if (!exposure.matchesFrozenAssignment(assignment, receipt) ||
                    outcome.exposureStateVersion != receipt.stateVersion ||
                    outcome.exposureReceiptDigest != observedUtilityExposureReceiptDigest(receipt)
                ) return@safeRead DurableObservedUtilityBatchResult.Unavailable
                receipt
            }
            if (assignment.expectedExposureId == null &&
                (outcome.exposureStateVersion != null || outcome.exposureReceiptDigest != null)
            ) return@safeRead DurableObservedUtilityBatchResult.Unavailable
            rows += DurableObservedUtilityRow(
                durableObservationIdentityDigest = outcome.outcomeReceiptDigest,
                arm = domainAssignment.arm,
                authoritativeOutcome = outcome.toAuthoritativeOutcome(),
                cohort = domainAssignment.cohort,
                policySetDigest = domainAssignment.design.targetPolicySetDigest,
                matchKeyDigest = domainAssignment.matchKeyDigest,
                propensity = domainAssignment.propensity,
                exposureReceipt = exposureReceipt,
                baselineHostDispatched = outcome.baselineHostDispatched,
                baselineProgressOrResponse = outcome.baselineProgressOrResponse,
                authoritativeOutcomeCommitted = outcome.authorityEvidenceDigest != null,
            )
        }
        DurableObservedUtilityBatchResult.Ready(
            DurableObservedUtilityBatch(
                rows = rows,
                sourceWatermarkDigest = LearningCanonicalId.digest(
                    domainVersion = "observed-utility-room-source-watermark-v1",
                    fields = listOf(
                        request.fence.scope.kind.name,
                        request.fence.scope.storageId,
                        request.fence.policyId,
                        request.fence.expectedRevision.toString(),
                        request.fence.expectedContentRevision.toString(),
                        request.fence.expectedArtifactHash,
                        observedUtilityDesignDigest(request.design),
                        request.expectedCohortDigest,
                        request.sourceWindowStartMs.toString(),
                        request.sourceWindowEndMs.toString(),
                        complete.toString(),
                        bounded.size.toString(),
                    ) + bounded.flatMap { entity ->
                        listOf(
                            entity.id,
                            outcomes[entity.id]?.outcomeReceiptDigest.orEmpty(),
                            outcomes[entity.id]?.recordedAtMs?.toString().orEmpty(),
                        )
                    },
                ),
                complete = complete,
            ),
        )
    }

    override suspend fun revalidatePolicyFence(fence: PolicyMutationFence): Boolean = try {
        observedUtilityDao.findExactPolicyFence(
            policyId = fence.policyId,
            scopeKind = fence.scope.kind.name,
            scopeId = fence.scope.storageId,
            stateVersion = fence.expectedRevision,
            contentRevision = fence.expectedContentRevision,
            artifactSha256 = fence.expectedArtifactHash,
        )?.isOperationalUtilityHead() == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    override suspend fun persistExact(
        receipt: ObservedUtilityEvaluationReceipt,
    ): ObservedUtilityPersistenceDisposition = safePersist {
        database.withTransaction {
            val dao = observedUtilityDao
            val entity = LearningObservedUtilityEvaluationReceiptEntity.from(receipt)
            dao.findEvaluationReceipt(receipt.receiptDigest)?.let { current ->
                return@withTransaction if (current == entity) {
                    ObservedUtilityPersistenceDisposition.DUPLICATE
                } else {
                    ObservedUtilityPersistenceDisposition.CONFLICT
                }
            }
            val sourceAssignment = dao.listExactAssignments(
                scopeKind = receipt.fence.scope.kind.name,
                scopeId = receipt.fence.scope.storageId,
                policyId = receipt.fence.policyId,
                policyStateVersion = receipt.fence.expectedRevision,
                policyContentRevision = receipt.fence.expectedContentRevision,
                policyArtifactSha256 = receipt.fence.expectedArtifactHash,
                policySetDigest = receipt.targetPolicySetDigest,
                designDigest = receipt.designDigest,
                cohortDigest = receipt.cohortDigest,
                sourceWindowStartMs = receipt.sourceWindowStartMs,
                sourceWindowEndMs = receipt.sourceWindowEndMs,
                limit = 1,
            ).singleOrNull() ?: return@withTransaction ObservedUtilityPersistenceDisposition.CONFLICT
            if (sourceAssignment.designDigest != receipt.designDigest ||
                sourceAssignment.cohortDigest != receipt.cohortDigest
            ) {
                return@withTransaction ObservedUtilityPersistenceDisposition.CONFLICT
            }
            val policy = dao.findExactPolicyFence(
                policyId = receipt.fence.policyId,
                scopeKind = receipt.fence.scope.kind.name,
                scopeId = receipt.fence.scope.storageId,
                stateVersion = receipt.fence.expectedRevision,
                contentRevision = receipt.fence.expectedContentRevision,
                artifactSha256 = receipt.fence.expectedArtifactHash,
            )
            if (receipt.status == ObservedUtilityRuntimeStatus.ESTIMATED &&
                policy?.isOperationalUtilityHead() != true
            ) return@withTransaction ObservedUtilityPersistenceDisposition.CONFLICT
            dao.insertEvaluationReceipt(entity)
            if (receipt.scalarProjectionPolicyId != null) {
                val delta = requireNotNull(receipt.observedUtilityDelta)
                val uncertainty = requireNotNull(receipt.utilityUncertainty)
                if (dao.updateObservedUtilityProjectionIfExact(
                        policyId = receipt.scalarProjectionPolicyId,
                        scopeKind = receipt.fence.scope.kind.name,
                        scopeId = receipt.fence.scope.storageId,
                        stateVersion = receipt.fence.expectedRevision,
                        contentRevision = receipt.fence.expectedContentRevision,
                        artifactSha256 = receipt.fence.expectedArtifactHash,
                        observedUtilityDelta = delta,
                        utilityUncertainty = uncertainty,
                        evaluatedAtMs = receipt.evaluatedAtMs,
                    ) != 1
                ) {
                    rollbackPersist()
                }
            }
            check(dao.findEvaluationReceipt(entity.receiptDigest) == entity)
            ObservedUtilityPersistenceDisposition.APPLIED
        }
    }

    override suspend fun listDue(
        after: ObservedUtilityMaintenanceCursor,
        frozenNowMs: Long,
        limit: Int,
    ): ObservedUtilityMaintenancePage {
        require(frozenNowMs >= 0L)
        require(limit in 1..MAX_OBSERVED_UTILITY_MAINTENANCE_DESIGNS)
        val entities = observedUtilityDao.listDueDesignRepresentatives(
            frozenNowMs = frozenNowMs,
            afterWindowEndMs = after.sourceWindowEndMs,
            afterDesignDigest = after.designDigest,
            afterCohortDigest = after.cohortDigest,
            afterPolicyId = after.policyId,
            afterAssignmentId = after.representativeAssignmentId,
            limit = limit + 1,
        )
        val hasMore = entities.size > limit
        val candidates = entities.take(limit).map { entity ->
            val assignment = entity.toDomain()
            ObservedUtilityMaintenanceCandidate(
                request = ObservedUtilityRuntimeRequest(
                    fence = assignment.fence,
                    design = assignment.design,
                    expectedCohortDigest = assignment.cohortDigest,
                    sourceWindowStartMs = assignment.sourceWindowStartMs,
                    sourceWindowEndMs = assignment.sourceWindowEndMs,
                    limit = MAX_DURABLE_UTILITY_ROWS,
                ),
                representativeAssignmentId = entity.id,
            )
        }
        return ObservedUtilityMaintenancePage(candidates, hasMore)
    }

    private suspend fun restoreExposureReceipt(
        entity: LearningPolicyExposureEntity,
    ): PolicyExposureReceipt? = runCatching {
        val items = database.policyExposureDao().listItems(
            entity.id,
            MAX_UTILITY_EXPOSURE_ITEMS + 1,
        )
        require(items.isNotEmpty() && items.size <= MAX_UTILITY_EXPOSURE_ITEMS)
        val scope = requireNotNull(LearningScope.parseOrNull(entity.scopeKind, entity.scopeId))
        val bundle = PolicyExposureBundle.create(items.map { it.toExposurePolicyRef(scope) })
        require(bundle.policySetDigest == entity.policySetDigest)
        val reservation = PolicyExposureReservation(
            key = PolicyExposureReservationKey(
                streamId = Uuid.parse(entity.streamId),
                episodeId = requireNotNull(EpisodeId.parseOrNull(entity.episodeId)),
                logicalRunId = Uuid.parse(entity.logicalRunId),
                attemptOrdinal = entity.attemptOrdinal,
                policySetDigest = entity.policySetDigest,
            ),
            bundle = bundle,
        )
        require(reservation.key.reservationId == entity.id)
        val states = buildSet {
            add(PolicyExposureState.RETRIEVED)
            if (entity.compiledAtMs != null) add(PolicyExposureState.COMPILED)
            if (entity.injectedAtMs != null) add(PolicyExposureState.INJECTED)
            if (entity.hostDispatchedAtMs != null) add(PolicyExposureState.HOST_DISPATCHED)
            if (entity.firstProgressAtMs != null) add(PolicyExposureState.FIRST_PROGRESS)
            if (entity.responseFinishedAtMs != null) add(PolicyExposureState.RESPONSE_FINISHED)
            if (entity.outcomeLinkedAtMs != null) add(PolicyExposureState.OUTCOME_LINKED)
        }
        PolicyExposureReceipt.restore(
            reservation = reservation,
            observedStates = states,
            stateVersion = entity.stateVersion,
            terminalOutcome = entity.terminalOutcome?.let(ProviderAttemptTerminalOutcome::valueOf),
        ).also { require(it.latestState.name == entity.furthestState) }
    }.getOrNull()

    private suspend fun safeRead(
        block: suspend () -> DurableObservedUtilityBatchResult,
    ): DurableObservedUtilityBatchResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DurableObservedUtilityBatchResult.Unavailable
    }

    private suspend fun safeLedgerWrite(
        block: suspend () -> ObservedUtilityLedgerWriteResult,
    ): ObservedUtilityLedgerWriteResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: UtilityLedgerPersistRollback) {
        ObservedUtilityLedgerWriteResult.Unavailable
    } catch (_: IllegalStateException) {
        ObservedUtilityLedgerWriteResult.Unavailable
    } catch (_: Exception) {
        ObservedUtilityLedgerWriteResult.Unavailable
    }

    private suspend fun safePersist(
        block: suspend () -> ObservedUtilityPersistenceDisposition,
    ): ObservedUtilityPersistenceDisposition = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: UtilityLedgerPersistRollback) {
        ObservedUtilityPersistenceDisposition.CONFLICT
    } catch (_: IllegalStateException) {
        ObservedUtilityPersistenceDisposition.UNAVAILABLE
    } catch (_: Exception) {
        ObservedUtilityPersistenceDisposition.UNAVAILABLE
    }
}

private fun LearningPolicyEntity.isOperationalUtilityHead(): Boolean =
    status == StoredLearningPolicyStatus.ACTIVE.name && sourceValid && schemaValid &&
        staleReason == null

private fun LearningPolicyExposureItemEntity.toExposurePolicyRef(
    scope: LearningScope,
) = PolicyExposurePolicyRef(
    policyId = policyId,
    policyRevision = policyRevision,
    artifactSha256 = artifactSha256,
    scope = scope,
    rank = rank,
    estimatedTokens = estimatedTokens,
    applicabilityCohortDigest = applicabilityCohortDigest,
)

private fun LearningPolicyExposureEntity.matchesPreTreatmentAssignment(
    assignment: ObservedUtilityPreTreatmentAssignment,
    receipt: PolicyExposureReceipt,
): Boolean = id == assignment.expectedExposureId &&
    streamId == assignment.streamId.toString() &&
    replayGeneration == assignment.replayGeneration &&
    episodeId == assignment.episodeId.value &&
    logicalRunId == assignment.logicalRunId.toString() &&
    attemptOrdinal == assignment.attemptOrdinal &&
    scopeKind == assignment.fence.scope.kind.name &&
    scopeId == assignment.fence.scope.storageId &&
    taskSignature == assignment.cohort.taskSignature &&
    policySetDigest == assignment.design.targetPolicySetDigest &&
    modelIdentity == assignment.cohort.modelIdentity &&
    providerIdentity == assignment.cohort.providerIdentity &&
    providerGeneration == assignment.cohort.providerConfigurationGeneration &&
    toolsetFingerprint == assignment.cohort.toolsetFingerprint &&
    retrievedAtMs != null && retrievedAtMs <= assignment.assignedAtMs &&
    receipt.hasObserved(PolicyExposureState.RETRIEVED) &&
    !receipt.hasObserved(PolicyExposureState.COMPILED) &&
    !receipt.hasObserved(PolicyExposureState.INJECTED) &&
    !receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED) &&
    receipt.terminalOutcome == null &&
    receipt.reservation.bundle.policies.any {
        it.policyId == assignment.fence.policyId &&
            it.policyRevision == assignment.fence.expectedContentRevision &&
            it.artifactSha256 == assignment.fence.expectedArtifactHash
    }

private fun LearningPolicyExposureEntity.matchesFrozenAssignment(
    assignment: LearningObservedUtilityAssignmentEntity,
    receipt: PolicyExposureReceipt,
): Boolean = id == assignment.expectedExposureId &&
    streamId == assignment.streamId && replayGeneration == assignment.replayGeneration &&
    episodeId == assignment.episodeId && logicalRunId == assignment.logicalRunId &&
    attemptOrdinal == assignment.attemptOrdinal && scopeKind == assignment.scopeKind &&
    scopeId == assignment.scopeId && taskSignature == assignment.taskSignature &&
    policySetDigest == assignment.policySetDigest &&
    modelIdentity == assignment.modelIdentity && providerIdentity == assignment.providerIdentity &&
    providerGeneration == assignment.providerConfigurationGeneration &&
    toolsetFingerprint == assignment.toolsetFingerprint &&
    assignment.expectedExposureStateVersion != null &&
    assignment.expectedExposureReceiptDigest != null &&
    receipt.reservation.bundle.policies.any {
        it.policyId == assignment.targetPolicyId &&
            it.policyRevision == assignment.targetPolicyContentRevision &&
            it.artifactSha256 == assignment.targetPolicyArtifactSha256
    }

internal fun observedUtilityExposureReceiptDigest(receipt: PolicyExposureReceipt): String =
    LearningCanonicalId.digest(
        domainVersion = "observed-utility-exposure-receipt-v1",
        fields = listOf(
            receipt.reservation.key.reservationId,
            receipt.stateVersion.toString(),
            receipt.terminalOutcome?.name.orEmpty(),
            receipt.observedStates.sortedBy { it.ordinal }.joinToString(",") { it.name },
            receipt.reservation.bundle.policySetDigest,
        ),
    )

private fun LearningEpisodeEntity.matchesOutcomeAuthority(
    authority: ObservedUtilityOutcomeAuthority,
): Boolean = when (authority.sourceKind) {
    me.rerere.rikkahub.learning.model.LearningSourceKind.CONVERSATION_MESSAGE ->
        resultAssistantMessageId == authority.sourceId &&
            resultAssistantMessageRevision == authority.sourceRevision
    me.rerere.rikkahub.learning.model.LearningSourceKind.COMMAND ->
        (finalCommandId ?: rootCommandId) == authority.sourceId &&
            (finalCommandRevision ?: rootCommandRevision) == authority.sourceRevision
    else -> false
}

private fun LearningEpisodeEntity.matchesObservedUtilityAssignment(
    assignment: LearningObservedUtilityAssignmentEntity,
): Boolean = id == assignment.episodeId && streamId == assignment.streamId &&
    replayGeneration == assignment.replayGeneration && scopeKind == assignment.scopeKind &&
    scopeId == assignment.scopeId && generationRunId == assignment.logicalRunId &&
    taskSignature == assignment.taskSignature && finalizedAtMs != null

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

private class UtilityLedgerPersistRollback : RuntimeException()

private fun rollbackPersist(): Nothing = throw UtilityLedgerPersistRollback()


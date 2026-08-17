package me.rerere.rikkahub.learning.exposure

import androidx.room.withTransaction
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.ai.ProviderAttemptEvent
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyExposureAttributionState
import me.rerere.rikkahub.learning.storage.LearningPolicyExposureEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyExposureItemEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus

private const val MAX_ROOM_EXPOSURE_ITEMS = 20
private val EXPOSURE_DROP_CODE = Regex("[A-Z][A-Z0-9_]{0,95}")

/** Room transaction owner for the P2 Policy exposure ledger. */
class RoomPolicyExposureStore(
    private val database: LearningDatabase,
) : PolicyExposureStore {
    override suspend fun recordDrops(
        reservationId: String,
        expectedStateVersion: Long,
        reasonByPolicyId: Map<String, String>,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult = safeStoreOperation {
        if (reasonByPolicyId.isEmpty() || reasonByPolicyId.size > MAX_ROOM_EXPOSURE_ITEMS ||
            reasonByPolicyId.values.any { !it.matches(EXPOSURE_DROP_CODE) }
        ) {
            return@safeStoreOperation PolicyExposureStoreResult.Conflict(
                PolicyExposureStoreConflict.DROP_OBSERVATION_CONFLICT,
            )
        }
        database.withTransaction {
            val entity = database.policyExposureDao().findExposure(reservationId)
                ?: return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.RESERVATION_CONFLICT,
                )
            val snapshot = readSnapshot(entity)
            if (snapshot.receipt.stateVersion != expectedStateVersion) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.STATE_VERSION_MISMATCH,
                    snapshot.receipt,
                )
            }
            if (frozenNowEpochMs < entity.updatedAtMs || frozenNowEpochMs < 0L) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.CLOCK_ROLLBACK,
                    snapshot.receipt,
                )
            }
            if (entity.injectedAtMs != null || entity.hostDispatchedAtMs != null ||
                entity.terminalOutcome != null || entity.outcomeLinkedAtMs != null ||
                snapshot.items.map { it.policyId }.toSet() != reasonByPolicyId.keys
            ) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.DROP_OBSERVATION_CONFLICT,
                    snapshot.receipt,
                )
            }
            if (snapshot.items.all { item -> item.dropReason == reasonByPolicyId[item.policyId] }) {
                return@withTransaction PolicyExposureStoreResult.Available(
                    snapshot.receipt,
                    PolicyExposureWriteDisposition.DUPLICATE,
                )
            }
            if (snapshot.items.any { item ->
                    item.dropReason != null && item.dropReason != reasonByPolicyId[item.policyId]
                }
            ) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.DROP_OBSERVATION_CONFLICT,
                    snapshot.receipt,
                )
            }
            val dao = database.policyExposureDao()
            snapshot.items.filter { it.dropReason == null }.forEach { item ->
                if (dao.markItemDroppedIfNotInjected(
                        exposureId = reservationId,
                        policyId = item.policyId,
                        dropReason = checkNotNull(reasonByPolicyId[item.policyId]),
                    ) != 1
                ) {
                    abortTransaction(
                        PolicyExposureStoreResult.Conflict(
                            PolicyExposureStoreConflict.ITEM_CONFLICT,
                            snapshot.receipt,
                        ),
                    )
                }
            }
            val changed = dao.updateSnapshotIfCurrent(
                id = entity.id,
                expectedStateVersion = expectedStateVersion,
                furthestState = entity.furthestState,
                retrievedAtMs = entity.retrievedAtMs,
                compiledAtMs = entity.compiledAtMs,
                injectedAtMs = entity.injectedAtMs,
                hostDispatchedAtMs = entity.hostDispatchedAtMs,
                firstProgressAtMs = entity.firstProgressAtMs,
                responseFinishedAtMs = entity.responseFinishedAtMs,
                outcomeLinkedAtMs = entity.outcomeLinkedAtMs,
                terminalOutcome = entity.terminalOutcome,
                terminalAtMs = entity.terminalAtMs,
                outcomeSourceType = entity.outcomeSourceType,
                outcomeSourceId = entity.outcomeSourceId,
                outcomeSourceRevision = entity.outcomeSourceRevision,
                attributionState = entity.attributionState,
                updatedAtMs = frozenNowEpochMs,
            )
            if (changed != 1) {
                abortTransaction(
                    PolicyExposureStoreResult.Conflict(
                        PolicyExposureStoreConflict.CAS_LOST,
                        snapshot.receipt,
                    ),
                )
            }
            PolicyExposureStoreResult.Available(
                readSnapshot(checkNotNull(dao.findExposure(reservationId))).receipt,
                PolicyExposureWriteDisposition.APPLIED,
            )
        }
    }

    override suspend fun reserve(
        reservation: PolicyExposureReservation,
        metadata: PolicyExposureMetadata,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult = safeStoreOperation {
        if (frozenNowEpochMs < 0L) {
            return@safeStoreOperation PolicyExposureStoreResult.Conflict(
                PolicyExposureStoreConflict.CLOCK_ROLLBACK,
            )
        }
        database.withTransaction {
            val episode = database.episodeDao().findEpisode(reservation.key.episodeId.value)
                ?: return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.EPISODE_NOT_FOUND,
                )
            validateEpisode(episode, reservation, metadata)?.let { return@withTransaction it }
            if (episode.updatedAtMs > frozenNowEpochMs) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.CLOCK_ROLLBACK,
                )
            }

            val expectedEntity = reservation.toInitialEntity(metadata, frozenNowEpochMs)
            val expectedItems = reservation.toInitialItems(frozenNowEpochMs)
            val dao = database.policyExposureDao()
            val byId = dao.findExposure(reservation.key.reservationId)
            val byTuple = dao.findExactReservation(
                streamId = reservation.key.streamId.toString(),
                episodeId = reservation.key.episodeId.value,
                logicalRunId = reservation.key.logicalRunId.toString(),
                attemptOrdinal = reservation.key.attemptOrdinal,
                policySetDigest = reservation.key.policySetDigest,
            )
            if (byId != null || byTuple.isNotEmpty()) {
                if (byTuple.size != 1 || byId == null || byTuple.single().id != byId.id) {
                    return@withTransaction PolicyExposureStoreResult.Conflict(
                        PolicyExposureStoreConflict.RESERVATION_CONFLICT,
                    )
                }
                val snapshot = readSnapshot(byId)
                if (!snapshot.matchesReservation(expectedEntity, expectedItems)) {
                    return@withTransaction PolicyExposureStoreResult.Conflict(
                        PolicyExposureStoreConflict.RESERVATION_CONFLICT,
                        snapshot.receipt,
                    )
                }
                return@withTransaction PolicyExposureStoreResult.Available(
                    snapshot.receipt,
                    PolicyExposureWriteDisposition.DUPLICATE,
                )
            }

            dao.insertExposure(expectedEntity)
            expectedItems.forEach { dao.insertItem(it) }
            PolicyExposureStoreResult.Available(
                PolicyExposureReceipt.initial(reservation),
                PolicyExposureWriteDisposition.APPLIED,
            )
        }
    }

    override suspend fun observeMilestone(
        reservationId: String,
        expectedStateVersion: Long,
        state: PolicyExposureState,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult {
        if (state == PolicyExposureState.RETRIEVED || state == PolicyExposureState.OUTCOME_LINKED) {
            return PolicyExposureStoreResult.Conflict(
                PolicyExposureStoreConflict.INVALID_TRANSITION,
            )
        }
        return mutate(
            reservationId = reservationId,
            expectedStateVersion = expectedStateVersion,
            frozenNowEpochMs = frozenNowEpochMs,
            transition = { receipt ->
                PolicyExposureStateMachine.observe(receipt, expectedStateVersion, state)
            },
            snapshotMutation = { entity -> entity.withMilestone(state, frozenNowEpochMs) },
            itemMutation = when (state) {
                PolicyExposureState.COMPILED -> ItemMutation.COMPILED
                PolicyExposureState.INJECTED -> ItemMutation.INJECTED
                else -> ItemMutation.NONE
            },
        )
    }

    override suspend fun observeProviderAttempt(
        reservationId: String,
        expectedStateVersion: Long,
        event: ProviderAttemptEvent,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult = safeStoreOperation {
        database.withTransaction {
            val entity = database.policyExposureDao().findExposure(reservationId)
                ?: return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.RESERVATION_CONFLICT,
                )
            if (entity.attemptOrdinal != event.attemptOrdinal) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.ATTEMPT_ORDINAL_MISMATCH,
                )
            }
            mutateInCurrentTransaction(
                entity = entity,
                expectedStateVersion = expectedStateVersion,
                frozenNowEpochMs = frozenNowEpochMs,
                transition = { receipt ->
                    when (event) {
                        is ProviderAttemptEvent.HostDispatched -> PolicyExposureStateMachine.observe(
                            receipt,
                            expectedStateVersion,
                            PolicyExposureState.HOST_DISPATCHED,
                        )
                        is ProviderAttemptEvent.FirstProgress -> PolicyExposureStateMachine.observe(
                            receipt,
                            expectedStateVersion,
                            PolicyExposureState.FIRST_PROGRESS,
                        )
                        is ProviderAttemptEvent.ResponseFinished -> PolicyExposureStateMachine.observe(
                            receipt,
                            expectedStateVersion,
                            PolicyExposureState.RESPONSE_FINISHED,
                        )
                        is ProviderAttemptEvent.Terminal -> PolicyExposureStateMachine.recordTerminal(
                            receipt,
                            expectedStateVersion,
                            event.outcome,
                        )
                    }
                },
                snapshotMutation = { current ->
                    when (event) {
                        is ProviderAttemptEvent.HostDispatched -> current.withMilestone(
                            PolicyExposureState.HOST_DISPATCHED,
                            frozenNowEpochMs,
                        )
                        is ProviderAttemptEvent.FirstProgress -> current.withMilestone(
                            PolicyExposureState.FIRST_PROGRESS,
                            frozenNowEpochMs,
                        )
                        is ProviderAttemptEvent.ResponseFinished -> current.withMilestone(
                            PolicyExposureState.RESPONSE_FINISHED,
                            frozenNowEpochMs,
                        )
                        is ProviderAttemptEvent.Terminal -> current.withTerminal(
                            event.outcome,
                            frozenNowEpochMs,
                        )
                    }
                },
                itemMutation = ItemMutation.NONE,
            )
        }
    }

    override suspend fun linkOutcome(
        reservationId: String,
        expectedStateVersion: Long,
        authority: PolicyExposureOutcomeAuthority,
        frozenNowEpochMs: Long,
    ): PolicyExposureStoreResult = safeStoreOperation {
        database.withTransaction {
            val entity = database.policyExposureDao().findExposure(reservationId)
                ?: return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.RESERVATION_CONFLICT,
                )
            val snapshot = readSnapshot(entity)
            // Outcome authority and utility are separate facts. A durable provider terminal after
            // HOST_DISPATCHED may be linked even when the attempt failed/cancelled or produced no
            // meaningful progress. A later estimator owns the stricter utility-evidence gate.
            if (snapshot.receipt.terminalOutcome == null ||
                PolicyExposureState.HOST_DISPATCHED !in snapshot.receipt.observedStates
            ) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.OUTCOME_NOT_ELIGIBLE,
                    snapshot.receipt,
                )
            }
            val episode = database.episodeDao().findEpisode(entity.episodeId)
                ?: return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.EPISODE_NOT_FOUND,
                    snapshot.receipt,
                )
            if (episode.status == StoredLearningEpisodeStatus.OPEN.name ||
                episode.finalizedAtMs == null
            ) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.OUTCOME_NOT_ELIGIBLE,
                    snapshot.receipt,
                )
            }
            if (!episode.matchesOutcomeAuthority(authority)) {
                return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.OUTCOME_AUTHORITY_MISMATCH,
                    snapshot.receipt,
                )
            }
            mutateSnapshotInCurrentTransaction(
                snapshot = snapshot,
                expectedStateVersion = expectedStateVersion,
                frozenNowEpochMs = frozenNowEpochMs,
                transition = { receipt ->
                    PolicyExposureStateMachine.observe(
                        receipt,
                        expectedStateVersion,
                        PolicyExposureState.OUTCOME_LINKED,
                    )
                },
                snapshotMutation = { current ->
                    current.withOutcome(authority, frozenNowEpochMs)
                },
                itemMutation = ItemMutation.NONE,
            )
        }
    }

    override suspend fun load(reservationId: String): PolicyExposureStoreResult = safeStoreOperation {
        database.withTransaction {
            val entity = database.policyExposureDao().findExposure(reservationId)
                ?: return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.RESERVATION_CONFLICT,
                )
            PolicyExposureStoreResult.Available(
                readSnapshot(entity).receipt,
                PolicyExposureWriteDisposition.DUPLICATE,
            )
        }
    }

    private suspend fun mutate(
        reservationId: String,
        expectedStateVersion: Long,
        frozenNowEpochMs: Long,
        transition: (PolicyExposureReceipt) -> PolicyExposureMutationResult,
        snapshotMutation: (LearningPolicyExposureEntity) -> LearningPolicyExposureEntity,
        itemMutation: ItemMutation,
    ): PolicyExposureStoreResult = safeStoreOperation {
        database.withTransaction {
            val entity = database.policyExposureDao().findExposure(reservationId)
                ?: return@withTransaction PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.RESERVATION_CONFLICT,
                )
            mutateInCurrentTransaction(
                entity,
                expectedStateVersion,
                frozenNowEpochMs,
                transition,
                snapshotMutation,
                itemMutation,
            )
        }
    }

    private suspend fun mutateInCurrentTransaction(
        entity: LearningPolicyExposureEntity,
        expectedStateVersion: Long,
        frozenNowEpochMs: Long,
        transition: (PolicyExposureReceipt) -> PolicyExposureMutationResult,
        snapshotMutation: (LearningPolicyExposureEntity) -> LearningPolicyExposureEntity,
        itemMutation: ItemMutation,
    ): PolicyExposureStoreResult = mutateSnapshotInCurrentTransaction(
        snapshot = readSnapshot(entity),
        expectedStateVersion = expectedStateVersion,
        frozenNowEpochMs = frozenNowEpochMs,
        transition = transition,
        snapshotMutation = snapshotMutation,
        itemMutation = itemMutation,
    )

    private suspend fun mutateSnapshotInCurrentTransaction(
        snapshot: StoredExposureSnapshot,
        expectedStateVersion: Long,
        frozenNowEpochMs: Long,
        transition: (PolicyExposureReceipt) -> PolicyExposureMutationResult,
        snapshotMutation: (LearningPolicyExposureEntity) -> LearningPolicyExposureEntity,
        itemMutation: ItemMutation,
    ): PolicyExposureStoreResult {
        if (frozenNowEpochMs < snapshot.entity.updatedAtMs) {
            return PolicyExposureStoreResult.Conflict(
                PolicyExposureStoreConflict.CLOCK_ROLLBACK,
                snapshot.receipt,
            )
        }
        return when (val mutation = transition(snapshot.receipt)) {
            is PolicyExposureMutationResult.Duplicate -> PolicyExposureStoreResult.Available(
                mutation.receipt,
                PolicyExposureWriteDisposition.DUPLICATE,
            )
            is PolicyExposureMutationResult.Rejected -> PolicyExposureStoreResult.Conflict(
                reason = if (
                    mutation.reason == PolicyExposureRejectionReason.STATE_VERSION_MISMATCH
                ) {
                    PolicyExposureStoreConflict.STATE_VERSION_MISMATCH
                } else {
                    PolicyExposureStoreConflict.INVALID_TRANSITION
                },
                currentReceipt = mutation.receipt,
            )
            is PolicyExposureMutationResult.Applied -> {
                val nextEntity = try {
                    snapshotMutation(snapshot.entity).copy(
                        stateVersion = mutation.receipt.stateVersion,
                        furthestState = mutation.receipt.latestState.name,
                        updatedAtMs = frozenNowEpochMs,
                    )
                } catch (_: IllegalArgumentException) {
                    return PolicyExposureStoreResult.Conflict(
                        PolicyExposureStoreConflict.INVALID_TRANSITION,
                        snapshot.receipt,
                    )
                }
                val dao = database.policyExposureDao()
                val changed = dao.updateSnapshotIfCurrent(
                    id = nextEntity.id,
                    expectedStateVersion = expectedStateVersion,
                    furthestState = nextEntity.furthestState,
                    retrievedAtMs = nextEntity.retrievedAtMs,
                    compiledAtMs = nextEntity.compiledAtMs,
                    injectedAtMs = nextEntity.injectedAtMs,
                    hostDispatchedAtMs = nextEntity.hostDispatchedAtMs,
                    firstProgressAtMs = nextEntity.firstProgressAtMs,
                    responseFinishedAtMs = nextEntity.responseFinishedAtMs,
                    outcomeLinkedAtMs = nextEntity.outcomeLinkedAtMs,
                    terminalOutcome = nextEntity.terminalOutcome,
                    terminalAtMs = nextEntity.terminalAtMs,
                    outcomeSourceType = nextEntity.outcomeSourceType,
                    outcomeSourceId = nextEntity.outcomeSourceId,
                    outcomeSourceRevision = nextEntity.outcomeSourceRevision,
                    attributionState = nextEntity.attributionState,
                    updatedAtMs = nextEntity.updatedAtMs,
                )
                if (changed != 1) {
                    return PolicyExposureStoreResult.Conflict(
                        PolicyExposureStoreConflict.CAS_LOST,
                        snapshot.receipt,
                    )
                }
                val itemChanges = when (itemMutation) {
                    ItemMutation.NONE -> snapshot.items.size
                    ItemMutation.COMPILED -> dao.markItemsCompiledIfRetrieved(
                        nextEntity.id,
                        frozenNowEpochMs,
                    )
                    ItemMutation.INJECTED -> dao.markItemsInjectedIfCompiled(
                        nextEntity.id,
                        frozenNowEpochMs,
                    )
                }
                if (itemMutation != ItemMutation.NONE && itemChanges != snapshot.items.size) {
                    abortTransaction(
                        PolicyExposureStoreResult.Conflict(
                            PolicyExposureStoreConflict.ITEM_CONFLICT,
                            snapshot.receipt,
                        ),
                    )
                }
                if (mutation.receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED) &&
                    !snapshot.receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED)
                ) {
                    val usageChanges = snapshot.receipt.reservation.bundle.policies.sumOf { policy ->
                        database.policyDao().recordExactActivePolicyUsage(
                            policyId = policy.policyId,
                            contentRevision = policy.policyRevision,
                            artifactSha256 = policy.artifactSha256,
                            scopeKind = policy.scope.kind.name,
                            scopeId = policy.scope.storageId,
                            usedAtMs = frozenNowEpochMs,
                        )
                    }
                    if (usageChanges != snapshot.items.size) {
                        abortTransaction(
                            PolicyExposureStoreResult.Conflict(
                                PolicyExposureStoreConflict.ITEM_CONFLICT,
                                snapshot.receipt,
                            ),
                        )
                    }
                }
                PolicyExposureStoreResult.Available(
                    mutation.receipt,
                    PolicyExposureWriteDisposition.APPLIED,
                )
            }
        }
    }

    private suspend fun readSnapshot(
        entity: LearningPolicyExposureEntity,
    ): StoredExposureSnapshot {
        val items = database.policyExposureDao().listItems(
            exposureId = entity.id,
            limit = MAX_ROOM_EXPOSURE_ITEMS + 1,
        )
        if (items.isEmpty() || items.size > MAX_ROOM_EXPOSURE_ITEMS) {
            abortTransaction(
                PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.CORRUPT_SNAPSHOT,
                ),
            )
        }
        return try {
            val scope = requireNotNull(LearningScope.parseOrNull(entity.scopeKind, entity.scopeId))
            val episodeId = requireNotNull(EpisodeId.parseOrNull(entity.episodeId))
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
            check(bundle.policySetDigest == entity.policySetDigest)
            val reservation = PolicyExposureReservation(
                key = PolicyExposureReservationKey(
                    streamId = Uuid.parse(entity.streamId),
                    episodeId = episodeId,
                    logicalRunId = Uuid.parse(entity.logicalRunId),
                    attemptOrdinal = entity.attemptOrdinal,
                    policySetDigest = entity.policySetDigest,
                ),
                bundle = bundle,
            )
            check(reservation.key.reservationId == entity.id)
            val states = buildSet {
                add(PolicyExposureState.RETRIEVED)
                if (entity.compiledAtMs != null) add(PolicyExposureState.COMPILED)
                if (entity.injectedAtMs != null) add(PolicyExposureState.INJECTED)
                if (entity.hostDispatchedAtMs != null) add(PolicyExposureState.HOST_DISPATCHED)
                if (entity.firstProgressAtMs != null) add(PolicyExposureState.FIRST_PROGRESS)
                if (entity.responseFinishedAtMs != null) add(PolicyExposureState.RESPONSE_FINISHED)
                if (entity.outcomeLinkedAtMs != null) add(PolicyExposureState.OUTCOME_LINKED)
            }
            val terminal = entity.terminalOutcome?.let(ProviderAttemptTerminalOutcome::valueOf)
            val receipt = PolicyExposureReceipt.restore(
                reservation = reservation,
                observedStates = states,
                stateVersion = entity.stateVersion,
                terminalOutcome = terminal,
            )
            check(receipt.latestState.name == entity.furthestState)
            check(items.all { it.retrievedAtMs == entity.retrievedAtMs })
            check(items.all { it.compiledAtMs == entity.compiledAtMs })
            check(items.all { it.injectedAtMs == entity.injectedAtMs })
            StoredExposureSnapshot(entity, items, receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            abortTransaction(
                PolicyExposureStoreResult.Conflict(
                    PolicyExposureStoreConflict.CORRUPT_SNAPSHOT,
                ),
            )
        }
    }

    private suspend fun safeStoreOperation(
        block: suspend () -> PolicyExposureStoreResult,
    ): PolicyExposureStoreResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (aborted: PolicyExposureTransactionAbort) {
        aborted.result
    } catch (_: IllegalStateException) {
        PolicyExposureStoreResult.Unavailable(
            PolicyExposureStoreUnavailable.DATABASE_UNAVAILABLE,
        )
    } catch (_: Throwable) {
        PolicyExposureStoreResult.Unavailable(
            PolicyExposureStoreUnavailable.STORAGE_FAILURE,
        )
    }
}

private data class StoredExposureSnapshot(
    val entity: LearningPolicyExposureEntity,
    val items: List<LearningPolicyExposureItemEntity>,
    val receipt: PolicyExposureReceipt,
) {
    fun matchesReservation(
        expectedEntity: LearningPolicyExposureEntity,
        expectedItems: List<LearningPolicyExposureItemEntity>,
    ): Boolean {
        val immutableEntityMatches = entity.run {
            id == expectedEntity.id &&
                streamId == expectedEntity.streamId &&
                replayGeneration == expectedEntity.replayGeneration &&
                episodeId == expectedEntity.episodeId &&
                logicalRunId == expectedEntity.logicalRunId &&
                attemptOrdinal == expectedEntity.attemptOrdinal &&
                scopeKind == expectedEntity.scopeKind &&
                scopeId == expectedEntity.scopeId &&
                taskSignature == expectedEntity.taskSignature &&
                policySetDigest == expectedEntity.policySetDigest &&
                treatmentArm == expectedEntity.treatmentArm &&
                modelIdentity == expectedEntity.modelIdentity &&
                providerIdentity == expectedEntity.providerIdentity &&
                providerGeneration == expectedEntity.providerGeneration &&
                toolsetFingerprint == expectedEntity.toolsetFingerprint &&
                contextCompilerAbi == expectedEntity.contextCompilerAbi &&
                retrievedAtMs == expectedEntity.retrievedAtMs &&
                createdAtMs == expectedEntity.createdAtMs
        }
        if (!immutableEntityMatches || items.size != expectedItems.size) return false
        return items.zip(expectedItems).all { (actual, expected) ->
            actual.exposureId == expected.exposureId &&
                actual.policyId == expected.policyId &&
                actual.policyRevision == expected.policyRevision &&
                actual.artifactSha256 == expected.artifactSha256 &&
                actual.applicabilityCohortDigest == expected.applicabilityCohortDigest &&
                actual.rank == expected.rank &&
                actual.estimatedTokens == expected.estimatedTokens &&
                actual.retrievedAtMs == expected.retrievedAtMs
        }
    }
}

private enum class ItemMutation { NONE, COMPILED, INJECTED }

private class PolicyExposureTransactionAbort(
    val result: PolicyExposureStoreResult,
) : RuntimeException(null, null, false, false)

private fun abortTransaction(result: PolicyExposureStoreResult): Nothing =
    throw PolicyExposureTransactionAbort(result)

private fun validateEpisode(
    episode: LearningEpisodeEntity,
    reservation: PolicyExposureReservation,
    metadata: PolicyExposureMetadata,
): PolicyExposureStoreResult.Conflict? {
    val key = reservation.key
    val identityMatches = episode.id == key.episodeId.value &&
        episode.streamId == key.streamId.toString() &&
        episode.replayGeneration == metadata.replayGeneration &&
        episode.scopeKind == metadata.scope.kind.name &&
        episode.scopeId == metadata.scope.storageId &&
        episode.taskSignature == metadata.taskSignature &&
        episode.generationRunId == key.logicalRunId.toString() &&
        reservation.bundle.policies.all { it.scope == metadata.scope }
    if (!identityMatches) {
        return PolicyExposureStoreResult.Conflict(
            PolicyExposureStoreConflict.EPISODE_IDENTITY_MISMATCH,
        )
    }
    // A reservation describes provider bytes for the currently executing logical run. A terminal
    // Episode may later be used only to link an already-dispatched exposure outcome; it must never
    // admit a fresh exposure (including a late watchdog retry) after authority has closed the run.
    val eligible = StoredLearningEpisodeStatus.valueOf(episode.status) ==
        StoredLearningEpisodeStatus.OPEN
    return if (eligible) null else PolicyExposureStoreResult.Conflict(
        PolicyExposureStoreConflict.EPISODE_NOT_ELIGIBLE,
    )
}

private fun PolicyExposureReservation.toInitialEntity(
    metadata: PolicyExposureMetadata,
    frozenNowEpochMs: Long,
) = LearningPolicyExposureEntity(
    id = key.reservationId,
    streamId = key.streamId.toString(),
    replayGeneration = metadata.replayGeneration,
    episodeId = key.episodeId.value,
    logicalRunId = key.logicalRunId.toString(),
    attemptOrdinal = key.attemptOrdinal,
    scopeKind = metadata.scope.kind.name,
    scopeId = metadata.scope.storageId,
    taskSignature = metadata.taskSignature,
    policySetDigest = key.policySetDigest,
    treatmentArm = metadata.treatmentArm,
    modelIdentity = metadata.modelIdentity,
    providerIdentity = metadata.providerIdentity,
    providerGeneration = metadata.providerGeneration,
    toolsetFingerprint = metadata.toolsetFingerprint,
    contextCompilerAbi = metadata.contextCompilerAbi,
    stateVersion = 0L,
    furthestState = PolicyExposureState.RETRIEVED.name,
    retrievedAtMs = frozenNowEpochMs,
    compiledAtMs = null,
    injectedAtMs = null,
    hostDispatchedAtMs = null,
    firstProgressAtMs = null,
    responseFinishedAtMs = null,
    outcomeLinkedAtMs = null,
    terminalOutcome = null,
    terminalAtMs = null,
    outcomeSourceType = null,
    outcomeSourceId = null,
    outcomeSourceRevision = null,
    attributionState = LearningPolicyExposureAttributionState.UNKNOWN.name,
    createdAtMs = frozenNowEpochMs,
    updatedAtMs = frozenNowEpochMs,
)

private fun PolicyExposureReservation.toInitialItems(
    frozenNowEpochMs: Long,
): List<LearningPolicyExposureItemEntity> = bundle.policies.map { policy ->
    LearningPolicyExposureItemEntity(
        exposureId = key.reservationId,
        policyId = policy.policyId,
        policyRevision = policy.policyRevision,
        artifactSha256 = policy.artifactSha256,
        applicabilityCohortDigest = policy.applicabilityCohortDigest,
        rank = policy.rank,
        estimatedTokens = policy.estimatedTokens,
        dropReason = null,
        retrievedAtMs = frozenNowEpochMs,
        compiledAtMs = null,
        injectedAtMs = null,
    )
}

private fun LearningPolicyExposureEntity.withMilestone(
    state: PolicyExposureState,
    timestamp: Long,
): LearningPolicyExposureEntity = when (state) {
    PolicyExposureState.RETRIEVED -> this
    PolicyExposureState.COMPILED -> copy(
        stateVersion = stateVersion + 1L,
        furthestState = state.name,
        compiledAtMs = timestamp,
        updatedAtMs = timestamp,
    )
    PolicyExposureState.INJECTED -> copy(
        stateVersion = stateVersion + 1L,
        furthestState = state.name,
        injectedAtMs = timestamp,
        updatedAtMs = timestamp,
    )
    PolicyExposureState.HOST_DISPATCHED -> copy(
        stateVersion = stateVersion + 1L,
        furthestState = state.name,
        hostDispatchedAtMs = timestamp,
        updatedAtMs = timestamp,
    )
    PolicyExposureState.FIRST_PROGRESS -> copy(
        stateVersion = stateVersion + 1L,
        furthestState = state.name,
        firstProgressAtMs = timestamp,
        updatedAtMs = timestamp,
    )
    PolicyExposureState.RESPONSE_FINISHED -> copy(
        stateVersion = stateVersion + 1L,
        furthestState = state.name,
        responseFinishedAtMs = timestamp,
        updatedAtMs = timestamp,
    )
    PolicyExposureState.OUTCOME_LINKED -> copy(
        stateVersion = stateVersion + 1L,
        furthestState = state.name,
        outcomeLinkedAtMs = timestamp,
        updatedAtMs = timestamp,
    )
}

private fun LearningPolicyExposureEntity.withTerminal(
    outcome: ProviderAttemptTerminalOutcome,
    timestamp: Long,
): LearningPolicyExposureEntity = copy(
    stateVersion = stateVersion + 1L,
    terminalOutcome = outcome.name,
    terminalAtMs = timestamp,
    updatedAtMs = timestamp,
)

private fun LearningPolicyExposureEntity.withOutcome(
    authority: PolicyExposureOutcomeAuthority,
    timestamp: Long,
): LearningPolicyExposureEntity = copy(
    stateVersion = stateVersion + 1L,
    furthestState = PolicyExposureState.OUTCOME_LINKED.name,
    outcomeLinkedAtMs = timestamp,
    outcomeSourceType = authority.sourceKind.name,
    outcomeSourceId = authority.sourceId,
    outcomeSourceRevision = authority.sourceRevision,
    attributionState = LearningPolicyExposureAttributionState.KNOWN.name,
    updatedAtMs = timestamp,
)

private fun LearningEpisodeEntity.matchesOutcomeAuthority(
    authority: PolicyExposureOutcomeAuthority,
): Boolean = when (authority.sourceKind) {
    LearningSourceKind.COMMAND -> {
        val expectedId = finalCommandId ?: rootCommandId
        val expectedRevision = finalCommandRevision ?: rootCommandRevision
        authority.sourceId == expectedId && authority.sourceRevision == expectedRevision
    }
    LearningSourceKind.CONVERSATION_MESSAGE ->
        authority.sourceId == resultAssistantMessageId &&
            authority.sourceRevision == resultAssistantMessageRevision
    else -> false
}

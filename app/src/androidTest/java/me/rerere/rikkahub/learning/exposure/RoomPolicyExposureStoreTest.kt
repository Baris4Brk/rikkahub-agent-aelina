package me.rerere.rikkahub.learning.exposure

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ProviderAttemptEvent
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.episode.LearningCompletionKind
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeBoundaryReason
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.POLICY_IDENTITY_APPLICABILITY_ANY
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomPolicyExposureStoreTest {
    private lateinit var database: LearningDatabase
    private lateinit var store: RoomPolicyExposureStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
        store = RoomPolicyExposureStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reserve_isAtomicIdempotentAndRejectsIdentityConflict() = runBlocking {
        database.episodeDao().insertEpisodeIgnore(episode())

        val first = store.reserve(reservation(), metadata(), 10).available()
        assertEquals(PolicyExposureWriteDisposition.APPLIED, first.disposition)
        val duplicate = store.reserve(reservation(), metadata(), 10).available()
        assertEquals(PolicyExposureWriteDisposition.DUPLICATE, duplicate.disposition)
        assertEquals(first.receipt, duplicate.receipt)
        assertEquals(
            2,
            database.policyExposureDao().listItems(reservation().key.reservationId, 20).size,
        )

        val conflict = store.reserve(
            reservation(),
            metadata().copy(providerIdentity = "other-provider"),
            10,
        )
        assertConflict(conflict, PolicyExposureStoreConflict.RESERVATION_CONFLICT)
    }

    @Test
    fun reserve_rejectsEpisodeMismatchAndIneligibleTerminal() = runBlocking {
        database.episodeDao().insertEpisodeIgnore(episode(taskSignature = "other-task"))
        assertConflict(
            store.reserve(reservation(), metadata(), 10),
            PolicyExposureStoreConflict.EPISODE_IDENTITY_MISMATCH,
        )

        database.close()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
        store = RoomPolicyExposureStore(database)
        database.episodeDao().insertEpisodeIgnore(
            episode(
                status = StoredLearningEpisodeStatus.SUPERSEDED,
                finalizedAtMs = 2,
            ),
        )
        assertConflict(
            store.reserve(reservation(), metadata(), 10),
            PolicyExposureStoreConflict.EPISODE_NOT_ELIGIBLE,
        )
    }

    @Test
    fun expectedVersionCasAndProcessDeathSnapshot_areDurable() = runBlocking {
        database.episodeDao().insertEpisodeIgnore(episode())
        store.reserve(reservation(), metadata(), 10).available()

        val compiled = store.observeMilestone(
            reservation().key.reservationId,
            expectedStateVersion = 0,
            state = PolicyExposureState.COMPILED,
            frozenNowEpochMs = 11,
        ).available()
        assertEquals(1L, compiled.receipt.stateVersion)
        assertConflict(
            store.observeMilestone(
                reservation().key.reservationId,
                expectedStateVersion = 0,
                state = PolicyExposureState.INJECTED,
                frozenNowEpochMs = 12,
            ),
            PolicyExposureStoreConflict.STATE_VERSION_MISMATCH,
        )

        val afterProcessDeath = RoomPolicyExposureStore(database)
        val restored = afterProcessDeath.load(reservation().key.reservationId).available().receipt
        assertEquals(compiled.receipt, restored)
        val injected = afterProcessDeath.observeMilestone(
            reservation().key.reservationId,
            expectedStateVersion = restored.stateVersion,
            state = PolicyExposureState.INJECTED,
            frozenNowEpochMs = 12,
        ).available().receipt
        assertEquals(2L, injected.stateVersion)
        assertTrue(injected.hasObserved(PolicyExposureState.INJECTED))
        assertTrue(
            database.policyExposureDao().listItems(reservation().key.reservationId, 20)
                .all { it.compiledAtMs == 11L && it.injectedAtMs == 12L },
        )
    }

    @Test
    fun watchdogRetry_usesFreshOrdinalAndRejectsCrossAttemptCallback() = runBlocking {
        database.episodeDao().insertEpisodeIgnore(episode())
        val first = reservation()
        val retry = first.nextRetry()
        store.reserve(first, metadata(), 10).available()
        store.reserve(retry, metadata(), 20).available()
        assertTrue(first.key.reservationId != retry.key.reservationId)

        assertConflict(
            store.observeProviderAttempt(
                retry.key.reservationId,
                expectedStateVersion = 0,
                event = ProviderAttemptEvent.HostDispatched(
                    attemptOrdinal = first.key.attemptOrdinal,
                    stream = true,
                ),
                frozenNowEpochMs = 21,
            ),
            PolicyExposureStoreConflict.ATTEMPT_ORDINAL_MISMATCH,
        )
    }

    @Test
    fun outcomeLink_requiresTerminalDispatchAndExactAuthority_notUtilityProgress() = runBlocking {
        database.episodeDao().insertEpisodeIgnore(episode())
        insertActivePolicies()
        var receipt = store.reserve(reservation(), metadata(), 10).available().receipt
        val retry = reservation().nextRetry()
        var cancelled = store.reserve(retry, metadata(), 10).available().receipt
        receipt = store.observeMilestone(
            reservation().key.reservationId,
            receipt.stateVersion,
            PolicyExposureState.COMPILED,
            11,
        ).available().receipt
        receipt = store.observeMilestone(
            reservation().key.reservationId,
            receipt.stateVersion,
            PolicyExposureState.INJECTED,
            12,
        ).available().receipt
        receipt = store.observeProviderAttempt(
            reservation().key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.HostDispatched(1, stream = true),
            13,
        ).available().receipt
        receipt = store.observeProviderAttempt(
            reservation().key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.Terminal(1, ProviderAttemptTerminalOutcome.FAILED),
            14,
        ).available().receipt

        cancelled = store.observeMilestone(
            retry.key.reservationId,
            cancelled.stateVersion,
            PolicyExposureState.COMPILED,
            15,
        ).available().receipt
        cancelled = store.observeMilestone(
            retry.key.reservationId,
            cancelled.stateVersion,
            PolicyExposureState.INJECTED,
            16,
        ).available().receipt
        cancelled = store.observeProviderAttempt(
            retry.key.reservationId,
            cancelled.stateVersion,
            ProviderAttemptEvent.Terminal(2, ProviderAttemptTerminalOutcome.CANCELLED),
            17,
        ).available().receipt

        assertEquals(
            1,
            database.episodeDao().updateBoundaryIfCurrent(
                episodeId = EPISODE_ID,
                expectedRevision = 1,
                expectedStatus = StoredLearningEpisodeStatus.OPEN.name,
                conversationRevision = 5,
                finalCommandId = FINAL_COMMAND_ID,
                finalCommandRevision = 2,
                resultAssistantMessageId = null,
                resultAssistantMessageRevision = null,
                generationRunId = LOGICAL_RUN_ID,
                executionId = null,
                taskSignature = TASK_SIGNATURE,
                newStatus = StoredLearningEpisodeStatus.FAILURE.name,
                boundaryReason = LearningEpisodeBoundaryReason.UNKNOWN.name,
                finalizedAtMs = 18,
                updatedAtMs = 18,
            ),
        )

        assertConflict(
            store.linkOutcome(
                reservation().key.reservationId,
                receipt.stateVersion,
                PolicyExposureOutcomeAuthority(
                    LearningSourceKind.COMMAND,
                    "wrong-command",
                    1,
                ),
                19,
            ),
            PolicyExposureStoreConflict.OUTCOME_AUTHORITY_MISMATCH,
        )
        val linked = store.linkOutcome(
            reservation().key.reservationId,
            receipt.stateVersion,
            PolicyExposureOutcomeAuthority(
                LearningSourceKind.COMMAND,
                FINAL_COMMAND_ID,
                2,
            ),
            19,
        ).available().receipt
        assertTrue(linked.canAttributeOutcome)
        assertFalse(linked.canAttributeObservedUtility)

        assertConflict(
            store.linkOutcome(
                retry.key.reservationId,
                cancelled.stateVersion,
                PolicyExposureOutcomeAuthority(LearningSourceKind.COMMAND, FINAL_COMMAND_ID, 2),
                20,
            ),
            PolicyExposureStoreConflict.OUTCOME_NOT_ELIGIBLE,
        )
    }

    @Test
    fun committedTerminalReplay_linksBoundedExactAttemptAndIsIdempotent() = runBlocking {
        val open = episode().copy(
            finalCommandId = null,
            finalCommandRevision = null,
            resultAssistantMessageId = null,
            resultAssistantMessageRevision = null,
        )
        database.episodeDao().insertEpisodeIgnore(open)
        insertActivePolicies()
        var receipt = store.reserve(reservation(), metadata(), 1).available().receipt
        receipt = store.observeMilestone(
            reservation().key.reservationId,
            receipt.stateVersion,
            PolicyExposureState.COMPILED,
            2,
        ).available().receipt
        receipt = store.observeMilestone(
            reservation().key.reservationId,
            receipt.stateVersion,
            PolicyExposureState.INJECTED,
            3,
        ).available().receipt
        receipt = store.observeProviderAttempt(
            reservation().key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.HostDispatched(1, stream = true),
            4,
        ).available().receipt
        receipt = store.observeProviderAttempt(
            reservation().key.reservationId,
            receipt.stateVersion,
            ProviderAttemptEvent.Terminal(1, ProviderAttemptTerminalOutcome.FAILED),
            5,
        ).available().receipt
        assertConflict(
            store.linkOutcome(
                reservation().key.reservationId,
                receipt.stateVersion,
                PolicyExposureOutcomeAuthority(
                    LearningSourceKind.COMMAND,
                    "root-command",
                    1,
                ),
                6,
            ),
            PolicyExposureStoreConflict.OUTCOME_NOT_ELIGIBLE,
        )

        assertEquals(
            1,
            database.episodeDao().updateBoundaryIfCurrent(
                episodeId = EPISODE_ID,
                expectedRevision = 1,
                expectedStatus = StoredLearningEpisodeStatus.OPEN.name,
                conversationRevision = 5,
                finalCommandId = FINAL_COMMAND_ID,
                finalCommandRevision = 2,
                resultAssistantMessageId = null,
                resultAssistantMessageRevision = null,
                generationRunId = LOGICAL_RUN_ID,
                executionId = null,
                taskSignature = TASK_SIGNATURE,
                newStatus = StoredLearningEpisodeStatus.FAILURE.name,
                boundaryReason = LearningEpisodeBoundaryReason.UNKNOWN.name,
                finalizedAtMs = 6,
                updatedAtMs = 6,
            ),
        )
        val terminalEpisode = requireNotNull(database.episodeDao().findEpisode(EPISODE_ID))
        val terminalEvent = terminalEvent()
        val linker = PolicyExposureOutcomeLinker(database)

        assertEquals(
            PolicyExposureOutcomeLinkResult.INELIGIBLE,
            linker.replayCommittedTerminal(terminalEvent, terminalEpisode),
        )
        assertTrue(database.inboxDao().insertIgnore(terminalEvent) != -1L)
        val linked = linker.replayCommittedTerminal(terminalEvent, terminalEpisode)
        assertTrue(linked.authorityEligible)
        assertEquals(1, linked.scanned)
        assertEquals(1, linked.applied)
        assertEquals(0, linked.conflicts)

        val stored = requireNotNull(
            database.policyExposureDao().findExposure(reservation().key.reservationId),
        )
        assertEquals(PolicyExposureState.OUTCOME_LINKED.name, stored.furthestState)
        assertEquals(LearningSourceKind.COMMAND.name, stored.outcomeSourceType)
        assertEquals(FINAL_COMMAND_ID, stored.outcomeSourceId)
        assertEquals(2L, stored.outcomeSourceRevision)
        assertEquals(7L, stored.outcomeLinkedAtMs)

        val replay = linker.replayCommittedTerminal(terminalEvent, terminalEpisode)
        assertTrue(replay.authorityEligible)
        assertEquals(0, replay.scanned)
        assertEquals(0, replay.applied)
    }

    private fun reservation(): PolicyExposureReservation {
        val policies = listOf(
            policy("policy-one", rank = 1),
            policy("policy-two", rank = 2),
        )
        val bundle = PolicyExposureBundle.create(policies)
        return PolicyExposureReservation(
            key = PolicyExposureReservationKey(
                streamId = Uuid.parse(STREAM_ID),
                episodeId = requireNotNull(EpisodeId.parseOrNull(EPISODE_ID)),
                logicalRunId = Uuid.parse(LOGICAL_RUN_ID),
                attemptOrdinal = 1,
                policySetDigest = bundle.policySetDigest,
            ),
            bundle = bundle,
        )
    }

    private fun policy(policyId: String, rank: Int) = PolicyExposurePolicyRef(
        policyId = policyId,
        policyRevision = rank.toLong(),
        artifactSha256 = rank.toString().repeat(64),
        scope = SCOPE,
        rank = rank,
        estimatedTokens = rank * 10,
        applicabilityCohortDigest = "a".repeat(64),
    )

    private fun metadata() = PolicyExposureMetadata(
        replayGeneration = 3,
        scope = SCOPE,
        taskSignature = TASK_SIGNATURE,
        treatmentArm = "TREATMENT",
        modelIdentity = "model-v1",
        providerIdentity = "provider-v1",
        providerGeneration = 2,
        toolsetFingerprint = "a".repeat(64),
        contextCompilerAbi = "recall-compiler-v1",
    )

    private suspend fun insertActivePolicies() {
        reservation().bundle.policies.forEach { policy ->
            database.policyDao().insertPolicy(
                LearningPolicyEntity(
                    id = policy.policyId,
                    scopeKind = policy.scope.kind.name,
                    scopeId = policy.scope.storageId,
                    taskSignature = TASK_SIGNATURE,
                    policyType = "PROCEDURE",
                    triggerSummary = "trigger summary",
                    procedureSummary = "procedure summary",
                    verificationSummary = "verification summary",
                    boundarySummary = "boundary summary",
                    failureModeSummary = "failure summary",
                    stateVersion = 1,
                    contentRevision = policy.policyRevision,
                    artifactSha256 = policy.artifactSha256,
                    compilerAbi = "policy-compiler-v1",
                    status = StoredLearningPolicyStatus.ACTIVE.name,
                    sourceValid = true,
                    schemaValid = true,
                    applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(emptySet()),
                    applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("b".repeat(64)),
                    applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("c".repeat(64)),
                    applicableTemplateIdentity = "e".repeat(64),
                    applicableConfigurationIdentity = "d".repeat(64),
                    applicableConfigurationGeneration = 1L,
                    applicableCapabilityDigest = null,
                    applicableAuthorityDigest = null,
                    staleReason = null,
                    distinctEpisodeSupport = 2,
                    positiveEpisodeCount = 2,
                    negativeEpisodeCount = 0,
                    usageCount = 0,
                    confidence = 0.9,
                    observedUtilityDelta = null,
                    utilityUncertainty = null,
                    producerModelIdentity = "b".repeat(64),
                    producerProviderIdentity = "c".repeat(64),
                    producerProviderKind = "local_litert",
                    producerConfigurationIdentity = "d".repeat(64),
                    producerConfigGeneration = 1,
                    producerPromptIdentity = "prompt-v1",
                    producerTemplateIdentity = "template-v1",
                    producerSchemaIdentity = "schema-v1",
                    createdAtMs = 0,
                    updatedAtMs = 0,
                    lastUsedAtMs = null,
                ),
            )
        }
    }

    private fun terminalEvent() = LearningInboxEventEntity(
        streamId = STREAM_ID,
        eventId = "terminal-event",
        outboxSeq = 2,
        eventTypeCode = "COMMAND_TERMINAL",
        eventSchemaVersion = 2,
        terminalState = "FAILED",
        decodeState = LearningEventDecodeState.KNOWN.name,
        interpretationVersion = 1,
        sourceType = LearningSourceKind.COMMAND.name,
        sourceId = FINAL_COMMAND_ID,
        sourceRevision = 2,
        previousSourceRevision = 1,
        sourceState = "FAILED",
        missingRevisionReason = null,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = "conversation-one",
        conversationSourceRevision = 5,
        commandId = FINAL_COMMAND_ID,
        lineageId = "lineage-one",
        parentCommandId = null,
        branchAnchorMessageId = "branch-message",
        branchAnchorMessageRevision = 1,
        completionKind = LearningCompletionKind.FAILED_OTHER.name,
        generationRunId = LOGICAL_RUN_ID,
        executionId = null,
        toolCallId = null,
        toolName = null,
        toolSchemaFingerprint = null,
        messageId = null,
        messageRevision = null,
        occurredAtMs = 6,
        createdAtMs = 6,
        ingestedAtMs = 7,
        replayGeneration = 3,
    )

    private fun episode(
        taskSignature: String = TASK_SIGNATURE,
        status: StoredLearningEpisodeStatus = StoredLearningEpisodeStatus.OPEN,
        finalizedAtMs: Long? = null,
    ) = LearningEpisodeEntity(
        id = EPISODE_ID,
        streamId = STREAM_ID,
        replayGeneration = 3,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = "conversation-one",
        conversationRevision = 1,
        rootCommandId = "root-command",
        rootCommandRevision = 1,
        finalCommandId = FINAL_COMMAND_ID,
        finalCommandRevision = 2,
        lineageId = "lineage-one",
        branchAnchorMessageId = "branch-message",
        branchAnchorMessageRevision = 1,
        resultAssistantMessageId = "result-message",
        resultAssistantMessageRevision = 3,
        generationRunId = LOGICAL_RUN_ID,
        executionId = null,
        taskSignature = taskSignature,
        status = status.name,
        boundaryReason = if (status == StoredLearningEpisodeStatus.OPEN) {
            LearningEpisodeBoundaryReason.COMMAND_ADMITTED.name
        } else if (status == StoredLearningEpisodeStatus.SUPERSEDED) {
            LearningEpisodeBoundaryReason.REGENERATED_BRANCH.name
        } else if (status == StoredLearningEpisodeStatus.FAILURE) {
            LearningEpisodeBoundaryReason.UNKNOWN.name
        } else {
            LearningEpisodeBoundaryReason.FINAL_SAVED.name
        },
        revision = 1,
        startedAtMs = 1,
        finalizedAtMs = finalizedAtMs,
        createdAtMs = 1,
        updatedAtMs = finalizedAtMs ?: 1,
    )

    private fun PolicyExposureStoreResult.available(): PolicyExposureStoreResult.Available {
        assertTrue("Expected Available, got $this", this is PolicyExposureStoreResult.Available)
        return this as PolicyExposureStoreResult.Available
    }

    private fun assertConflict(
        result: PolicyExposureStoreResult,
        reason: PolicyExposureStoreConflict,
    ) {
        assertTrue("Expected Conflict, got $result", result is PolicyExposureStoreResult.Conflict)
        assertEquals(reason, (result as PolicyExposureStoreResult.Conflict).reason)
    }

    private companion object {
        const val STREAM_ID = "00000000-0000-0000-0000-000000000101"
        const val LOGICAL_RUN_ID = "00000000-0000-0000-0000-000000000102"
        const val EPISODE_ID =
            "episode-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TASK_SIGNATURE = "task-signature-v1"
        const val FINAL_COMMAND_ID = "final-command"
        val SCOPE = LearningScope.Assistant(
            Uuid.parse("00000000-0000-0000-0000-000000000103"),
        )
    }
}

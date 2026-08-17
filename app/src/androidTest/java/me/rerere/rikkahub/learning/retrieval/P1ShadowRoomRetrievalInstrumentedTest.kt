package me.rerere.rikkahub.learning.retrieval

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.exposure.PolicyLearningCommandContext
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeBoundaryReason
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningEpisodeLessonEntity
import me.rerere.rikkahub.learning.storage.LearningLessonState
import me.rerere.rikkahub.learning.storage.LearningLessonType
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.POLICY_IDENTITY_APPLICABILITY_ANY
import me.rerere.rikkahub.learning.storage.LearningPolicyEvidencePolarity
import me.rerere.rikkahub.learning.storage.LearningRewardDimension
import me.rerere.rikkahub.learning.storage.LearningRewardKnowledge
import me.rerere.rikkahub.learning.storage.LearningRewardSignalEntity
import me.rerere.rikkahub.learning.storage.LearningRewardSignalKind
import me.rerere.rikkahub.learning.storage.LearningSourceValidityEntity
import me.rerere.rikkahub.learning.storage.LearningSourceValidityState
import me.rerere.rikkahub.learning.storage.LearningTraceFeatureEntity
import me.rerere.rikkahub.learning.storage.PolicyEvidenceEntity
import me.rerere.rikkahub.learning.storage.PolicyRewardEvidenceEntity
import me.rerere.rikkahub.learning.storage.PolicyRevisionEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this test on the user's primary phone. */
@RunWith(AndroidJUnit4::class)
class P1ShadowRoomRetrievalInstrumentedTest {
    private lateinit var database: LearningDatabase
    private val streamId = "00000000-0000-4000-8000-000000000001"
    private val scope = LearningScope.Assistant(
        Uuid.parse("00000000-0000-4000-8000-000000000002"),
    )
    private val signature = TaskSignatureV1.create(
        LearningTaskClass.INFORMATION,
        LearningLanguageClass.CHINESE,
        LearningModalityClass.TEXT_ONLY,
        emptySet(),
    )
    private val policyId = "policy-candidate-v1:" + "f".repeat(64)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun twoAuthoritativeEvidenceRowsAreRetrievableUntilOneSourceIsInvalidated() = runBlocking {
        val episodes = listOf(episode('1'), episode('2'))
        episodes.forEachIndexed { index, episode -> insertEvidenceGraph(index + 1, episode) }
        database.policyDao().insertPolicy(policy())
        database.policyDao().insertRevision(
            PolicyRevisionEntity(
                policyId = policyId,
                revision = 1L,
                beforeSnapshot = null,
                afterSnapshot = "Validated candidate snapshot.",
                beforeArtifactSha256 = null,
                afterArtifactSha256 = "e".repeat(64),
                reasonCode = "CREATE",
                actor = "SYSTEM",
                createdAtMs = 5L,
            ),
        )
        episodes.forEachIndexed { index, episode ->
            val ordinal = index + 1
            database.policyDao().insertEvidenceIgnore(policyEvidence(ordinal, episode))
            database.rewardSignalDao().insertPolicyRewardEvidenceIgnore(
                policyRewardEvidence(ordinal, episode),
            )
        }

        val retriever = RoomPolicyShadowRetriever(
            database = database,
            retriever = PolicyRetriever(ByteArray(32) { 5 }),
            clock = { 5L },
        )
        val request = PolicyRetrievalRequest(
            scope = scope,
            taskSignature = signature,
            // Exact-task Room retrieval is enough here and avoids requiring an FTS tokenizer in
            // the generic in-memory test builder.
            query = "",
        )
        assertEquals(
            listOf(policyId),
            retriever.retrieve(request).hits.map { it.candidate.policyId },
        )

        assertEquals(
            1,
            database.episodeDao().updateSourceValidityIfCurrent(
                streamId = streamId,
                replayGeneration = 0L,
                scopeKind = scope.kind.name,
                scopeId = scope.storageId,
                sourceType = "CONVERSATION_MESSAGE",
                sourceId = "message-1",
                sourceRevision = 1L,
                previousSourceRevision = null,
                expectedState = LearningSourceValidityState.VALID.name,
                newState = LearningSourceValidityState.TOMBSTONED.name,
                integritySha256 = null,
                invalidationReason = "SOURCE_TOMBSTONED",
                authorityEventId = "learning-event-v1:message-1-tombstone",
                occurredAtMs = 20L,
                updatedAtMs = 20L,
            ),
        )

        assertTrue(retriever.retrieve(request).hits.isEmpty())
        // Immediate safety comes from the live evidence join; eventual policy reconciliation is
        // allowed to lag without making this still-marked candidate visible.
        assertTrue(requireNotNull(database.policyDao().findPolicy(policyId)).sourceValid)
    }

    @Test
    fun stageDAdmissionPromotionAndObservationCommitAtomicallyAndDeduplicateRetry() = runBlocking {
        val episodes = listOf(episode('1'), episode('2'))
        episodes.forEachIndexed { index, episode -> insertEvidenceGraph(index + 1, episode) }
        database.policyDao().insertPolicy(policy())
        database.policyDao().insertRevision(
            PolicyRevisionEntity(
                policyId = policyId,
                revision = 1L,
                beforeSnapshot = null,
                afterSnapshot = "Validated candidate snapshot.",
                beforeArtifactSha256 = null,
                afterArtifactSha256 = "e".repeat(64),
                reasonCode = "CREATE",
                actor = "SYSTEM",
                createdAtMs = 5L,
            ),
        )
        episodes.forEachIndexed { index, episode ->
            val ordinal = index + 1
            database.policyDao().insertEvidenceIgnore(policyEvidence(ordinal, episode))
            database.rewardSignalDao().insertPolicyRewardEvidenceIgnore(
                policyRewardEvidence(ordinal, episode),
            )
        }
        val result = RoomPolicyShadowRetriever(
            database = database,
            retriever = PolicyRetriever(ByteArray(32) { 7 }),
            clock = { 6L },
        ).retrieve(PolicyRetrievalRequest(scope, signature, ""))
        val command = PolicyLearningCommandContext(
            scope = scope,
            consumingAssistantId = scope.assistantId,
            lineageId = Uuid.parse("00000000-0000-4000-8000-000000000011"),
            branchAnchorMessageId = Uuid.parse("00000000-0000-4000-8000-000000000012"),
            branchAnchorMessageRevision = 1L,
            logicalRunId = Uuid.parse("00000000-0000-4000-8000-000000000013"),
        )
        val request = PolicyShadowRuntimeRequest.forCommand(command, signature, "")
        val store = RoomPolicyShadowObservationStore(database)

        assertTrue(store.record(request, result, 6L) is PolicyShadowObservationCommitResult.Committed)
        val promoted = requireNotNull(database.policyDao().findPolicy(policyId))
        assertEquals(StoredLearningPolicyStatus.SHADOW.name, promoted.status)
        assertEquals(2L, promoted.stateVersion)
        assertEquals(0L, promoted.usageCount)
        assertNull(promoted.observedUtilityDelta)
        assertNull(promoted.utilityUncertainty)
        val observation = requireNotNull(
            database.policyShadowObservationDao().findObservation(request.requestIdentity),
        )
        assertEquals(1, observation.selectedCount)
        assertEquals(
            listOf(policyId),
            database.policyShadowObservationDao().listItems(request.requestIdentity, 21)
                .map { it.policyId },
        )

        assertTrue(store.record(request, result, 6L) is PolicyShadowObservationCommitResult.Duplicate)
        assertEquals(
            1L,
            database.policyShadowObservationDao().aggregateForPolicyReview(policyId).recallCount,
        )
    }

    @Test
    fun rolloutFenceDropAfterPromotionAttemptRollsBackLifecycleAndObservation() = runBlocking {
        val episodes = listOf(episode('1'), episode('2'))
        episodes.forEachIndexed { index, episode -> insertEvidenceGraph(index + 1, episode) }
        database.policyDao().insertPolicy(policy())
        database.policyDao().insertRevision(
            PolicyRevisionEntity(
                policyId = policyId,
                revision = 1L,
                beforeSnapshot = null,
                afterSnapshot = "Validated candidate snapshot.",
                beforeArtifactSha256 = null,
                afterArtifactSha256 = "e".repeat(64),
                reasonCode = "CREATE",
                actor = "SYSTEM",
                createdAtMs = 5L,
            ),
        )
        episodes.forEachIndexed { index, episode ->
            val ordinal = index + 1
            database.policyDao().insertEvidenceIgnore(policyEvidence(ordinal, episode))
            database.rewardSignalDao().insertPolicyRewardEvidenceIgnore(
                policyRewardEvidence(ordinal, episode),
            )
        }
        val result = RoomPolicyShadowRetriever(
            database = database,
            retriever = PolicyRetriever(ByteArray(32) { 9 }),
            clock = { 6L },
        ).retrieve(PolicyRetrievalRequest(scope, signature, ""))
        val command = PolicyLearningCommandContext(
            scope = scope,
            consumingAssistantId = scope.assistantId,
            lineageId = Uuid.parse("00000000-0000-4000-8000-000000000021"),
            branchAnchorMessageId = Uuid.parse("00000000-0000-4000-8000-000000000022"),
            branchAnchorMessageRevision = 1L,
            logicalRunId = Uuid.parse("00000000-0000-4000-8000-000000000023"),
        )
        val request = PolicyShadowRuntimeRequest.forCommand(command, signature, "")
        var checks = 0
        val commit = RoomPolicyShadowObservationStore(database) {
            checks += 1
            checks < 3
        }.record(request, result, 6L)

        assertEquals(
            PolicyShadowObservationCommitResult.Rejected(
                PolicyShadowObservationCommitFailure.ROLLOUT_DISABLED,
            ),
            commit,
        )
        val unchanged = requireNotNull(database.policyDao().findPolicy(policyId))
        assertEquals(StoredLearningPolicyStatus.CANDIDATE.name, unchanged.status)
        assertEquals(1L, unchanged.stateVersion)
        assertEquals(1, database.policyDao().listRevisions(policyId, 20).size)
        assertNull(database.policyShadowObservationDao().findObservation(request.requestIdentity))
    }

    private suspend fun insertEvidenceGraph(ordinal: Int, episode: EpisodeId) {
        val messageIntegrity = ordinal.toString(16).repeat(64).take(64)
        val rewardIntegrity = (ordinal + 2).toString(16).repeat(64).take(64)
        database.episodeDao().insertEpisodeIgnore(
            LearningEpisodeEntity(
                id = episode.value,
                streamId = streamId,
                replayGeneration = 0L,
                scopeKind = scope.kind.name,
                scopeId = scope.storageId,
                conversationId = "conversation-$ordinal",
                conversationRevision = 1L,
                rootCommandId = "command-$ordinal",
                rootCommandRevision = 1L,
                finalCommandId = "command-$ordinal",
                finalCommandRevision = 1L,
                lineageId = "lineage-$ordinal",
                branchAnchorMessageId = "message-$ordinal",
                branchAnchorMessageRevision = 1L,
                resultAssistantMessageId = "assistant-message-$ordinal",
                resultAssistantMessageRevision = 1L,
                generationRunId = "generation-$ordinal",
                executionId = null,
                taskSignature = signature.value,
                status = StoredLearningEpisodeStatus.SUCCESS.name,
                boundaryReason = LearningEpisodeBoundaryReason.FINAL_SAVED.name,
                revision = 1L,
                startedAtMs = 1L,
                finalizedAtMs = 2L,
                createdAtMs = 1L,
                updatedAtMs = 2L,
            ),
        )
        database.episodeDao().insertTraceIgnore(
            LearningTraceFeatureEntity(
                episodeId = episode.value,
                sequence = 1L,
                sourceOrdinal = 0,
                sourceType = "CONVERSATION_MESSAGE",
                sourceId = "message-$ordinal",
                sourceRevision = 1L,
                missingRevisionReason = null,
                actionType = "MODEL",
                actionName = "assistant.response",
                toolSchemaFingerprint = null,
                outcomeClass = "SUCCESS",
                errorCode = null,
                stateSummary = null,
                observationSummary = "The bounded response completed.",
                inputTokenCount = 10L,
                outputTokenCount = 10L,
                toolCount = 0,
                retryCount = 0,
                durationMs = 10L,
                alpha = null,
                quality = 0.9,
                featureSchemaIdentity = "trace-feature-v1",
                createdAtMs = 2L,
            ),
        )
        database.episodeDao().insertLessonIgnore(
            LearningEpisodeLessonEntity(
                episodeId = episode.value,
                lessonVersion = 1,
                scopeKind = scope.kind.name,
                scopeId = scope.storageId,
                lessonType = LearningLessonType.SUCCESS_PATTERN.name,
                triggerSummary = "Use this for the matching bounded task.",
                observationSummary = "The checked execution completed.",
                lessonSummary = "Check prerequisites before the bounded steps.",
                boundarySummary = "Apply only inside the current assistant scope.",
                evidenceManifestSha256 = messageIntegrity,
                artifactSha256 = (ordinal + 4).toString(16).repeat(64).take(64),
                producerProviderIdentity = "a".repeat(64),
                producerProviderKind = "local_litert",
                producerModelIdentity = "b".repeat(64),
                producerConfigurationIdentity = "c".repeat(64),
                producerConfigGeneration = 1L,
                algorithmIdentity = "reflection-v1",
                promptIdentity = "reflection-v1",
                templateIdentity = "reflection-v1",
                schemaIdentity = "episode-lesson-v1",
                inputTokenCount = 10L,
                outputTokenCount = 10L,
                estimatedCostMicros = 0L,
                remoteProvider = false,
                state = LearningLessonState.VALID.name,
                createdAtMs = 3L,
                updatedAtMs = 3L,
            ),
        )
        database.episodeDao().insertSourceValidityIgnore(
            sourceValidity(
                sourceType = "CONVERSATION_MESSAGE",
                sourceId = "message-$ordinal",
                integrity = messageIntegrity,
                eventId = "learning-event-v1:message-$ordinal",
            ),
        )
        database.episodeDao().insertSourceValidityIgnore(
            sourceValidity(
                sourceType = "USER_FEEDBACK",
                sourceId = "feedback-$ordinal",
                integrity = rewardIntegrity,
                eventId = "learning-event-v1:feedback-$ordinal",
            ),
        )
        database.rewardSignalDao().insertSignalIgnore(
            LearningRewardSignalEntity(
                id = "reward-signal-v1:$ordinal",
                episodeId = episode.value,
                streamId = streamId,
                replayGeneration = 0L,
                scopeKind = scope.kind.name,
                scopeId = scope.storageId,
                authorityEventId = "learning-event-v1:feedback-$ordinal",
                sourceType = "USER_FEEDBACK",
                sourceId = "feedback-$ordinal",
                sourceRevision = 1L,
                sourceIntegritySha256 = rewardIntegrity,
                dimension = LearningRewardDimension.USER.name,
                signalKind = LearningRewardSignalKind.EXPLICIT_USER_FEEDBACK.name,
                knowledge = LearningRewardKnowledge.KNOWN.name,
                valueMilli = 1_000,
                unknownReason = null,
                occurredAtMs = 4L,
                createdAtMs = 4L,
            ),
        )
    }

    private fun policy() = LearningPolicyEntity(
        id = policyId,
        scopeKind = scope.kind.name,
        scopeId = scope.storageId,
        taskSignature = signature.value,
        policyType = "PROCEDURE",
        triggerSummary = "Use this for the matching bounded task.",
        procedureSummary = "Check prerequisites, then perform the bounded steps.",
        verificationSummary = "Verify the structured outcome before finishing.",
        boundarySummary = "Apply only inside the current assistant scope.",
        failureModeSummary = "Abstain when the evidence is incomplete.",
        stateVersion = 1L,
        contentRevision = 1L,
        artifactSha256 = "e".repeat(64),
        compilerAbi = "p1-shadow-compiler-v1",
        status = StoredLearningPolicyStatus.CANDIDATE.name,
        sourceValid = true,
        schemaValid = true,
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(emptySet()),
        applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("b".repeat(64)),
        applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("c".repeat(64)),
        applicableTemplateIdentity = "8".repeat(64),
        applicableConfigurationIdentity = "d".repeat(64),
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = null,
        applicableAuthorityDigest = null,
        staleReason = null,
        distinctEpisodeSupport = 2L,
        positiveEpisodeCount = 2L,
        negativeEpisodeCount = 0L,
        usageCount = 0L,
        confidence = 0.9,
        observedUtilityDelta = null,
        utilityUncertainty = null,
        producerModelIdentity = "b".repeat(64),
        producerProviderIdentity = "a".repeat(64),
        producerProviderKind = "local_litert",
        producerConfigurationIdentity = "c".repeat(64),
        producerConfigGeneration = 1L,
        producerPromptIdentity = "policy-distillation-v1",
        producerTemplateIdentity = "policy-distillation-v1",
        producerSchemaIdentity = "policy-candidate-v1",
        createdAtMs = 5L,
        updatedAtMs = 5L,
        lastUsedAtMs = null,
    )

    private fun policyEvidence(ordinal: Int, episode: EpisodeId) = PolicyEvidenceEntity(
        policyId = policyId,
        episodeId = episode.value,
        evidenceKind = "LESSON",
        polarity = LearningPolicyEvidencePolarity.POSITIVE.name,
        quality = 0.9,
        lessonVersion = 1,
        sourceType = "CONVERSATION_MESSAGE",
        sourceId = "message-$ordinal",
        sourceRevision = 1L,
        sourceIntegritySha256 = ordinal.toString(16).repeat(64).take(64),
        createdAtMs = 5L,
    )

    private fun policyRewardEvidence(ordinal: Int, episode: EpisodeId) =
        PolicyRewardEvidenceEntity(
            policyId = policyId,
            episodeId = episode.value,
            rewardSignalId = "reward-signal-v1:$ordinal",
            sourceType = "USER_FEEDBACK",
            sourceId = "feedback-$ordinal",
            sourceRevision = 1L,
            sourceIntegritySha256 = (ordinal + 2).toString(16).repeat(64).take(64),
            createdAtMs = 5L,
        )

    private fun sourceValidity(
        sourceType: String,
        sourceId: String,
        integrity: String,
        eventId: String,
    ) = LearningSourceValidityEntity(
        streamId = streamId,
        scopeKind = scope.kind.name,
        scopeId = scope.storageId,
        sourceType = sourceType,
        sourceId = sourceId,
        sourceRevision = 1L,
        previousSourceRevision = null,
        state = LearningSourceValidityState.VALID.name,
        integritySha256 = integrity,
        invalidationReason = null,
        authorityEventId = eventId,
        replayGeneration = 0L,
        occurredAtMs = 1L,
        updatedAtMs = 1L,
    )

    private fun episode(marker: Char): EpisodeId = requireNotNull(
        EpisodeId.parseOrNull("episode-v1:${marker.toString().repeat(64)}"),
    )
}

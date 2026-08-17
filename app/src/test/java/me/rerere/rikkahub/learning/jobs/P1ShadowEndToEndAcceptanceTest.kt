package me.rerere.rikkahub.learning.jobs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.BackgroundProviderDispatchCallback
import me.rerere.ai.provider.BackgroundRuntimeAttestation
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.rikkahub.data.ai.background.AttestedBackgroundGenerationExecution
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationAuthorizationGate
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationBinder
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationBindingResult
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationClient
import me.rerere.rikkahub.data.ai.background.BackgroundProviderAttemptAuthority
import me.rerere.rikkahub.data.ai.background.BackgroundProviderTerminalOutcome
import me.rerere.rikkahub.data.ai.background.BackgroundProviderUsage
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningProviderKind
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.ResolvedLearningModel
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyCandidateJobOutput
import me.rerere.rikkahub.learning.policy.PolicyDistillationInput
import me.rerere.rikkahub.learning.policy.PolicyDistillationJobHandler
import me.rerere.rikkahub.learning.policy.PolicyDistillationMaterial
import me.rerere.rikkahub.learning.policy.PolicyDistillationPrompt
import me.rerere.rikkahub.learning.policy.PolicyEvidenceAuthorityOutcome
import me.rerere.rikkahub.learning.policy.PolicyEvidenceHandle
import me.rerere.rikkahub.learning.policy.ReasoningPolicyDistiller
import me.rerere.rikkahub.learning.policy.policyApplicableConfigurationGeneration
import me.rerere.rikkahub.learning.policy.policyApplicableConfigurationIdentity
import me.rerere.rikkahub.learning.policy.policyApplicableTemplateIdentity
import me.rerere.rikkahub.learning.reflection.EpisodeLessonJobOutput
import me.rerere.rikkahub.learning.reflection.ReflectionInputBundle
import me.rerere.rikkahub.learning.reflection.ReflectionJobHandler
import me.rerere.rikkahub.learning.reflection.ReflectionJobMaterial
import me.rerere.rikkahub.learning.reflection.ReflectionPrompt
import me.rerere.rikkahub.learning.resources.LearningCancellationCapability
import me.rerere.rikkahub.learning.resources.LearningDeviceConditions
import me.rerere.rikkahub.learning.resources.LearningExecutionClass
import me.rerere.rikkahub.learning.resources.LearningForegroundRegistry
import me.rerere.rikkahub.learning.resources.LearningResourceGovernor
import me.rerere.rikkahub.learning.resources.LearningRouteCapabilities
import me.rerere.rikkahub.learning.resources.LearningSignal
import me.rerere.rikkahub.learning.resources.LearningThermalState
import me.rerere.rikkahub.learning.retrieval.PolicyRetrievalDropReason
import me.rerere.rikkahub.learning.retrieval.PolicyRetrievalRequest
import me.rerere.rikkahub.learning.retrieval.PolicyRetriever
import me.rerere.rikkahub.learning.retrieval.PolicyShadowCandidate
import me.rerere.rikkahub.learning.reward.RewardAuthorityJobHandler
import me.rerere.rikkahub.learning.reward.RewardAuthorityJobMaterial
import me.rerere.rikkahub.learning.reward.RewardAuthorityJobOutput
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.LearningRewardAuthorityOutcome
import me.rerere.rikkahub.learning.storage.LearningRewardDimension
import me.rerere.rikkahub.learning.storage.LearningRewardKnowledge
import me.rerere.rikkahub.learning.storage.LearningRewardSignalEntity
import me.rerere.rikkahub.learning.storage.LearningRewardSignalKind
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 acceptance harness with the production handlers, parser/distiller and provider dispatch
 * fence. Persistence is deliberately a tiny fake here; Room-specific authority joins and crash
 * recovery are covered by the paired instrumentation tests and are never run on a primary phone.
 */
class P1ShadowEndToEndAcceptanceTest {
    private val streamId = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val scope = LearningScope.Assistant(
        Uuid.parse("00000000-0000-4000-8000-000000000002"),
    )
    private val taskSignature = TaskSignatureV1.create(
        LearningTaskClass.INFORMATION,
        LearningLanguageClass.CHINESE,
        LearningModalityClass.TEXT_ONLY,
        emptySet(),
    )
    private val attestation = BackgroundRuntimeAttestation(
        providerRuntimeAbi = "p1-test-runtime-v1",
        sdkAbi = "p1-test-sdk-v1",
        cancellationFenceAbi = "p1-test-cancel-v1",
        artifactSha256 = "9".repeat(64),
        forceCpu = true,
        accelerator = "cpu",
        contextWindowTokens = 16_384,
        topK = 40,
        topP = 0.95,
        temperature = 0.0,
        promptRendererAbi = "p1-test-prompt-v1",
        nativeToolAbi = "p1-test-no-tools-v1",
    )
    private val frozenModel = ResolvedLearningModel(
        providerKind = LearningProviderKind.LOCAL_LITERT,
        providerIdentityDigest = "a".repeat(64),
        modelIdentityDigest = "b".repeat(64),
        configurationDigest = "c".repeat(64),
        route = LearningRouteCapabilities(
            executionClass = LearningExecutionClass.LOCAL_COMPUTE,
            requiresNetwork = false,
            cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
        ),
        runtimeAttestationDigest = attestation.opaqueDigestSha256(),
    )

    @Test
    fun episodeReflectionExplicitRewardDistillationAndShadowRetrievalCloseTheP1Loop() = runBlocking {
        val episodes = listOf(episode('1'), episode('2'))
        val reflectionKeys = episodes.mapIndexed { index, _ -> requestKey(index + 1) }
        val distillationKey = requestKey(3)
        val responses = buildMap {
            episodes.forEachIndexed { index, episode ->
                put(reflectionKeys[index], reflectionResponse(reflectionInput(episode)))
            }
            put(distillationKey, policyResponse())
        }
        val execution = RecordingAttestedExecution(frozenModel, attestation, responses)
        val client = client(execution)
        val reflectionInputs = episodes.associateWith(::reflectionInput)
        val reflectionHandler = ReflectionJobHandler(
            materialResolver = { input ->
                reflectionInputs.entries.singleOrNull {
                    it.key.value == input.sourceEventId
                }
                    ?.value
                    ?.let { ReflectionJobMaterial(it, frozenModel) }
            },
            client = client,
            clockMs = { 100L },
        )

        val lessons = episodes.mapIndexed { index, episode ->
            val authority = RecordingAttemptAuthority(
                reflectionKeys[index],
                attestation.opaqueDigestSha256(),
            )
            val result = reflectionHandler.execute(
                providerInput(
                    jobId = "reflection-${index + 1}",
                    sourceEventId = episode.value,
                    jobType = LearningJobType.REFLECT_EPISODE_V1,
                    promptIdentity = ReflectionPrompt.TEMPLATE_VERSION,
                    outputSchemaIdentity = "episode-lesson-v1",
                    maxOutputTokens = ReflectionPrompt.MAX_OUTPUT_TOKENS.toLong(),
                    maxOutputUtf8Bytes = ReflectionPrompt.MAX_OUTPUT_UTF8_BYTES.toLong(),
                    providerRequestKey = reflectionKeys[index],
                    authority = authority,
                ),
                control(),
            ) as LearningJobHandlerResult.Success<EpisodeLessonJobOutput>
            assertEquals(1, authority.dispatchStarts)
            assertEquals(1, authority.terminals)
            result.output as EpisodeLessonJobOutput.Lesson
        }
        assertEquals(2, lessons.map { it.episodeId }.distinct().size)

        val rewardHandler = RewardAuthorityJobHandler { input ->
            val index = input.sourceEventId.removePrefix("feedback-").toInt()
            val signal = explicitSuccessSignal(index, episodes[index - 1])
            RewardAuthorityJobMaterial(
                signal = signal,
                expectedWindowRevision = 1L,
                signalSetSha256 = index.toString(16).repeat(64).take(64),
                authorityOutcome = LearningRewardAuthorityOutcome.SUCCESS,
            )
        }
        val rewards = episodes.indices.map { zeroBased ->
            val index = zeroBased + 1
            val result = rewardHandler.execute(
                providerFreeInput(
                    jobId = "reward-$index",
                    sourceEventId = "feedback-$index",
                ),
                control(),
            ) as LearningJobHandlerResult.Success<RewardAuthorityJobOutput>
            result.output.also { output ->
                assertEquals(LearningRewardKnowledge.KNOWN.name, output.signal.knowledge)
                assertEquals(1_000, output.signal.valueMilli)
                assertEquals(LearningRewardAuthorityOutcome.SUCCESS, output.authorityOutcome)
            }
        }

        val evidence = lessons.mapIndexed { index, lesson ->
            PolicyEvidenceHandle(
                lessonId = "episode-lesson-v1:${index + 1}",
                episodeId = requireNotNull(EpisodeId.parseOrNull(lesson.episodeId)),
                scope = scope,
                lessonRevision = 1L,
                sourceValid = true,
                authorityOutcome = when (rewards[index].authorityOutcome) {
                    LearningRewardAuthorityOutcome.SUCCESS -> PolicyEvidenceAuthorityOutcome.SUCCESS
                    else -> PolicyEvidenceAuthorityOutcome.UNKNOWN
                },
            )
        }
        val distillationInput = PolicyDistillationInput(
            scope = scope,
            taskSignature = taskSignature,
            evidenceAllowlist = linkedMapOf("E1" to evidence[0], "E2" to evidence[1]),
            toolSchemaAllowlist = emptySet(),
            producerIdentity = frozenModel.providerIdentityDigest,
            modelIdentity = frozenModel.modelIdentityDigest,
            promptVersion = PolicyDistillationPrompt.TEMPLATE_VERSION,
            applicableTemplateIdentity = policyApplicableTemplateIdentity(
                PolicyDistillationPrompt.TEMPLATE_VERSION,
            ),
            applicableConfigurationIdentity = policyApplicableConfigurationIdentity(
                frozenModel.providerIdentityDigest,
                frozenModel.modelIdentityDigest,
            ),
            applicableConfigurationGeneration = policyApplicableConfigurationGeneration(
                policyApplicableConfigurationIdentity(
                    frozenModel.providerIdentityDigest,
                    frozenModel.modelIdentityDigest,
                ),
            ),
        )
        val distillationAuthority = RecordingAttemptAuthority(
            distillationKey,
            attestation.opaqueDigestSha256(),
        )
        val distillation = PolicyDistillationJobHandler(
            materialResolver = {
                PolicyDistillationMaterial(
                    input = distillationInput,
                    payloadJson = """{"schema_version":1,"evidence":["E1","E2"]}""",
                    frozenModel = frozenModel,
                )
            },
            client = client,
            clockMs = { 200L },
        ).execute(
            providerInput(
                jobId = "distill-1",
                sourceEventId = "distillation-source",
                jobType = LearningJobType.DISTILL_POLICY_V1,
                promptIdentity = PolicyDistillationPrompt.TEMPLATE_VERSION,
                outputSchemaIdentity = "policy-candidate-v2",
                maxOutputTokens = PolicyDistillationPrompt.MAX_OUTPUT_TOKENS.toLong(),
                maxOutputUtf8Bytes = ReasoningPolicyDistiller.MAX_OUTPUT_UTF8_BYTES.toLong(),
                providerRequestKey = distillationKey,
                authority = distillationAuthority,
            ),
            control(),
        ) as LearningJobHandlerResult.Success<PolicyCandidateJobOutput>
        val candidate = (distillation.output as PolicyCandidateJobOutput.Candidate).draft
        assertEquals(2, candidate.distinctEpisodeSupport)
        assertEquals(1, distillationAuthority.dispatchStarts)
        assertEquals(1, distillationAuthority.terminals)
        assertEquals(
            mapOf(reflectionKeys[0] to 1, reflectionKeys[1] to 1, distillationKey to 1),
            execution.callsByRequestKey,
        )

        val shadowCandidate = PolicyShadowCandidate(
            policyId = candidate.candidateId,
            scope = candidate.scope,
            taskSignature = candidate.taskSignature,
            status = LearningPolicyStatus.CANDIDATE,
            artifactHash = candidate.artifactHash,
            sourceValid = true,
            toolSchemaValid = true,
            searchableText = listOf(
                candidate.trigger.value,
                candidate.procedure.value,
                candidate.verification.value,
            ).joinToString(" "),
            estimatedTokens = 64,
            updatedAtMs = 200L,
        )
        val retriever = PolicyRetriever(ByteArray(32) { 7 })
        val request = PolicyRetrievalRequest(scope, taskSignature, "bounded task")
        assertEquals(
            listOf(candidate.candidateId),
            retriever.retrieve(request, listOf(shadowCandidate)).hits.map {
                it.candidate.policyId
            },
        )

        val invalidated = retriever.retrieve(
            request,
            listOf(shadowCandidate.copy(sourceValid = false)),
        )
        assertTrue(invalidated.hits.isEmpty())
        assertEquals(1, invalidated.trace.dropReasonCounts[PolicyRetrievalDropReason.SOURCE_STALE])
    }

    @Test
    fun policyShadowObservationIsSeparatedFromReviewedPolicyInjection() {
        val root = appRoot()
        val generation = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt"),
        )
        val shadowBlock = generation.substringAfter("// Stage D is content-free observation only")
            .substringBefore("val policyRetrieval = if")
        listOf(
            "PolicyRetriever",
            "PolicyRetrievalHit",
            "PolicyShadowCandidate",
        ).forEach { symbol ->
            assertFalse("P1 shadow implementation entered GenerationHandler: $symbol", symbol in generation)
        }
        listOf("LearnedPolicySource", "compileRecallPrompt", "createSystemPromptLayout").forEach { symbol ->
            assertFalse("Stage-D observation affected provider projection: $symbol", symbol in shadowBlock)
        }
        assertTrue("Stage D must discard retrieval content", "The result never enters Recall" in shadowBlock)
        assertTrue(
            "Stage E must remain an independently reviewed assistant opt-in",
            "assistantPolicyOptIn = assistant.reviewedPolicyInjectionEnabled" in generation,
        )
        assertTrue(
            "Learned projection must fail closed to the already-prepared baseline",
            "if (policySelected) checkNotNull(learnedPrepared) else baselinePrepared" in generation,
        )
    }

    private fun client(execution: RecordingAttestedExecution): BackgroundGenerationClient =
        BackgroundGenerationClient(
            governor = LearningResourceGovernor(
                foregroundRegistry = LearningForegroundRegistry(),
                conditionsSource = { allowedConditions() },
                admissionWaitMs = 250L,
            ),
            binder = BackgroundGenerationBinder {
                BackgroundGenerationBindingResult.Bound(execution)
            },
            authorizationGate = BackgroundGenerationAuthorizationGate {
                it == execution.frozenModel
            },
        )

    private fun providerInput(
        jobId: String,
        sourceEventId: String,
        jobType: LearningJobType,
        promptIdentity: String,
        outputSchemaIdentity: String,
        maxOutputTokens: Long,
        maxOutputUtf8Bytes: Long,
        providerRequestKey: String,
        authority: BackgroundProviderAttemptAuthority,
    ): LearningJobExecutionInputV1 {
        val spec = LearningJobExecutionSpecV1(
            jobType = jobType,
            jobSchemaVersion = 1,
            algorithmIdentity = "p1-shadow-e2e-v1",
            promptIdentity = promptIdentity,
            providerKindIdentity = LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode,
            modelIdentity = frozenModel.modelIdentityDigest,
            providerIdentity = frozenModel.providerIdentityDigest,
            providerConfigurationIdentity = frozenModel.configurationDigest,
            providerConfigGeneration = 1L,
            sourceSchemaIdentity = "p1-shadow-source-v1",
            toolsetIdentity = "no-tools-v1",
            outputSchemaIdentity = outputSchemaIdentity,
        )
        val receipt = LearningProviderManifestReceipt(
            cohortId = "cohort-$jobId",
            providerKind = LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode,
            providerIdentitySha256 = frozenModel.providerIdentityDigest,
            modelIdentitySha256 = frozenModel.modelIdentityDigest,
            configurationIdentitySha256 = frozenModel.configurationDigest,
            configurationGeneration = 1L,
            manifestSchemaVersion = 1,
            requestHmacSha256 = "d".repeat(64),
            inputIdentitySha256 = "e".repeat(64),
            runtimeAttestationSha256 = attestation.opaqueDigestSha256(),
            redactionPolicyIdentity = "learning-redaction-v1",
            fieldCategoriesIdentity = "bounded-learning-fields-v1",
            tokenEstimatorIdentity = "p1-test-token-estimator-v1",
            providerRequestKey = providerRequestKey,
            inputUtf8Bytes = 32L,
            maxInputUtf8Bytes = 128L * 1_024L,
            estimatedInputTokens = 16L,
            maxOutputTokens = maxOutputTokens,
            maxOutputUtf8Bytes = maxOutputUtf8Bytes,
            maxProviderCalls = 1,
            maxCostMicros = 0L,
            timeoutMs = 120_000L,
            frozenAtMs = 1L,
        )
        return LearningJobExecutionInputV1(
            jobId = jobId,
            sourceEventId = sourceEventId,
            streamId = streamId.toString(),
            scopeKind = scope.kind.name,
            scopeId = scope.storageId,
            replayGeneration = 0L,
            createdAtMs = 1L,
            attempt = 1,
            stableProviderIdempotencyKey = providerRequestKey,
            executionSpec = spec,
            providerAttemptAuthority = authority,
            providerManifestReceipt = receipt,
        )
    }

    private fun providerFreeInput(jobId: String, sourceEventId: String) =
        LearningJobExecutionInputV1(
            jobId = jobId,
            sourceEventId = sourceEventId,
            streamId = streamId.toString(),
            scopeKind = scope.kind.name,
            scopeId = scope.storageId,
            replayGeneration = 0L,
            createdAtMs = 1L,
            attempt = 1,
            stableProviderIdempotencyKey = "provider-free:$jobId",
            executionSpec = LearningJobExecutionSpecV1(
                jobType = LearningJobType.APPLY_REWARD_AUTHORITY_V1,
                jobSchemaVersion = 1,
                algorithmIdentity = "reward-authority-v1",
                promptIdentity = "no-provider-prompt-v1",
                providerKindIdentity = LearningJobProviderKindIdentity.NONE.wireCode,
                modelIdentity = NO_PROVIDER_MODEL_IDENTITY,
                providerIdentity = NO_PROVIDER_IDENTITY,
                providerConfigurationIdentity = NO_PROVIDER_CONFIGURATION_IDENTITY,
                providerConfigGeneration = 0L,
                sourceSchemaIdentity = "user-feedback-v3",
                toolsetIdentity = "authority-event-only-v1",
                outputSchemaIdentity = "reward-authority-output-v1",
            ),
        )

    private fun reflectionInput(episode: EpisodeId): ReflectionInputBundle =
        ReflectionInputBundle(
            inputId = "reflection-input-v2:" + episode.value.substringAfter(':'),
            episodeId = episode,
            allowedEvidence = linkedMapOf(
                "E1" to LearningSourceRef(
                    sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
                    sourceId = "message-${episode.value.takeLast(1)}",
                    sourceRevision = 1L,
                    missingRevisionReason = null,
                    databaseStreamId = streamId,
                    scope = scope,
                    occurredAtMs = 1L,
                ),
            ),
            payloadJson = """{"schema_version":2,"evidence":["E1"]}""",
        )

    private fun reflectionResponse(input: ReflectionInputBundle): String = """
        {
          "schema_version":1,
          "input_id":"${input.inputId}",
          "op":"LESSON",
          "lesson_type":"SUCCESS_PATTERN",
          "trigger":"Use this for a matching bounded task.",
          "observation":"The checked execution reached its expected outcome.",
          "lesson":"Check prerequisites before performing the bounded steps.",
          "boundary":"Apply only inside the current assistant scope.",
          "evidence_aliases":["E1"],
          "quality":0.9
        }
    """.trimIndent()

    private fun policyResponse(): String = """
        {
          "schema_version":2,
          "op":"CANDIDATE",
          "type":"PROCEDURE",
          "trigger":"Use this for a matching bounded task.",
          "procedure":"Check prerequisites, then perform the bounded steps.",
          "verification":"Verify the structured outcome before finishing.",
          "boundary":"Apply only inside the current assistant scope.",
          "failure_mode":"Abstain when the evidence is incomplete.",
          "evidence_ids":["E1","E2"],
          "tool_schema_fingerprints":[]
        }
    """.trimIndent()

    private fun explicitSuccessSignal(index: Int, episode: EpisodeId) =
        LearningRewardSignalEntity(
            id = "reward-signal-v1:$index",
            episodeId = episode.value,
            streamId = streamId.toString(),
            replayGeneration = 0L,
            scopeKind = scope.kind.name,
            scopeId = scope.storageId,
            authorityEventId = "learning-event-v1:feedback-$index",
            sourceType = "USER_FEEDBACK",
            sourceId = "feedback-$index",
            sourceRevision = 1L,
            sourceIntegritySha256 = index.toString(16).repeat(64).take(64),
            dimension = LearningRewardDimension.USER.name,
            signalKind = LearningRewardSignalKind.EXPLICIT_USER_FEEDBACK.name,
            knowledge = LearningRewardKnowledge.KNOWN.name,
            valueMilli = 1_000,
            unknownReason = null,
            occurredAtMs = 10L,
            createdAtMs = 10L,
        )

    private fun episode(marker: Char): EpisodeId = requireNotNull(
        EpisodeId.parseOrNull("episode-v1:${marker.toString().repeat(64)}"),
    )

    private fun requestKey(ordinal: Int): String =
        "learning-provider-v1:" + ordinal.toString(16).repeat(64).take(64)

    private fun control() = LearningJobExecutionControl(Long.MAX_VALUE) { 0L }

    private fun allowedConditions() = LearningDeviceConditions(
        userAllowsBackgroundWork = true,
        batterySaver = LearningSignal.NO,
        thermalState = LearningThermalState.NOMINAL,
        networkValidated = LearningSignal.YES,
        networkMetered = LearningSignal.NO,
        userAllowsMeteredNetwork = false,
    )

    private fun appRoot(): Path {
        val working = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(working.resolve("app/src/main"))) working.resolve("app") else working
    }

    private class RecordingAttemptAuthority(
        override val stableProviderIdempotencyKey: String,
        override val expectedRuntimeAttestationSha256: String,
    ) : BackgroundProviderAttemptAuthority {
        var dispatchStarts = 0
            private set
        var terminals = 0
            private set

        override suspend fun markDispatchStarted(
            observedRuntimeAttestationSha256: String,
        ): Boolean {
            if (observedRuntimeAttestationSha256 != expectedRuntimeAttestationSha256) return false
            dispatchStarts += 1
            return dispatchStarts == 1
        }

        override suspend fun releaseUndispatched(): Boolean = false

        override suspend fun markTerminal(
            outcome: BackgroundProviderTerminalOutcome,
            usage: BackgroundProviderUsage,
        ): Boolean {
            if (dispatchStarts != 1) return false
            terminals += 1
            return terminals == 1
        }
    }

    private class RecordingAttestedExecution(
        override val frozenModel: ResolvedLearningModel,
        private val attestation: BackgroundRuntimeAttestation,
        private val responses: Map<String, String>,
    ) : AttestedBackgroundGenerationExecution {
        override val model: Model = Model(
            modelId = "p1-fake-chat",
            userContextWindowTokens = attestation.contextWindowTokens,
        )
        val callsByRequestKey = linkedMapOf<String, Int>()

        override suspend fun resolveTrustedContextWindowTokens(): Int =
            attestation.contextWindowTokens

        override suspend fun streamText(
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = streamText(
            messages,
            params,
            BackgroundProviderDispatchCallback.NO_OP,
        )

        override suspend fun streamText(
            messages: List<UIMessage>,
            params: TextGenerationParams,
            onDispatchStarted: BackgroundProviderDispatchCallback,
        ): Flow<MessageChunk> = flow {
            val key = requireNotNull(params.stableProviderIdempotencyKey)
            onDispatchStarted.onDispatchStarted(attestation)
            callsByRequestKey[key] = (callsByRequestKey[key] ?: 0) + 1
            emit(textChunk(responses.getValue(key)))
        }

        private fun textChunk(text: String) = MessageChunk(
            id = "p1-shadow-e2e",
            model = model.modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage.assistant(text),
                    message = null,
                    finishReason = "stop",
                ),
            ),
            terminal = GenerationTerminal(
                terminalSeen = true,
                category = FinishCategory.STOP,
            ),
        )
    }
}

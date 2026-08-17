package me.rerere.rikkahub.learning.jobs

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationClient
import me.rerere.rikkahub.data.ai.background.SettingsBackedBackgroundGenerationHost
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.learning.episode.EpisodeAssemblyMaterialResolver
import me.rerere.rikkahub.learning.episode.EpisodeAssemblyMutation
import me.rerere.rikkahub.learning.episode.EpisodeAuthorityAnchor
import me.rerere.rikkahub.learning.episode.EpisodeBoundaryReason
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.episode.EpisodeSnapshot
import me.rerere.rikkahub.learning.episode.LearningCompletionKind
import me.rerere.rikkahub.learning.episode.LearningEpisodeStatus
import me.rerere.rikkahub.learning.exposure.PolicyExposureOutcomeLinker
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningModelResolution
import me.rerere.rikkahub.learning.model.LearningProviderKind
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.MissingSourceRevisionReason
import me.rerere.rikkahub.learning.model.ResolvedLearningModel
import me.rerere.rikkahub.learning.policy.PolicyCandidateIdFactory
import me.rerere.rikkahub.learning.policy.PolicyDistillationInput
import me.rerere.rikkahub.learning.policy.PolicyDistillationMaterial
import me.rerere.rikkahub.learning.policy.PolicyDistillationMaterialResolver
import me.rerere.rikkahub.learning.policy.PolicyDistillationPrompt
import me.rerere.rikkahub.learning.policy.PolicyEvidenceAuthorityOutcome
import me.rerere.rikkahub.learning.policy.PolicyEvidenceHandle
import me.rerere.rikkahub.learning.policy.PolicyProviderInputManifest
import me.rerere.rikkahub.learning.policy.POLICY_PROVIDER_INPUT_IDENTITY_PREFIX
import me.rerere.rikkahub.learning.policy.runtime.NoOpPolicyOutcomeLinkedObserver
import me.rerere.rikkahub.learning.policy.runtime.NoOpPolicyOutcomeLinkedObserverFactory
import me.rerere.rikkahub.learning.policy.runtime.PolicyOutcomeLinkedObserver
import me.rerere.rikkahub.learning.policy.runtime.PolicyOutcomeLinkedObserverFactory
import me.rerere.rikkahub.learning.reflection.ReflectionInputComposeResult
import me.rerere.rikkahub.learning.reflection.ReflectionInputBundle
import me.rerere.rikkahub.learning.reflection.ReflectionInputComposer
import me.rerere.rikkahub.learning.reflection.ReflectionJobMaterial
import me.rerere.rikkahub.learning.reflection.ReflectionJobMaterialResolver
import me.rerere.rikkahub.learning.reflection.ReflectionPrompt
import me.rerere.rikkahub.learning.resources.LearningCancellationCapability
import me.rerere.rikkahub.learning.resources.LearningExecutionClass
import me.rerere.rikkahub.learning.resources.LearningRouteCapabilities
import me.rerere.rikkahub.learning.reward.RewardComponent
import me.rerere.rikkahub.learning.reward.RewardJobMaterial
import me.rerere.rikkahub.learning.reward.RewardJobMaterialResolver
import me.rerere.rikkahub.learning.reward.RewardSignal
import me.rerere.rikkahub.learning.reward.RewardAuthorityJobMaterial
import me.rerere.rikkahub.learning.reward.RewardAuthorityJobMaterialResolver
import me.rerere.rikkahub.learning.reward.RewardUnknownReason
import me.rerere.rikkahub.learning.reward.RewardWindow
import me.rerere.rikkahub.learning.reward.RewardWindowState
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeBoundaryReason
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningEpisodeLessonEntity
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.LearningLessonState
import me.rerere.rikkahub.learning.storage.LearningRewardKnowledge
import me.rerere.rikkahub.learning.storage.LearningRewardAuthorityOutcome
import me.rerere.rikkahub.learning.storage.LearningRewardDimension
import me.rerere.rikkahub.learning.storage.LearningRewardSignalEntity
import me.rerere.rikkahub.learning.storage.LearningRewardSignalKind
import me.rerere.rikkahub.learning.storage.LearningRewardWindowEntity
import me.rerere.rikkahub.learning.storage.LearningRewardWindowState
import me.rerere.rikkahub.learning.storage.LearningProviderConfigCohortEntity
import me.rerere.rikkahub.learning.storage.LearningProviderJobManifestEntity
import me.rerere.rikkahub.learning.storage.LearningSourceValidityState
import me.rerere.rikkahub.learning.storage.LearningTraceFeatureEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.task.RuntimeTaskSignatureClassifier
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.learning.trace.SanitizedTraceSummary
import me.rerere.rikkahub.learning.trace.TraceActionType
import me.rerere.rikkahub.learning.trace.TraceFeature
import me.rerere.rikkahub.learning.trace.TraceMetric
import me.rerere.rikkahub.learning.trace.TraceOutcomeClass
import me.rerere.rikkahub.learning.trace.TraceSanitizationResult
import me.rerere.rikkahub.learning.trace.TraceSanitizer
import me.rerere.rikkahub.learning.trace.TraceUnknownReason
import me.rerere.rikkahub.learning.trace.ExecutionTraceJobMaterial
import me.rerere.rikkahub.learning.trace.ExecutionTraceJobMaterialResolver
import kotlin.uuid.Uuid

/** Dispatch facts required at the exact provider boundary; no editable readiness booleans. */
internal data class P1ProviderExecutionCapabilities(
    /** LOCAL runtime digest or REMOTE official transport/request digest. */
    val runtimeAttestationSha256: String?,
    val exactManifestValidated: Boolean,
    val durableAttemptAuthorityPresent: Boolean,
    val exactRemoteConsent: Boolean = false,
) {
    val dispatchAttestationSha256: String?
        get() = runtimeAttestationSha256
}

internal fun isP1ProviderExecutionAuthorized(
    providerKind: LearningProviderKind,
    capabilities: P1ProviderExecutionCapabilities,
): Boolean = when (providerKind) {
    LearningProviderKind.LOCAL_LITERT ->
        capabilities.dispatchAttestationSha256?.matches(LOWER_SHA256) == true &&
            capabilities.exactManifestValidated && capabilities.durableAttemptAuthorityPresent
    LearningProviderKind.REMOTE ->
        capabilities.dispatchAttestationSha256?.matches(LOWER_SHA256) == true &&
            capabilities.exactManifestValidated && capabilities.durableAttemptAuthorityPresent &&
            capabilities.exactRemoteConsent
    LearningProviderKind.AICORE -> false
}

internal data class P1ExactMessageAuthority(
    val sourceRevision: Long,
    val payloadIntegritySha256: String,
    val occurredAtMs: Long,
)

internal interface P1MainSourceAuthorityReader {
    suspend fun findExactActiveMessage(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        messageId: String,
        sourceRevision: Long,
    ): P1ExactMessageAuthority?

    suspend fun isExactActiveConversation(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        sourceRevision: Long,
    ): Boolean
}

/** Main DB stays behind this narrow read adapter; no DAO or entity enters the Learning graph. */
internal class RoomP1MainSourceAuthorityReader(
    private val database: AppDatabase,
) : P1MainSourceAuthorityReader {
    override suspend fun findExactActiveMessage(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        messageId: String,
        sourceRevision: Long,
    ): P1ExactMessageAuthority? {
        val row = database.learningSourceAuthorityDao().findMessage(
            scopeKind,
            scopeId,
            messageId,
        ) ?: return null
        if (
            row.conversationId != conversationId ||
            row.sourceRevision != sourceRevision ||
            row.sourceState != "ACTIVE"
        ) return null
        return P1ExactMessageAuthority(
            sourceRevision = row.sourceRevision,
            payloadIntegritySha256 = row.payloadIntegritySha256 ?: return null,
            occurredAtMs = row.occurredAtMs,
        )
    }

    override suspend fun isExactActiveConversation(
        scopeKind: String,
        scopeId: String,
        conversationId: String,
        sourceRevision: Long,
    ): Boolean {
        val row = database.learningSourceAuthorityDao().findConversation(
            scopeKind,
            scopeId,
            conversationId,
        ) ?: return false
        return row.sourceRevision == sourceRevision && row.sourceState == "ACTIVE"
    }
}

internal class P1RuntimeFeatureGate(
    private val flags: LearningFeatureFlagSource,
) {
    fun captureEnabled(): Boolean = current()?.let { it.capture && it.jobs } == true
    fun reflectionEnabled(): Boolean = current()?.reflectionShadow == true
    fun policyEnabled(): Boolean = current()?.policyCandidate == true
    fun remoteReflectionAllowed(): Boolean = current()?.allowRemoteReflection == true

    private fun current() = runCatching { flags.current() }.getOrNull()
        ?.takeIf { it.isValid }
        ?.effective
}

internal class P1LearningModelClaimSource(
    private val host: SettingsBackedBackgroundGenerationHost,
    private val gate: P1RuntimeFeatureGate,
) {
    suspend fun resolve(): ResolvedLearningModel? {
        val model = (
            host.resolveSingleAuthorizedForAttestedClaim() as? LearningModelResolution.Resolved
        )
            ?.model ?: return null
        // Exact manifest and attempt authority are proved after recovery/lease. Remote additionally
        // requires the exact disclosed official provider/model pair at this planning boundary.
        return model.takeIf { candidate ->
            when (candidate.providerKind) {
                LearningProviderKind.LOCAL_LITERT ->
                    candidate.runtimeAttestationDigest?.matches(LOWER_SHA256) == true
                LearningProviderKind.REMOTE -> gate.remoteReflectionAllowed() &&
                    candidate.runtimeAttestationDigest == null &&
                    host.remoteReflectionDisclosureTarget()?.let { target ->
                        target.providerIdentityDigest == candidate.providerIdentityDigest &&
                            target.modelIdentityDigest == candidate.modelIdentityDigest
                    } == true
                LearningProviderKind.AICORE -> false
            }
        }
    }

    fun exactRemoteConsent(model: ResolvedLearningModel): Boolean =
        model.providerKind == LearningProviderKind.REMOTE && gate.remoteReflectionAllowed() &&
            host.remoteReflectionDisclosureTarget()?.let { target ->
                target.providerIdentityDigest == model.providerIdentityDigest &&
                    target.modelIdentityDigest == model.modelIdentityDigest
            } == true

    fun expectedDispatchAttestationSha256(
        model: ResolvedLearningModel,
        templateVersion: String,
        inputIdentitySha256: String,
        providerRequestKey: String,
        maxOutputTokens: Int,
    ): String? = when (model.providerKind) {
        LearningProviderKind.LOCAL_LITERT -> model.runtimeAttestationDigest
            ?.takeIf(LOWER_SHA256::matches)
        LearningProviderKind.REMOTE -> if (exactRemoteConsent(model)) {
            host.expectedRemoteDispatchAttestationSha256(
                frozenModel = model,
                templateVersion = templateVersion,
                inputIdentitySha256 = inputIdentitySha256,
                providerRequestKey = providerRequestKey,
                maxOutputTokens = maxOutputTokens,
            )?.takeIf(LOWER_SHA256::matches)
        } else {
            null
        }
        LearningProviderKind.AICORE -> null
    }

    fun matchesExactCurrentConsentAndDispatch(
        model: ResolvedLearningModel,
        templateVersion: String,
        inputIdentitySha256: String,
        providerRequestKey: String,
        maxOutputTokens: Int,
        expectedDispatchAttestationSha256: String,
    ): Boolean {
        if (!expectedDispatchAttestationSha256.matches(LOWER_SHA256)) return false
        return expectedDispatchAttestationSha256(
            model = model,
            templateVersion = templateVersion,
            inputIdentitySha256 = inputIdentitySha256,
            providerRequestKey = providerRequestKey,
            maxOutputTokens = maxOutputTokens,
        )?.let { observed ->
            constantTimeShaEquals(observed, expectedDispatchAttestationSha256)
        } == true
    }
}

internal class RoomEpisodeAssemblyMaterialResolver(
    private val database: LearningDatabase,
    private val mainSources: P1MainSourceAuthorityReader,
    private val gate: P1RuntimeFeatureGate,
) : EpisodeAssemblyMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): EpisodeAssemblyMutation? {
        if (!gate.captureEnabled()) throw P1LearningConfigurationUnavailableException()
        if (input.executionSpec.jobType != LearningJobType.ASSEMBLE_EPISODE_SHADOW) return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (!event.matches(input) || event.eventSchemaVersion != P1_EVENT_SCHEMA_VERSION) return null
        if (event.eventTypeCode == "COMMAND_ADMITTED") {
            val root = validateRootEvent(event) ?: return null
            val anchor = root.toEpisodeAnchor() ?: return null
            return EpisodeAssemblyMutation.Admit(
                authority = anchor,
                taskSignature = defaultTaskSignature(),
                occurredAtMs = requireNotNull(root.occurredAtMs),
            )
        }
        if (event.eventTypeCode !in setOf("COMMAND_WAITING_APPROVAL", "COMMAND_TERMINAL")) {
            return null
        }
        val completionKind = LearningCompletionKind.parseOrNull(event.completionKind) ?: return null
        val root = validateRootEvent(event) ?: return null
        val anchor = event.toEpisodeAnchor() ?: return null
        val currentEntity = database.episodeDao().findEpisodeByBoundary(
            input.streamId,
            input.replayGeneration,
            requireNotNull(event.lineageId),
            requireNotNull(event.branchAnchorMessageId),
        )
        val traceMaterial = event.toTraceMaterial(anchor.episodeId)
        return EpisodeAssemblyMutation.Complete(
            current = currentEntity?.toDomainSnapshot(),
            authority = anchor,
            taskSignature = currentEntity?.let { TaskSignatureV1.parseOrNull(it.taskSignature) }
                ?: defaultTaskSignature(),
            startedAtMs = requireNotNull(root.occurredAtMs),
            completionKind = completionKind,
            terminalStateCode = event.terminalState,
            occurredAtMs = requireNotNull(event.occurredAtMs),
            traceFeatures = traceMaterial?.let { listOf(it.first) }.orEmpty(),
            sourceIntegrityByRef = traceMaterial?.let { mapOf(it.first.sources.single() to it.second) }
                .orEmpty(),
        )
    }

    private suspend fun validateRootEvent(
        event: LearningInboxEventEntity,
    ): LearningInboxEventEntity? {
        val lineageId = event.lineageId ?: return null
        val roots = database.inboxDao().findRootAdmissionCandidates(
            event.streamId,
            event.replayGeneration,
            lineageId,
        )
        val root = roots.singleOrNull()?.takeIf { root ->
            root.commandId == lineageId &&
                root.parentCommandId == null &&
                root.scopeKind == event.scopeKind &&
                root.scopeId == event.scopeId &&
                root.conversationId == event.conversationId &&
                root.branchAnchorMessageId == event.branchAnchorMessageId &&
                root.branchAnchorMessageRevision == event.branchAnchorMessageRevision
        } ?: return null
        if (!mainSources.isExactActiveConversation(
                scopeKind = requireNotNull(event.scopeKind),
                scopeId = requireNotNull(event.scopeId),
                conversationId = requireNotNull(event.conversationId),
                sourceRevision = requireNotNull(event.conversationSourceRevision),
            )
        ) return null
        return root
    }

    private suspend fun LearningInboxEventEntity.toTraceMaterial(
        episodeId: EpisodeId,
    ): Pair<TraceFeature, String>? {
        val exactMessageId = messageId ?: branchAnchorMessageId ?: return null
        val exactRevision = messageRevision ?: branchAnchorMessageRevision ?: return null
        val exact = mainSources.findExactActiveMessage(
            scopeKind = requireNotNull(scopeKind),
            scopeId = requireNotNull(scopeId),
            conversationId = requireNotNull(conversationId),
            messageId = exactMessageId,
            sourceRevision = exactRevision,
        ) ?: return null
        val scope = LearningScope.parseOrNull(requireNotNull(scopeKind), requireNotNull(scopeId))
            ?: return null
        val source = LearningSourceRef(
            sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
            sourceId = exactMessageId,
            sourceRevision = exactRevision,
            missingRevisionReason = null,
            databaseStreamId = Uuid.parse(streamId),
            scope = scope,
            occurredAtMs = exact.occurredAtMs,
        )
        val censored = completionKind == LearningCompletionKind.CENSORED_CANCELLED.name
        val outcome = when {
            censored -> TraceOutcomeClass.CENSORED
            completionKind == LearningCompletionKind.GENERATION_FINAL_SAVED.name &&
                terminalState == "COMPLETED" -> TraceOutcomeClass.SUCCESS
            terminalState == "FAILED" -> TraceOutcomeClass.FAILURE
            eventTypeCode == "COMMAND_WAITING_APPROVAL" -> TraceOutcomeClass.UNKNOWN
            else -> TraceOutcomeClass.UNKNOWN
        }
        return TraceFeature(
            episodeId = episodeId,
            sequence = outboxSeq,
            sources = listOf(source),
            actionType = if (eventTypeCode == "COMMAND_WAITING_APPROVAL") {
                TraceActionType.APPROVAL
            } else {
                TraceActionType.COMMAND
            },
            canonicalActionName = null,
            toolSchemaFingerprint = null,
            outcomeClass = outcome,
            errorCode = if (terminalState == "FAILED") "COMMAND_FAILED" else null,
            stateSummary = null,
            observationSummary = null,
            inputTokens = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            outputTokens = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            toolCallCount = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            retryCount = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            durationMs = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            producerIdentity = "trace-feature-v1",
            quality = null,
            createdAtMs = requireNotNull(occurredAtMs),
        ) to exact.payloadIntegritySha256
    }
}

internal class RoomExecutionTraceJobMaterialResolver(
    private val database: LearningDatabase,
    private val gate: P1RuntimeFeatureGate,
) : ExecutionTraceJobMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): ExecutionTraceJobMaterial? {
        if (!gate.captureEnabled()) throw P1LearningConfigurationUnavailableException()
        if (input.executionSpec.jobType != LearningJobType.RECONCILE_SOURCE) return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (
            !event.matches(input) ||
            event.eventTypeCode != "EXECUTION_TERMINAL" ||
            event.eventSchemaVersion != P1_EVENT_SCHEMA_VERSION
        ) return null
        val conversationId = event.conversationId ?: return null
        val commandId = event.commandId ?: return null
        val episodes = database.episodeDao().findEpisodesByCommandAuthority(
            input.streamId,
            input.replayGeneration,
            input.scopeKind,
            input.scopeId,
            conversationId,
            commandId,
        )
        if (episodes.isEmpty()) throw IllegalStateException("execution_episode_not_ready")
        val episode = episodes.singleOrNull() ?: return null
        val sourceRevision = event.sourceRevision ?: return null
        val scope = LearningScope.parseOrNull(input.scopeKind, input.scopeId) ?: return null
        val source = LearningSourceRef(
            sourceKind = LearningSourceKind.EXECUTION_EVENT,
            sourceId = event.sourceId ?: return null,
            // Execution retention has no atomic invalidation writer. Preserve the redacted
            // observation, but mark it ineligible for durable lesson/reward/policy support.
            sourceRevision = null,
            missingRevisionReason = MissingSourceRevisionReason.RETENTION_GAP,
            databaseStreamId = Uuid.parse(input.streamId),
            scope = scope,
            occurredAtMs = event.occurredAtMs ?: return null,
        )
        val outcome = when (event.terminalState) {
            "SUCCEEDED" -> TraceOutcomeClass.SUCCESS
            "FAILED" -> TraceOutcomeClass.FAILURE
            "CANCELLED" -> TraceOutcomeClass.CANCELLED
            "TIMED_OUT" -> TraceOutcomeClass.TIMEOUT
            else -> TraceOutcomeClass.UNKNOWN
        }
        val feature = TraceFeature(
            episodeId = EpisodeId.parseOrNull(episode.id) ?: return null,
            sequence = event.outboxSeq,
            sources = listOf(source),
            actionType = TraceActionType.TOOL,
            canonicalActionName = event.toolName ?: return null,
            toolSchemaFingerprint = event.toolSchemaFingerprint ?: return null,
            outcomeClass = outcome,
            errorCode = if (outcome == TraceOutcomeClass.FAILURE) "TOOL_EXECUTION_FAILED" else null,
            stateSummary = null,
            observationSummary = null,
            inputTokens = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            outputTokens = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            toolCallCount = TraceMetric.Known(1),
            retryCount = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            durationMs = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
            producerIdentity = "execution-trace-v1",
            quality = null,
            createdAtMs = event.occurredAtMs,
        )
        val integrity = LearningCanonicalId.digest(
            "execution-trace-integrity-v1",
            listOf(
                event.eventId,
                requireNotNull(event.sourceId),
                sourceRevision.toString(),
                requireNotNull(event.executionId),
                requireNotNull(event.toolCallId),
                requireNotNull(event.toolName),
                requireNotNull(event.toolSchemaFingerprint),
                event.terminalState,
            ),
        )
        return ExecutionTraceJobMaterial(feature, integrity)
    }
}

internal class RoomReflectionJobMaterialResolver(
    private val database: LearningDatabase,
    private val gate: P1RuntimeFeatureGate,
    private val manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
    private val modelClaims: P1LearningModelClaimSource,
) : ReflectionJobMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): ReflectionJobMaterial? {
        if (!gate.reflectionEnabled()) throw P1LearningConfigurationUnavailableException()
        if (input.executionSpec.jobType != LearningJobType.REFLECT_EPISODE_V1) return null
        if (
            input.executionSpec.algorithmIdentity != REFLECTION_ALGORITHM_IDENTITY ||
            input.executionSpec.promptIdentity != ReflectionPrompt.TEMPLATE_VERSION ||
            input.executionSpec.outputSchemaIdentity != "episode-lesson-v1"
        ) return null
        val providerEnvelope = database.findExactProviderEnvelope(input) ?: return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (
            !event.matches(input) ||
            event.eventTypeCode != "COMMAND_TERMINAL" ||
            event.eventSchemaVersion != P1_EVENT_SCHEMA_VERSION
        ) return null
        val episode = event.findEpisode(database) ?: return null
        if (episode.status !in REFLECTABLE_EPISODE_STATES) return null
        val composed = database.composeReflectionInput(
            episode,
            providerEnvelope.manifest.frozenAtMs,
        ) ?: return null
        if (input.executionSpec.sourceSchemaIdentity != composed.inputId) return null
        val inputIdentity = composed.inputId.substringAfterLast(':')
        val inputBytes = ReflectionPrompt.create(composed).totalUtf8Bytes.toLong()
        return ReflectionJobMaterial(
            input = composed,
            frozenModel = providerEnvelope.validateAndResolve(
                input = input,
                gate = gate,
                modelClaims = modelClaims,
                manifestKeyer = manifestKeyer,
                rebuiltInputIdentity = inputIdentity,
                rebuiltInputUtf8Bytes = inputBytes,
                expectedMaxOutputTokens = ReflectionPrompt.MAX_OUTPUT_TOKENS.toLong(),
                expectedMaxOutputUtf8Bytes = ReflectionPrompt.MAX_OUTPUT_UTF8_BYTES.toLong(),
                expectedTimeoutMs = P1_PROVIDER_TIMEOUT_MS,
            ) ?: return null,
        )
    }
}

/** Rebuilds exactly the provider-bound Reflection bytes and their source manifest. */
private suspend fun LearningDatabase.composeReflectionInput(
    episode: LearningEpisodeEntity,
    frozenAtMs: Long = Long.MAX_VALUE,
): ReflectionInputBundle? {
    val expectedExecutionTraces = inboxDao().countExecutionTerminalsForEpisode(
        episode.streamId,
        episode.replayGeneration,
        episode.scopeKind,
        episode.scopeId,
        episode.conversationId,
        episode.rootCommandId,
        episode.finalCommandId ?: episode.rootCommandId,
    )
    if (episodeDao().countExecutionTraceSources(episode.id) != expectedExecutionTraces) {
        throw IllegalStateException("execution_traces_not_ready")
    }
    val traceRows = episodeDao().listTrace(episode.id, MAX_REFLECTION_TRACE_ROWS)
    if (
        traceRows.isEmpty() ||
        traceRows.any { row -> row.createdAtMs > frozenAtMs } ||
        traceRows.any { row ->
            row.sourceType == LearningSourceKind.CONVERSATION_MESSAGE.name &&
                !row.hasExactValidSource(this, episode)
        } ||
        traceRows.none { row ->
            row.sourceType == LearningSourceKind.CONVERSATION_MESSAGE.name &&
                row.hasExactValidSource(this, episode)
        }
    ) return null
    val traces = traceRows.toDomainTrace(episode) ?: return null
    return (
        ReflectionInputComposer.compose(
            episodeId = EpisodeId.parseOrNull(episode.id) ?: return null,
            episodeStatus = episode.status.toDomainStatus() ?: return null,
            features = traces,
        ) as? ReflectionInputComposeResult.Composed
    )?.input
}

internal class RoomRewardJobMaterialResolver(
    private val database: LearningDatabase,
    private val gate: P1RuntimeFeatureGate,
) : RewardJobMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): RewardJobMaterial? {
        if (!gate.captureEnabled()) throw P1LearningConfigurationUnavailableException()
        if (input.executionSpec.jobType != LearningJobType.CLOSE_REWARD_WINDOW_V1) return null
        if (
            input.executionSpec.providerKindIdentity != LearningJobProviderKindIdentity.NONE.wireCode ||
            input.executionSpec.algorithmIdentity != REWARD_ALGORITHM_IDENTITY ||
            input.executionSpec.outputSchemaIdentity != "reward-window-v1"
        ) return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (
            !event.matches(input) ||
            event.eventTypeCode != "COMMAND_TERMINAL" ||
            event.eventSchemaVersion != P1_EVENT_SCHEMA_VERSION
        ) return null
        val episode = event.findEpisode(database) ?: return null
        val stored = database.episodeDao().findRewardWindowByEpisode(episode.id) ?: return null
        if (stored.state != LearningRewardWindowState.OPEN.name) return null
        val window = stored.toOpenDomainWindow() ?: return null
        // Saving/failing a host command is not evidence that the user's goal succeeded or failed.
        // Until an explicit feedback or verified-outcome authority is wired, every dimension stays
        // UNKNOWN(NO_SIGNAL) and cannot become distillation support by inference.
        val censored = event.completionKind == LearningCompletionKind.CENSORED_CANCELLED.name
        return RewardJobMaterial(
            window = window,
            signals = P1RewardAuthorityPolicy.commandTerminalSignals(
                completionKind = event.completionKind,
                terminalState = event.terminalState,
            ),
            frozenNowMs = if (censored) {
                maxOf(window.openedAtMs, requireNotNull(event.occurredAtMs))
            } else {
                window.closeAfterMs
            },
            censored = censored,
        )
    }
}

internal class RoomRewardAuthorityJobMaterialResolver(
    private val database: LearningDatabase,
    private val gate: P1RuntimeFeatureGate,
) : RewardAuthorityJobMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): RewardAuthorityJobMaterial? {
        if (input.executionSpec.jobType != LearningJobType.APPLY_REWARD_AUTHORITY_V1) return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (
            !event.matches(input) || event.eventTypeCode != "USER_FEEDBACK_RECORDED" ||
            event.eventSchemaVersion != 3 || event.sourceType != LearningSourceKind.USER_FEEDBACK.name
        ) return null
        // Capture consent gates only the first positive feedback projection. Once authority has an
        // adjacent revision, an ACTIVE replacement or TOMBSTONED retraction is a negative
        // maintenance fact and must remove/supersede the prior signal even after capture is off.
        if (!rewardAuthorityCaptureGateAllows(
                captureEnabled = gate.captureEnabled(),
                previousSourceRevision = event.previousSourceRevision,
            )
        ) throw P1LearningConfigurationUnavailableException()
        val episode = database.episodeDao().findEpisodesByCommandAuthority(
            streamId = input.streamId,
            replayGeneration = input.replayGeneration,
            scopeKind = input.scopeKind,
            scopeId = input.scopeId,
            conversationId = event.conversationId ?: return null,
            commandId = event.commandId ?: return null,
        ).singleOrNull() ?: throw IllegalStateException("feedback_episode_not_ready")
        if (
            episode.resultAssistantMessageId != event.messageId ||
            episode.resultAssistantMessageRevision != event.messageRevision
        ) return null
        val window = database.episodeDao().findRewardWindowByEpisode(episode.id)
            ?: throw IllegalStateException("feedback_reward_window_not_ready")
        val sourceRevision = event.sourceRevision ?: return null
        val valueMilli = event.rewardValueMilli
        val active = event.sourceState == "ACTIVE"
        if (active != (valueMilli != null)) return null
        val signalId = "reward-signal-v1:" + LearningCanonicalId.digest(
            "reward-signal-v1",
            listOf(
                input.streamId,
                input.replayGeneration.toString(),
                input.scopeKind,
                input.scopeId,
                requireNotNull(event.sourceId),
                sourceRevision.toString(),
                requireNotNull(event.rewardDimension),
            ),
        )
        val integrity = LearningCanonicalId.digest(
            "reward-feedback-integrity-v1",
            listOf(
                requireNotNull(event.sourceId),
                sourceRevision.toString(),
                requireNotNull(event.rewardDimension),
                requireNotNull(event.rewardSignalKind),
                valueMilli?.toString(),
                requireNotNull(event.sourceState),
                requireNotNull(event.messageId),
                requireNotNull(event.messageRevision).toString(),
            ),
        )
        val signal = LearningRewardSignalEntity(
            id = signalId,
            episodeId = episode.id,
            streamId = input.streamId,
            replayGeneration = input.replayGeneration,
            scopeKind = input.scopeKind,
            scopeId = input.scopeId,
            authorityEventId = event.eventId,
            sourceType = LearningSourceKind.USER_FEEDBACK.name,
            sourceId = requireNotNull(event.sourceId),
            sourceRevision = sourceRevision,
            sourceIntegritySha256 = integrity,
            dimension = requireNotNull(event.rewardDimension),
            signalKind = requireNotNull(event.rewardSignalKind),
            knowledge = if (active) LearningRewardKnowledge.KNOWN.name
                else LearningRewardKnowledge.CENSORED.name,
            valueMilli = valueMilli,
            unknownReason = if (active) null else RewardUnknownReason.CENSORED.name,
            occurredAtMs = requireNotNull(event.occurredAtMs),
            createdAtMs = event.ingestedAtMs,
        )
        val priorSignals = database.rewardSignalDao().listValidSignalsForEpisode(
            episode.id,
            MAX_REWARD_SIGNALS,
        ).filterNot { prior ->
            prior.sourceType == signal.sourceType && prior.sourceId == signal.sourceId &&
                event.previousSourceRevision == prior.sourceRevision
        }
        val signals = (priorSignals + listOfNotNull(signal.takeIf { active }))
            .distinctBy { it.id }.sortedBy { it.id }
        val known = signals.filter { it.knowledge == LearningRewardKnowledge.KNOWN.name }
        val outcome = foldRewardAuthorityOutcome(known)
        val setIdentity = LearningCanonicalId.digest(
            "reward-signal-set-v1",
            known.flatMap { listOf(it.id, it.sourceRevision.toString(), it.valueMilli.toString()) },
        )
        return RewardAuthorityJobMaterial(
            signal = signal,
            expectedWindowRevision = window.revision,
            signalSetSha256 = setIdentity,
            authorityOutcome = outcome,
        )
    }
}

private fun foldRewardAuthorityOutcome(
    signals: List<LearningRewardSignalEntity>,
): LearningRewardAuthorityOutcome {
    if (signals.isEmpty()) return LearningRewardAuthorityOutcome.UNKNOWN
    val goalOrUser = signals.filter {
        it.dimension == LearningRewardDimension.GOAL.name ||
            it.dimension == LearningRewardDimension.USER.name
    }.mapNotNull(LearningRewardSignalEntity::valueMilli)
    if (goalOrUser.any { it < 0 } && goalOrUser.any { it > 0 }) {
        return LearningRewardAuthorityOutcome.CONFLICT
    }
    return when {
        goalOrUser.any { it < 0 } -> LearningRewardAuthorityOutcome.FAILURE
        goalOrUser.any { it > 0 } -> LearningRewardAuthorityOutcome.SUCCESS
        else -> LearningRewardAuthorityOutcome.UNKNOWN
    }
}

/** Command persistence/host state cannot by itself prove task success, failure, or user value. */
internal object P1RewardAuthorityPolicy {
    fun commandTerminalSignals(
        completionKind: String?,
        terminalState: String?,
    ): List<RewardSignal> {
        // Keep the parameters explicit so a future authority adapter cannot silently conflate them
        // with feedback. Only a real feedback/verified-outcome source may return a signal here.
        @Suppress("UNUSED_VARIABLE")
        val contentFreeTerminal = completionKind to terminalState
        return emptyList()
    }
}

internal class RoomPolicyDistillationMaterialResolver(
    private val database: LearningDatabase,
    private val gate: P1RuntimeFeatureGate,
    private val manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
    private val modelClaims: P1LearningModelClaimSource,
) : PolicyDistillationMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): PolicyDistillationMaterial? {
        if (!gate.policyEnabled()) throw P1LearningConfigurationUnavailableException()
        if (input.executionSpec.jobType != LearningJobType.DISTILL_POLICY_V1) return null
        if (
            input.executionSpec.algorithmIdentity != DISTILLATION_ALGORITHM_IDENTITY ||
            input.executionSpec.promptIdentity != PolicyDistillationPrompt.TEMPLATE_VERSION ||
            input.executionSpec.outputSchemaIdentity != "policy-candidate-v2"
        ) return null
        val providerEnvelope = database.findExactProviderEnvelope(input) ?: return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (!event.matches(input) || event.eventSchemaVersion != P1_EVENT_SCHEMA_VERSION) return null
        val triggerEpisode = event.findEpisode(database) ?: return null
        val expectedInputIdentity = input.executionSpec.sourceSchemaIdentity
            .removePrefix(POLICY_PROVIDER_INPUT_IDENTITY_PREFIX)
            .takeIf { it.matches(LOWER_SHA256) } ?: return null
        val eligible = database.distillationEvidence(
            triggerEpisode.scopeKind,
            triggerEpisode.scopeId,
            triggerEpisode.taskSignature,
            providerEnvelope.manifest.frozenAtMs,
        )
        val exactCohortEvidence = P1DerivedCascadePolicy.singleDistillationCohort(
            eligible,
            MAX_POLICY_EVIDENCE,
            P1DistillationEvidence::producerCohortIdentity,
        )
        val frozenEvidence = (2..minOf(exactCohortEvidence.size, MAX_POLICY_EVIDENCE))
            .firstNotNullOfOrNull { size ->
                exactCohortEvidence.take(size).takeIf { evidence ->
                    evidence.toPolicyMaterial(
                        producerIdentity = input.executionSpec.providerIdentity,
                        modelIdentity = input.executionSpec.modelIdentity,
                        promptIdentity = input.executionSpec.promptIdentity,
                    )?.let { material ->
                        PolicyProviderInputManifest.identity(material.first, material.second)
                    } == expectedInputIdentity
                }
        } ?: return null
        if (policyToolsetIdentity(frozenEvidence) != input.executionSpec.toolsetIdentity) return null
        val (policyInput, payloadJson) = frozenEvidence.toPolicyMaterial(
            producerIdentity = input.executionSpec.providerIdentity,
            modelIdentity = input.executionSpec.modelIdentity,
            promptIdentity = input.executionSpec.promptIdentity,
        ) ?: return null
        val rebuiltIdentity = PolicyProviderInputManifest.identity(policyInput, payloadJson)
        if (rebuiltIdentity != expectedInputIdentity) return null
        val inputBytes = PolicyDistillationPrompt.create(payloadJson).totalUtf8Bytes.toLong()
        return PolicyDistillationMaterial(
            input = policyInput,
            payloadJson = payloadJson,
            frozenModel = providerEnvelope.validateAndResolve(
                input = input,
                gate = gate,
                modelClaims = modelClaims,
                manifestKeyer = manifestKeyer,
                rebuiltInputIdentity = rebuiltIdentity,
                rebuiltInputUtf8Bytes = inputBytes,
                expectedMaxOutputTokens = PolicyDistillationPrompt.MAX_OUTPUT_TOKENS.toLong(),
                expectedMaxOutputUtf8Bytes =
                    me.rerere.rikkahub.learning.policy.ReasoningPolicyDistiller
                        .MAX_OUTPUT_UTF8_BYTES.toLong(),
                expectedTimeoutMs = P1_PROVIDER_TIMEOUT_MS,
            ) ?: return null,
        )
    }
}

internal class RoomP1DerivedJobEnqueuer(
    private val gate: P1RuntimeFeatureGate,
    private val policyOutcomeObserver: PolicyOutcomeLinkedObserver =
        NoOpPolicyOutcomeLinkedObserver,
) : P1DerivedJobEnqueuer {
    override suspend fun afterEpisodeCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        episode: LearningEpisodeEntity,
    ) {
        // The output committer and catch-up both invoke this callback while holding the same
        // LearningDatabase transaction as the terminal Episode projection. Re-read committed
        // authority and repair only bounded, exact logical-run attempts before optional P1 work.
        PolicyExposureOutcomeLinker(
            database = database,
            outcomeObserver = policyOutcomeObserver,
        ).replayCommittedTerminal(event, episode)
        val hasStableSource =
            database.episodeDao().countValidStableTraceSources(episode.id) > 0L &&
                episode.hasCompleteExecutionTraceProjection(database)
        val plan = P1DerivedCascadePolicy.afterEpisode(
            episodeStatus = episode.status,
            captureEnabled = gate.captureEnabled(),
            reflectionEnabled = gate.reflectionEnabled(),
            policyEnabled = gate.policyEnabled(),
            hasStableSource = hasStableSource,
            lessonAlreadyExists = database.episodeDao().findLesson(episode.id, 1) != null,
            // Exact replay/model-cohort dedupe is enforced by enqueueExact below. An old cohort
            // must never suppress a job for the currently frozen provider configuration.
            reflectionJobAlreadyExists = false,
            // Provider planning, including a potentially multi-gigabyte artifact SHA, is owned by
            // post-commit catch-up. This callback always runs inside the output transaction.
            modelConfigured = false,
        )
        if (!plan.enqueueRewardClose && !plan.enqueueReflection && !plan.attemptPolicyDistillation) {
            return
        }
        if (plan.enqueueRewardClose) {
            val reward = database.ensureOpenRewardWindow(episode, event)
            database.enqueueExact(
                P1LearningJobFactory.create(
                    source = event,
                    frozen = providerFreeSpec(
                        LearningJobType.CLOSE_REWARD_WINDOW_V1,
                        algorithm = REWARD_ALGORITHM_IDENTITY,
                        sourceSchema = REWARD_SOURCE_SCHEMA_IDENTITY,
                        outputSchema = "reward-window-v1",
                    ),
                    createdAtMs = event.ingestedAtMs,
                    notBeforeMs = maxOf(event.ingestedAtMs, reward.closeAfterMs),
                ),
            )
        }
        check(!plan.enqueueReflection) { "Provider job planning escaped post-commit maintenance" }
    }

    override suspend fun afterLessonCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        lesson: LearningEpisodeLessonEntity,
    ) {
        // Provider planning is post-commit only; this callback executes in the output transaction.
    }

    override suspend fun afterRewardCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        reward: LearningRewardWindowEntity,
    ) {
        // Provider planning is post-commit only; this callback executes in the output transaction.
    }

    override suspend fun afterExecutionTraceCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        episode: LearningEpisodeEntity,
    ) {
        // Provider planning is post-commit only; the planner will observe this trace on catch-up.
    }
}

internal fun interface P1DerivedJobCatchUp {
    suspend fun catchUp(
        database: LearningDatabase,
        frozenNowMs: Long,
    ): P1DerivedJobCatchUpResult
}

internal object NoOpP1DerivedJobCatchUp : P1DerivedJobCatchUp {
    override suspend fun catchUp(
        database: LearningDatabase,
        frozenNowMs: Long,
    ): P1DerivedJobCatchUpResult = P1DerivedJobCatchUpResult.Disabled
}

internal enum class P1DerivedMaintenanceFailureCode {
    STORAGE,
    INVARIANT,
    INTERNAL,
}

internal sealed interface P1DerivedJobCatchUpResult {
    data object Disabled : P1DerivedJobCatchUpResult

    data class Completed(
        val episodesVisited: Int,
        val redactedInvalidTraceRows: Int,
        val staleInvalidLessons: Int,
        val staleInvalidPolicies: Int,
    ) : P1DerivedJobCatchUpResult {
        val didWork: Boolean
            get() = episodesVisited > 0 || redactedInvalidTraceRows > 0 ||
                staleInvalidLessons > 0 || staleInvalidPolicies > 0

        val workMayRemain: Boolean
            get() = episodesVisited == CATCH_UP_BATCH_SIZE ||
                redactedInvalidTraceRows == CATCH_UP_BATCH_SIZE ||
                staleInvalidLessons == CATCH_UP_BATCH_SIZE ||
                staleInvalidPolicies == CATCH_UP_BATCH_SIZE
    }

    data class Failed(
        val code: P1DerivedMaintenanceFailureCode,
    ) : P1DerivedJobCatchUpResult
}

internal class RoomP1DerivedJobCatchUp(
    private val downstream: P1DerivedJobEnqueuer,
    private val gate: P1RuntimeFeatureGate,
    private val providerJobs: P1ProviderJobPlanner? = null,
) : P1DerivedJobCatchUp {
    private var afterUpdatedAtMs = -1L
    private var afterEpisodeId = ""

    override suspend fun catchUp(
        database: LearningDatabase,
        frozenNowMs: Long,
    ): P1DerivedJobCatchUpResult {
        require(frozenNowMs >= 0L)
        if (!gate.captureEnabled()) return P1DerivedJobCatchUpResult.Disabled
        return try {
            // Expensive local-runtime attestation happens here, outside every Room transaction.
            // The planner then opens one short transaction to revalidate and insert immutable
            // provider jobs/manifests.
            providerJobs?.prepareOne(database, frozenNowMs)
            val episodesVisited = database.withTransaction {
                val page = database.episodeDao().listTerminalEpisodePage(
                    afterUpdatedAtMs,
                    afterEpisodeId,
                    CATCH_UP_BATCH_SIZE,
                )
                if (page.isEmpty() && (afterUpdatedAtMs >= 0L || afterEpisodeId.isNotEmpty())) {
                    afterUpdatedAtMs = -1L
                    afterEpisodeId = ""
                    // End the completed pass. A later maintenance cycle starts a fresh bounded
                    // audit; do not reread the first page in this transaction and spin forever.
                    return@withTransaction 0
                }
                page.forEach { episode ->
                    val event = episode.terminalEvent(database) ?: return@forEach
                    val syntheticInput = event.toCatchUpInput()
                    downstream.afterEpisodeCommitted(database, syntheticInput, event, episode)
                    database.episodeDao().findLesson(episode.id, 1)?.let { lesson ->
                        downstream.afterLessonCommitted(database, syntheticInput, event, lesson)
                    }
                    database.episodeDao().findRewardWindowByEpisode(episode.id)?.let { reward ->
                        if (reward.state != LearningRewardWindowState.OPEN.name) {
                            downstream.afterRewardCommitted(database, syntheticInput, reward)
                        }
                    }
                }
                page.lastOrNull()?.let { last ->
                    afterUpdatedAtMs = last.updatedAtMs
                    afterEpisodeId = last.id
                }
                page.size
            }
            val invalidState = database.withTransaction {
                P1InvalidDerivedStateResult(
                    redactedTraceRows = database.episodeDao()
                        .clearTraceSummariesWithInvalidSource(CATCH_UP_BATCH_SIZE),
                    staleLessons = database.episodeDao().markLessonsStaleWithInvalidSource(
                        frozenNowMs,
                        CATCH_UP_BATCH_SIZE,
                    ),
                    stalePolicies = reconcileInvalidPolicyEvidence(
                        database,
                        frozenNowMs,
                        CATCH_UP_BATCH_SIZE,
                    ),
                )
            }
            P1DerivedJobCatchUpResult.Completed(
                episodesVisited = episodesVisited,
                redactedInvalidTraceRows = invalidState.redactedTraceRows,
                staleInvalidLessons = invalidState.staleLessons,
                staleInvalidPolicies = invalidState.stalePolicies,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            P1DerivedJobCatchUpResult.Failed(P1DerivedMaintenanceFailureCode.STORAGE)
        } catch (_: IllegalArgumentException) {
            P1DerivedJobCatchUpResult.Failed(P1DerivedMaintenanceFailureCode.INVARIANT)
        } catch (_: IllegalStateException) {
            P1DerivedJobCatchUpResult.Failed(P1DerivedMaintenanceFailureCode.INVARIANT)
        } catch (_: Exception) {
            P1DerivedJobCatchUpResult.Failed(P1DerivedMaintenanceFailureCode.INTERNAL)
        }
    }
}

/**
 * Bounded post-commit planner for provider-backed P1 jobs.
 *
 * It first chooses a content-free Episode candidate in a read transaction, resolves the exact
 * provider/runtime attestation outside Room, then revalidates and inserts job+cohort+manifest in
 * one short write transaction. At most one provider job is created per maintenance cycle.
 */
internal class P1ProviderJobPlanner(
    private val gate: P1RuntimeFeatureGate,
    private val models: P1LearningModelClaimSource,
    private val manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
) {
    private var afterUpdatedAtMs = -1L
    private var afterEpisodeId = ""

    suspend fun prepareOne(database: LearningDatabase, frozenNowMs: Long): Boolean {
        if (!gate.reflectionEnabled() && !gate.policyEnabled()) return false
        val candidates = database.withTransaction {
            val page = database.episodeDao().listTerminalEpisodePage(
                afterUpdatedAtMs,
                afterEpisodeId,
                CATCH_UP_BATCH_SIZE,
            )
            if (page.isEmpty()) {
                afterUpdatedAtMs = -1L
                afterEpisodeId = ""
                return@withTransaction emptyList()
            }
            page.last().let { last ->
                afterUpdatedAtMs = last.updatedAtMs
                afterEpisodeId = last.id
            }
            page.filter { episode ->
                episode.finalizedAtMs != null && episode.finalizedAtMs <= frozenNowMs &&
                    episode.updatedAtMs <= frozenNowMs
            }
        }
        if (candidates.isEmpty()) return false
        val model = models.resolve() ?: return false
        return database.withTransaction {
            for (candidate in candidates) {
                val current = database.episodeDao().findEpisode(candidate.id)
                    ?.takeIf {
                        it.revision == candidate.revision &&
                            it.updatedAtMs == candidate.updatedAtMs &&
                            it.finalizedAtMs != null && it.finalizedAtMs <= frozenNowMs &&
                            it.updatedAtMs <= frozenNowMs
                    }
                    ?: continue
                val created = if (
                    gate.reflectionEnabled() &&
                    database.episodeDao().findLesson(current.id, 1) == null
                ) {
                    createReflectionProviderJob(
                        database, current, model, models, frozenNowMs, manifestKeyer,
                    )
                } else if (gate.policyEnabled()) {
                    createDistillationProviderJob(
                        database, current, model, models, frozenNowMs, manifestKeyer,
                    )
                } else {
                    false
                }
                if (created) return@withTransaction true
            }
            false
        }
    }
}

private suspend fun createReflectionProviderJob(
    database: LearningDatabase,
    episode: LearningEpisodeEntity,
    model: ResolvedLearningModel,
    modelClaims: P1LearningModelClaimSource,
    frozenNowMs: Long,
    manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
): Boolean {
    if (episode.status !in REFLECTABLE_EPISODE_STATES) return false
    if (
        database.episodeDao().countValidStableTraceSources(episode.id) <= 0L ||
        !episode.hasCompleteExecutionTraceProjection(database)
    ) return false
    val event = episode.terminalEvent(database) ?: return false
    if (event.createdAtMs > frozenNowMs) return false
    val input = database.composeReflectionInput(episode, frozenNowMs) ?: return false
    val payloadBytes = ReflectionPrompt.create(input).totalUtf8Bytes.toLong()
    if (payloadBytes !in 1L..P1_PROVIDER_MAX_INPUT_UTF8_BYTES) return false
    val cohort = resolveOrCreateProviderCohort(database, model, frozenNowMs)
    val job = P1LearningJobFactory.create(
        source = event,
        frozen = modelSpec(
            type = LearningJobType.REFLECT_EPISODE_V1,
            algorithm = REFLECTION_ALGORITHM_IDENTITY,
            prompt = ReflectionPrompt.TEMPLATE_VERSION,
            sourceSchema = input.inputId,
            toolset = REDACTED_TRACE_TOOLSET_IDENTITY,
            outputSchema = "episode-lesson-v1",
            model = model,
            providerConfigGeneration = cohort.configurationGeneration,
        ),
        createdAtMs = frozenNowMs,
    )
    val inputIdentity = input.inputId.substringAfterLast(':')
    val dispatchAttestation = modelClaims.expectedDispatchAttestationSha256(
        model = model,
        templateVersion = ReflectionPrompt.TEMPLATE_VERSION,
        inputIdentitySha256 = inputIdentity,
        providerRequestKey = learningProviderIdempotencyKey(job.id),
        maxOutputTokens = ReflectionPrompt.MAX_OUTPUT_TOKENS,
    ) ?: return false
    val manifest = job.providerManifest(
        cohort = cohort,
        model = model,
        dispatchAttestationDigest = dispatchAttestation,
        inputIdentity = inputIdentity,
        payloadBytes = payloadBytes,
        maxOutputTokens = ReflectionPrompt.MAX_OUTPUT_TOKENS.toLong(),
        maxOutputBytes = ReflectionPrompt.MAX_OUTPUT_UTF8_BYTES.toLong(),
        timeoutMs = P1_PROVIDER_TIMEOUT_MS,
        frozenAtMs = frozenNowMs,
        manifestKeyer = manifestKeyer,
    )
    return database.enqueueProviderJobExact(job, manifest)
}

private suspend fun createDistillationProviderJob(
    database: LearningDatabase,
    trigger: LearningEpisodeEntity,
    model: ResolvedLearningModel,
    modelClaims: P1LearningModelClaimSource,
    frozenNowMs: Long,
    manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
): Boolean {
    val evidence = database.distillationEvidence(
        trigger.scopeKind,
        trigger.scopeId,
        trigger.taskSignature,
        frozenNowMs,
    )
    if (!P1DerivedCascadePolicy.afterLessonOrReward(true, evidence.size)) return false
    val frozenEvidence = P1DerivedCascadePolicy.singleDistillationCohort(
        evidence,
        MAX_POLICY_EVIDENCE,
        P1DistillationEvidence::producerCohortIdentity,
    )
    if (frozenEvidence.size < 2) return false
    val event = frozenEvidence.first().episode.terminalEvent(database) ?: return false
    if (event.createdAtMs > frozenNowMs) return false
    val cohort = resolveOrCreateProviderCohort(database, model, frozenNowMs)
    val (input, payload) = frozenEvidence.toPolicyMaterial(
        model.providerIdentityDigest,
        model.modelIdentityDigest,
        PolicyDistillationPrompt.TEMPLATE_VERSION,
    ) ?: return false
    val payloadBytes = PolicyDistillationPrompt.create(payload).totalUtf8Bytes.toLong()
    if (payloadBytes !in 1L..P1_PROVIDER_MAX_INPUT_UTF8_BYTES) return false
    val inputIdentity = PolicyProviderInputManifest.identity(input, payload)
    val job = P1LearningJobFactory.create(
        source = event,
        frozen = modelSpec(
            LearningJobType.DISTILL_POLICY_V1,
            DISTILLATION_ALGORITHM_IDENTITY,
            PolicyDistillationPrompt.TEMPLATE_VERSION,
            "$POLICY_PROVIDER_INPUT_IDENTITY_PREFIX$inputIdentity",
            policyToolsetIdentity(frozenEvidence),
            "policy-candidate-v2",
            model,
            cohort.configurationGeneration,
        ),
        createdAtMs = frozenNowMs,
    )
    val dispatchAttestation = modelClaims.expectedDispatchAttestationSha256(
        model = model,
        templateVersion = PolicyDistillationPrompt.TEMPLATE_VERSION,
        inputIdentitySha256 = inputIdentity,
        providerRequestKey = learningProviderIdempotencyKey(job.id),
        maxOutputTokens = PolicyDistillationPrompt.MAX_OUTPUT_TOKENS,
    ) ?: return false
    return database.enqueueProviderJobExact(
        job,
        job.providerManifest(
            cohort,
            model,
            dispatchAttestation,
            inputIdentity,
            payloadBytes,
            PolicyDistillationPrompt.MAX_OUTPUT_TOKENS.toLong(),
            me.rerere.rikkahub.learning.policy.ReasoningPolicyDistiller.MAX_OUTPUT_UTF8_BYTES.toLong(),
            P1_PROVIDER_TIMEOUT_MS,
            frozenNowMs,
            manifestKeyer,
        ),
    )
}

private suspend fun resolveOrCreateProviderCohort(
    database: LearningDatabase,
    model: ResolvedLearningModel,
    frozenNowMs: Long,
): LearningProviderConfigCohortEntity {
    val dao = database.providerExecutionDao()
    val kind = model.providerKind.toJobWireCode()
    dao.findReusableConfigCohort(
        kind,
        model.providerIdentityDigest,
        model.modelIdentityDigest,
        model.configurationDigest,
    ).singleOrNull()?.let { existing ->
        require(existing.createdAtMs <= frozenNowMs) {
            "Provider cohort clock rollback"
        }
        return existing
    }
    val generation = Math.addExact(dao.maxConfigurationGeneration() ?: 0L, 1L)
    val id = "provider-cohort-v1:" + LearningCanonicalId.digest(
        "provider-cohort-v1",
        listOf(
            kind,
            model.providerIdentityDigest,
            model.modelIdentityDigest,
            model.configurationDigest,
            generation.toString(),
        ),
    )
    val entity = LearningProviderConfigCohortEntity(
        id,
        kind,
        model.providerIdentityDigest,
        model.modelIdentityDigest,
        model.configurationDigest,
        generation,
        frozenNowMs,
    )
    if (dao.insertConfigCohortIgnore(entity) == -1L) {
        return dao.findReusableConfigCohort(
            kind,
            model.providerIdentityDigest,
            model.modelIdentityDigest,
            model.configurationDigest,
        ).singleOrNull() ?: throw IllegalStateException("Provider cohort allocation conflict")
    }
    return entity
}

private suspend fun LearningDatabase.enqueueProviderJobExact(
    job: LearningJobEntity,
    manifest: LearningProviderJobManifestEntity,
): Boolean {
    val inserted = jobDao().insertIgnore(job)
    if (inserted == -1L) {
        val existing = jobDao().findByDedupeKey(job.dedupeKey)
            ?: throw IllegalStateException("Provider job replay disappeared")
        require(existing.matchesProviderPlan(job)) { "Provider job replay identity conflict" }
        // Job+manifest are inserted in the same transaction. A replay must retain the originally
        // frozen timestamp/HMAC, not manufacture a new manifest from the maintenance clock.
        require(providerExecutionDao().findJobManifest(existing.id) != null) {
            "Provider job replay is missing its immutable manifest"
        }
        return false
    }
    val manifestInserted = providerExecutionDao().insertJobManifestIgnore(manifest)
    if (manifestInserted == -1L) {
        require(providerExecutionDao().findJobManifest(job.id) == manifest) {
            "Provider manifest replay identity conflict"
        }
    }
    return inserted != -1L
}

private fun LearningJobEntity.matchesProviderPlan(other: LearningJobEntity): Boolean =
    id == other.id && dedupeKey == other.dedupeKey && jobType == other.jobType &&
        jobSchemaVersion == other.jobSchemaVersion && streamId == other.streamId &&
        sourceEventId == other.sourceEventId && scopeKind == other.scopeKind &&
        scopeId == other.scopeId && replayGeneration == other.replayGeneration &&
        algorithmIdentity == other.algorithmIdentity && promptIdentity == other.promptIdentity &&
        providerKindIdentity == other.providerKindIdentity && modelIdentity == other.modelIdentity &&
        providerIdentity == other.providerIdentity &&
        providerConfigurationIdentity == other.providerConfigurationIdentity &&
        providerConfigGeneration == other.providerConfigGeneration &&
        sourceSchemaIdentity == other.sourceSchemaIdentity &&
        toolsetIdentity == other.toolsetIdentity &&
        outputSchemaIdentity == other.outputSchemaIdentity

private fun LearningJobEntity.providerManifest(
    cohort: LearningProviderConfigCohortEntity,
    model: ResolvedLearningModel,
    dispatchAttestationDigest: String,
    inputIdentity: String,
    payloadBytes: Long,
    maxOutputTokens: Long,
    maxOutputBytes: Long,
    timeoutMs: Long,
    frozenAtMs: Long,
    manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
): LearningProviderJobManifestEntity {
    val providerRequestKey = learningProviderIdempotencyKey(id)
    val maxInputBytes = P1_PROVIDER_MAX_INPUT_UTF8_BYTES
    val estimatedTokens = ((payloadBytes + 3L) / 4L).coerceAtLeast(1L)
    val maxCalls = 1
    val maxCostMicros = when (model.providerKind) {
        LearningProviderKind.LOCAL_LITERT -> 0L
        // This is a conservative authorization reservation, not a price estimate. Actual
        // provider-reported cost is committed independently by the attempt authority.
        LearningProviderKind.REMOTE -> REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS
        LearningProviderKind.AICORE -> throw IllegalArgumentException(
            "AICore cannot run Learning jobs",
        )
    }
    val canonicalRequest = providerRequestAuditDigest(
        jobId = id,
        jobType = jobType,
        jobSchemaVersion = jobSchemaVersion,
        manifestSchemaVersion =
            me.rerere.rikkahub.learning.storage.PROVIDER_JOB_MANIFEST_SCHEMA_VERSION,
        algorithmIdentity = requireNotNull(algorithmIdentity),
        promptIdentity = requireNotNull(promptIdentity),
        providerKindIdentity = requireNotNull(providerKindIdentity),
        modelIdentity = requireNotNull(modelIdentity),
        providerIdentity = requireNotNull(providerIdentity),
        providerConfigurationIdentity = requireNotNull(providerConfigurationIdentity),
        providerConfigGeneration = requireNotNull(providerConfigGeneration),
        sourceSchemaIdentity = requireNotNull(sourceSchemaIdentity),
        toolsetIdentity = requireNotNull(toolsetIdentity),
        outputSchemaIdentity = requireNotNull(outputSchemaIdentity),
        cohortId = cohort.id,
        dispatchAttestationDigest = dispatchAttestationDigest,
        inputIdentity = inputIdentity,
        providerRequestKey = providerRequestKey,
        inputBytes = payloadBytes,
        maxInputBytes = maxInputBytes,
        estimatedInputTokens = estimatedTokens,
        maxOutputTokens = maxOutputTokens,
        maxOutputBytes = maxOutputBytes,
        maxProviderCalls = maxCalls,
        maxCostMicros = maxCostMicros,
        timeoutMs = timeoutMs,
        frozenAtMs = frozenAtMs,
    )
    val requestHmac = signProviderRequest(manifestKeyer, canonicalRequest, id)
    return LearningProviderJobManifestEntity(
        jobId = id,
        cohortId = cohort.id,
        manifestSchemaVersion = me.rerere.rikkahub.learning.storage.PROVIDER_JOB_MANIFEST_SCHEMA_VERSION,
        requestHmacSha256 = requestHmac,
        inputIdentitySha256 = inputIdentity,
        runtimeAttestationSha256 = dispatchAttestationDigest,
        redactionPolicyIdentity = P1_PROVIDER_REDACTION_IDENTITY,
        fieldCategoriesIdentity = P1_PROVIDER_FIELDS_IDENTITY,
        tokenEstimatorIdentity = P1_PROVIDER_TOKEN_ESTIMATOR_IDENTITY,
        providerRequestKey = providerRequestKey,
        inputUtf8Bytes = payloadBytes,
        maxInputUtf8Bytes = maxInputBytes,
        estimatedInputTokens = estimatedTokens,
        maxOutputTokens = maxOutputTokens,
        maxOutputUtf8Bytes = maxOutputBytes,
        maxProviderCalls = maxCalls,
        maxCostMicros = maxCostMicros,
        timeoutMs = timeoutMs,
        frozenAtMs = frozenAtMs,
    )
}

private fun providerRequestAuditDigest(
    jobId: String,
    jobType: String,
    jobSchemaVersion: Int,
    manifestSchemaVersion: Int,
    algorithmIdentity: String,
    promptIdentity: String,
    providerKindIdentity: String,
    modelIdentity: String,
    providerIdentity: String,
    providerConfigurationIdentity: String,
    providerConfigGeneration: Long,
    sourceSchemaIdentity: String,
    toolsetIdentity: String,
    outputSchemaIdentity: String,
    cohortId: String,
    dispatchAttestationDigest: String,
    inputIdentity: String,
    providerRequestKey: String,
    inputBytes: Long,
    maxInputBytes: Long,
    estimatedInputTokens: Long,
    maxOutputTokens: Long,
    maxOutputBytes: Long,
    maxProviderCalls: Int,
    maxCostMicros: Long,
    timeoutMs: Long,
    frozenAtMs: Long,
): String = LearningCanonicalId.digest(
    "learning-provider-request-hmac-v1",
    listOf(
        jobId, jobType, jobSchemaVersion.toString(), manifestSchemaVersion.toString(),
        algorithmIdentity, promptIdentity,
        providerKindIdentity, modelIdentity, providerIdentity, providerConfigurationIdentity,
        providerConfigGeneration.toString(), sourceSchemaIdentity, toolsetIdentity,
        outputSchemaIdentity, cohortId, dispatchAttestationDigest, inputIdentity,
        P1_PROVIDER_REDACTION_IDENTITY, P1_PROVIDER_FIELDS_IDENTITY,
        P1_PROVIDER_TOKEN_ESTIMATOR_IDENTITY, providerRequestKey, inputBytes.toString(),
        maxInputBytes.toString(), estimatedInputTokens.toString(), maxOutputTokens.toString(),
        maxOutputBytes.toString(), maxProviderCalls.toString(), maxCostMicros.toString(),
        timeoutMs.toString(), frozenAtMs.toString(),
    ),
)

private fun signProviderRequest(
    keyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
    canonicalRequest: String,
    jobId: String,
): String = keyer.ownerTokenFor(
    "learning_provider_request_hmac_v1", canonicalRequest, jobId, "left",
) + keyer.ownerTokenFor(
    "learning_provider_request_hmac_v1", canonicalRequest, jobId, "right",
)

private const val P1_PROVIDER_REDACTION_IDENTITY = "learning-redaction-v1"
private const val P1_PROVIDER_FIELDS_IDENTITY = "p1-bounded-provider-fields-v1"
private const val P1_PROVIDER_TOKEN_ESTIMATOR_IDENTITY = "utf8-quarter-token-upper-v1"
private const val P1_PROVIDER_MAX_INPUT_UTF8_BYTES = 160L * 1_024L
private const val P1_PROVIDER_TIMEOUT_MS = 2L * 60L * 1_000L

private data class P1InvalidDerivedStateResult(
    val redactedTraceRows: Int,
    val staleLessons: Int,
    val stalePolicies: Int,
)

/** Production composition; every resolver is scoped to the one open LearningDatabase instance. */
internal class ProductionP1LearningRuntimeDependencyFactory(
    featureFlags: LearningFeatureFlagSource,
    private val backgroundClient: BackgroundGenerationClient,
    backgroundHost: SettingsBackedBackgroundGenerationHost,
    mainDatabase: AppDatabase,
    private val manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
    private val policyOutcomeObserverFactory: PolicyOutcomeLinkedObserverFactory =
        NoOpPolicyOutcomeLinkedObserverFactory,
) : P1LearningRuntimeDependencyFactory {
    private val gate = P1RuntimeFeatureGate(featureFlags)
    private val modelClaims = P1LearningModelClaimSource(backgroundHost, gate)
    private val mainSources = RoomP1MainSourceAuthorityReader(mainDatabase)

    override fun create(database: LearningDatabase): P1LearningRuntimeDependencies {
        val downstream = RoomP1DerivedJobEnqueuer(
            gate = gate,
            policyOutcomeObserver = policyOutcomeObserverFactory.create(database),
        )
        return P1LearningRuntimeDependencies(
            episodeAssemblyResolver = RoomEpisodeAssemblyMaterialResolver(database, mainSources, gate),
            executionTraceResolver = RoomExecutionTraceJobMaterialResolver(database, gate),
            reflectionResolver = RoomReflectionJobMaterialResolver(
                database,
                gate,
                manifestKeyer,
                modelClaims,
            ),
            backgroundGenerationClient = backgroundClient,
            rewardResolver = RoomRewardJobMaterialResolver(database, gate),
            rewardAuthorityResolver = RoomRewardAuthorityJobMaterialResolver(database, gate),
            policyDistillationResolver = RoomPolicyDistillationMaterialResolver(
                database,
                gate,
                manifestKeyer,
                modelClaims,
            ),
            sourceIntegrityResolver = LearningSourceIntegrityResolver { event ->
                if (event.sourceType != LearningSourceKind.CONVERSATION_MESSAGE.name) return@LearningSourceIntegrityResolver null
                val revision = event.sourceRevision ?: return@LearningSourceIntegrityResolver null
                mainSources.findExactActiveMessage(
                    scopeKind = event.scopeKind ?: return@LearningSourceIntegrityResolver null,
                    scopeId = event.scopeId ?: return@LearningSourceIntegrityResolver null,
                    conversationId = event.conversationId ?: return@LearningSourceIntegrityResolver null,
                    messageId = event.sourceId ?: return@LearningSourceIntegrityResolver null,
                    sourceRevision = revision,
                )?.payloadIntegritySha256
            },
            sourceInvalidationResolver = SourceInvalidationJobMaterialResolver { input ->
                // SOURCE_INVALIDATED is an authority-loss lane, not new learning capture. It must
                // remain executable after capture/jobs rollout is disabled.
                RoomSourceInvalidationJobMaterialResolver(
                    database = database,
                    integrityResolver = LearningSourceIntegrityResolver { event ->
                        if (event.sourceType != LearningSourceKind.CONVERSATION_MESSAGE.name) {
                            return@LearningSourceIntegrityResolver null
                        }
                        val revision = event.sourceRevision
                            ?: return@LearningSourceIntegrityResolver null
                        mainSources.findExactActiveMessage(
                            scopeKind = event.scopeKind
                                ?: return@LearningSourceIntegrityResolver null,
                            scopeId = event.scopeId
                                ?: return@LearningSourceIntegrityResolver null,
                            conversationId = event.conversationId
                                ?: return@LearningSourceIntegrityResolver null,
                            messageId = event.sourceId
                                ?: return@LearningSourceIntegrityResolver null,
                            sourceRevision = revision,
                        )?.payloadIntegritySha256
                    },
                ).resolve(input)
            },
            derivedJobEnqueuer = downstream,
            catchUp = RoomP1DerivedJobCatchUp(
                downstream,
                gate,
                P1ProviderJobPlanner(gate, modelClaims, manifestKeyer),
            ),
            readiness = P1LearningRuntimeReadiness(
                episodeAssembly = gate.readiness(gate::captureEnabled),
                executionTrace = gate.readiness(gate::captureEnabled),
                reflection = gate.modelReadiness(gate::reflectionEnabled, modelClaims),
                reward = gate.readiness(gate::captureEnabled),
                // Mixed job type: resolver still defers an initial positive feedback while the
                // always-ready lane lets adjacent replacement/tombstone authority drain.
                rewardAuthority = alwaysReadyP1JobProbe(),
                policyDistillation = gate.modelReadiness(gate::policyEnabled, modelClaims),
                sourceInvalidation = alwaysReadyP1JobProbe(),
            ),
        )
    }
}

/** Initial feedback is positive capture; every adjacent revision is mandatory invalidation. */
internal fun rewardAuthorityCaptureGateAllows(
    captureEnabled: Boolean,
    previousSourceRevision: Long?,
): Boolean = captureEnabled || previousSourceRevision != null

private fun alwaysReadyP1JobProbe() = LearningJobHandlerReadinessProbe {
    LearningJobHandlerReadiness.READY
}

private fun P1RuntimeFeatureGate.readiness(
    enabled: () -> Boolean,
) = LearningJobHandlerReadinessProbe {
    if (enabled()) LearningJobHandlerReadiness.READY
    else LearningJobHandlerReadiness.WAITING_CONFIGURATION
}

private fun P1RuntimeFeatureGate.modelReadiness(
    enabled: () -> Boolean,
    models: P1LearningModelClaimSource,
) = LearningJobHandlerReadinessProbe {
    if (enabled() && models.resolve() != null) LearningJobHandlerReadiness.READY
    else LearningJobHandlerReadiness.WAITING_CONFIGURATION
}

private data class P1DistillationEvidence(
    val episode: LearningEpisodeEntity,
    val lesson: LearningEpisodeLessonEntity,
    val reward: LearningRewardWindowEntity,
    val handle: PolicyEvidenceHandle,
    val toolSchemas: Set<String>,
)

private fun P1DistillationEvidence.producerCohortIdentity(): String =
    LearningCanonicalId.digest(
        domainVersion = "policy-evidence-producer-cohort-v1",
        fields = listOf(
            lesson.producerProviderKind,
            lesson.producerProviderIdentity,
            lesson.producerModelIdentity,
            lesson.producerConfigurationIdentity,
            lesson.producerConfigGeneration.toString(),
            lesson.algorithmIdentity,
            lesson.promptIdentity,
            lesson.templateIdentity,
            lesson.schemaIdentity,
        ),
    )

private suspend fun LearningDatabase.distillationEvidence(
    scopeKind: String,
    scopeId: String,
    taskSignature: String,
    frozenAtMs: Long,
): List<P1DistillationEvidence> = episodeDao().listDistillationEvidenceEpisodes(
    scopeKind,
    scopeId,
    taskSignature,
    frozenAtMs,
    MAX_DISTILLATION_SCAN,
).mapNotNull { episode ->
    val lesson = episodeDao().findLesson(episode.id, 1)
        ?.takeIf {
            it.state == LearningLessonState.VALID.name && it.updatedAtMs <= frozenAtMs
        } ?: return@mapNotNull null
    val reward = episodeDao().findRewardWindowByEpisode(episode.id)
        ?.takeIf {
            it.state == LearningRewardWindowState.CLOSED.name &&
                it.updatedAtMs <= frozenAtMs &&
                it.authorityOutcome in setOf(
                    LearningRewardAuthorityOutcome.SUCCESS.name,
                    LearningRewardAuthorityOutcome.FAILURE.name,
                )
        } ?: return@mapNotNull null
    val outcome = when (reward.authorityOutcome) {
        LearningRewardAuthorityOutcome.SUCCESS.name -> PolicyEvidenceAuthorityOutcome.SUCCESS
        LearningRewardAuthorityOutcome.FAILURE.name -> PolicyEvidenceAuthorityOutcome.FAILURE
        else -> return@mapNotNull null
    }
    if (episodeDao().countValidStableTraceSources(episode.id) <= 0L) return@mapNotNull null
    val scope = LearningScope.parseOrNull(episode.scopeKind, episode.scopeId) ?: return@mapNotNull null
    val schemas = episodeDao().listTrace(episode.id, MAX_REFLECTION_TRACE_ROWS)
        .mapNotNull(LearningTraceFeatureEntity::toolSchemaFingerprint)
        .filter(LOWER_SHA256::matches)
        .toSortedSet()
    P1DistillationEvidence(
        episode = episode,
        lesson = lesson,
        reward = reward,
        handle = PolicyEvidenceHandle(
            lessonId = "episode-lesson-v1:${episode.id}:1",
            episodeId = EpisodeId.parseOrNull(episode.id) ?: return@mapNotNull null,
            scope = scope,
            lessonRevision = 1,
            sourceValid = true,
            authorityOutcome = outcome,
        ),
        toolSchemas = schemas,
    )
    }

private fun List<P1DistillationEvidence>.toPolicyPayload(
    aliases: Map<String, PolicyEvidenceHandle>,
): String {
    val aliasByLesson = aliases.entries.associate { it.value.lessonId to it.key }
    return buildJsonObject {
        put("schema_version", 1)
        put("task_signature", first().episode.taskSignature)
        put("evidence", buildJsonArray {
            this@toPolicyPayload.forEach { evidence ->
                add(buildJsonObject {
                    put("id", aliasByLesson.getValue(evidence.handle.lessonId))
                    put("outcome", evidence.handle.authorityOutcome.name)
                    put("lesson_type", evidence.lesson.lessonType)
                    put("trigger", evidence.lesson.triggerSummary)
                    put("observation", evidence.lesson.observationSummary)
                    put("lesson", evidence.lesson.lessonSummary)
                    put("boundary", evidence.lesson.boundarySummary)
                    put("reward_goal", evidence.reward.goalValue?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("reward_process", evidence.reward.processValue?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("reward_user", evidence.reward.userValue?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        })
        put("tool_schema_allowlist", buildJsonArray {
            this@toPolicyPayload.flatMap(P1DistillationEvidence::toolSchemas)
                .distinct().sorted().forEach { add(JsonPrimitive(it)) }
        })
    }.toString()
}

/** Exact identity of every byte that can influence the provider's distillation response. */
private fun List<P1DistillationEvidence>.toPolicyMaterial(
    producerIdentity: String = firstOrNull()?.lesson?.producerProviderIdentity.orEmpty(),
    modelIdentity: String = firstOrNull()?.lesson?.producerModelIdentity.orEmpty(),
    promptIdentity: String = PolicyDistillationPrompt.TEMPLATE_VERSION,
): Pair<PolicyDistillationInput, String>? {
    if (size !in 2..MAX_POLICY_EVIDENCE) return null
    val aliases = linkedMapOf<String, PolicyEvidenceHandle>()
    forEachIndexed { index, item -> aliases["E${index + 1}"] = item.handle }
    val firstEpisode = first().episode
    val applicableConfigurationIdentity =
        me.rerere.rikkahub.learning.policy.policyApplicableConfigurationIdentity(
            producerIdentity,
            modelIdentity,
        )
    val frozenToolSchemas = flatMap(P1DistillationEvidence::toolSchemas).toSortedSet()
    val input = PolicyDistillationInput(
        scope = LearningScope.parseOrNull(firstEpisode.scopeKind, firstEpisode.scopeId) ?: return null,
        taskSignature = TaskSignatureV1.parseOrNull(firstEpisode.taskSignature) ?: return null,
        evidenceAllowlist = aliases,
        toolSchemaAllowlist = frozenToolSchemas,
        producerIdentity = producerIdentity,
        modelIdentity = modelIdentity,
        promptVersion = promptIdentity,
        applicableTemplateIdentity =
            me.rerere.rikkahub.learning.policy.policyApplicableTemplateIdentity(promptIdentity),
        applicableConfigurationIdentity = applicableConfigurationIdentity,
        applicableConfigurationGeneration =
            me.rerere.rikkahub.learning.policy.policyApplicableConfigurationGeneration(
                applicableConfigurationIdentity,
            ),
        // An empty tool requirement has an exact empty capability surface. Non-empty schemas do
        // not prove the complete capability catalog, so those candidates remain UNKNOWN/inert.
        applicableCapabilityDigest =
            me.rerere.rikkahub.learning.policy.policyApplicableCapabilityDigest(
                frozenToolSchemas,
            ),
        applicableAuthorityDigest = null,
    )
    return input to toPolicyPayload(aliases)
}

private fun LearningInboxEventEntity.matches(input: LearningJobExecutionInputV1): Boolean =
    streamId == input.streamId &&
        eventId == input.sourceEventId &&
        replayGeneration == input.replayGeneration &&
        scopeKind == input.scopeKind &&
        scopeId == input.scopeId

private fun LearningInboxEventEntity.toEpisodeAnchor(): EpisodeAuthorityAnchor? = runCatching {
    EpisodeAuthorityAnchor(
        streamId = Uuid.parse(streamId),
        scope = requireNotNull(LearningScope.parseOrNull(requireNotNull(scopeKind), requireNotNull(scopeId))),
        conversationId = Uuid.parse(requireNotNull(conversationId)),
        commandId = Uuid.parse(requireNotNull(commandId)),
        lineageId = Uuid.parse(requireNotNull(lineageId)),
        branchAnchorMessageId = Uuid.parse(requireNotNull(branchAnchorMessageId)),
        branchAnchorMessageRevision = requireNotNull(branchAnchorMessageRevision),
        parentCommandId = parentCommandId?.let { Uuid.parse(it) },
        resultAssistantMessageId = messageId?.let { Uuid.parse(it) },
        resultAssistantMessageRevision = messageRevision,
    )
}.getOrNull()

private fun defaultTaskSignature(): TaskSignatureV1 =
    RuntimeTaskSignatureClassifier.admissionSignature()

private fun LearningEpisodeEntity.toDomainSnapshot(): EpisodeSnapshot? = runCatching {
    val scope = requireNotNull(LearningScope.parseOrNull(scopeKind, scopeId))
    EpisodeSnapshot(
        authority = EpisodeAuthorityAnchor(
            streamId = Uuid.parse(streamId),
            scope = scope,
            conversationId = Uuid.parse(conversationId),
            commandId = Uuid.parse(finalCommandId ?: rootCommandId),
            lineageId = Uuid.parse(lineageId),
            branchAnchorMessageId = Uuid.parse(branchAnchorMessageId),
            branchAnchorMessageRevision = branchAnchorMessageRevision,
            parentCommandId = null,
            resultAssistantMessageId = resultAssistantMessageId?.let { Uuid.parse(it) },
            resultAssistantMessageRevision = resultAssistantMessageRevision,
        ),
        taskSignature = requireNotNull(TaskSignatureV1.parseOrNull(taskSignature)),
        status = requireNotNull(status.toDomainStatus()),
        boundaryReason = when (boundaryReason) {
            LearningEpisodeBoundaryReason.COMMAND_ADMITTED.name -> EpisodeBoundaryReason.ROOT_COMMAND_ADMITTED
            LearningEpisodeBoundaryReason.WAITING_APPROVAL.name -> EpisodeBoundaryReason.WAITING_APPROVAL_CHECKPOINT
            LearningEpisodeBoundaryReason.FINAL_SAVED.name -> EpisodeBoundaryReason.FINAL_RESPONSE_SAVED
            LearningEpisodeBoundaryReason.STOPPED.name -> EpisodeBoundaryReason.USER_CANCELLED
            LearningEpisodeBoundaryReason.REGENERATED_BRANCH.name -> EpisodeBoundaryReason.REGENERATED_BRANCH
            LearningEpisodeBoundaryReason.FINAL_SAVE_FAILED.name -> EpisodeBoundaryReason.FINAL_SAVE_FAILED
            else -> EpisodeBoundaryReason.COMMAND_FAILED
        },
        revision = revision,
        startedAtMs = startedAtMs,
        finalizedAtMs = finalizedAtMs,
    )
}.getOrNull()

private fun String.toDomainStatus(): LearningEpisodeStatus? = when (this) {
    StoredLearningEpisodeStatus.OPEN.name -> LearningEpisodeStatus.OPEN
    StoredLearningEpisodeStatus.SUCCESS.name -> LearningEpisodeStatus.SUCCESS
    StoredLearningEpisodeStatus.PARTIAL.name -> LearningEpisodeStatus.PARTIAL
    StoredLearningEpisodeStatus.FAILURE.name -> LearningEpisodeStatus.FAILURE
    StoredLearningEpisodeStatus.ABORTED.name -> LearningEpisodeStatus.ABORTED
    StoredLearningEpisodeStatus.TIMEOUT.name -> LearningEpisodeStatus.TIMEOUT
    StoredLearningEpisodeStatus.CENSORED.name -> LearningEpisodeStatus.CENSORED
    StoredLearningEpisodeStatus.SUPERSEDED.name -> LearningEpisodeStatus.SUPERSEDED
    StoredLearningEpisodeStatus.UNKNOWN.name -> LearningEpisodeStatus.UNKNOWN
    else -> null
}

private suspend fun LearningInboxEventEntity.findEpisode(
    database: LearningDatabase,
): LearningEpisodeEntity? = database.episodeDao().findEpisodeByBoundary(
    streamId,
    replayGeneration,
    lineageId ?: return null,
    branchAnchorMessageId ?: return null,
)

private suspend fun LearningEpisodeEntity.terminalEvent(
    database: LearningDatabase,
): LearningInboxEventEntity? {
    val command = finalCommandId ?: return null
    val revision = finalCommandRevision ?: return null
    return database.inboxDao().findTerminalCommandCandidates(
        streamId,
        replayGeneration,
        command,
        revision,
    ).singleOrNull()
}

private suspend fun LearningEpisodeEntity.hasCompleteExecutionTraceProjection(
    database: LearningDatabase,
): Boolean {
    val expected = database.inboxDao().countExecutionTerminalsForEpisode(
        streamId,
        replayGeneration,
        scopeKind,
        scopeId,
        conversationId,
        rootCommandId,
        finalCommandId ?: rootCommandId,
    )
    return database.episodeDao().countExecutionTraceSources(id) == expected
}

private fun List<LearningTraceFeatureEntity>.toDomainTrace(
    episode: LearningEpisodeEntity,
): List<TraceFeature>? = groupBy(LearningTraceFeatureEntity::sequence)
    .toSortedMap()
    .map { (sequence, rows) ->
        val orderedRows = rows.sortedBy(LearningTraceFeatureEntity::sourceOrdinal)
        val first = orderedRows.first()
        if (orderedRows.map(LearningTraceFeatureEntity::sourceOrdinal) != orderedRows.indices.toList()) {
            return null
        }
        val sources = orderedRows.map { row ->
            LearningSourceRef(
                sourceKind = LearningSourceKind.entries.firstOrNull { it.name == row.sourceType }
                    ?: return null,
                sourceId = row.sourceId,
                sourceRevision = row.sourceRevision,
                missingRevisionReason = row.missingRevisionReason?.let { raw ->
                    MissingSourceRevisionReason.entries.firstOrNull { it.name == raw } ?: return null
                },
                databaseStreamId = Uuid.parse(episode.streamId),
                scope = LearningScope.parseOrNull(episode.scopeKind, episode.scopeId) ?: return null,
                occurredAtMs = row.createdAtMs,
            )
        }
        TraceFeature(
            episodeId = EpisodeId.parseOrNull(episode.id) ?: return null,
            sequence = sequence,
            sources = sources,
            actionType = TraceActionType.entries.firstOrNull { it.name == first.actionType }
                ?: return null,
            canonicalActionName = first.actionName,
            toolSchemaFingerprint = first.toolSchemaFingerprint,
            outcomeClass = TraceOutcomeClass.entries.firstOrNull { it.name == first.outcomeClass }
                ?: return null,
            errorCode = first.errorCode,
            stateSummary = first.stateSummary.toSanitizedOrNull(),
            observationSummary = first.observationSummary.toSanitizedOrNull(),
            inputTokens = first.inputTokenCount.toMetric(),
            outputTokens = first.outputTokenCount.toMetric(),
            toolCallCount = first.toolCount.toMetric(),
            retryCount = first.retryCount.toMetric(),
            durationMs = first.durationMs.toMetric(),
            producerIdentity = first.featureSchemaIdentity,
            quality = first.quality,
            createdAtMs = first.createdAtMs,
        )
    }

private fun String?.toSanitizedOrNull(): SanitizedTraceSummary? = when {
    this == null -> null
    else -> (TraceSanitizer.sanitize(this) as? TraceSanitizationResult.Accepted)?.summary
}

private fun Long?.toMetric(): TraceMetric<Long> = this?.let { TraceMetric.Known(it) }
    ?: TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED)

private fun Int?.toMetric(): TraceMetric<Int> = this?.let { TraceMetric.Known(it) }
    ?: TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED)

private suspend fun LearningTraceFeatureEntity.toValidSource(
    database: LearningDatabase,
    episode: LearningEpisodeEntity,
): LearningSourceRef? {
    if (sourceType != LearningSourceKind.CONVERSATION_MESSAGE.name) return null
    val revision = sourceRevision ?: return null
    val validity = database.episodeDao().findSourceValidity(
        episode.streamId,
        episode.replayGeneration,
        episode.scopeKind,
        episode.scopeId,
        sourceType,
        sourceId,
        revision,
    ) ?: return null
    if (
        validity.state != LearningSourceValidityState.VALID.name ||
        validity.integritySha256 == null ||
        validity.replayGeneration != episode.replayGeneration
    ) {
        return null
    }
    return LearningSourceRef(
        sourceKind = LearningSourceKind.entries.firstOrNull { it.name == sourceType } ?: return null,
        sourceId = sourceId,
        sourceRevision = revision,
        missingRevisionReason = null,
        databaseStreamId = Uuid.parse(episode.streamId),
        scope = LearningScope.parseOrNull(episode.scopeKind, episode.scopeId) ?: return null,
        occurredAtMs = createdAtMs,
    )
}

private suspend fun LearningTraceFeatureEntity.hasExactValidSource(
    database: LearningDatabase,
    episode: LearningEpisodeEntity,
): Boolean {
    val revision = sourceRevision ?: return false
    val validity = database.episodeDao().findSourceValidity(
        episode.streamId,
        episode.replayGeneration,
        episode.scopeKind,
        episode.scopeId,
        sourceType,
        sourceId,
        revision,
    ) ?: return false
    return validity.state == LearningSourceValidityState.VALID.name &&
        validity.integritySha256?.matches(LOWER_SHA256) == true &&
        validity.replayGeneration == episode.replayGeneration
}

private fun LearningRewardWindowEntity.toOpenDomainWindow(): RewardWindow? {
    if (
        goalKnowledge != LearningRewardKnowledge.UNKNOWN.name ||
        processKnowledge != LearningRewardKnowledge.UNKNOWN.name ||
        userKnowledge != LearningRewardKnowledge.UNKNOWN.name
    ) return null
    return RewardWindow(
        episodeId = EpisodeId.parseOrNull(episodeId) ?: return null,
        openedAtMs = openedAtMs,
        closeAfterMs = closeAfterMs,
        state = RewardWindowState.OPEN,
        goal = RewardComponent.Unknown(RewardUnknownReason.valueOf(requireNotNull(goalUnknownReason))),
        process = RewardComponent.Unknown(RewardUnknownReason.valueOf(requireNotNull(processUnknownReason))),
        user = RewardComponent.Unknown(RewardUnknownReason.valueOf(requireNotNull(userUnknownReason))),
        rewardConfigVersion = rewardConfigIdentity,
        closedAtMs = null,
        revision = 1,
    )
}

private suspend fun LearningDatabase.ensureOpenRewardWindow(
    episode: LearningEpisodeEntity,
    event: LearningInboxEventEntity,
): LearningRewardWindowEntity {
    episodeDao().findRewardWindowByEpisode(episode.id)?.let { return it }
    val openedAt = requireNotNull(episode.finalizedAtMs)
    val censored = episode.status == StoredLearningEpisodeStatus.CENSORED.name
    val entity = LearningRewardWindowEntity(
        id = "reward-window-v1:" + LearningCanonicalId.digest("reward-window-v1", listOf(episode.id)),
        episodeId = episode.id,
        scopeKind = episode.scopeKind,
        scopeId = episode.scopeId,
        openedAtMs = openedAt,
        closeAfterMs = if (censored) openedAt else Math.addExact(openedAt, REWARD_WINDOW_DURATION_MS),
        state = LearningRewardWindowState.OPEN.name,
        goalKnowledge = LearningRewardKnowledge.UNKNOWN.name,
        goalValue = null,
        goalUnknownReason = RewardUnknownReason.NO_SIGNAL.name,
        goalEvidenceSha256 = null,
        processKnowledge = LearningRewardKnowledge.UNKNOWN.name,
        processValue = null,
        processUnknownReason = RewardUnknownReason.NO_SIGNAL.name,
        processEvidenceSha256 = null,
        userKnowledge = LearningRewardKnowledge.UNKNOWN.name,
        userValue = null,
        userUnknownReason = RewardUnknownReason.NO_SIGNAL.name,
        userEvidenceSha256 = null,
        weakLabel = null,
        rewardConfigIdentity = REWARD_CONFIG_IDENTITY,
        closedAtMs = null,
        updatedAtMs = event.ingestedAtMs,
    )
    val inserted = episodeDao().insertRewardWindowIgnore(entity)
    return if (inserted != -1L) entity else requireNotNull(episodeDao().findRewardWindowByEpisode(episode.id))
}

private suspend fun LearningDatabase.enqueueExact(job: LearningJobEntity) {
    if (jobDao().insertIgnore(job) != -1L) return
    val existing = jobDao().findByDedupeKey(job.dedupeKey)
        ?: throw IllegalStateException("P1 child job conflict")
    require(
        existing.id == job.id &&
            existing.jobType == job.jobType &&
            existing.sourceEventId == job.sourceEventId &&
            existing.streamId == job.streamId &&
            existing.scopeKind == job.scopeKind &&
            existing.scopeId == job.scopeId &&
            existing.replayGeneration == job.replayGeneration &&
            existing.algorithmIdentity == job.algorithmIdentity &&
            existing.promptIdentity == job.promptIdentity &&
            existing.providerKindIdentity == job.providerKindIdentity &&
            existing.modelIdentity == job.modelIdentity &&
            existing.providerIdentity == job.providerIdentity &&
            existing.providerConfigurationIdentity == job.providerConfigurationIdentity &&
            existing.providerConfigGeneration == job.providerConfigGeneration &&
            existing.sourceSchemaIdentity == job.sourceSchemaIdentity &&
            existing.toolsetIdentity == job.toolsetIdentity &&
            existing.outputSchemaIdentity == job.outputSchemaIdentity
    ) { "P1 child job replay identity conflict" }
}

private fun providerFreeSpec(
    type: LearningJobType,
    algorithm: String,
    sourceSchema: String,
    outputSchema: String,
) = P1LearningJobFrozenSpec(
    jobType = type,
    algorithmIdentity = algorithm,
    promptIdentity = "no-provider-prompt-v1",
    providerKindIdentity = LearningJobProviderKindIdentity.NONE.wireCode,
    modelIdentity = NO_PROVIDER_MODEL_IDENTITY,
    providerIdentity = NO_PROVIDER_IDENTITY,
    providerConfigurationIdentity = NO_PROVIDER_CONFIGURATION_IDENTITY,
    providerConfigGeneration = 0,
    sourceSchemaIdentity = sourceSchema,
    toolsetIdentity = "authority-event-only-v1",
    outputSchemaIdentity = outputSchema,
)

private fun modelSpec(
    type: LearningJobType,
    algorithm: String,
    prompt: String,
    sourceSchema: String,
    toolset: String,
    outputSchema: String,
    model: ResolvedLearningModel,
    providerConfigGeneration: Long = 0L,
) = P1LearningJobFrozenSpec(
    jobType = type,
    algorithmIdentity = algorithm,
    promptIdentity = prompt,
    providerKindIdentity = model.providerKind.toJobWireCode(),
    modelIdentity = model.modelIdentityDigest,
    providerIdentity = model.providerIdentityDigest,
    providerConfigurationIdentity = model.configurationDigest,
    providerConfigGeneration = providerConfigGeneration,
    sourceSchemaIdentity = sourceSchema,
    toolsetIdentity = toolset,
    outputSchemaIdentity = outputSchema,
)

private fun LearningProviderKind.toJobWireCode(): String = when (this) {
    LearningProviderKind.LOCAL_LITERT -> LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode
    LearningProviderKind.REMOTE -> LearningJobProviderKindIdentity.REMOTE.wireCode
    LearningProviderKind.AICORE -> throw IllegalArgumentException("AICore cannot run Learning jobs")
}

private data class P1ExactProviderEnvelope(
    val manifest: LearningProviderJobManifestEntity,
    val cohort: LearningProviderConfigCohortEntity,
)

private suspend fun LearningDatabase.findExactProviderEnvelope(
    input: LearningJobExecutionInputV1,
): P1ExactProviderEnvelope? {
    val spec = input.executionSpec
    if (
        spec.providerKindIdentity != LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode &&
        spec.providerKindIdentity != LearningJobProviderKindIdentity.REMOTE.wireCode
    ) {
        return null
    }
    if (spec.providerConfigGeneration <= 0L) return null
    val manifest = providerExecutionDao().findExactJobManifest(
        jobId = input.jobId,
        providerKind = spec.providerKindIdentity,
        providerIdentitySha256 = spec.providerIdentity,
        modelIdentitySha256 = spec.modelIdentity,
        configurationIdentitySha256 = spec.providerConfigurationIdentity,
        configurationGeneration = spec.providerConfigGeneration,
    ).singleOrNull() ?: return null
    val cohort = providerExecutionDao().findConfigCohort(manifest.cohortId) ?: return null
    if (
        cohort.providerKind != spec.providerKindIdentity ||
        cohort.providerIdentitySha256 != spec.providerIdentity ||
        cohort.modelIdentitySha256 != spec.modelIdentity ||
        cohort.configurationIdentitySha256 != spec.providerConfigurationIdentity ||
        cohort.configurationGeneration != spec.providerConfigGeneration
    ) return null
    return P1ExactProviderEnvelope(manifest, cohort)
}

private fun P1ExactProviderEnvelope.validateAndResolve(
    input: LearningJobExecutionInputV1,
    gate: P1RuntimeFeatureGate,
    modelClaims: P1LearningModelClaimSource,
    manifestKeyer: me.rerere.rikkahub.execution.ExecutionTokenProvider,
    rebuiltInputIdentity: String,
    rebuiltInputUtf8Bytes: Long,
    expectedMaxOutputTokens: Long,
    expectedMaxOutputUtf8Bytes: Long,
    expectedTimeoutMs: Long,
): ResolvedLearningModel? {
    val spec = input.executionSpec
    val receipt = input.providerManifestReceipt ?: return null
    val attemptAuthority = input.providerAttemptAuthority ?: return null
    if (!gate.reflectionEnabled() && !gate.policyEnabled()) {
        throw P1LearningConfigurationUnavailableException()
    }
    if (!matches(receipt)) return null
    val expectedEstimatedTokens = ((rebuiltInputUtf8Bytes + 3L) / 4L).coerceAtLeast(1L)
    if (
        manifest.jobId != input.jobId ||
        manifest.cohortId != cohort.id ||
        manifest.manifestSchemaVersion !=
            me.rerere.rikkahub.learning.storage.PROVIDER_JOB_MANIFEST_SCHEMA_VERSION ||
        manifest.inputIdentitySha256 != rebuiltInputIdentity ||
        !manifest.runtimeAttestationSha256.matches(LOWER_SHA256) ||
        manifest.redactionPolicyIdentity != P1_PROVIDER_REDACTION_IDENTITY ||
        manifest.fieldCategoriesIdentity != P1_PROVIDER_FIELDS_IDENTITY ||
        manifest.tokenEstimatorIdentity != P1_PROVIDER_TOKEN_ESTIMATOR_IDENTITY ||
        manifest.providerRequestKey != input.stableProviderIdempotencyKey ||
        manifest.providerRequestKey != learningProviderIdempotencyKey(input.jobId) ||
        manifest.inputUtf8Bytes != rebuiltInputUtf8Bytes ||
        manifest.maxInputUtf8Bytes != P1_PROVIDER_MAX_INPUT_UTF8_BYTES ||
        manifest.estimatedInputTokens != expectedEstimatedTokens ||
        manifest.maxOutputTokens != expectedMaxOutputTokens ||
        manifest.maxOutputUtf8Bytes != expectedMaxOutputUtf8Bytes ||
        manifest.maxProviderCalls != 1 ||
        !manifest.hasValidCostReservation(spec.providerKindIdentity) ||
        manifest.timeoutMs != expectedTimeoutMs ||
        manifest.frozenAtMs != input.createdAtMs ||
        cohort.createdAtMs > manifest.frozenAtMs
    ) return null
    val canonicalRequest = providerRequestAuditDigest(
        jobId = input.jobId,
        jobType = spec.jobType.name,
        jobSchemaVersion = spec.jobSchemaVersion,
        manifestSchemaVersion = manifest.manifestSchemaVersion,
        algorithmIdentity = spec.algorithmIdentity,
        promptIdentity = spec.promptIdentity,
        providerKindIdentity = spec.providerKindIdentity,
        modelIdentity = spec.modelIdentity,
        providerIdentity = spec.providerIdentity,
        providerConfigurationIdentity = spec.providerConfigurationIdentity,
        providerConfigGeneration = spec.providerConfigGeneration,
        sourceSchemaIdentity = spec.sourceSchemaIdentity,
        toolsetIdentity = spec.toolsetIdentity,
        outputSchemaIdentity = spec.outputSchemaIdentity,
        cohortId = cohort.id,
        dispatchAttestationDigest = manifest.dispatchAttestationSha256,
        inputIdentity = rebuiltInputIdentity,
        providerRequestKey = manifest.providerRequestKey,
        inputBytes = manifest.inputUtf8Bytes,
        maxInputBytes = manifest.maxInputUtf8Bytes,
        estimatedInputTokens = manifest.estimatedInputTokens,
        maxOutputTokens = manifest.maxOutputTokens,
        maxOutputBytes = manifest.maxOutputUtf8Bytes,
        maxProviderCalls = manifest.maxProviderCalls,
        maxCostMicros = manifest.maxCostMicros,
        timeoutMs = manifest.timeoutMs,
        frozenAtMs = manifest.frozenAtMs,
    )
    val expectedHmac = signProviderRequest(manifestKeyer, canonicalRequest, input.jobId)
    if (!constantTimeShaEquals(expectedHmac, manifest.requestHmacSha256)) return null
    val frozenModel = spec.toResolvedProviderModel(manifest.dispatchAttestationSha256) ?: return null
    val exactCurrentDispatch = modelClaims.matchesExactCurrentConsentAndDispatch(
        model = frozenModel,
        templateVersion = spec.promptIdentity,
        inputIdentitySha256 = rebuiltInputIdentity,
        providerRequestKey = manifest.providerRequestKey,
        maxOutputTokens = expectedMaxOutputTokens.toInt(),
        expectedDispatchAttestationSha256 = manifest.dispatchAttestationSha256,
    )
    if (!exactCurrentDispatch && frozenModel.providerKind == LearningProviderKind.REMOTE) {
        // Revocation/configuration drift is not corrupt work. Keep it undispatched and retry only
        // after the exact disclosed target becomes authorized again.
        throw P1LearningConfigurationUnavailableException()
    }
    if (
        attemptAuthority.stableProviderIdempotencyKey != manifest.providerRequestKey ||
        attemptAuthority.expectedDispatchAttestationSha256 !=
        manifest.dispatchAttestationSha256 ||
        !exactCurrentDispatch ||
        !isP1ProviderExecutionAuthorized(
            providerKind = frozenModel.providerKind,
            capabilities = P1ProviderExecutionCapabilities(
                runtimeAttestationSha256 = manifest.dispatchAttestationSha256,
                exactManifestValidated = true,
                durableAttemptAuthorityPresent = true,
                exactRemoteConsent = frozenModel.providerKind != LearningProviderKind.REMOTE ||
                    modelClaims.exactRemoteConsent(frozenModel),
            ),
        )
    ) return null
    return frozenModel
}

private fun LearningProviderJobManifestEntity.hasValidCostReservation(
    providerKindIdentity: String,
): Boolean = when (providerKindIdentity) {
    LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode -> maxCostMicros == 0L
    LearningJobProviderKindIdentity.REMOTE.wireCode ->
        maxCostMicros == REMOTE_PER_ATTEMPT_COST_RESERVATION_MICROS
    else -> false
}

private fun String.toLearningProviderKind(): LearningProviderKind? = when (this) {
    LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode -> LearningProviderKind.LOCAL_LITERT
    LearningJobProviderKindIdentity.REMOTE.wireCode -> LearningProviderKind.REMOTE
    else -> null
}

private fun LearningJobExecutionSpecV1.toResolvedProviderModel(
    dispatchAttestationSha256: String,
): ResolvedLearningModel? {
    val kind = providerKindIdentity.toLearningProviderKind() ?: return null
    val remote = kind == LearningProviderKind.REMOTE
    return ResolvedLearningModel(
        providerKind = kind,
        providerIdentityDigest = providerIdentity,
        modelIdentityDigest = modelIdentity,
        configurationDigest = providerConfigurationIdentity,
        route = LearningRouteCapabilities(
            executionClass = if (remote) {
                LearningExecutionClass.REMOTE_NETWORK
            } else {
                LearningExecutionClass.LOCAL_COMPUTE
            },
            requiresNetwork = remote,
            cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
        ),
        runtimeAttestationDigest = dispatchAttestationSha256.takeUnless { remote },
    )
}

private fun P1ExactProviderEnvelope.matches(
    receipt: LearningProviderManifestReceipt,
): Boolean =
    receipt.cohortId == cohort.id &&
        receipt.providerKind == cohort.providerKind &&
        receipt.providerIdentitySha256 == cohort.providerIdentitySha256 &&
        receipt.modelIdentitySha256 == cohort.modelIdentitySha256 &&
        receipt.configurationIdentitySha256 == cohort.configurationIdentitySha256 &&
        receipt.configurationGeneration == cohort.configurationGeneration &&
        receipt.manifestSchemaVersion == manifest.manifestSchemaVersion &&
        receipt.requestHmacSha256 == manifest.requestHmacSha256 &&
        receipt.inputIdentitySha256 == manifest.inputIdentitySha256 &&
        receipt.runtimeAttestationSha256 == manifest.runtimeAttestationSha256 &&
        receipt.redactionPolicyIdentity == manifest.redactionPolicyIdentity &&
        receipt.fieldCategoriesIdentity == manifest.fieldCategoriesIdentity &&
        receipt.tokenEstimatorIdentity == manifest.tokenEstimatorIdentity &&
        receipt.providerRequestKey == manifest.providerRequestKey &&
        receipt.inputUtf8Bytes == manifest.inputUtf8Bytes &&
        receipt.maxInputUtf8Bytes == manifest.maxInputUtf8Bytes &&
        receipt.estimatedInputTokens == manifest.estimatedInputTokens &&
        receipt.maxOutputTokens == manifest.maxOutputTokens &&
        receipt.maxOutputUtf8Bytes == manifest.maxOutputUtf8Bytes &&
        receipt.maxProviderCalls == manifest.maxProviderCalls &&
        receipt.maxCostMicros == manifest.maxCostMicros &&
        receipt.timeoutMs == manifest.timeoutMs &&
        receipt.frozenAtMs == manifest.frozenAtMs

private fun constantTimeShaEquals(left: String, right: String): Boolean =
    java.security.MessageDigest.isEqual(
        left.toByteArray(Charsets.US_ASCII),
        right.toByteArray(Charsets.US_ASCII),
    )

private fun policyToolsetIdentity(evidence: List<P1DistillationEvidence>): String =
    "policy-toolset-v1:" + LearningCanonicalId.digest(
        "policy-toolset-v1",
        evidence.flatMap(P1DistillationEvidence::toolSchemas).distinct().sorted(),
    )

private fun LearningInboxEventEntity.toCatchUpInput(): LearningJobExecutionInputV1 =
    LearningJobExecutionInputV1(
        jobId = "catch-up:${eventId}",
        sourceEventId = eventId,
        streamId = streamId,
        scopeKind = requireNotNull(scopeKind),
        scopeId = requireNotNull(scopeId),
        replayGeneration = replayGeneration,
        createdAtMs = ingestedAtMs,
        attempt = 0,
        stableProviderIdempotencyKey = "catch-up",
        executionSpec = LearningJobExecutionSpecs.forNewP0Job(
            LearningJobType.ASSEMBLE_EPISODE_SHADOW,
        ),
    )

private val REFLECTABLE_EPISODE_STATES = setOf(
    StoredLearningEpisodeStatus.SUCCESS.name,
    StoredLearningEpisodeStatus.PARTIAL.name,
    StoredLearningEpisodeStatus.FAILURE.name,
)
private val LOWER_SHA256 = Regex("[0-9a-f]{64}")
private const val REFLECTION_ALGORITHM_IDENTITY = "reflection-handler-v2"
private const val REDACTED_TRACE_TOOLSET_IDENTITY = "redacted-trace-toolset-v1"
private const val REWARD_ALGORITHM_IDENTITY = "reward-close-v1"
private const val REWARD_SOURCE_SCHEMA_IDENTITY = "reward-window-input-v1"
private const val REWARD_CONFIG_IDENTITY = "reward-config-v1"
private const val REWARD_WINDOW_DURATION_MS = 24L * 60L * 60L * 1_000L
private const val DISTILLATION_ALGORITHM_IDENTITY = "reasoning-policy-distiller-v1"
private const val MAX_REFLECTION_TRACE_ROWS = 256
private const val MAX_POLICY_EVIDENCE = 16
private const val MAX_REWARD_SIGNALS = 64
private const val MAX_DISTILLATION_SCAN = MAX_POLICY_EVIDENCE
private const val CATCH_UP_BATCH_SIZE = 64
private const val P1_EVENT_SCHEMA_VERSION = 2

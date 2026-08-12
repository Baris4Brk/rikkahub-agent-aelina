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
import me.rerere.rikkahub.learning.reflection.ReflectionInputComposeResult
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
import me.rerere.rikkahub.learning.storage.LearningRewardWindowEntity
import me.rerere.rikkahub.learning.storage.LearningRewardWindowState
import me.rerere.rikkahub.learning.storage.LearningRetentionPolicyV1
import me.rerere.rikkahub.learning.storage.LearningRetentionResult
import me.rerere.rikkahub.learning.storage.LearningRetentionStore
import me.rerere.rikkahub.learning.storage.LearningSourceValidityState
import me.rerere.rikkahub.learning.storage.LearningTraceFeatureEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.LearningToolSignature
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

/**
 * Provider-backed P1 work stays fail-closed until execution bytes/config have a durable attestation.
 * Remote additionally needs transport idempotency; LiteRT needs artifact/runtime-preference identity.
 */
internal const val P1_REMOTE_TRANSPORT_IDEMPOTENCY_READY = false
internal const val P1_LOCAL_RUNTIME_ATTESTATION_READY = false
internal const val P1_DURABLE_PROVIDER_BUDGET_READY = false
internal const val P1_FROZEN_PROVIDER_INPUT_READY = false

internal fun isP1ProviderExecutionAuthorized(
    providerKind: LearningProviderKind,
    remoteFlagEnabled: Boolean,
    remoteTransportIdempotencyReady: Boolean = P1_REMOTE_TRANSPORT_IDEMPOTENCY_READY,
    localRuntimeAttestationReady: Boolean = P1_LOCAL_RUNTIME_ATTESTATION_READY,
    durableProviderBudgetReady: Boolean = P1_DURABLE_PROVIDER_BUDGET_READY,
    frozenProviderInputReady: Boolean = P1_FROZEN_PROVIDER_INPUT_READY,
): Boolean = when (providerKind) {
    LearningProviderKind.LOCAL_LITERT ->
        localRuntimeAttestationReady && durableProviderBudgetReady && frozenProviderInputReady
    LearningProviderKind.REMOTE ->
        remoteFlagEnabled && remoteTransportIdempotencyReady && durableProviderBudgetReady &&
            frozenProviderInputReady
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
    fun resolve(): ResolvedLearningModel? {
        val model = (host.resolveSingleAuthorizedForClaim() as? LearningModelResolution.Resolved)
            ?.model ?: return null
        // A durable job can be recovered after process death. Until the provider transport accepts
        // the job's stable idempotency key, remote retry could duplicate a paid call.
        if (!isP1ProviderExecutionAuthorized(model.providerKind, gate.remoteReflectionAllowed())) {
            return null
        }
        return model
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
            validateRootEvent(event) ?: return null
            return EpisodeAssemblyMutation.ObserveAdmission
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
        val observedTaskSignature = event.episodeTaskSignature()
        val traceMaterial = event.toTraceMaterial(anchor.episodeId)
        return EpisodeAssemblyMutation.Complete(
            current = currentEntity?.toDomainSnapshot(),
            authority = anchor,
            taskSignature = observedTaskSignature
                ?: currentEntity?.let { TaskSignatureV1.parseOrNull(it.taskSignature) }
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

    private suspend fun LearningInboxEventEntity.episodeTaskSignature(): TaskSignatureV1? {
        val rootCommandId = lineageId ?: return null
        val finalCommandId = commandId ?: return null
        val events = database.inboxDao().listExecutionTerminalsForEpisode(
            streamId = streamId,
            replayGeneration = replayGeneration,
            scopeKind = scopeKind ?: return null,
            scopeId = scopeId ?: return null,
            conversationId = conversationId ?: return null,
            rootCommandId = rootCommandId,
            finalCommandId = finalCommandId,
            limit = MAX_EPISODE_TOOL_EVENTS + 1,
        )
        return taskSignature(events)
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
) : ReflectionJobMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): ReflectionJobMaterial? {
        if (!gate.reflectionEnabled()) throw P1LearningConfigurationUnavailableException()
        if (input.executionSpec.jobType != LearningJobType.REFLECT_EPISODE_V1) return null
        if (
            input.executionSpec.algorithmIdentity != REFLECTION_ALGORITHM_IDENTITY ||
            input.executionSpec.promptIdentity != ReflectionPrompt.TEMPLATE_VERSION ||
            input.executionSpec.sourceSchemaIdentity != REFLECTION_SOURCE_SCHEMA_IDENTITY ||
            input.executionSpec.outputSchemaIdentity != "episode-lesson-v1"
        ) return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (
            !event.matches(input) ||
            event.eventTypeCode != "COMMAND_TERMINAL" ||
            event.eventSchemaVersion != P1_EVENT_SCHEMA_VERSION
        ) return null
        val episode = event.findEpisode(database) ?: return null
        if (episode.status !in REFLECTABLE_EPISODE_STATES) return null
        val expectedExecutionTraces = database.inboxDao().countExecutionTerminalsForEpisode(
            episode.streamId,
            episode.replayGeneration,
            episode.scopeKind,
            episode.scopeId,
            episode.conversationId,
            episode.rootCommandId,
            episode.finalCommandId ?: episode.rootCommandId,
        )
        if (database.episodeDao().countExecutionTraceSources(episode.id) != expectedExecutionTraces) {
            throw IllegalStateException("execution_traces_not_ready")
        }
        val traceRows = database.episodeDao().listTrace(episode.id, MAX_REFLECTION_TRACE_ROWS)
        if (
            traceRows.isEmpty() ||
            traceRows.any { row ->
                row.sourceType == LearningSourceKind.CONVERSATION_MESSAGE.name &&
                    !row.hasExactValidSource(database, episode)
            } ||
            traceRows.none { row ->
                row.sourceType == LearningSourceKind.CONVERSATION_MESSAGE.name &&
                    row.hasExactValidSource(database, episode)
            }
        ) return null
        val traces = traceRows.toDomainTrace(episode) ?: return null
        val composed = ReflectionInputComposer.compose(
            episodeId = EpisodeId.parseOrNull(episode.id) ?: return null,
            episodeStatus = episode.status.toDomainStatus() ?: return null,
            features = traces,
        ) as? ReflectionInputComposeResult.Composed ?: return null
        return ReflectionJobMaterial(
            input = composed.input,
            frozenModel = input.executionSpec.toResolvedModel(gate) ?: return null,
        )
    }
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
) : PolicyDistillationMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): PolicyDistillationMaterial? {
        if (!gate.policyEnabled()) throw P1LearningConfigurationUnavailableException()
        if (input.executionSpec.jobType != LearningJobType.DISTILL_POLICY_V1) return null
        if (
            input.executionSpec.algorithmIdentity != DISTILLATION_ALGORITHM_IDENTITY ||
            input.executionSpec.promptIdentity != PolicyDistillationPrompt.TEMPLATE_VERSION ||
            input.executionSpec.outputSchemaIdentity != "policy-candidate-v1"
        ) return null
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (!event.matches(input) || event.eventSchemaVersion != P1_EVENT_SCHEMA_VERSION) return null
        val triggerEpisode = event.findEpisode(database) ?: return null
        val expectedHash = input.executionSpec.sourceSchemaIdentity
            .removePrefix(POLICY_INPUT_SCHEMA_PREFIX)
            .takeIf { it.matches(LOWER_SHA256) } ?: return null
        val eligible = database.distillationEvidence(
            triggerEpisode.scopeKind,
            triggerEpisode.scopeId,
            triggerEpisode.taskSignature,
            input.createdAtMs,
        )
        val frozenEvidence = (2..minOf(eligible.size, MAX_POLICY_EVIDENCE)).firstNotNullOfOrNull { size ->
            eligible.take(size).takeIf {
                PolicyCandidateIdFactory.inputSetHash(it.map(P1DistillationEvidence::handle)) ==
                    expectedHash
            }
        } ?: return null
        if (policyToolsetIdentity(frozenEvidence) != input.executionSpec.toolsetIdentity) return null
        val aliases = linkedMapOf<String, PolicyEvidenceHandle>()
        frozenEvidence.forEachIndexed { index, evidence -> aliases["E${index + 1}"] = evidence.handle }
        val toolSchemas = frozenEvidence.flatMap(P1DistillationEvidence::toolSchemas).toSortedSet()
        val scope = LearningScope.parseOrNull(input.scopeKind, input.scopeId) ?: return null
        val policyInput = PolicyDistillationInput(
            scope = scope,
            taskSignature = TaskSignatureV1.parseOrNull(triggerEpisode.taskSignature) ?: return null,
            evidenceAllowlist = aliases,
            toolSchemaAllowlist = toolSchemas,
            producerIdentity = input.executionSpec.providerIdentity,
            modelIdentity = input.executionSpec.modelIdentity,
            promptVersion = input.executionSpec.promptIdentity,
        )
        return PolicyDistillationMaterial(
            input = policyInput,
            payloadJson = frozenEvidence.toPolicyPayload(aliases),
            frozenModel = input.executionSpec.toResolvedModel(gate) ?: return null,
        )
    }
}

internal class RoomP1DerivedJobEnqueuer(
    private val gate: P1RuntimeFeatureGate,
    private val modelClaims: P1LearningModelClaimSource,
) : P1DerivedJobEnqueuer {
    override suspend fun afterEpisodeCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        episode: LearningEpisodeEntity,
    ) {
        val model = if (gate.reflectionEnabled()) modelClaims.resolve() else null
        val reflectionSpec = model?.let {
            modelSpec(
                type = LearningJobType.REFLECT_EPISODE_V1,
                algorithm = REFLECTION_ALGORITHM_IDENTITY,
                prompt = ReflectionPrompt.TEMPLATE_VERSION,
                sourceSchema = REFLECTION_SOURCE_SCHEMA_IDENTITY,
                toolset = REDACTED_TRACE_TOOLSET_IDENTITY,
                outputSchema = "episode-lesson-v1",
                model = it,
            )
        }
        val plan = P1DerivedCascadePolicy.afterEpisode(
            episodeStatus = episode.status,
            captureEnabled = gate.captureEnabled(),
            reflectionEnabled = gate.reflectionEnabled(),
            policyEnabled = gate.policyEnabled(),
            hasStableSource = database.episodeDao().countValidStableTraceSources(episode.id) > 0L &&
                episode.hasCompleteExecutionTraceProjection(database),
            lessonAlreadyExists = database.episodeDao().findLesson(episode.id, 1) != null,
            // Exact replay/model-cohort dedupe is enforced by enqueueExact below. An old cohort
            // must never suppress a job for the currently frozen provider configuration.
            reflectionJobAlreadyExists = false,
            modelConfigured = model != null,
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
        if (plan.enqueueReflection) {
            database.enqueueExact(
                P1LearningJobFactory.create(
                    source = event,
                    frozen = requireNotNull(reflectionSpec),
                    createdAtMs = event.ingestedAtMs,
                ),
            )
        }
        if (plan.attemptPolicyDistillation) maybeEnqueueDistillation(database, episode)
    }

    override suspend fun afterLessonCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        lesson: LearningEpisodeLessonEntity,
    ) {
        val episode = database.episodeDao().findEpisode(lesson.episodeId) ?: return
        maybeEnqueueDistillation(database, episode)
    }

    override suspend fun afterRewardCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        reward: LearningRewardWindowEntity,
    ) {
        val episode = database.episodeDao().findEpisode(reward.episodeId) ?: return
        maybeEnqueueDistillation(database, episode)
    }

    override suspend fun afterExecutionTraceCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        episode: LearningEpisodeEntity,
    ) {
        // This runs in the trace job's fenced output transaction. It is the deterministic wake-up
        // when Episode assembly originally observed an incomplete execution-trace set.
        maybeEnqueueReflection(database, episode, input.createdAtMs)
    }

    private suspend fun maybeEnqueueDistillation(
        database: LearningDatabase,
        triggerEpisode: LearningEpisodeEntity,
    ) {
        val evidence = database.distillationEvidence(
            triggerEpisode.scopeKind,
            triggerEpisode.scopeId,
            triggerEpisode.taskSignature,
            Long.MAX_VALUE,
        )
        if (!P1DerivedCascadePolicy.afterLessonOrReward(gate.policyEnabled(), evidence.size)) return
        val model = modelClaims.resolve() ?: return
        // One deterministic, newest-first bounded cohort yields at most one provider-effect job for
        // this cycle. The full evidence set participates in the frozen input hash/dedupe identity.
        val frozenEvidence = P1DerivedCascadePolicy.singleDistillationCohort(
            evidence,
            MAX_POLICY_EVIDENCE,
        )
        val sourceEpisode = frozenEvidence.first().episode
        val sourceEvent = sourceEpisode.terminalEvent(database) ?: return
        val inputHash = PolicyCandidateIdFactory.inputSetHash(
            frozenEvidence.map(P1DistillationEvidence::handle),
        )
        database.enqueueExact(
            P1LearningJobFactory.create(
                source = sourceEvent,
                frozen = modelSpec(
                    type = LearningJobType.DISTILL_POLICY_V1,
                    algorithm = DISTILLATION_ALGORITHM_IDENTITY,
                    prompt = PolicyDistillationPrompt.TEMPLATE_VERSION,
                    sourceSchema = "$POLICY_INPUT_SCHEMA_PREFIX$inputHash",
                    toolset = policyToolsetIdentity(frozenEvidence),
                    outputSchema = "policy-candidate-v1",
                    model = model,
                ),
                createdAtMs = maxOf(
                    sourceEvent.ingestedAtMs,
                    frozenEvidence.maxOf {
                        maxOf(
                            requireNotNull(it.episode.finalizedAtMs),
                            it.lesson.updatedAtMs,
                            it.reward.updatedAtMs,
                        )
                    },
                ),
            ),
        )
    }

    private suspend fun maybeEnqueueReflection(
        database: LearningDatabase,
        episode: LearningEpisodeEntity,
        materializedAtMs: Long,
    ) {
        if (!gate.reflectionEnabled() || episode.status !in REFLECTABLE_EPISODE_STATES) return
        if (database.episodeDao().findLesson(episode.id, 1) != null) return
        val model = modelClaims.resolve() ?: return
        val event = episode.terminalEvent(database) ?: return
        if (
            database.episodeDao().countValidStableTraceSources(episode.id) <= 0L ||
            !episode.hasCompleteExecutionTraceProjection(database)
        ) return
        database.enqueueExact(
            P1LearningJobFactory.create(
                source = event,
                frozen = modelSpec(
                    type = LearningJobType.REFLECT_EPISODE_V1,
                    algorithm = REFLECTION_ALGORITHM_IDENTITY,
                    prompt = ReflectionPrompt.TEMPLATE_VERSION,
                    sourceSchema = REFLECTION_SOURCE_SCHEMA_IDENTITY,
                    toolset = REDACTED_TRACE_TOOLSET_IDENTITY,
                    outputSchema = "episode-lesson-v1",
                    model = model,
                ),
                createdAtMs = maxOf(event.ingestedAtMs, episode.updatedAtMs, materializedAtMs),
            ),
        )
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
        val retention: LearningRetentionResult,
    ) : P1DerivedJobCatchUpResult {
        val didWork: Boolean
            get() = episodesVisited > 0 || redactedInvalidTraceRows > 0 ||
                staleInvalidLessons > 0 || staleInvalidPolicies > 0 ||
                retention.totalMutations > 0

        val workMayRemain: Boolean
            get() = episodesVisited == CATCH_UP_BATCH_SIZE ||
                redactedInvalidTraceRows == CATCH_UP_BATCH_SIZE ||
                staleInvalidLessons == CATCH_UP_BATCH_SIZE ||
                staleInvalidPolicies == CATCH_UP_BATCH_SIZE ||
                retention.anyBatchSaturated(CATCH_UP_BATCH_SIZE)
    }

    data class Failed(
        val code: P1DerivedMaintenanceFailureCode,
    ) : P1DerivedJobCatchUpResult
}

internal class RoomP1DerivedJobCatchUp(
    private val downstream: P1DerivedJobEnqueuer,
    private val gate: P1RuntimeFeatureGate,
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
            val retention = LearningRetentionStore(
                database = database,
                policy = LearningRetentionPolicyV1 { frozenNowMs },
                batchLimit = CATCH_UP_BATCH_SIZE,
            ).sweepOnce()
            P1DerivedJobCatchUpResult.Completed(
                episodesVisited = episodesVisited,
                redactedInvalidTraceRows = invalidState.redactedTraceRows,
                staleInvalidLessons = invalidState.staleLessons,
                staleInvalidPolicies = invalidState.stalePolicies,
                retention = retention,
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

private data class P1InvalidDerivedStateResult(
    val redactedTraceRows: Int,
    val staleLessons: Int,
    val stalePolicies: Int,
)

private val LearningRetentionResult.totalMutations: Int
    get() = censoredOpenEpisodes + deletedPolicies + deletedPolicyRevisions + deletedLessons +
        deletedTraceFeatures + deletedRewardWindows + deletedEpisodes +
        deletedSourceValidityRows

private fun LearningRetentionResult.anyBatchSaturated(batchSize: Int): Boolean = listOf(
    censoredOpenEpisodes,
    deletedPolicies,
    deletedPolicyRevisions,
    deletedLessons,
    deletedTraceFeatures,
    deletedRewardWindows,
    deletedEpisodes,
    deletedSourceValidityRows,
).any { it == batchSize }

/** Production composition; every resolver is scoped to the one open LearningDatabase instance. */
internal class ProductionP1LearningRuntimeDependencyFactory(
    featureFlags: LearningFeatureFlagSource,
    private val backgroundClient: BackgroundGenerationClient,
    backgroundHost: SettingsBackedBackgroundGenerationHost,
    mainDatabase: AppDatabase,
) : P1LearningRuntimeDependencyFactory {
    private val gate = P1RuntimeFeatureGate(featureFlags)
    private val modelClaims = P1LearningModelClaimSource(backgroundHost, gate)
    private val mainSources = RoomP1MainSourceAuthorityReader(mainDatabase)

    override fun create(database: LearningDatabase): P1LearningRuntimeDependencies {
        val downstream = RoomP1DerivedJobEnqueuer(gate, modelClaims)
        return P1LearningRuntimeDependencies(
            episodeAssemblyResolver = RoomEpisodeAssemblyMaterialResolver(database, mainSources, gate),
            executionTraceResolver = RoomExecutionTraceJobMaterialResolver(database, gate),
            reflectionResolver = RoomReflectionJobMaterialResolver(database, gate),
            backgroundGenerationClient = backgroundClient,
            rewardResolver = RoomRewardJobMaterialResolver(database, gate),
            policyDistillationResolver = RoomPolicyDistillationMaterialResolver(database, gate),
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
                if (!gate.captureEnabled()) throw P1LearningConfigurationUnavailableException()
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
            catchUp = RoomP1DerivedJobCatchUp(downstream, gate),
            readiness = P1LearningRuntimeReadiness(
                episodeAssembly = gate.readiness(gate::captureEnabled),
                executionTrace = gate.readiness(gate::captureEnabled),
                reflection = gate.modelReadiness(gate::reflectionEnabled, modelClaims),
                reward = gate.readiness(gate::captureEnabled),
                policyDistillation = gate.modelReadiness(gate::policyEnabled, modelClaims),
                sourceInvalidation = gate.readiness(gate::captureEnabled),
            ),
        )
    }
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
    val outcome = when (episode.status) {
        StoredLearningEpisodeStatus.SUCCESS.name -> PolicyEvidenceAuthorityOutcome.SUCCESS
        StoredLearningEpisodeStatus.FAILURE.name -> PolicyEvidenceAuthorityOutcome.FAILURE
        else -> return@mapNotNull null
}
    val lesson = episodeDao().findLesson(episode.id, 1)
        ?.takeIf { it.state == LearningLessonState.VALID.name } ?: return@mapNotNull null
    val reward = episodeDao().findRewardWindowByEpisode(episode.id)
        ?.takeIf {
            it.state == LearningRewardWindowState.CLOSED.name && it.hasKnownRewardAuthority()
        } ?: return@mapNotNull null
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

private fun taskSignature(
    executionEvents: List<LearningInboxEventEntity>,
): TaskSignatureV1? {
    val tools = executionEvents.mapNotNull { execution ->
        val name = execution.toolName?.takeIf { it.matches(Regex("[a-z][a-z0-9_.-]{0,95}")) }
            ?: return@mapNotNull null
        val schema = execution.toolSchemaFingerprint?.takeIf(LOWER_SHA256::matches)
            ?: return@mapNotNull null
        LearningToolSignature(name, schema)
    }.toSet()
    if (tools.size > MAX_EPISODE_TOOL_EVENTS) return null
    return TaskSignatureV1.create(
        taskClass = if (tools.isEmpty()) LearningTaskClass.OTHER else LearningTaskClass.AUTOMATION,
        languageClass = LearningLanguageClass.UNKNOWN,
        modalityClass = LearningModalityClass.TEXT_ONLY,
        tools = tools,
    )
}

private fun defaultTaskSignature(): TaskSignatureV1 = requireNotNull(taskSignature(emptyList()))

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

internal fun LearningRewardWindowEntity.hasKnownRewardAuthority(): Boolean =
    goalKnowledge == LearningRewardKnowledge.KNOWN.name ||
        processKnowledge == LearningRewardKnowledge.KNOWN.name ||
        userKnowledge == LearningRewardKnowledge.KNOWN.name

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
) = P1LearningJobFrozenSpec(
    jobType = type,
    algorithmIdentity = algorithm,
    promptIdentity = prompt,
    providerKindIdentity = model.providerKind.toJobWireCode(),
    modelIdentity = model.modelIdentityDigest,
    providerIdentity = model.providerIdentityDigest,
    providerConfigurationIdentity = model.configurationDigest,
    providerConfigGeneration = 0,
    sourceSchemaIdentity = sourceSchema,
    toolsetIdentity = toolset,
    outputSchemaIdentity = outputSchema,
)

private fun LearningProviderKind.toJobWireCode(): String = when (this) {
    LearningProviderKind.LOCAL_LITERT -> LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode
    LearningProviderKind.REMOTE -> LearningJobProviderKindIdentity.REMOTE.wireCode
    LearningProviderKind.AICORE -> throw IllegalArgumentException("AICore cannot run Learning jobs")
}

private fun LearningJobExecutionSpecV1.toResolvedModel(
    gate: P1RuntimeFeatureGate,
): ResolvedLearningModel? {
    val remote = when (providerKindIdentity) {
        LearningJobProviderKindIdentity.LOCAL_LITERT.wireCode -> false
        LearningJobProviderKindIdentity.REMOTE.wireCode -> true
        else -> return null
    }
    val providerKind = if (remote) LearningProviderKind.REMOTE else LearningProviderKind.LOCAL_LITERT
    if (!isP1ProviderExecutionAuthorized(providerKind, gate.remoteReflectionAllowed())) {
        throw P1LearningConfigurationUnavailableException()
    }
    if (
        !providerIdentity.matches(LOWER_SHA256) ||
        !modelIdentity.matches(LOWER_SHA256) ||
        !providerConfigurationIdentity.matches(LOWER_SHA256)
    ) return null
    return ResolvedLearningModel(
        providerKind = providerKind,
        providerIdentityDigest = providerIdentity,
        modelIdentityDigest = modelIdentity,
        configurationDigest = providerConfigurationIdentity,
        route = LearningRouteCapabilities(
            executionClass = if (remote) LearningExecutionClass.REMOTE_NETWORK else LearningExecutionClass.LOCAL_COMPUTE,
            requiresNetwork = remote,
            cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
        ),
    )
}

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
private const val REFLECTION_ALGORITHM_IDENTITY = "reflection-handler-v1"
private const val REFLECTION_SOURCE_SCHEMA_IDENTITY = "reflection-input-v1"
private const val REDACTED_TRACE_TOOLSET_IDENTITY = "redacted-trace-toolset-v1"
private const val REWARD_ALGORITHM_IDENTITY = "reward-close-v1"
private const val REWARD_SOURCE_SCHEMA_IDENTITY = "reward-window-input-v1"
private const val REWARD_CONFIG_IDENTITY = "reward-config-v1"
private const val REWARD_WINDOW_DURATION_MS = 24L * 60L * 60L * 1_000L
private const val DISTILLATION_ALGORITHM_IDENTITY = "reasoning-policy-distiller-v1"
private const val POLICY_INPUT_SCHEMA_PREFIX = "policy-input-set-v1:"
private const val MAX_REFLECTION_TRACE_ROWS = 256
private const val MAX_POLICY_EVIDENCE = 16
private const val MAX_DISTILLATION_SCAN = MAX_POLICY_EVIDENCE
private const val CATCH_UP_BATCH_SIZE = 64
private const val P1_EVENT_SCHEMA_VERSION = 2
private const val MAX_EPISODE_TOOL_EVENTS = 16

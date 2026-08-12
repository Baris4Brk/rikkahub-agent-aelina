package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.episode.EpisodeAssemblyJobOutput
import me.rerere.rikkahub.learning.episode.EpisodeBoundaryReason
import me.rerere.rikkahub.learning.episode.LearningEpisodeStatus as DomainEpisodeStatus
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.policy.PolicyCandidateJobOutput
import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.policy.PolicyEvidenceAuthorityOutcome
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.P1PolicyEvidenceRecalculator
import me.rerere.rikkahub.learning.policy.P1PolicyEvidenceSignal
import me.rerere.rikkahub.learning.policy.PolicyEvidencePolarity
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.policy.PolicyMutationStore
import me.rerere.rikkahub.learning.policy.PolicyMutationTransaction
import me.rerere.rikkahub.learning.policy.ValidatingPolicyMutationStore
import me.rerere.rikkahub.learning.reflection.EpisodeLessonJobOutput
import me.rerere.rikkahub.learning.reward.RewardComponent
import me.rerere.rikkahub.learning.reward.RewardUnknownReason
import me.rerere.rikkahub.learning.reward.RewardWindowJobOutput
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeBoundaryReason
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningEpisodeLessonEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.storage.LearningLessonState
import me.rerere.rikkahub.learning.storage.LearningLessonType
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyEvidencePolarity
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionActor
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionReason
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.storage.LearningRewardKnowledge
import me.rerere.rikkahub.learning.storage.LearningRewardWindowEntity
import me.rerere.rikkahub.learning.storage.LearningSourceValidityState
import me.rerere.rikkahub.learning.storage.PolicyEvidenceEntity
import me.rerere.rikkahub.learning.storage.PolicyRevisionEntity
import me.rerere.rikkahub.learning.storage.LearningSourceValidityEntity
import me.rerere.rikkahub.learning.storage.LearningTraceFeatureEntity
import me.rerere.rikkahub.learning.storage.TraceFeatureEntityMapper
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.trace.ExecutionTraceJobOutput

/** Storage adapter for the pure Episode reducer output. */
internal class EpisodeAssemblyJobOutputCommitter(
    private val downstream: P1DerivedJobEnqueuer = NoOpP1DerivedJobEnqueuer,
) :
    LearningJobTypedOutputCommitter<EpisodeAssemblyJobOutput> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: EpisodeAssemblyJobOutput,
    ) {
        val event = input.requireAuthoritativeSourceEvent(database)
        if (output === EpisodeAssemblyJobOutput.NoEpisode) return
        output as EpisodeAssemblyJobOutput.Snapshot
        val snapshot = output.snapshot
        val anchor = snapshot.authority
        require(output.outputSchemaIdentity == input.executionSpec.outputSchemaIdentity)
        require(event.eventSchemaVersion == P1_EVENT_SCHEMA_VERSION) {
            "Only the exact P1 command schema can prove an Episode"
        }
        require(event.eventTypeCode in COMMAND_EPISODE_EVENTS)
        require(event.commandId == anchor.commandId.toString())
        require(event.lineageId == anchor.lineageId.toString())
        require(event.lineageId == anchor.episodeRootCommandId())
        require(event.conversationId == anchor.conversationId.toString())
        require(event.branchAnchorMessageId == anchor.branchAnchorMessageId.toString())
        require(event.branchAnchorMessageRevision == anchor.branchAnchorMessageRevision)
        require(event.conversationSourceRevision != null)
        require(event.sourceRevision != null)
        require(event.messageId == anchor.resultAssistantMessageId?.toString())
        require(event.messageRevision == anchor.resultAssistantMessageRevision)

        val rootCandidates = database.inboxDao().findRootAdmissionCandidates(
            streamId = input.streamId,
            replayGeneration = input.replayGeneration,
            lineageId = anchor.lineageId.toString(),
        )
        require(rootCandidates.size == 1) { "Root command revision is not uniquely recoverable" }
        val root = rootCandidates.single()
        require(root.commandId == anchor.lineageId.toString())
        require(root.parentCommandId == null)
        require(root.branchAnchorMessageId == event.branchAnchorMessageId)
        require(root.branchAnchorMessageRevision == event.branchAnchorMessageRevision)
        require(root.scopeKind == input.scopeKind && root.scopeId == input.scopeId)
        val rootRevision = requireNotNull(root.sourceRevision)

        val desiredStatus = snapshot.status.toStorageStatus()
        val entity = LearningEpisodeEntity(
            id = anchor.episodeId.value,
            streamId = input.streamId,
            replayGeneration = input.replayGeneration,
            scopeKind = input.scopeKind,
            scopeId = input.scopeId,
            conversationId = anchor.conversationId.toString(),
            conversationRevision = requireNotNull(event.conversationSourceRevision),
            rootCommandId = anchor.lineageId.toString(),
            rootCommandRevision = rootRevision,
            finalCommandId = event.commandId.takeIf {
                desiredStatus != StoredLearningEpisodeStatus.OPEN.name
            },
            finalCommandRevision = event.sourceRevision.takeIf {
                desiredStatus != StoredLearningEpisodeStatus.OPEN.name
            },
            lineageId = anchor.lineageId.toString(),
            branchAnchorMessageId = anchor.branchAnchorMessageId.toString(),
            branchAnchorMessageRevision = anchor.branchAnchorMessageRevision,
            resultAssistantMessageId = anchor.resultAssistantMessageId?.toString(),
            resultAssistantMessageRevision = anchor.resultAssistantMessageRevision,
            generationRunId = event.generationRunId,
            executionId = event.executionId,
            taskSignature = snapshot.taskSignature.value,
            status = desiredStatus,
            boundaryReason = snapshot.boundaryReason.toStorageReason(),
            revision = snapshot.revision,
            startedAtMs = snapshot.startedAtMs,
            finalizedAtMs = snapshot.finalizedAtMs,
            createdAtMs = requireNotNull(root.occurredAtMs),
            updatedAtMs = requireNotNull(event.occurredAtMs),
        )
        val existing = database.episodeDao().findEpisode(entity.id)
        val sourceValidities = output.sourceIntegrityByRef.entries.sortedWith(
            compareBy({ it.key.sourceKind.name }, { it.key.sourceId }, { it.key.sourceRevision }),
        ).map { (source, integrity) ->
            val revision = requireNotNull(source.sourceRevision)
            require(source.databaseStreamId.toString() == input.streamId)
            require(source.scope.kind.name == input.scopeKind && source.scope.storageId == input.scopeId)
            val validity = LearningSourceValidityEntity(
                streamId = input.streamId,
                scopeKind = input.scopeKind,
                scopeId = input.scopeId,
                sourceType = source.sourceKind.name,
                sourceId = source.sourceId,
                sourceRevision = revision,
                previousSourceRevision = null,
                state = LearningSourceValidityState.VALID.name,
                integritySha256 = integrity,
                invalidationReason = null,
                authorityEventId = event.eventId,
                replayGeneration = input.replayGeneration,
                occurredAtMs = source.occurredAtMs,
                updatedAtMs = event.ingestedAtMs,
            )
            validity
        }
        val traceEntities = output.traceFeatures
            .sortedBy { it.sequence }
            .flatMap(TraceFeatureEntityMapper::map)
        // Validity is the fail-closed authority gate for every derived row. Materialize every exact
        // source as VALID before either the Episode or its trace features become visible, while all
        // writes still share the runner's single fenced transaction.
        sourceValidities.forEach { validity ->
            val prior = database.episodeDao().findSourceValidity(
                input.streamId,
                input.replayGeneration,
                input.scopeKind,
                input.scopeId,
                validity.sourceType,
                validity.sourceId,
                validity.sourceRevision,
            )
            if (prior == null) {
                require(database.episodeDao().insertSourceValidityIgnore(validity) != -1L)
            } else {
                require(
                    prior.state == LearningSourceValidityState.VALID.name &&
                        prior.integritySha256 == validity.integritySha256 &&
                        prior.replayGeneration == input.replayGeneration
                ) { "Episode source validity replay conflict" }
            }
        }
        AssembleEpisodeOutputCommitter.persistInOpenTransaction(
            database = database,
            input = input,
            output = AssembleEpisodeOutputV1(
                episode = entity,
                expectedPreviousRevision = existing?.revision,
                expectedPreviousStatus = existing?.status,
                traceFeatures = emptyList(),
                initialRewardWindow = null,
            ),
        )
        traceEntities.forEach { trace ->
            val inserted = database.episodeDao().insertTraceIgnore(trace)
            require(
                inserted != -1L || database.episodeDao().findTrace(
                    trace.episodeId,
                    trace.sequence,
                    trace.sourceOrdinal,
                ) == trace
            ) { "Episode trace replay conflict" }
        }
        downstream.afterEpisodeCommitted(database, input, event, entity)
    }
}

/** Deterministic execution observation; no raw arguments/output can cross this typed boundary. */
internal class ExecutionTraceJobOutputCommitter(
    private val downstream: P1DerivedJobEnqueuer = NoOpP1DerivedJobEnqueuer,
) :
    LearningJobTypedOutputCommitter<ExecutionTraceJobOutput> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: ExecutionTraceJobOutput,
    ) {
        val event = input.requireAuthoritativeSourceEvent(database)
        require(output.outputSchemaIdentity == input.executionSpec.outputSchemaIdentity)
        require(
            event.eventTypeCode == "EXECUTION_TERMINAL" &&
                event.eventSchemaVersion == P1_EVENT_SCHEMA_VERSION
        )
        require(output.feature.sources.size == 1)
        val source = output.feature.sources.single()
        require(source.sourceKind.name == event.sourceType)
        require(source.sourceId == event.sourceId)
        require(source.sourceRevision == null)
        require(source.missingRevisionReason == me.rerere.rikkahub.learning.model.MissingSourceRevisionReason.RETENTION_GAP)
        require(source.databaseStreamId.toString() == input.streamId)
        require(source.scope.kind.name == input.scopeKind && source.scope.storageId == input.scopeId)
        require(!source.eligibleForPersistentPolicyEvidence)
        TraceFeatureEntityMapper.map(output.feature).forEach { trace ->
            val inserted = database.episodeDao().insertTraceIgnore(trace)
            require(
                inserted != -1L ||
                    database.episodeDao().findTrace(
                        trace.episodeId,
                        trace.sequence,
                        trace.sourceOrdinal,
                    ) == trace
            ) { "Execution trace replay conflict" }
        }
        val episode = requireNotNull(database.episodeDao().findEpisode(output.feature.episodeId.value))
        downstream.afterExecutionTraceCommitted(database, input, episode)
    }
}

/** Storage adapter for the bounded Reflection output; the handler never sees Room entities. */
internal class EpisodeLessonJobOutputCommitter(
    private val downstream: P1DerivedJobEnqueuer = NoOpP1DerivedJobEnqueuer,
) :
    LearningJobTypedOutputCommitter<EpisodeLessonJobOutput> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: EpisodeLessonJobOutput,
    ) {
        val event = input.requireAuthoritativeSourceEvent(database)
        val episodeId = when (output) {
            is EpisodeLessonJobOutput.Lesson -> output.episodeId
            is EpisodeLessonJobOutput.Abstained -> {
                val lineage = requireNotNull(event.lineageId)
                val anchor = requireNotNull(event.branchAnchorMessageId)
                requireNotNull(
                    database.episodeDao().findEpisodeByBoundary(
                        input.streamId,
                        input.replayGeneration,
                        lineage,
                        anchor,
                    ),
                ).id
            }
        }
        val episode = requireNotNull(database.episodeDao().findEpisode(episodeId))
        require(episode.streamId == input.streamId && episode.replayGeneration == input.replayGeneration)
        require(episode.scopeKind == input.scopeKind && episode.scopeId == input.scopeId)

        val entity = when (output) {
            is EpisodeLessonJobOutput.Abstained -> {
                require(output.outboundReceipt.providerIdentityDigest == input.executionSpec.providerIdentity)
                require(output.outboundReceipt.modelIdentityDigest == input.executionSpec.modelIdentity)
                require(output.producerConfigurationDigest == input.executionSpec.providerConfigurationIdentity)
                require(output.producerProviderKind == input.executionSpec.providerKindIdentity)
                LearningEpisodeLessonEntity(
                    episodeId = episode.id,
                    lessonVersion = LESSON_SCHEMA_VERSION,
                    scopeKind = input.scopeKind,
                    scopeId = input.scopeId,
                    lessonType = LearningLessonType.ABSTAIN.name,
                    triggerSummary = "Reflection abstained for this bounded Episode.",
                    observationSummary = "No durable lesson was proposed.",
                    lessonSummary = "Do not create policy evidence from this result.",
                    boundarySummary = "This result is a non-learning terminal output.",
                    evidenceManifestSha256 = LearningCanonicalId.digest(
                        "lesson-evidence-v1",
                        listOf(output.inputId),
                    ),
                    artifactSha256 = LearningCanonicalId.digest(
                        "episode-lesson-v1",
                        listOf(episode.id, output.inputId, "ABSTAIN"),
                    ),
                    producerProviderIdentity = output.outboundReceipt.providerIdentityDigest,
                    producerProviderKind = output.producerProviderKind,
                    producerModelIdentity = output.outboundReceipt.modelIdentityDigest,
                    producerConfigurationIdentity = output.producerConfigurationDigest,
                    producerConfigGeneration = input.executionSpec.providerConfigGeneration,
                    algorithmIdentity = input.executionSpec.algorithmIdentity,
                    promptIdentity = input.executionSpec.promptIdentity,
                    templateIdentity = input.executionSpec.promptIdentity,
                    schemaIdentity = output.outputSchemaIdentity,
                    inputTokenCount = output.outboundReceipt.inputTokens,
                    outputTokenCount = output.outboundReceipt.outputTokens,
                    estimatedCostMicros = output.outboundReceipt.costMicros,
                    remoteProvider = output.producerProviderKind == "remote",
                    state = LearningLessonState.REJECTED.name,
                    createdAtMs = output.outboundReceipt.createdAtMs,
                    updatedAtMs = output.outboundReceipt.createdAtMs,
                )
            }

            is EpisodeLessonJobOutput.Lesson -> {
                require(output.producerProviderDigest == input.executionSpec.providerIdentity)
                require(output.producerModelDigest == input.executionSpec.modelIdentity)
                require(output.promptVersion == input.executionSpec.promptIdentity)
                require(output.producerConfigurationDigest == input.executionSpec.providerConfigurationIdentity)
                require(output.producerProviderKind == input.executionSpec.providerKindIdentity)
                require(output.outboundReceipt.providerIdentityDigest == output.producerProviderDigest)
                require(output.outboundReceipt.modelIdentityDigest == output.producerModelDigest)
                output.draft.evidence.forEach { source ->
                    require(database.isValidSourceForEpisode(episode, source)) {
                        "Reflection evidence is stale or cross-scope"
                    }
                }
                LearningEpisodeLessonEntity(
                    episodeId = episode.id,
                    lessonVersion = LESSON_SCHEMA_VERSION,
                    scopeKind = input.scopeKind,
                    scopeId = input.scopeId,
                    lessonType = output.draft.lessonType.name,
                    triggerSummary = output.draft.trigger.value,
                    observationSummary = output.draft.observation.value,
                    lessonSummary = output.draft.lesson.value,
                    boundarySummary = output.draft.boundary.value,
                    evidenceManifestSha256 = sourceManifest(output.draft.evidence),
                    artifactSha256 = output.draft.artifactHash,
                    producerProviderIdentity = output.producerProviderDigest,
                    producerProviderKind = output.producerProviderKind,
                    producerModelIdentity = output.producerModelDigest,
                    producerConfigurationIdentity = output.producerConfigurationDigest,
                    producerConfigGeneration = input.executionSpec.providerConfigGeneration,
                    algorithmIdentity = input.executionSpec.algorithmIdentity,
                    promptIdentity = output.promptVersion,
                    templateIdentity = output.templateVersion,
                    schemaIdentity = output.outputSchemaIdentity,
                    inputTokenCount = output.outboundReceipt.inputTokens,
                    outputTokenCount = output.outboundReceipt.outputTokens,
                    estimatedCostMicros = output.outboundReceipt.costMicros,
                    remoteProvider = output.producerProviderKind == "remote",
                    state = LearningLessonState.VALID.name,
                    createdAtMs = output.outboundReceipt.createdAtMs,
                    updatedAtMs = output.outboundReceipt.createdAtMs,
                )
            }
        }
        EpisodeLessonOutputCommitter.persistInOpenTransaction(
            database,
            input,
            EpisodeLessonOutputV1(entity),
        )
        downstream.afterLessonCommitted(database, input, event, entity)
    }
}

/** Storage adapter for the deterministic reward window reducer. */
internal class RewardWindowJobOutputCommitter(
    private val downstream: P1DerivedJobEnqueuer = NoOpP1DerivedJobEnqueuer,
) :
    LearningJobTypedOutputCommitter<RewardWindowJobOutput> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: RewardWindowJobOutput,
    ) {
        val event = input.requireAuthoritativeSourceEvent(database)
        val window = output.window
        val episode = requireNotNull(database.episodeDao().findEpisode(window.episodeId.value))
        require(episode.streamId == input.streamId && episode.replayGeneration == input.replayGeneration)
        require(episode.scopeKind == input.scopeKind && episode.scopeId == input.scopeId)
        val goal = database.toStorageComponent(episode, window.goal)
        val process = database.toStorageComponent(episode, window.process)
        val user = database.toStorageComponent(episode, window.user)
        val entity = LearningRewardWindowEntity(
            id = "reward-window-v1:" + LearningCanonicalId.digest(
                "reward-window-v1",
                listOf(episode.id),
            ),
            episodeId = episode.id,
            scopeKind = input.scopeKind,
            scopeId = input.scopeId,
            openedAtMs = window.openedAtMs,
            closeAfterMs = window.closeAfterMs,
            state = window.state.name,
            goalKnowledge = goal.knowledge,
            goalValue = goal.value,
            goalUnknownReason = goal.reason,
            goalEvidenceSha256 = goal.evidence,
            processKnowledge = process.knowledge,
            processValue = process.value,
            processUnknownReason = process.reason,
            processEvidenceSha256 = process.evidence,
            userKnowledge = user.knowledge,
            userValue = user.value,
            userUnknownReason = user.reason,
            userEvidenceSha256 = user.evidence,
            weakLabel = null,
            rewardConfigIdentity = window.rewardConfigVersion,
            closedAtMs = window.closedAtMs,
            updatedAtMs = maxOf(
                event.ingestedAtMs,
                window.closedAtMs ?: window.openedAtMs,
            ),
        )
        val existing = database.episodeDao().findRewardWindow(entity.id)
        if (existing == null) {
            require(entity.state == "OPEN")
            require(database.episodeDao().insertRewardWindowIgnore(entity) != -1L)
        } else if (existing != entity) {
            RewardWindowOutputCommitter.persistInOpenTransaction(
                database,
                input,
                RewardWindowOutputV1(entity),
            )
        }
        downstream.afterRewardCommitted(database, input, entity)
    }
}

/** Storage adapter for the one and only Distiller candidate output. */
internal object PolicyCandidateJobOutputCommitter :
    LearningJobTypedOutputCommitter<PolicyCandidateJobOutput> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: PolicyCandidateJobOutput,
    ) {
        input.requireAuthoritativeSourceEvent(database)
        require(output.outboundReceipt.providerIdentityDigest == input.executionSpec.providerIdentity)
        require(output.outboundReceipt.modelIdentityDigest == input.executionSpec.modelIdentity)
        require(output.producerConfigurationDigest == input.executionSpec.providerConfigurationIdentity)
        require(output.producerProviderKind == input.executionSpec.providerKindIdentity)
        if (output is PolicyCandidateJobOutput.Abstained) return
        output as PolicyCandidateJobOutput.Candidate
        // completeTyped() already owns the sole outer Room transaction. The callback below is the
        // storage capability behind the canonical store; it deliberately opens no nested one, so
        // every Policy row/revision/evidence write rolls back with the job DONE CAS.
        val mutationStore: PolicyMutationStore = ValidatingPolicyMutationStore(
            PolicyMutationTransaction { request ->
                require(request is PolicyMutationRequest.CreateCandidate)
                require(request.draft == output.draft) { "Policy draft changed at write boundary" }
                val draft = request.draft
                require(draft.scope.kind.name == input.scopeKind && draft.scope.storageId == input.scopeId)
                require(draft.evidence.map { it.episodeId.value }.distinct().size == draft.evidence.size) {
                    "Multiple retries from one Episode cannot become independent evidence"
                }
                require(draft.evidence.all {
                    it.sourceValid && it.authorityOutcome !in setOf(
                        PolicyEvidenceAuthorityOutcome.UNKNOWN,
                        PolicyEvidenceAuthorityOutcome.CENSORED,
                    )
                })

                val negative = draft.type == PolicyCandidateType.AVOID ||
                    draft.type == PolicyCandidateType.FAILURE_MODE
                if (negative) {
                    require(draft.evidence.any {
                        it.authorityOutcome == PolicyEvidenceAuthorityOutcome.FAILURE
                    }) { "Failure policy requires an authoritative failure" }
                }
                val existingArtifacts = database.policyDao().findPoliciesByArtifact(
                    input.scopeKind,
                    input.scopeId,
                    draft.taskSignature.value,
                    draft.artifactHash,
                )
                require(existingArtifacts.size <= 1) { "Policy artifact uniqueness violated" }
                val existingArtifact = existingArtifacts.singleOrNull()
                val targetPolicyId = existingArtifact?.id ?: draft.candidateId
                val evidence = draft.evidence.sortedBy { it.episodeId.value }.map { handle ->
                    val episode = requireNotNull(database.episodeDao().findEpisode(handle.episodeId.value))
                    require(episode.scopeKind == input.scopeKind && episode.scopeId == input.scopeId)
                    val lessonVersion = Math.toIntExact(handle.lessonRevision)
                    requireNotNull(database.episodeDao().findLesson(episode.id, lessonVersion))
                    val traces = database.episodeDao().listTrace(episode.id, MAX_TRACE_SOURCE_SCAN)
                        .filter { it.sourceType == LearningSourceKind.CONVERSATION_MESSAGE.name }
                    require(traces.isNotEmpty()) { "Policy evidence Episode has no trace source" }
                    val stableSources = traces.map { trace ->
                        val revision = requireNotNull(trace.sourceRevision) {
                            "Policy evidence contains an unversioned source"
                        }
                        val validity = database.episodeDao().findSourceValidity(
                            episode.streamId,
                            episode.replayGeneration,
                            episode.scopeKind,
                            episode.scopeId,
                            trace.sourceType,
                            trace.sourceId,
                            revision,
                        )
                        require(
                            validity?.state == LearningSourceValidityState.VALID.name &&
                                validity.integritySha256 != null &&
                                validity.replayGeneration == episode.replayGeneration
                        ) { "Policy evidence contains an invalid source revision" }
                        trace to validity
                    }
                    val stableSourcePair = stableSources.first()
                    PolicyEvidenceEntity(
                        policyId = targetPolicyId,
                        episodeId = episode.id,
                        evidenceKind = when (handle.authorityOutcome) {
                            PolicyEvidenceAuthorityOutcome.FAILURE -> "NEGATIVE_LESSON"
                            PolicyEvidenceAuthorityOutcome.SUCCESS -> "POSITIVE_LESSON"
                            else -> error("Unknown/censored evidence passed the validator")
                        },
                        polarity = when (handle.authorityOutcome) {
                            PolicyEvidenceAuthorityOutcome.FAILURE ->
                                LearningPolicyEvidencePolarity.NEGATIVE.name
                            PolicyEvidenceAuthorityOutcome.SUCCESS ->
                                LearningPolicyEvidencePolarity.POSITIVE.name
                            else -> error("Unknown/censored evidence passed the validator")
                        },
                        quality = null,
                        lessonVersion = lessonVersion,
                        sourceType = stableSourcePair.first.sourceType,
                        sourceId = stableSourcePair.first.sourceId,
                        sourceRevision = requireNotNull(stableSourcePair.first.sourceRevision),
                        sourceIntegritySha256 = requireNotNull(stableSourcePair.second.integritySha256),
                        createdAtMs = stableSourcePair.first.createdAtMs,
                    )
                }
                val nowMs = evidence.maxOf(PolicyEvidenceEntity::createdAtMs)
                val existingEvidence = existingArtifact?.let { existing ->
                    database.policyDao().listEvidenceValidity(existing.id, MAX_POLICY_SOURCE_SCAN + 1)
                        .also { rows ->
                            require(rows.size <= MAX_POLICY_SOURCE_SCAN) { "Policy evidence bound exceeded" }
                            require(rows.all { it.sourceValid }) { "Existing policy evidence is stale" }
                        }
                }.orEmpty()
                val newEvidence = if (existingArtifact == null) {
                    evidence
                } else {
                    require(existingArtifact.sourceValid && existingArtifact.schemaValid)
                    require(existingArtifact.policyType == draft.type.name)
                    require(existingArtifact.triggerSummary == draft.trigger.value)
                    require(existingArtifact.procedureSummary == draft.procedure.value)
                    require(existingArtifact.verificationSummary == draft.verification.value)
                    require(existingArtifact.boundarySummary == draft.boundary.value)
                    require(existingArtifact.failureModeSummary == draft.failureMode.value)
                    evidence.filter { edge ->
                        val prior = database.policyDao().findEvidence(edge.policyId, edge.episodeId)
                        if (prior != null) require(prior == edge) { "Policy evidence replay conflict" }
                        prior == null
                    }
                }
                if (existingArtifact != null && newEvidence.isEmpty()) {
                    return@PolicyMutationTransaction PolicyMutationResult.Duplicate(
                        policyId = existingArtifact.id,
                        revision = existingArtifact.stateVersion,
                    )
                }
                val statistics = P1PolicyEvidenceRecalculator.calculate(
                    existingEvidence.map { row ->
                        P1PolicyEvidenceSignal(
                            evidenceId = row.episodeId,
                            polarity = row.polarity.toDomainEvidencePolarity(),
                            quality = row.quality,
                        )
                    } + newEvidence.map { edge ->
                        P1PolicyEvidenceSignal(
                            evidenceId = edge.episodeId,
                            polarity = edge.polarity.toDomainEvidencePolarity(),
                            quality = edge.quality,
                        )
                    },
                )
                val policy = existingArtifact?.copy(
                    stateVersion = Math.addExact(existingArtifact.stateVersion, 1L),
                    distinctEpisodeSupport = statistics.distinctEpisodeSupport.toLong(),
                    positiveEpisodeCount = statistics.positiveEpisodeCount.toLong(),
                    negativeEpisodeCount = statistics.negativeEpisodeCount.toLong(),
                    confidence = statistics.confidence,
                    updatedAtMs = maxOf(existingArtifact.updatedAtMs, nowMs),
                ) ?: LearningPolicyEntity(
                    id = targetPolicyId,
                    scopeKind = input.scopeKind,
                    scopeId = input.scopeId,
                    taskSignature = draft.taskSignature.value,
                    policyType = draft.type.name,
                    triggerSummary = draft.trigger.value,
                    procedureSummary = draft.procedure.value,
                    verificationSummary = draft.verification.value,
                    boundarySummary = draft.boundary.value,
                    failureModeSummary = draft.failureMode.value,
                    stateVersion = 1,
                    artifactSha256 = draft.artifactHash,
                    compilerAbi = P1_POLICY_COMPILER_ABI,
                    status = StoredLearningPolicyStatus.CANDIDATE.name,
                    sourceValid = true,
                    schemaValid = true,
                    staleReason = null,
                    distinctEpisodeSupport = statistics.distinctEpisodeSupport.toLong(),
                    positiveEpisodeCount = statistics.positiveEpisodeCount.toLong(),
                    negativeEpisodeCount = statistics.negativeEpisodeCount.toLong(),
                    usageCount = 0,
                    confidence = statistics.confidence,
                    observedUtilityDelta = null,
                    utilityUncertainty = null,
                    producerModelIdentity = draft.modelIdentity,
                    producerProviderIdentity = input.executionSpec.providerIdentity,
                    producerProviderKind = output.producerProviderKind,
                    producerConfigurationIdentity = output.producerConfigurationDigest,
                    producerConfigGeneration = input.executionSpec.providerConfigGeneration,
                    producerPromptIdentity = draft.promptVersion,
                    producerTemplateIdentity = draft.promptVersion,
                    producerSchemaIdentity = "policy-candidate-schema-v${draft.schemaVersion}",
                    createdAtMs = nowMs,
                    updatedAtMs = nowMs,
                    lastUsedAtMs = null,
                )
                val beforeSnapshot = existingArtifact?.toCandidateAuditSnapshot()
                val afterSnapshot = policy.toCandidateAuditSnapshot()
                PolicyMutationOutputCommitter.persistInOpenTransaction(
                    database,
                    input,
                    PolicyMutationOutputV1(
                        policy = policy,
                        expectedPreviousStateVersion = existingArtifact?.stateVersion,
                        expectedPreviousArtifactSha256 = existingArtifact?.artifactSha256,
                        revision = PolicyRevisionEntity(
                            policyId = policy.id,
                            revision = policy.stateVersion,
                            beforeSnapshot = beforeSnapshot,
                            afterSnapshot = afterSnapshot,
                            beforeArtifactSha256 = existingArtifact?.artifactSha256,
                            afterArtifactSha256 = policy.artifactSha256,
                            reasonCode = if (existingArtifact == null) {
                                LearningPolicyRevisionReason.CREATE.name
                            } else {
                                LearningPolicyRevisionReason.EVIDENCE_ADDED.name
                            },
                            actor = LearningPolicyRevisionActor.SYSTEM.name,
                            createdAtMs = nowMs,
                        ),
                        evidence = if (existingArtifact == null) evidence else newEvidence,
                        lineage = emptyList(),
                    ),
                )
                PolicyMutationResult.Applied(
                    policyId = policy.id,
                    revision = policy.stateVersion,
                    status = LearningPolicyStatus.valueOf(policy.status),
                )
            },
        )
        when (
            mutationStore.mutate(
                PolicyMutationRequest.CreateCandidate(draft = output.draft),
            )
        ) {
            is PolicyMutationResult.Applied,
            is PolicyMutationResult.Duplicate -> Unit
            is PolicyMutationResult.Conflict -> error("Canonical Policy candidate mutation rejected")
        }
    }
}

private suspend fun LearningJobExecutionInputV1.requireAuthoritativeSourceEvent(
    database: LearningDatabase,
) = requireNotNull(database.inboxDao().find(streamId, sourceEventId)) {
    "Learning job source event disappeared"
}.also { event ->
    require(event.replayGeneration == replayGeneration)
    require(event.scopeKind == scopeKind && event.scopeId == scopeId)
}

private fun DomainEpisodeStatus.toStorageStatus(): String = when (this) {
    DomainEpisodeStatus.OPEN -> StoredLearningEpisodeStatus.OPEN.name
    DomainEpisodeStatus.SUCCESS -> StoredLearningEpisodeStatus.SUCCESS.name
    DomainEpisodeStatus.PARTIAL -> StoredLearningEpisodeStatus.PARTIAL.name
    DomainEpisodeStatus.FAILURE -> StoredLearningEpisodeStatus.FAILURE.name
    DomainEpisodeStatus.ABORTED -> StoredLearningEpisodeStatus.ABORTED.name
    DomainEpisodeStatus.TIMEOUT -> StoredLearningEpisodeStatus.TIMEOUT.name
    DomainEpisodeStatus.CENSORED -> StoredLearningEpisodeStatus.CENSORED.name
    DomainEpisodeStatus.SUPERSEDED -> StoredLearningEpisodeStatus.SUPERSEDED.name
    DomainEpisodeStatus.UNKNOWN -> StoredLearningEpisodeStatus.UNKNOWN.name
}

private fun EpisodeBoundaryReason.toStorageReason(): String = when (this) {
    EpisodeBoundaryReason.ROOT_COMMAND_ADMITTED -> LearningEpisodeBoundaryReason.COMMAND_ADMITTED.name
    EpisodeBoundaryReason.WAITING_APPROVAL_CHECKPOINT ->
        LearningEpisodeBoundaryReason.WAITING_APPROVAL.name
    EpisodeBoundaryReason.FINAL_RESPONSE_SAVED -> LearningEpisodeBoundaryReason.FINAL_SAVED.name
    EpisodeBoundaryReason.USER_CANCELLED -> LearningEpisodeBoundaryReason.STOPPED.name
    EpisodeBoundaryReason.REGENERATED_BRANCH ->
        LearningEpisodeBoundaryReason.REGENERATED_BRANCH.name
    EpisodeBoundaryReason.FINAL_SAVE_FAILED -> LearningEpisodeBoundaryReason.FINAL_SAVE_FAILED.name
    EpisodeBoundaryReason.COMMAND_FAILED -> LearningEpisodeBoundaryReason.UNKNOWN.name
    EpisodeBoundaryReason.LEGACY_OR_INCOMPLETE_AUTHORITY ->
        throw IllegalArgumentException("Legacy Episode authority is not persistable")
}

private fun me.rerere.rikkahub.learning.episode.EpisodeAuthorityAnchor.episodeRootCommandId(): String =
    lineageId.toString()

private suspend fun LearningDatabase.isValidSourceForEpisode(
    episode: LearningEpisodeEntity,
    source: LearningSourceRef,
): Boolean {
    val revision = source.sourceRevision ?: return false
    if (!source.eligibleForPersistentPolicyEvidence) return false
    if (
        source.databaseStreamId.toString() != episode.streamId ||
        source.scope.kind.name != episode.scopeKind ||
        source.scope.storageId != episode.scopeId
    ) return false
    val validity = episodeDao().findSourceValidity(
        episode.streamId,
        episode.replayGeneration,
        episode.scopeKind,
        episode.scopeId,
        source.sourceKind.name,
        source.sourceId,
        revision,
    ) ?: return false
    return validity.state == LearningSourceValidityState.VALID.name &&
        validity.integritySha256 != null &&
        validity.replayGeneration == episode.replayGeneration
}

private fun String.toDomainEvidencePolarity(): PolicyEvidencePolarity = when (this) {
    LearningPolicyEvidencePolarity.POSITIVE.name -> PolicyEvidencePolarity.POSITIVE
    LearningPolicyEvidencePolarity.NEGATIVE.name -> PolicyEvidencePolarity.NEGATIVE
    else -> PolicyEvidencePolarity.NEUTRAL
}

private fun sourceManifest(sources: List<LearningSourceRef>): String = LearningCanonicalId.digest(
    "lesson-evidence-v1",
    sources.sortedWith(compareBy({ it.sourceKind.name }, { it.sourceId }, { it.sourceRevision }))
        .flatMap { listOf(it.sourceKind.name, it.sourceId, it.sourceRevision?.toString()) },
)

private data class StorageRewardComponent(
    val knowledge: String,
    val value: Double?,
    val reason: String?,
    val evidence: String?,
)

private suspend fun LearningDatabase.toStorageComponent(
    episode: LearningEpisodeEntity,
    component: RewardComponent,
): StorageRewardComponent = when (component) {
    is RewardComponent.Known -> {
        component.evidence.forEach { source ->
            require(isValidSourceForEpisode(episode, source))
        }
        StorageRewardComponent(
            knowledge = LearningRewardKnowledge.KNOWN.name,
            value = component.value,
            reason = null,
            evidence = sourceManifest(component.evidence),
        )
    }

    is RewardComponent.Unknown -> StorageRewardComponent(
        knowledge = if (component.reason == RewardUnknownReason.CENSORED) {
            LearningRewardKnowledge.CENSORED.name
        } else {
            LearningRewardKnowledge.UNKNOWN.name
        },
        value = null,
        reason = component.reason.name,
        evidence = null,
    )
}

private fun LearningPolicyEntity.toCandidateAuditSnapshot(): String {
    return listOf(
        "policy-candidate-snapshot-v1",
        "type=$policyType",
        "task=$taskSignature",
        "trigger=$triggerSummary",
        "procedure=$procedureSummary",
        "verification=$verificationSummary",
        "boundary=$boundarySummary",
        "failure=$failureModeSummary",
        "artifact=$artifactSha256",
        "support=$distinctEpisodeSupport",
        "positive=$positiveEpisodeCount",
        "negative=$negativeEpisodeCount",
    ).joinToString("\n")
}

private val COMMAND_EPISODE_EVENTS = setOf(
    "COMMAND_ADMITTED",
    "COMMAND_WAITING_APPROVAL",
    "COMMAND_TERMINAL",
)
private const val LESSON_SCHEMA_VERSION = 1
private const val MAX_TRACE_SOURCE_SCAN = 256
private const val MAX_POLICY_SOURCE_SCAN = 256
private const val P1_POLICY_COMPILER_ABI = "policy-shadow-compiler-v1"
private const val P1_EVENT_SCHEMA_VERSION = 2

/** Runs only inside the fenced output transaction; implementations may enqueue bounded child jobs. */
internal interface P1DerivedJobEnqueuer {
    suspend fun afterEpisodeCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        episode: LearningEpisodeEntity,
    )

    suspend fun afterLessonCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        lesson: LearningEpisodeLessonEntity,
    )

    suspend fun afterRewardCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        reward: LearningRewardWindowEntity,
    )

    /** Execution observations can arrive before their command Episode; retry is the first path. */
    suspend fun afterExecutionTraceCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        episode: LearningEpisodeEntity,
    )
}

internal object NoOpP1DerivedJobEnqueuer : P1DerivedJobEnqueuer {
    override suspend fun afterEpisodeCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        episode: LearningEpisodeEntity,
    ) = Unit

    override suspend fun afterLessonCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        event: LearningInboxEventEntity,
        lesson: LearningEpisodeLessonEntity,
    ) = Unit

    override suspend fun afterRewardCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        reward: LearningRewardWindowEntity,
    ) = Unit

    override suspend fun afterExecutionTraceCommitted(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        episode: LearningEpisodeEntity,
    ) = Unit
}

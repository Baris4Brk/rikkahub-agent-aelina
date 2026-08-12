package me.rerere.rikkahub.learning.handoff

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.MissingSourceRevisionReason
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionSpecs
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionSpecV1
import me.rerere.rikkahub.learning.jobs.LearningJobProviderKindIdentity
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.requiresFrozenP1ExecutionIdentity
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import me.rerere.rikkahub.learning.storage.LearningStreamResetReason
import kotlin.uuid.Uuid

private const val DEFAULT_BATCH_LIMIT = 64
private const val DEFAULT_JOB_MAX_ATTEMPTS = 5

data class LearningIngestBatch(
    val streamId: Uuid,
    val replayGeneration: Long,
    val expectedPreviousSeq: Long,
    val observedHeadSeq: Long,
    val events: List<LearningHandoffEvent>,
    val ingestedAtMs: Long,
) {
    init {
        require(replayGeneration >= 0L) { "Negative replay generation" }
        require(expectedPreviousSeq >= 0L) { "Negative checkpoint" }
        require(observedHeadSeq >= expectedPreviousSeq) { "Outbox head moved behind checkpoint" }
        require(events.size <= DEFAULT_BATCH_LIMIT) { "Learning inbox batch too large" }
        require(ingestedAtMs >= 0L) { "Negative ingestion time" }
        events.forEach { event ->
            require(event.streamId == streamId) { "Mixed database streams in one batch" }
            require(event.outboxSeq > expectedPreviousSeq) { "Event precedes checkpoint" }
            require(event.outboxSeq <= observedHeadSeq) { "Event exceeds observed outbox head" }
        }
        require(events.zipWithNext().all { (left, right) -> left.outboxSeq < right.outboxSeq }) {
            "Outbox events must be strictly ordered"
        }
    }
}

data class LearningIngestResult(
    val insertedEvents: Int,
    val duplicateEvents: Int,
    val insertedJobs: Int,
    val duplicateJobs: Int,
    val lastContiguousSeq: Long,
)

class LearningHandoffIdentityConflictException(
    message: String,
) : IllegalStateException(message)

class LearningCheckpointConflictException : IllegalStateException("Learning checkpoint changed")

/**
 * Immutable event authority supplied to a versioned interpreter. In particular, this view omits
 * the stored decode state and interpretation version: both are derived projections, not identity.
 */
data class LearningInboxAuthoritativeEvent(
    val streamId: Uuid,
    val eventId: String,
    val outboxSeq: Long,
    val eventCode: LearningEventCode,
    val terminalStateCode: String?,
    val sourceTypeCode: String?,
    val sourceId: String?,
    val sourceRevision: Long?,
    val previousSourceRevision: Long? = null,
    val sourceStateCode: String? = null,
    val missingRevisionReasonCode: String?,
    val scopeKindCode: String?,
    val scopeId: String?,
    val correlation: LearningCorrelation,
    val occurredAtMs: Long?,
    val createdAtMs: Long,
    val replayGeneration: Long,
) {
    override fun toString(): String =
        "LearningInboxAuthoritativeEvent(seq=$outboxSeq, type=${eventCode.knownType}, " +
            "schema=${eventCode.schemaVersion}, source=${sourceId != null}, " +
            "scope=${scopeKindCode != null}, replay=$replayGeneration, ids=<redacted>)"
}

/** Must be a pure, deterministic and bounded interpretation of the supplied authority columns. */
fun interface LearningInboxEventInterpreter {
    fun reinterpret(
        event: LearningInboxAuthoritativeEvent,
        targetInterpretationVersion: Int,
    ): LearningEventDecodeState
}

/** Current fail-closed structural interpreter; it never consults the row's prior decode result. */
object CurrentLearningInboxEventInterpreter : LearningInboxEventInterpreter {
    override fun reinterpret(
        event: LearningInboxAuthoritativeEvent,
        targetInterpretationVersion: Int,
    ): LearningEventDecodeState {
        require(targetInterpretationVersion > 0) { "Invalid interpretation version" }
        if (event.eventCode.decodeState != LearningEventDecodeState.KNOWN) {
            return event.eventCode.decodeState
        }
        return try {
            event.toValidatedHandoffEvent()
            LearningEventDecodeState.KNOWN
        } catch (_: IllegalArgumentException) {
            LearningEventDecodeState.INCOMPATIBLE_SCHEMA
        }
    }
}

/**
 * Pure deterministic factory seam kept separate while P0 job identity is frozen. Returning a job
 * is mandatory exactly when the newly interpreted event is eligible for a current P0 job.
 */
fun interface LearningReinterpretedJobFactory {
    fun createEligibleJob(
        event: LearningInboxAuthoritativeEvent,
        targetInterpretationVersion: Int,
        reinterpretedAtMs: Long,
    ): LearningJobEntity?
}

/** Current non-model P0 factory. Every identity-bearing input is explicit in the dedupe digest. */
object CurrentLearningReinterpretedJobFactory : LearningReinterpretedJobFactory {
    override fun createEligibleJob(
        event: LearningInboxAuthoritativeEvent,
        targetInterpretationVersion: Int,
        reinterpretedAtMs: Long,
    ): LearningJobEntity? {
        val eventType = event.eventCode.knownType ?: return null
        if (!eventType.producesP0Job) return null
        val scopeKind = event.scopeKindCode ?: return null
        val scopeId = event.scopeId ?: return null
        if (LearningScope.parseOrNull(scopeKind, scopeId) == null) return null
        if (event.sourceTypeCode == null || event.sourceId == null) return null
        return createStructuralJob(
            streamId = event.streamId.toString(),
            eventId = event.eventId,
            eventType = eventType,
            eventSchemaVersion = event.eventCode.schemaVersion,
            interpretationVersion = targetInterpretationVersion,
            scopeKind = scopeKind,
            scopeId = scopeId,
            replayGeneration = event.replayGeneration,
            createdAtMs = reinterpretedAtMs,
        )
    }
}

data class LearningReinterpretationResult(
    val scannedEvents: Int,
    val updatedInterpretations: Int,
    val concurrentlyUpdatedEvents: Int,
    val insertedJobs: Int,
    val duplicateJobs: Int,
    val lastScannedSequence: Long,
)

/** Atomically absorbs inbox rows, deduplicated jobs and the corresponding checkpoint. */
class LearningInboxBatchStore(
    private val database: LearningDatabase,
) {
    suspend fun ingest(batch: LearningIngestBatch): LearningIngestResult = database.withTransaction {
        val checkpoint = database.checkpointDao().find(batch.streamId.toString())
            ?: throw LearningCheckpointConflictException()
        if (
            checkpoint.replayGeneration != batch.replayGeneration ||
            checkpoint.lastContiguousSeq != batch.expectedPreviousSeq
        ) {
            throw LearningCheckpointConflictException()
        }
        if (batch.events.isEmpty()) {
            if (batch.observedHeadSeq != batch.expectedPreviousSeq) {
                throw LearningHandoffIdentityConflictException(
                    "Observed outbox head has no corresponding readable events",
                )
            }
            return@withTransaction LearningIngestResult(
                insertedEvents = 0,
                duplicateEvents = 0,
                insertedJobs = 0,
                duplicateJobs = 0,
                lastContiguousSeq = batch.expectedPreviousSeq,
            )
        }

        var insertedEvents = 0
        var duplicateEvents = 0
        var insertedJobs = 0
        var duplicateJobs = 0
        batch.events.forEach { handoff ->
            val event = handoff.toInboxEntity(batch.ingestedAtMs, batch.replayGeneration)
            val inserted = database.inboxDao().insertIgnore(event) != -1L
            if (inserted) {
                insertedEvents += 1
            } else {
                val existing = database.inboxDao().find(event.streamId, event.eventId)
                    ?: throw LearningHandoffIdentityConflictException(
                        "Inbox uniqueness conflict without the expected event",
                    )
                if (!existing.hasSameAuthoritativeIdentityAs(event)) {
                    throw LearningHandoffIdentityConflictException(
                        "Same learning event ID has different authoritative fields",
                    )
                }
                duplicateEvents += 1
            }

            if (event.isSafeToCreateJob()) {
                val job = event.toInitialJob()
                val jobInserted = database.jobDao().insertIgnore(job) != -1L
                if (jobInserted) {
                    insertedJobs += 1
                } else {
                    val existing = database.jobDao().findByDedupeKey(job.dedupeKey)
                        ?: throw LearningHandoffIdentityConflictException(
                            "Job dedupe conflict without the expected job",
                        )
                    if (!existing.hasSameImmutableIdentityAs(job)) {
                        throw LearningHandoffIdentityConflictException(
                            "Same learning job key has different immutable fields",
                        )
                    }
                    duplicateJobs += 1
                }
            }
        }

        // SQLite AUTOINCREMENT may legally leave numeric holes (for example after an ignored
        // insert). "Contiguous" here means every existing ordered row through this watermark was
        // absorbed, not that every integer sequence value existed.
        val lastSeq = batch.events.last().outboxSeq
        val advanced = database.checkpointDao().advanceContiguously(
            streamId = batch.streamId.toString(),
            replayGeneration = batch.replayGeneration,
            expectedPreviousSeq = batch.expectedPreviousSeq,
            lastContiguousSeq = lastSeq,
            lastSeenHeadSeq = batch.observedHeadSeq,
            updatedAtMs = batch.ingestedAtMs,
        )
        if (advanced != 1) throw LearningCheckpointConflictException()
        LearningIngestResult(
            insertedEvents = insertedEvents,
            duplicateEvents = duplicateEvents,
            insertedJobs = insertedJobs,
            duplicateJobs = duplicateJobs,
            lastContiguousSeq = lastSeq,
        )
    }
}

/**
 * Reinterprets one fixed-size page. The interpretation CAS and any newly eligible deduplicated job
 * are committed in the same Learning DB transaction, so a kill cannot leave KNOWN without its job.
 */
class LearningInboxReinterpreter(
    private val database: LearningDatabase,
    private val interpreter: LearningInboxEventInterpreter,
    private val jobFactory: LearningReinterpretedJobFactory,
) {
    suspend fun reinterpretNextPage(
        streamId: Uuid,
        replayGeneration: Long,
        afterSequence: Long,
        targetInterpretationVersion: Int,
        reinterpretedAtMs: Long,
    ): LearningReinterpretationResult = database.withTransaction {
        require(replayGeneration >= 0L) { "Negative replay generation" }
        require(afterSequence >= 0L) { "Negative reinterpretation checkpoint" }
        require(targetInterpretationVersion > 0) { "Invalid interpretation version" }
        require(reinterpretedAtMs >= 0L) { "Negative reinterpretation time" }

        val rows = database.inboxDao().listNextInterpretationPage(
            streamId = streamId.toString(),
            replayGeneration = replayGeneration,
            afterSeq = afterSequence,
            targetInterpretationVersion = targetInterpretationVersion,
        )
        var updatedInterpretations = 0
        var concurrentlyUpdatedEvents = 0
        var insertedJobs = 0
        var duplicateJobs = 0
        rows.forEach { row ->
            val authority = row.toAuthoritativeEvent()
            val newDecodeState = interpreter.reinterpret(
                event = authority,
                targetInterpretationVersion = targetInterpretationVersion,
            )
            require(
                newDecodeState != LearningEventDecodeState.KNOWN ||
                    authority.eventCode.knownType != null
            ) { "An unknown event code cannot be interpreted as KNOWN" }
            val createsP0Job =
                newDecodeState == LearningEventDecodeState.KNOWN &&
                    authority.eventCode.knownType?.producesP0Job == true &&
                    authority.sourceTypeCode != null &&
                    LearningSourceKind.entries.any {
                        it != LearningSourceKind.UNKNOWN &&
                            it.name == authority.sourceTypeCode
                    } &&
                    authority.sourceId != null &&
                    authority.scopeKindCode != null &&
                    authority.scopeId != null
            val newJob = jobFactory.createEligibleJob(
                event = authority,
                targetInterpretationVersion = targetInterpretationVersion,
                reinterpretedAtMs = reinterpretedAtMs,
            )
            require((newJob != null) == createsP0Job) {
                "Reinterpreted job eligibility mismatch"
            }
            if (newJob != null) newJob.requireMatches(authority)

            val updated = database.inboxDao().reinterpretIfCurrent(
                streamId = row.streamId,
                eventId = row.eventId,
                replayGeneration = row.replayGeneration,
                expectedInterpretationVersion = row.interpretationVersion,
                expectedDecodeState = row.decodeState,
                targetInterpretationVersion = targetInterpretationVersion,
                newDecodeState = newDecodeState.name,
            )
            if (updated != 1) {
                concurrentlyUpdatedEvents += 1
                return@forEach
            }
            updatedInterpretations += 1

            if (newJob != null) {
                // An interpretation-version upgrade must not create a second semantic job for an
                // event that was already KNOWN under the prior interpreter. Identity changes that
                // intentionally create a new producer cohort use a distinct P1 enqueue path.
                val existingSourceJobs = database.jobDao().listBySourceEventAndType(
                    newJob.streamId,
                    newJob.replayGeneration,
                    newJob.sourceEventId,
                    newJob.jobType,
                )
                if (existingSourceJobs.isNotEmpty()) {
                    if (
                        existingSourceJobs.size != 1 ||
                        !existingSourceJobs.single().hasSameSourceJobAuthorityAs(newJob)
                    ) {
                        throw LearningHandoffIdentityConflictException(
                            "Reinterpreted source event has conflicting jobs",
                        )
                    }
                    duplicateJobs += 1
                } else {
                    val inserted = database.jobDao().insertIgnore(newJob) != -1L
                    if (inserted) {
                        insertedJobs += 1
                    } else {
                        val existing = database.jobDao().findByDedupeKey(newJob.dedupeKey)
                            ?: throw LearningHandoffIdentityConflictException(
                                "Reinterpreted job conflict without the expected job",
                            )
                        if (!existing.hasSameImmutableIdentityAs(newJob)) {
                            throw LearningHandoffIdentityConflictException(
                                "Reinterpreted job key has different immutable fields",
                            )
                        }
                        duplicateJobs += 1
                    }
                }
            }
        }
        LearningReinterpretationResult(
            scannedEvents = rows.size,
            updatedInterpretations = updatedInterpretations,
            concurrentlyUpdatedEvents = concurrentlyUpdatedEvents,
            insertedJobs = insertedJobs,
            duplicateJobs = duplicateJobs,
            lastScannedSequence = rows.lastOrNull()?.outboxSeq ?: afterSequence,
        )
    }
}

/**
 * Resets all P0 derived rows for a new authoritative lineage or a restored/rewound database.
 * Runtime workers must already be stopped; deleting the job rows is the final database fence.
 */
class LearningDerivedStateResetter(
    private val database: LearningDatabase,
) {
    suspend fun reset(
        streamId: Uuid,
        observedHeadSeq: Long,
        reason: LearningStreamResetReason,
        frozenNowMs: Long,
    ): LearningStreamCheckpointEntity = database.withTransaction {
        require(observedHeadSeq > 0L) { "A reset requires the authoritative stream sentinel" }
        require(frozenNowMs >= 0L) { "Negative reset time" }
        val highestExistingGeneration = listOfNotNull(
            database.checkpointDao().maxReplayGeneration(),
            database.inboxDao().maxReplayGeneration(),
            database.jobDao().maxReplayGeneration(),
            database.episodeDao().maxEpisodeReplayGeneration(),
            database.episodeDao().maxSourceValidityReplayGeneration(),
        ).maxOrNull() ?: -1L
        val nextGeneration = Math.addExact(highestExistingGeneration, 1L)
        // Policy rows point at Episodes and at one another. Delete the complete derived graph in
        // dependency order before resetting transport state; otherwise a restore/head rewind can
        // retain evidence from the future authority timeline or fail on a foreign-key fence.
        database.policyDao().deleteAllLineage()
        database.policyDao().deleteAllEvidence()
        database.policyDao().deleteAllRevisions()
        database.policyDao().deleteAllPolicies()
        database.episodeDao().deleteAllRewardWindows()
        database.episodeDao().deleteAllLessons()
        database.episodeDao().deleteAllTrace()
        database.episodeDao().deleteAllEpisodes()
        database.episodeDao().deleteAllSourceValidity()
        database.jobDao().deleteAll()
        database.inboxDao().deleteAll()
        database.checkpointDao().deleteAll()
        val checkpoint = LearningStreamCheckpointEntity(
            streamId = streamId.toString(),
            lastContiguousSeq = 0L,
            lastSeenHeadSeq = observedHeadSeq,
            replayGeneration = nextGeneration,
            resetReason = reason.name,
            bootstrapState = LearningBootstrapState.REQUIRED.name,
            bootstrapHeadSeq = observedHeadSeq,
            coverageStartMs = null,
            commandCoverageStartMs = null,
            executionCoverageStartMs = null,
            updatedAtMs = frozenNowMs,
        )
        database.checkpointDao().insert(checkpoint)
        checkpoint
    }
}

private fun LearningInboxEventEntity.toInitialJob(): LearningJobEntity {
    val eventType = requireNotNull(
        LearningEventType.entries.firstOrNull { it.name == eventTypeCode },
    )
    return createStructuralJob(
        streamId = streamId,
        eventId = eventId,
        eventType = eventType,
        eventSchemaVersion = eventSchemaVersion,
        interpretationVersion = interpretationVersion,
        scopeKind = requireNotNull(scopeKind),
        scopeId = requireNotNull(scopeId),
        replayGeneration = replayGeneration,
        createdAtMs = ingestedAtMs,
    )
}

private fun createStructuralJob(
    streamId: String,
    eventId: String,
    eventType: LearningEventType,
    eventSchemaVersion: Int,
    interpretationVersion: Int,
    scopeKind: String,
    scopeId: String,
    replayGeneration: Long,
    createdAtMs: Long,
): LearningJobEntity {
    require(interpretationVersion > 0) { "Invalid job interpretation version" }
    require(eventSchemaVersion > 0) { "Invalid source event schema" }
    require(LearningScope.parseOrNull(scopeKind, scopeId) != null) { "Invalid job scope" }
    val type = when (eventType) {
        LearningEventType.EXECUTION_TERMINAL -> LearningJobType.RECONCILE_SOURCE
        LearningEventType.COMMAND_ADMITTED,
        LearningEventType.COMMAND_WAITING_APPROVAL,
        LearningEventType.COMMAND_TERMINAL,
        -> LearningJobType.ASSEMBLE_EPISODE_SHADOW

        LearningEventType.SOURCE_INVALIDATED -> {
            require(eventSchemaVersion >= 2) { "Legacy invalidation has no typed transition" }
            LearningJobType.INVALIDATE_SOURCE_V1
        }

        LearningEventType.STREAM_INIT,
        LearningEventType.USER_FEEDBACK_RECORDED,
        LearningEventType.TOOL_SCHEMA_CHANGED,
        LearningEventType.WORKFLOW_TRIAL_TERMINAL,
        -> throw IllegalArgumentException("Event type does not produce a P0 job")
    }
    val executionSpec = if (type == LearningJobType.INVALIDATE_SOURCE_V1) {
        LearningJobExecutionSpecV1(
            jobType = type,
            jobSchemaVersion = 1,
            algorithmIdentity = "source-invalidation-v1",
            promptIdentity = "no-provider-prompt-v1",
            providerKindIdentity = LearningJobProviderKindIdentity.NONE.wireCode,
            modelIdentity = "no-provider-model-v1",
            providerIdentity = "no-provider-v1",
            providerConfigurationIdentity = "no-provider-configuration-v1",
            providerConfigGeneration = 0L,
            sourceSchemaIdentity = "learning-source-invalidation-event-v2",
            toolsetIdentity = "authority-event-only-v1",
            outputSchemaIdentity = "learning-source-validity-output-v1",
        )
    } else {
        LearningJobExecutionSpecs.forNewP0Job(type)
    }
    val digest = LearningCanonicalId.digest(
        domainVersion = P0_JOB_ID_DOMAIN,
        fields = listOf(
            streamId,
            eventId,
            scopeKind,
            scopeId,
            type.name,
            executionSpec.jobSchemaVersion.toString(),
            replayGeneration.toString(),
            eventSchemaVersion.toString(),
            interpretationVersion.toString(),
            executionSpec.algorithmIdentity,
            executionSpec.promptIdentity,
            executionSpec.providerKindIdentity,
            executionSpec.modelIdentity,
            executionSpec.providerIdentity,
            executionSpec.providerConfigurationIdentity,
            executionSpec.providerConfigGeneration.toString(),
            executionSpec.sourceSchemaIdentity,
            executionSpec.toolsetIdentity,
            executionSpec.outputSchemaIdentity,
        ),
    )
    return LearningJobEntity(
        id = "learning-job-v1:$digest",
        jobType = type.name,
        jobSchemaVersion = executionSpec.jobSchemaVersion,
        dedupeKey = "learning-job-dedupe-v1:$digest",
        streamId = streamId,
        sourceEventId = eventId,
        scopeKind = scopeKind,
        scopeId = scopeId,
        state = LearningJobState.PENDING.name,
        priority = 0,
        attempts = 0,
        maxAttempts = DEFAULT_JOB_MAX_ATTEMPTS,
        notBeforeMs = createdAtMs,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0L,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
        finishedAtMs = null,
        replayGeneration = replayGeneration,
        algorithmIdentity = executionSpec.algorithmIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        promptIdentity = executionSpec.promptIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        providerKindIdentity = executionSpec.providerKindIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        modelIdentity = executionSpec.modelIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        providerIdentity = executionSpec.providerIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        providerConfigurationIdentity = executionSpec.providerConfigurationIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        providerConfigGeneration = executionSpec.providerConfigGeneration.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        sourceSchemaIdentity = executionSpec.sourceSchemaIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        toolsetIdentity = executionSpec.toolsetIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
        outputSchemaIdentity = executionSpec.outputSchemaIdentity.takeIf {
            type.requiresFrozenP1ExecutionIdentity
        },
    )
}

private const val P0_JOB_ID_DOMAIN = "learning-job-v1"
private fun LearningJobEntity.hasSameImmutableIdentityAs(other: LearningJobEntity): Boolean =
    normalizedForImmutableComparison() == other.normalizedForImmutableComparison()

private fun LearningJobEntity.hasSameSourceJobAuthorityAs(other: LearningJobEntity): Boolean =
    streamId == other.streamId &&
        sourceEventId == other.sourceEventId &&
        scopeKind == other.scopeKind &&
        scopeId == other.scopeId &&
        jobType == other.jobType &&
        replayGeneration == other.replayGeneration

private fun LearningJobEntity.normalizedForImmutableComparison(): LearningJobEntity = copy(
    state = LearningJobState.PENDING.name,
    attempts = 0,
    notBeforeMs = 0L,
    leaseProcessSessionId = null,
    leaseWorkerId = null,
    leaseGeneration = 0L,
    leaseUntilMs = null,
    lastErrorCode = null,
    createdAtMs = 0L,
    updatedAtMs = 0L,
    finishedAtMs = null,
)

private fun LearningInboxEventEntity.toAuthoritativeEvent(): LearningInboxAuthoritativeEvent =
    LearningInboxAuthoritativeEvent(
        streamId = Uuid.parse(streamId),
        eventId = eventId,
        outboxSeq = outboxSeq,
        eventCode = LearningEventCode(eventTypeCode, eventSchemaVersion),
        terminalStateCode = terminalState,
        sourceTypeCode = sourceType,
        sourceId = sourceId,
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        sourceStateCode = sourceState,
        missingRevisionReasonCode = missingRevisionReason,
        scopeKindCode = scopeKind,
        scopeId = scopeId,
        correlation = LearningCorrelation(
            previousSourceRevision = previousSourceRevision,
            sourceStateCode = sourceState,
            conversationId = conversationId,
            conversationSourceRevision = conversationSourceRevision,
            commandId = commandId,
            lineageId = lineageId,
            parentCommandId = parentCommandId,
            branchAnchorMessageId = branchAnchorMessageId,
            branchAnchorMessageRevision = branchAnchorMessageRevision,
            completionKindCode = completionKind,
            generationRunId = generationRunId,
            executionId = executionId,
            toolCallId = toolCallId,
            toolName = toolName,
            toolSchemaFingerprint = toolSchemaFingerprint,
            messageId = messageId,
            messageRevision = messageRevision,
        ),
        occurredAtMs = occurredAtMs,
        createdAtMs = createdAtMs,
        replayGeneration = replayGeneration,
    )

private fun LearningInboxAuthoritativeEvent.toValidatedHandoffEvent(): LearningHandoffEvent {
    val parsedSource = sourceId?.let { storedSourceId ->
        val parsedScope = LearningScope.parseOrNull(
            requireNotNull(scopeKindCode),
            requireNotNull(scopeId),
        ) ?: throw IllegalArgumentException("Invalid reinterpreted scope")
        LearningSourceRef(
            sourceKind = LearningSourceKind.entries.firstOrNull { it.name == sourceTypeCode }
                ?: LearningSourceKind.UNKNOWN,
            sourceId = storedSourceId,
            sourceRevision = sourceRevision,
            missingRevisionReason = missingRevisionReasonCode?.let { rawReason ->
                MissingSourceRevisionReason.entries.firstOrNull { it.name == rawReason }
                    ?: MissingSourceRevisionReason.UNKNOWN
            },
            databaseStreamId = streamId,
            scope = parsedScope,
            occurredAtMs = requireNotNull(occurredAtMs),
        )
    }
    return LearningHandoffEvent(
        streamId = streamId,
        eventId = eventId,
        outboxSeq = outboxSeq,
        eventCode = eventCode,
        source = parsedSource,
        sourceTypeCode = sourceTypeCode,
        missingRevisionReasonCode = missingRevisionReasonCode,
        terminalStateCode = terminalStateCode,
        correlation = correlation,
        createdAtMs = createdAtMs,
    )
}

private fun LearningJobEntity.requireMatches(event: LearningInboxAuthoritativeEvent) {
    require(streamId == event.streamId.toString()) { "Reinterpreted job stream mismatch" }
    require(sourceEventId == event.eventId) { "Reinterpreted job source mismatch" }
    require(scopeKind == event.scopeKindCode && scopeId == event.scopeId) {
        "Reinterpreted job scope mismatch"
    }
    require(replayGeneration == event.replayGeneration) {
        "Reinterpreted job replay generation mismatch"
    }
    require(
        state == LearningJobState.PENDING.name &&
            attempts == 0 &&
            leaseProcessSessionId == null &&
            leaseWorkerId == null &&
            leaseGeneration == 0L &&
            leaseUntilMs == null &&
            lastErrorCode == null &&
            finishedAtMs == null
    ) { "Reinterpreted job is not an initial pending job" }
}

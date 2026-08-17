package me.rerere.rikkahub.learning.jobs

import java.util.ArrayDeque
import me.rerere.rikkahub.learning.policy.MAX_POLICY_LINEAGE_DEPTH
import me.rerere.rikkahub.learning.policy.MAX_POLICY_LINEAGE_VISITED
import me.rerere.rikkahub.learning.policy.P1PolicyEvidenceRecalculator
import me.rerere.rikkahub.learning.policy.P1PolicyEvidenceSignal
import me.rerere.rikkahub.learning.policy.PolicyEvidencePolarity
import me.rerere.rikkahub.learning.policy.PolicyEvidenceValiditySnapshot
import me.rerere.rikkahub.learning.policy.PolicyLineageEdge
import me.rerere.rikkahub.learning.policy.PolicyLineageKind
import me.rerere.rikkahub.learning.policy.PolicySourceInvalidationPlanner
import me.rerere.rikkahub.learning.policy.PolicySourcePropagationResult
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningEpisodeLessonEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import me.rerere.rikkahub.learning.storage.LearningLessonState
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyEvidencePolarity
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionActor
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionReason
import me.rerere.rikkahub.learning.storage.LearningRewardWindowEntity
import me.rerere.rikkahub.learning.storage.LearningRewardWindowState
import me.rerere.rikkahub.learning.storage.LearningSourceValidityEntity
import me.rerere.rikkahub.learning.storage.LearningSourceValidityState
import me.rerere.rikkahub.learning.storage.LearningTraceFeatureEntity
import me.rerere.rikkahub.learning.storage.PolicyEvidenceEntity
import me.rerere.rikkahub.learning.storage.PolicyLineageEntity
import me.rerere.rikkahub.learning.storage.PolicyRevisionEntity
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.storage.POLICY_SOURCE_REDACTION_MARKER
import me.rerere.rikkahub.learning.storage.sourcePrivacyAuditSnapshot
import me.rerere.rikkahub.learning.storage.sourceRedactedArtifactSha256
import me.rerere.rikkahub.learning.storage.sourceRedactedTaskSignature
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionActor
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionReason
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState

/** Atomic Episode projection output. The job terminal CAS is the enclosing transaction fence. */
data class AssembleEpisodeOutputV1(
    val episode: LearningEpisodeEntity,
    val expectedPreviousRevision: Long?,
    val expectedPreviousStatus: String?,
    val traceFeatures: List<LearningTraceFeatureEntity>,
    val initialRewardWindow: LearningRewardWindowEntity?,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "learning-episode-output-v1"

    init {
        require((expectedPreviousRevision == null) == (expectedPreviousStatus == null)) {
            "Incomplete Episode CAS expectation"
        }
        require(expectedPreviousRevision == null || expectedPreviousRevision > 0L)
        require(traceFeatures.size <= MAX_TYPED_TRACE_ROWS)
        require(
            traceFeatures.map { it.sequence to it.sourceOrdinal }.distinct().size ==
                traceFeatures.size
        )
        require(traceFeatures.all { it.episodeId == episode.id })
        require(initialRewardWindow == null || initialRewardWindow.episodeId == episode.id)
    }
}

data class EpisodeLessonOutputV1(
    val lesson: LearningEpisodeLessonEntity,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "learning-episode-lesson-output-v1"
}

data class RewardWindowOutputV1(
    val closedWindow: LearningRewardWindowEntity,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "learning-reward-window-output-v1"

    init {
        require(closedWindow.state != LearningRewardWindowState.OPEN.name) {
            "Reward completion cannot persist an OPEN output"
        }
    }
}

data class SourceValidityOutputV1(
    val validity: LearningSourceValidityEntity,
    val expectedPreviousState: String?,
    val transitionReason: String,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "learning-source-validity-output-v1"

    init {
        require(transitionReason.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
    }
}

/** Resolver performs no Room write; the fenced output committer owns the only durable mutation. */
fun interface SourceInvalidationJobMaterialResolver {
    suspend fun resolve(input: LearningJobExecutionInputV1): SourceValidityOutputV1?
}

class SourceInvalidationJobHandler(
    private val resolver: SourceInvalidationJobMaterialResolver,
) : LearningJobHandler<SourceValidityOutputV1> {
    override suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<SourceValidityOutputV1> {
        control.checkpoint()
        val output = try {
            resolver.resolve(input)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: P1LearningConfigurationUnavailableException) {
            return LearningJobHandlerResult.Retry(
                LearningJobFailureCode.WAITING_CONFIGURATION,
                SOURCE_INVALIDATION_RETRY_DELAY_MS,
            )
        } catch (_: Exception) {
            return LearningJobHandlerResult.Retry(
                LearningJobFailureCode.SOURCE_MISSING,
                SOURCE_INVALIDATION_RETRY_DELAY_MS,
            )
        } ?: return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.SOURCE_MISSING)
        control.checkpoint()
        return LearningJobHandlerResult.Success(output)
    }
}

fun interface LearningSourceIntegrityResolver {
    /** Returns the authority-owned payload digest for the exact current revision, never body text. */
    suspend fun resolveSha256(event: me.rerere.rikkahub.learning.storage.LearningInboxEventEntity): String?
}

object UnavailableLearningSourceIntegrityResolver : LearningSourceIntegrityResolver {
    override suspend fun resolveSha256(
        event: me.rerere.rikkahub.learning.storage.LearningInboxEventEntity,
    ): String? = null
}

/** Room-backed material adapter kept outside the handler. Unknown integrity remains fail-closed. */
class RoomSourceInvalidationJobMaterialResolver(
    private val database: LearningDatabase,
    private val integrityResolver: LearningSourceIntegrityResolver =
        UnavailableLearningSourceIntegrityResolver,
) : SourceInvalidationJobMaterialResolver {
    override suspend fun resolve(input: LearningJobExecutionInputV1): SourceValidityOutputV1? {
        val event = database.inboxDao().find(input.streamId, input.sourceEventId) ?: return null
        if (
            event.eventTypeCode != "SOURCE_INVALIDATED" ||
            event.eventSchemaVersion != SOURCE_INVALIDATION_EVENT_SCHEMA_VERSION ||
            event.replayGeneration != input.replayGeneration ||
            event.scopeKind != input.scopeKind ||
            event.scopeId != input.scopeId
        ) return null
        val currentRevision = event.sourceRevision ?: return null
        val previousRevision = event.previousSourceRevision ?: return null
        val sourceType = event.sourceType ?: return null
        val sourceId = event.sourceId ?: return null
        val sourceState = event.sourceState ?: return null
        val occurredAtMs = event.occurredAtMs ?: return null
        val integrity = if (sourceState == "ACTIVE") {
            integrityResolver.resolveSha256(event)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        } else {
            null
        }
        val state = when (sourceState) {
            "ACTIVE" -> if (integrity == null) {
                LearningSourceValidityState.UNKNOWN.name
            } else {
                LearningSourceValidityState.VALID.name
            }
            "SUPERSEDED" -> LearningSourceValidityState.SUPERSEDED.name
            "TOMBSTONED" -> LearningSourceValidityState.TOMBSTONED.name
            else -> return null
        }
        val reason = when {
            state == LearningSourceValidityState.VALID.name -> null
            sourceState == "ACTIVE" -> "INTEGRITY_UNAVAILABLE"
            sourceState == "SUPERSEDED" -> "SOURCE_SUPERSEDED"
            else -> "SOURCE_TOMBSTONED"
        }
        val transitionReason = when (sourceState) {
            "ACTIVE" -> "SOURCE_EDITED"
            "SUPERSEDED" -> "SOURCE_SUPERSEDED"
            else -> "SOURCE_DELETED"
        }
        val existing = database.episodeDao().findSourceValidity(
            input.streamId,
            input.replayGeneration,
            input.scopeKind,
            input.scopeId,
            sourceType,
            sourceId,
            currentRevision,
        )
        return SourceValidityOutputV1(
            validity = LearningSourceValidityEntity(
                streamId = input.streamId,
                scopeKind = input.scopeKind,
                scopeId = input.scopeId,
                sourceType = sourceType,
                sourceId = sourceId,
                sourceRevision = currentRevision,
                previousSourceRevision = previousRevision,
                state = state,
                integritySha256 = integrity,
                invalidationReason = reason,
                authorityEventId = event.eventId,
                replayGeneration = input.replayGeneration,
                occurredAtMs = occurredAtMs,
                updatedAtMs = event.ingestedAtMs,
            ),
            expectedPreviousState = existing?.state,
            transitionReason = transitionReason,
        )
    }
}

/**
 * The only typed shape accepted by the canonical P1 policy writer. It carries fully validated,
 * bounded entities rather than a map/JSON payload or provider response.
 */
data class PolicyMutationOutputV1(
    val policy: LearningPolicyEntity,
    val expectedPreviousStateVersion: Long?,
    val expectedPreviousArtifactSha256: String?,
    val revision: PolicyRevisionEntity,
    val evidence: List<PolicyEvidenceEntity>,
    val lineage: List<PolicyLineageEntity>,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "learning-policy-mutation-output-v1"

    init {
        require(
            (expectedPreviousStateVersion == null) == (expectedPreviousArtifactSha256 == null)
        ) { "Incomplete policy CAS expectation" }
        require(expectedPreviousStateVersion == null || expectedPreviousStateVersion > 0L)
        require(revision.policyId == policy.id && revision.revision == policy.stateVersion)
        require(revision.afterArtifactSha256 == policy.artifactSha256)
        require(evidence.size <= MAX_TYPED_POLICY_EVIDENCE)
        require(lineage.size <= MAX_TYPED_POLICY_LINEAGE)
        require(evidence.all { it.policyId == policy.id })
        require(lineage.all { it.childPolicyId == policy.id })
    }
}

internal object AssembleEpisodeOutputCommitter :
    LearningJobTypedOutputCommitter<AssembleEpisodeOutputV1> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: AssembleEpisodeOutputV1,
    ) {
        val desired = output.episode
        require(desired.streamId == input.streamId)
        require(desired.replayGeneration == input.replayGeneration)
        require(desired.scopeKind == input.scopeKind && desired.scopeId == input.scopeId)
        val source = requireNotNull(database.inboxDao().find(input.streamId, input.sourceEventId)) {
            "Episode source event disappeared"
        }
        require(source.replayGeneration == input.replayGeneration)
        require(source.scopeKind == desired.scopeKind && source.scopeId == desired.scopeId)
        require(source.conversationId == desired.conversationId)
        require(source.lineageId == desired.lineageId)
        require(source.branchAnchorMessageId == desired.branchAnchorMessageId)
        require(source.lineageId == desired.lineageId)
        require(source.sourceRevision != null) { "Episode source revision is not authoritative" }

        val dao = database.episodeDao()
        val existing = dao.findEpisode(desired.id)
        if (existing == null) {
            require(output.expectedPreviousRevision == null)
            require(desired.revision > 0L)
            require(dao.insertEpisodeIgnore(desired) != -1L || dao.findEpisode(desired.id) == desired) {
                "Episode identity conflict"
            }
        } else if (existing != desired) {
            val expectedRevision = requireNotNull(output.expectedPreviousRevision)
            val expectedStatus = requireNotNull(output.expectedPreviousStatus)
            require(existing.hasSameEpisodeRootAs(desired)) { "Episode root identity changed" }
            require(desired.revision == expectedRevision + 1L)
            val changed = dao.updateBoundaryIfCurrent(
                episodeId = desired.id,
                expectedRevision = expectedRevision,
                expectedStatus = expectedStatus,
                conversationRevision = desired.conversationRevision,
                finalCommandId = desired.finalCommandId,
                finalCommandRevision = desired.finalCommandRevision,
                resultAssistantMessageId = desired.resultAssistantMessageId,
                resultAssistantMessageRevision = desired.resultAssistantMessageRevision,
                generationRunId = desired.generationRunId,
                executionId = desired.executionId,
                taskSignature = desired.taskSignature,
                newStatus = desired.status,
                boundaryReason = desired.boundaryReason,
                finalizedAtMs = desired.finalizedAtMs,
                updatedAtMs = desired.updatedAtMs,
            )
            require(changed == 1 && dao.findEpisode(desired.id) == desired) {
                "Episode boundary CAS lost"
            }
        }

        output.traceFeatures.sortedBy { it.sequence }.forEach { trace ->
            val inserted = dao.insertTraceIgnore(trace)
            require(
                inserted != -1L ||
                    dao.findTrace(trace.episodeId, trace.sequence, trace.sourceOrdinal) == trace
            ) {
                "Trace feature replay conflict"
            }
        }
        output.initialRewardWindow?.let { reward ->
            val inserted = dao.insertRewardWindowIgnore(reward)
            require(inserted != -1L || dao.findRewardWindow(reward.id) == reward) {
                "Reward window replay conflict"
            }
        }
    }
}

internal object EpisodeLessonOutputCommitter :
    LearningJobTypedOutputCommitter<EpisodeLessonOutputV1> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: EpisodeLessonOutputV1,
    ) {
        val lesson = output.lesson
        val episode = requireNotNull(database.episodeDao().findEpisode(lesson.episodeId)) {
            "Lesson Episode disappeared"
        }
        require(episode.streamId == input.streamId && episode.replayGeneration == input.replayGeneration)
        require(lesson.scopeKind == input.scopeKind && lesson.scopeId == input.scopeId)
        require(episode.scopeKind == lesson.scopeKind && episode.scopeId == lesson.scopeId)
        require(episode.status != StoredLearningEpisodeStatus.OPEN.name) {
            "Reflection cannot finalize an OPEN Episode"
        }
        require(database.episodeDao().countValidStableTraceSources(episode.id) > 0L) {
            "Lesson has no valid stable source revision"
        }
        val inserted = database.episodeDao().insertLessonIgnore(lesson)
        require(
            inserted != -1L ||
                database.episodeDao().findLesson(lesson.episodeId, lesson.lessonVersion) == lesson
        ) { "Episode lesson replay conflict" }
    }
}

internal object RewardWindowOutputCommitter :
    LearningJobTypedOutputCommitter<RewardWindowOutputV1> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: RewardWindowOutputV1,
    ) {
        val desired = output.closedWindow
        val episode = requireNotNull(database.episodeDao().findEpisode(desired.episodeId))
        require(episode.streamId == input.streamId && episode.replayGeneration == input.replayGeneration)
        require(desired.scopeKind == input.scopeKind && desired.scopeId == input.scopeId)
        val existing = requireNotNull(database.episodeDao().findRewardWindow(desired.id))
        if (existing == desired) return
        require(existing.state == LearningRewardWindowState.OPEN.name)
        require(existing.episodeId == desired.episodeId)
        require(existing.rewardConfigIdentity == desired.rewardConfigIdentity)
        require(existing.openedAtMs == desired.openedAtMs && existing.closeAfterMs == desired.closeAfterMs)
        val changed = database.episodeDao().closeRewardWindowIfOpen(
            id = desired.id,
            newState = desired.state,
            goalKnowledge = desired.goalKnowledge,
            goalValue = desired.goalValue,
            goalUnknownReason = desired.goalUnknownReason,
            goalEvidenceSha256 = desired.goalEvidenceSha256,
            processKnowledge = desired.processKnowledge,
            processValue = desired.processValue,
            processUnknownReason = desired.processUnknownReason,
            processEvidenceSha256 = desired.processEvidenceSha256,
            userKnowledge = desired.userKnowledge,
            userValue = desired.userValue,
            userUnknownReason = desired.userUnknownReason,
            userEvidenceSha256 = desired.userEvidenceSha256,
            weakLabel = desired.weakLabel,
            closedAtMs = requireNotNull(desired.closedAtMs),
            updatedAtMs = desired.updatedAtMs,
        )
        require(changed == 1 && database.episodeDao().findRewardWindow(desired.id) == desired) {
            "Reward window CAS lost"
        }
    }
}

internal object SourceValidityOutputCommitter :
    LearningJobTypedOutputCommitter<SourceValidityOutputV1> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: SourceValidityOutputV1,
    ) {
        val desired = output.validity
        require(desired.streamId == input.streamId && desired.replayGeneration == input.replayGeneration)
        require(desired.scopeKind == input.scopeKind && desired.scopeId == input.scopeId)
        require(desired.authorityEventId == input.sourceEventId)
        val dao = database.episodeDao()
        desired.previousSourceRevision?.let { previousRevision ->
            val previous = dao.findSourceValidity(
                desired.streamId,
                desired.replayGeneration,
                desired.scopeKind,
                desired.scopeId,
                desired.sourceType,
                desired.sourceId,
                previousRevision,
            )
            val oldState = if (desired.state == LearningSourceValidityState.TOMBSTONED.name) {
                LearningSourceValidityState.TOMBSTONED.name
            } else {
                LearningSourceValidityState.SUPERSEDED.name
            }
            if (previous == null) {
                require(
                    dao.insertSourceValidityIgnore(
                        LearningSourceValidityEntity(
                            streamId = desired.streamId,
                            scopeKind = desired.scopeKind,
                            scopeId = desired.scopeId,
                            sourceType = desired.sourceType,
                            sourceId = desired.sourceId,
                            sourceRevision = previousRevision,
                            previousSourceRevision = null,
                            state = oldState,
                            integritySha256 = null,
                            invalidationReason = output.transitionReason,
                            authorityEventId = desired.authorityEventId,
                            replayGeneration = desired.replayGeneration,
                            occurredAtMs = desired.occurredAtMs,
                            updatedAtMs = desired.updatedAtMs,
                        ),
                    ) != -1L
                ) { "Previous source validity replay conflict" }
            } else if (previous.state in NON_TERMINAL_SOURCE_VALIDITY_STATES) {
                require(previous.replayGeneration == desired.replayGeneration) {
                    "Previous source validity replay conflict"
                }
                require(
                    dao.updateSourceValidityIfCurrent(
                        streamId = previous.streamId,
                        replayGeneration = previous.replayGeneration,
                        scopeKind = previous.scopeKind,
                        scopeId = previous.scopeId,
                        sourceType = previous.sourceType,
                        sourceId = previous.sourceId,
                        sourceRevision = previous.sourceRevision,
                        previousSourceRevision = previous.previousSourceRevision,
                        expectedState = previous.state,
                        newState = oldState,
                        integritySha256 = previous.integritySha256,
                        invalidationReason = output.transitionReason,
                        authorityEventId = desired.authorityEventId,
                        occurredAtMs = desired.occurredAtMs,
                        updatedAtMs = desired.updatedAtMs,
                    ) == 1
                ) { "Previous source validity CAS lost" }
            } else {
                require(previous.state in TERMINAL_SOURCE_VALIDITY_STATES) {
                    "Previous source validity conflict"
                }
            }
            dao.clearTraceSummariesForSource(
                desired.streamId,
                desired.replayGeneration,
                desired.scopeKind,
                desired.scopeId,
                desired.sourceType,
                desired.sourceId,
                previousRevision,
                MAX_SOURCE_INVALIDATION_FANOUT,
            )
            dao.markLessonsStaleForSource(
                desired.streamId,
                desired.replayGeneration,
                desired.scopeKind,
                desired.scopeId,
                desired.sourceType,
                desired.sourceId,
                previousRevision,
                desired.updatedAtMs,
                MAX_SOURCE_INVALIDATION_FANOUT,
            )
            invalidatePoliciesForSource(
                database,
                desired.streamId,
                desired.replayGeneration,
                desired.scopeKind,
                desired.scopeId,
                desired.sourceType,
                desired.sourceId,
                previousRevision,
                desired.updatedAtMs,
            )
        }
        // The current monotonic authority head supersedes every lower revision, not just N-1.
        // This matters when capture was disabled across multiple edits: reconciliation sees only
        // the current head, while an older derived revision may still be marked VALID locally.
        val historicalState = if (desired.state == LearningSourceValidityState.TOMBSTONED.name) {
            LearningSourceValidityState.TOMBSTONED.name
        } else {
            LearningSourceValidityState.SUPERSEDED.name
        }
        dao.invalidateAllEarlierSourceRevisions(
            streamId = desired.streamId,
            replayGeneration = desired.replayGeneration,
            scopeKind = desired.scopeKind,
            scopeId = desired.scopeId,
            sourceType = desired.sourceType,
            sourceId = desired.sourceId,
            currentRevision = desired.sourceRevision,
            newState = historicalState,
            invalidationReason = output.transitionReason,
            authorityEventId = desired.authorityEventId,
            occurredAtMs = desired.occurredAtMs,
            updatedAtMs = desired.updatedAtMs,
        )
        val existing = dao.findSourceValidity(
            desired.streamId,
            desired.replayGeneration,
            desired.scopeKind,
            desired.scopeId,
            desired.sourceType,
            desired.sourceId,
            desired.sourceRevision,
        )
        val committed = when {
            existing == null -> {
                require(output.expectedPreviousState == null)
                require(dao.insertSourceValidityIgnore(desired) != -1L)
                desired
            }
            existing == desired -> existing
            shouldPreserveExistingSourceInvalidity(existing.state, desired.state) -> existing
            else -> {
                val expectedState = requireNotNull(output.expectedPreviousState)
                require(existing.replayGeneration == desired.replayGeneration)
                val changed = dao.updateSourceValidityIfCurrent(
                    streamId = desired.streamId,
                    replayGeneration = desired.replayGeneration,
                    scopeKind = desired.scopeKind,
                    scopeId = desired.scopeId,
                    sourceType = desired.sourceType,
                    sourceId = desired.sourceId,
                    sourceRevision = desired.sourceRevision,
                    previousSourceRevision = desired.previousSourceRevision,
                    expectedState = expectedState,
                    newState = desired.state,
                    integritySha256 = desired.integritySha256,
                    invalidationReason = desired.invalidationReason,
                    authorityEventId = desired.authorityEventId,
                    occurredAtMs = desired.occurredAtMs,
                    updatedAtMs = desired.updatedAtMs,
                )
                require(changed == 1) { "Source validity CAS lost" }
                requireNotNull(
                    dao.findSourceValidity(
                        desired.streamId,
                        desired.replayGeneration,
                        desired.scopeKind,
                        desired.scopeId,
                        desired.sourceType,
                        desired.sourceId,
                        desired.sourceRevision,
                    ),
                )
            }
        }
        require(committed == desired || shouldPreserveExistingSourceInvalidity(
            committed.state,
            desired.state,
        )) { "Source validity replay conflict" }
        require(
            dao.findSourceValidity(
                desired.streamId,
                desired.replayGeneration,
                desired.scopeKind,
                desired.scopeId,
                desired.sourceType,
                desired.sourceId,
                desired.sourceRevision,
            ) == committed
        ) { "Source validity replay conflict" }
        if (committed.state != LearningSourceValidityState.VALID.name) {
            dao.clearTraceSummariesForSource(
                committed.streamId,
                committed.replayGeneration,
                committed.scopeKind,
                committed.scopeId,
                committed.sourceType,
                committed.sourceId,
                committed.sourceRevision,
                MAX_SOURCE_INVALIDATION_FANOUT,
            )
            dao.markLessonsStaleForSource(
                committed.streamId,
                committed.replayGeneration,
                committed.scopeKind,
                committed.scopeId,
                committed.sourceType,
                committed.sourceId,
                committed.sourceRevision,
                committed.updatedAtMs,
                MAX_SOURCE_INVALIDATION_FANOUT,
            )
            invalidatePoliciesForSource(
                database,
                committed.streamId,
                committed.replayGeneration,
                committed.scopeKind,
                committed.scopeId,
                committed.sourceType,
                committed.sourceId,
                committed.sourceRevision,
                committed.updatedAtMs,
            )
        }
    }
}

private val NON_TERMINAL_SOURCE_VALIDITY_STATES = setOf(
    LearningSourceValidityState.VALID.name,
    LearningSourceValidityState.UNKNOWN.name,
)

private val TERMINAL_SOURCE_VALIDITY_STATES = setOf(
    LearningSourceValidityState.INVALIDATED.name,
    LearningSourceValidityState.SUPERSEDED.name,
    LearningSourceValidityState.TOMBSTONED.name,
)

/** A later terminal projection must never regress when an older outbox row is replayed. */
internal fun shouldPreserveExistingSourceInvalidity(
    existingState: String,
    desiredState: String,
): Boolean = existingState == LearningSourceValidityState.TOMBSTONED.name ||
    (existingState in TERMINAL_SOURCE_VALIDITY_STATES &&
        desiredState != LearningSourceValidityState.TOMBSTONED.name)

private suspend fun invalidatePoliciesForSource(
    database: LearningDatabase,
    streamId: String,
    replayGeneration: Long,
    scopeKind: String,
    scopeId: String,
    sourceType: String,
    sourceId: String,
    sourceRevision: Long,
    updatedAtMs: Long,
) {
    val policyDao = database.policyDao()
    val affected = policyDao.listPoliciesUsingSource(
        streamId,
        replayGeneration,
        scopeKind,
        scopeId,
        sourceType,
        sourceId,
        sourceRevision,
        afterPolicyId = "",
        limit = MAX_POLICY_INVALIDATION_FANOUT,
    )
    reconcilePolicySourceChanges(database, affected, updatedAtMs)
}

/** Bounded eventual audit projection; retrieval's live validity join is the immediate safety gate. */
internal suspend fun reconcileInvalidPolicyEvidence(
    database: LearningDatabase,
    frozenNowMs: Long,
    limit: Int,
): Int {
    require(frozenNowMs >= 0L && limit in 1..MAX_POLICY_INVALIDATION_FANOUT)
    val affected = database.policyDao().listLivePoliciesWithInvalidEvidence(limit)
    reconcilePolicySourceChanges(database, affected, frozenNowMs)
    return affected.size
}

/**
 * Recomputes direct policies from surviving evidence and then propagates a zero-support stale root
 * through bounded derivation lineage. The caller owns one outer Room transaction; every rejected
 * cycle/depth/visited/fanout condition therefore rolls back validity, evidence, Policy and revision.
 */
private suspend fun reconcilePolicySourceChanges(
    database: LearningDatabase,
    policyIds: List<String>,
    updatedAtMs: Long,
) {
    val policyDao = database.policyDao()
    val staleRoots = linkedSetOf<String>()
    policyIds.distinct().sorted().forEach { policyId ->
        val current = policyDao.findPolicy(policyId) ?: return@forEach
        val evidence = policyDao.listEvidenceValidity(policyId, MAX_POLICY_EVIDENCE_AUDIT + 1)
        require(evidence.size <= MAX_POLICY_EVIDENCE_AUDIT) { "Policy evidence audit bound exceeded" }
        require(evidence.all { it.policyId == policyId }) { "Cross-policy evidence projection" }
        val invalid = evidence.filterNot { it.sourceValid }
        // A source-invalid row can predate the privacy-redaction invariant. The bounded audit
        // deliberately returns those legacy rows even when their invalid evidence edge has
        // already been removed, so they must still pass through the exact redaction CAS.
        if (invalid.isEmpty() && current.sourceValid) return@forEach
        if (invalid.isEmpty() && current.hasCompleteSourcePrivacyRedaction()) {
            redactCuratorCandidatesForPolicy(database, policyId, maxOf(current.updatedAtMs, updatedAtMs))
            staleWorkflowCandidatesForPolicy(
                database,
                policyId,
                maxOf(current.updatedAtMs, updatedAtMs),
            )
            return@forEach
        }
        val surviving = evidence.filter { it.sourceValid }
        val statistics = P1PolicyEvidenceRecalculator.calculate(
            surviving.map { row ->
                P1PolicyEvidenceSignal(
                    evidenceId = row.episodeId,
                    polarity = row.polarity.toDomainEvidencePolarity(),
                    quality = row.quality,
                )
            },
        )
        invalid.sortedBy { it.episodeId }.forEach { row ->
            require(policyDao.deleteEvidence(policyId, row.episodeId) == 1) {
                "Policy invalid evidence deletion lost"
            }
        }
        val lifecycleEvidence = PolicySourceLifecycleEvidence(
            digest = LearningCanonicalId.digest(
                domainVersion = "policy-source-reconciliation-evidence-v1",
                fields = buildList {
                    add(policyId)
                    add(current.stateVersion.toString())
                    add(current.contentRevision.toString())
                    add(current.artifactSha256)
                    invalid.map { it.episodeId }.sorted().forEach(::add)
                },
            ),
            observedAtMs = maxOf(current.updatedAtMs, updatedAtMs),
        )
        val next = current.copy(
            stateVersion = Math.addExact(current.stateVersion, 1L),
            contentRevision = Math.addExact(current.contentRevision, 1L),
            taskSignature = current.sourceRedactedTaskSignature(),
            triggerSummary = POLICY_SOURCE_REDACTION_MARKER,
            procedureSummary = POLICY_SOURCE_REDACTION_MARKER,
            verificationSummary = POLICY_SOURCE_REDACTION_MARKER,
            boundarySummary = POLICY_SOURCE_REDACTION_MARKER,
            failureModeSummary = POLICY_SOURCE_REDACTION_MARKER,
            artifactSha256 = current.sourceRedactedArtifactSha256(),
            status = StoredLearningPolicyStatus.STALE_SOURCE.name,
            sourceValid = false,
            staleReason = STALE_SOURCE_REASON,
            distinctEpisodeSupport = statistics.distinctEpisodeSupport.toLong(),
            positiveEpisodeCount = statistics.positiveEpisodeCount.toLong(),
            negativeEpisodeCount = statistics.negativeEpisodeCount.toLong(),
            confidence = statistics.confidence,
            updatedAtMs = maxOf(current.updatedAtMs, updatedAtMs),
        )
        persistSourceReconciliation(
            policyDao = policyDao,
            current = current,
            next = next,
            lifecycleEvidence = lifecycleEvidence,
        )
        redactCuratorCandidatesForPolicy(database, policyId, next.updatedAtMs)
        staleWorkflowCandidatesForPolicy(database, policyId, next.updatedAtMs)
        require(policyDao.countDistinctEpisodeSupport(policyId) == next.distinctEpisodeSupport)
        require(
            policyDao.countDistinctEpisodeSupportByPolarity(
                policyId,
                LearningPolicyEvidencePolarity.POSITIVE.name,
            ) == next.positiveEpisodeCount,
        )
        require(
            policyDao.countDistinctEpisodeSupportByPolarity(
                policyId,
                LearningPolicyEvidencePolarity.NEGATIVE.name,
            ) == next.negativeEpisodeCount,
        )
        staleRoots += policyId
    }
    if (staleRoots.isEmpty()) return

    val lineage = loadBoundedDerivedLineage(database, staleRoots)
    val plan = PolicySourceInvalidationPlanner.plan(
        evidence = staleRoots.sorted().map { policyId ->
            PolicyEvidenceValiditySnapshot(policyId, "stale-root:$policyId", sourceValid = false)
        },
        lineage = lineage,
    )
    val stalePolicyIds = when (plan) {
        is PolicySourcePropagationResult.Planned -> plan.plan.stalePolicyIds
        is PolicySourcePropagationResult.Rejected -> error(
            "Policy lineage propagation rejected: ${plan.failure.name}",
        )
    }
    stalePolicyIds.minus(staleRoots).sorted().forEach { policyId ->
        val current = policyDao.findPolicy(policyId)
            ?: error("Policy lineage child disappeared")
        if (!current.sourceValid && current.hasCompleteSourcePrivacyRedaction()) return@forEach
        val next = current.copy(
            stateVersion = Math.addExact(current.stateVersion, 1L),
            contentRevision = Math.addExact(current.contentRevision, 1L),
            taskSignature = current.sourceRedactedTaskSignature(),
            triggerSummary = POLICY_SOURCE_REDACTION_MARKER,
            procedureSummary = POLICY_SOURCE_REDACTION_MARKER,
            verificationSummary = POLICY_SOURCE_REDACTION_MARKER,
            boundarySummary = POLICY_SOURCE_REDACTION_MARKER,
            failureModeSummary = POLICY_SOURCE_REDACTION_MARKER,
            artifactSha256 = current.sourceRedactedArtifactSha256(),
            status = StoredLearningPolicyStatus.STALE_SOURCE.name,
            sourceValid = false,
            staleReason = STALE_SOURCE_REASON,
            updatedAtMs = maxOf(current.updatedAtMs, updatedAtMs),
        )
        persistSourceReconciliation(
            policyDao = policyDao,
            current = current,
            next = next,
            lifecycleEvidence = PolicySourceLifecycleEvidence(
                digest = LearningCanonicalId.digest(
                    domainVersion = "policy-source-lineage-evidence-v1",
                    fields = buildList {
                        add(policyId)
                        add(current.stateVersion.toString())
                        add(current.contentRevision.toString())
                        add(current.artifactSha256)
                        staleRoots.sorted().forEach(::add)
                    },
                ),
                observedAtMs = maxOf(current.updatedAtMs, updatedAtMs),
            ),
        )
        redactCuratorCandidatesForPolicy(database, policyId, next.updatedAtMs)
        staleWorkflowCandidatesForPolicy(database, policyId, next.updatedAtMs)
    }
}

/**
 * A learned workflow remains derived from its exact source Policy after promotion. Source
 * invalidation therefore stales every execution-capable candidate in the same LearningDatabase
 * transaction as the Policy/evidence mutation. The AppDatabase row may still say enabled, but
 * its per-fire authority validator immediately observes this terminal candidate state and denies.
 */
private suspend fun staleWorkflowCandidatesForPolicy(
    database: LearningDatabase,
    policyId: String,
    invalidatedAtMs: Long,
) {
    val dao = database.learnedWorkflowCandidateDao()
    while (true) {
        val candidates = dao.listSourceInvalidationCandidates(
            sourcePolicyId = policyId,
            limit = MAX_WORKFLOW_SOURCE_INVALIDATION_FANOUT,
        )
        if (candidates.isEmpty()) return
        candidates.forEach { current ->
            require(current.sourcePolicyId == policyId && current.stateVersion < Long.MAX_VALUE) {
                "Workflow source invalidation invariant failed"
            }
            val next = current.copy(
                state = LearnedWorkflowCandidateState.STALE_SOURCE.name,
                stateVersion = current.stateVersion + 1L,
                updatedAtMs = maxOf(current.updatedAtMs, invalidatedAtMs),
            )
            require(
                dao.transitionFenced(
                    expected = current,
                    next = next,
                    reason = LearnedWorkflowCandidateRevisionReason.SOURCE_INVALIDATED,
                    actor = LearnedWorkflowCandidateRevisionActor.SOURCE_RECONCILER,
                ),
            ) { "Workflow source invalidation CAS lost" }
        }
    }
}

/** Policy-derived Curator wires are privacy-derived state and must stale in the same Room fence. */
private suspend fun redactCuratorCandidatesForPolicy(
    database: LearningDatabase,
    policyId: String,
    redactedAtMs: Long,
) {
    var hasMore: Boolean
    do {
        val result = database.curatorDeltaDao().redactByPolicySource(
            policyId = policyId,
            redactedAtMs = redactedAtMs,
            limit = MAX_CURATOR_SOURCE_REDACTION_FANOUT,
        )
        require(result.redacted == result.scanned) {
            "Curator source redaction did not cover the complete page"
        }
        hasMore = result.hasMore
    } while (hasMore)
}

private suspend fun persistSourceReconciliation(
    policyDao: me.rerere.rikkahub.learning.storage.LearningPolicyDao,
    current: LearningPolicyEntity,
    next: LearningPolicyEntity,
    lifecycleEvidence: PolicySourceLifecycleEvidence,
) {
    require(
        policyDao.redactPolicySourceIfCurrent(
            policyId = current.id,
            expectedStateVersion = current.stateVersion,
            expectedContentRevision = current.contentRevision,
            expectedArtifactSha256 = current.artifactSha256,
            expectedApplicableToolSchemasWire = current.applicableToolSchemasWire,
            expectedApplicableModelIdentityWire = current.applicableModelIdentityWire,
            expectedApplicableProviderIdentityWire = current.applicableProviderIdentityWire,
            expectedApplicableTemplateIdentity = current.applicableTemplateIdentity,
            expectedApplicableConfigurationIdentity = current.applicableConfigurationIdentity,
            expectedApplicableConfigurationGeneration =
                current.applicableConfigurationGeneration,
            expectedApplicableCapabilityDigest = current.applicableCapabilityDigest,
            expectedApplicableAuthorityDigest = current.applicableAuthorityDigest,
            newContentRevision = next.contentRevision,
            redactedTaskSignature = next.taskSignature,
            redactedArtifactSha256 = next.artifactSha256,
            remainingSupport = next.distinctEpisodeSupport,
            remainingPositive = next.positiveEpisodeCount,
            remainingNegative = next.negativeEpisodeCount,
            remainingConfidence = next.confidence,
            updatedAtMs = next.updatedAtMs,
        ) == 1,
    ) { "Policy source reconciliation CAS lost" }
    require(policyDao.findPolicy(current.id) == next) { "Policy source reconciliation mismatch" }
    // Historic audit snapshots were previously allowed to contain the policy summaries. Source
    // erasure rewrites them to a fixed marker before appending the new content-free receipt.
    policyDao.redactPolicyRevisionSnapshots(current.id)
    policyDao.insertRevision(
        PolicyRevisionEntity(
            policyId = next.id,
            revision = next.stateVersion,
            beforeSnapshot = current.sourcePrivacyAuditSnapshot(lifecycleEvidence.digest),
            afterSnapshot = next.sourcePrivacyAuditSnapshot(lifecycleEvidence.digest),
            beforeArtifactSha256 = current.artifactSha256,
            afterArtifactSha256 = next.artifactSha256,
            reasonCode = LearningPolicyRevisionReason.SOURCE_INVALIDATED.name,
            actor = LearningPolicyRevisionActor.SYSTEM.name,
            createdAtMs = next.updatedAtMs,
        ),
    )
}

private fun LearningPolicyEntity.hasCompleteSourcePrivacyRedaction(): Boolean =
    taskSignature.startsWith("policy-source-redacted-v1:") &&
        triggerSummary == POLICY_SOURCE_REDACTION_MARKER &&
        procedureSummary == POLICY_SOURCE_REDACTION_MARKER &&
        verificationSummary == POLICY_SOURCE_REDACTION_MARKER &&
        boundarySummary == POLICY_SOURCE_REDACTION_MARKER &&
        failureModeSummary == POLICY_SOURCE_REDACTION_MARKER

private const val MAX_CURATOR_SOURCE_REDACTION_FANOUT = 128
private const val MAX_WORKFLOW_SOURCE_INVALIDATION_FANOUT = 128

private data class PolicySourceLifecycleEvidence(
    val digest: String,
    val observedAtMs: Long,
) {
    init {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        require(observedAtMs >= 0L)
    }
}

private suspend fun loadBoundedDerivedLineage(
    database: LearningDatabase,
    roots: Set<String>,
): List<PolicyLineageEdge> {
    data class Pending(val policyId: String, val depth: Int, val scopeKind: String, val scopeId: String)

    val dao = database.policyDao()
    val pending = ArrayDeque<Pending>()
    roots.sorted().forEach { rootId ->
        val root = requireNotNull(dao.findPolicy(rootId)) { "Policy stale root missing" }
        pending.addLast(Pending(rootId, 0, root.scopeKind, root.scopeId))
    }
    val expanded = linkedSetOf<String>()
    val edges = linkedSetOf<PolicyLineageEdge>()
    while (pending.isNotEmpty()) {
        val node = pending.removeFirst()
        if (!expanded.add(node.policyId)) continue
        require(expanded.size <= MAX_POLICY_LINEAGE_VISITED) { "Policy lineage visited bound exceeded" }
        val children = dao.listDerivedChildren(node.policyId, MAX_POLICY_LINEAGE_FANOUT + 1)
        require(children.size <= MAX_POLICY_LINEAGE_FANOUT) { "Policy lineage fanout bound exceeded" }
        if (node.depth >= MAX_POLICY_LINEAGE_DEPTH) {
            require(children.isEmpty()) { "Policy lineage depth exceeded" }
        }
        children.forEach { stored ->
            val child = requireNotNull(dao.findPolicy(stored.childPolicyId)) {
                "Policy lineage child missing"
            }
            require(child.scopeKind == node.scopeKind && child.scopeId == node.scopeId) {
                "Cross-scope policy lineage"
            }
            val edge = PolicyLineageEdge(
                fromPolicyId = stored.parentPolicyId,
                toPolicyId = stored.childPolicyId,
                kind = PolicyLineageKind.valueOf(stored.relationType),
            )
            edges += edge
            require(edges.size <= MAX_POLICY_LINEAGE_EDGES) { "Policy lineage edge bound exceeded" }
            pending.addLast(Pending(child.id, node.depth + 1, node.scopeKind, node.scopeId))
        }
    }
    return edges.toList()
}

private fun String.toDomainEvidencePolarity(): PolicyEvidencePolarity = when (this) {
    LearningPolicyEvidencePolarity.POSITIVE.name -> PolicyEvidencePolarity.POSITIVE
    LearningPolicyEvidencePolarity.NEGATIVE.name -> PolicyEvidencePolarity.NEGATIVE
    else -> PolicyEvidencePolarity.NEUTRAL
}

internal object PolicyMutationOutputCommitter :
    LearningJobTypedOutputCommitter<PolicyMutationOutputV1> {
    override suspend fun persistInOpenTransaction(
        database: LearningDatabase,
        input: LearningJobExecutionInputV1,
        output: PolicyMutationOutputV1,
    ) {
        val desired = output.policy
        require(desired.scopeKind == input.scopeKind && desired.scopeId == input.scopeId)
        val dao = database.policyDao()
        val existing = dao.findPolicy(desired.id)
        if (existing == null) {
            require(output.expectedPreviousStateVersion == null)
            require(desired.stateVersion == 1L)
            require(output.revision.beforeSnapshot == null)
            dao.insertPolicy(desired)
        } else if (existing != desired) {
            val expectedVersion = requireNotNull(output.expectedPreviousStateVersion)
            val expectedArtifact = requireNotNull(output.expectedPreviousArtifactSha256)
            require(existing.stateVersion == expectedVersion && existing.artifactSha256 == expectedArtifact)
            require(desired.stateVersion == expectedVersion + 1L)
            require(existing.hasSamePolicyProducerAndScopeAs(desired))
            // Evidence is inserted before statistics are checked; a lost final policy CAS rolls the
            // entire outer job transaction back, including these rows.
        }

        output.evidence.sortedWith(compareBy({ it.episodeId }, { it.evidenceKind })).forEach { edge ->
            requireEvidenceAuthority(database, desired, edge)
            val inserted = dao.insertEvidenceIgnore(edge)
            require(
                inserted != -1L ||
                    dao.findEvidence(edge.policyId, edge.episodeId) == edge
            ) { "Policy evidence replay conflict" }
        }
        require(dao.countDistinctEpisodeSupport(desired.id) == desired.distinctEpisodeSupport) {
            "Policy support is not distinct-Episode support"
        }
        require(
            dao.countDistinctEpisodeSupportByPolarity(desired.id, "POSITIVE") ==
                desired.positiveEpisodeCount
        ) { "Policy positive support mismatch" }
        require(
            dao.countDistinctEpisodeSupportByPolarity(desired.id, "NEGATIVE") ==
                desired.negativeEpisodeCount
        ) { "Policy negative support mismatch" }

        if (existing != null && existing != desired) {
            val changed = dao.updatePolicyIfCurrent(
                policyId = desired.id,
                expectedStateVersion = requireNotNull(output.expectedPreviousStateVersion),
                expectedContentRevision = existing.contentRevision,
                expectedArtifactSha256 = requireNotNull(output.expectedPreviousArtifactSha256),
                expectedApplicableToolSchemasWire = existing.applicableToolSchemasWire,
                expectedApplicableModelIdentityWire = existing.applicableModelIdentityWire,
                expectedApplicableProviderIdentityWire = existing.applicableProviderIdentityWire,
                expectedApplicableTemplateIdentity = existing.applicableTemplateIdentity,
                expectedApplicableConfigurationIdentity = existing.applicableConfigurationIdentity,
                expectedApplicableConfigurationGeneration =
                    existing.applicableConfigurationGeneration,
                expectedApplicableCapabilityDigest = existing.applicableCapabilityDigest,
                expectedApplicableAuthorityDigest = existing.applicableAuthorityDigest,
                taskSignature = desired.taskSignature,
                policyType = desired.policyType,
                triggerSummary = desired.triggerSummary,
                procedureSummary = desired.procedureSummary,
                verificationSummary = desired.verificationSummary,
                boundarySummary = desired.boundarySummary,
                failureModeSummary = desired.failureModeSummary,
                newContentRevision = desired.contentRevision,
                newArtifactSha256 = desired.artifactSha256,
                compilerAbi = desired.compilerAbi,
                status = desired.status,
                sourceValid = desired.sourceValid,
                schemaValid = desired.schemaValid,
                applicableToolSchemasWire = desired.applicableToolSchemasWire,
                applicableModelIdentityWire = desired.applicableModelIdentityWire,
                applicableProviderIdentityWire = desired.applicableProviderIdentityWire,
                applicableTemplateIdentity = desired.applicableTemplateIdentity,
                applicableConfigurationIdentity = desired.applicableConfigurationIdentity,
                applicableConfigurationGeneration = desired.applicableConfigurationGeneration,
                applicableCapabilityDigest = desired.applicableCapabilityDigest,
                applicableAuthorityDigest = desired.applicableAuthorityDigest,
                staleReason = desired.staleReason,
                distinctEpisodeSupport = desired.distinctEpisodeSupport,
                positiveEpisodeCount = desired.positiveEpisodeCount,
                negativeEpisodeCount = desired.negativeEpisodeCount,
                confidence = desired.confidence,
                updatedAtMs = desired.updatedAtMs,
            )
            require(changed == 1 && dao.findPolicy(desired.id) == desired) { "Policy CAS lost" }
        }

        val priorRevision = dao.findRevision(output.revision.policyId, output.revision.revision)
        if (priorRevision == null) dao.insertRevision(output.revision)
        else require(priorRevision == output.revision) { "Policy revision replay conflict" }

        output.lineage.sortedWith(compareBy({ it.parentPolicyId }, { it.relationType })).forEach { edge ->
            val parent = requireNotNull(dao.findPolicy(edge.parentPolicyId)) {
                "Policy lineage parent missing"
            }
            require(parent.scopeKind == desired.scopeKind && parent.scopeId == desired.scopeId) {
                "Cross-scope policy lineage"
            }
            require(dao.countCyclePaths(edge.childPolicyId, edge.parentPolicyId, MAX_LINEAGE_DEPTH) == 0L) {
                "Policy lineage cycle"
            }
            require(dao.countPathsAtDepthLimit(edge.parentPolicyId, MAX_LINEAGE_DEPTH) == 0L) {
                "Policy lineage depth exceeded"
            }
            val inserted = dao.insertLineageIgnore(edge)
            require(
                inserted != -1L ||
                    dao.findLineage(edge.childPolicyId, edge.parentPolicyId, edge.relationType) == edge
            ) { "Policy lineage replay conflict" }
        }
    }
}

private suspend fun requireEvidenceAuthority(
    database: LearningDatabase,
    policy: LearningPolicyEntity,
    edge: PolicyEvidenceEntity,
) {
    val episode = requireNotNull(database.episodeDao().findEpisode(edge.episodeId)) {
        "Policy evidence Episode missing"
    }
    require(episode.scopeKind == policy.scopeKind && episode.scopeId == policy.scopeId) {
        "Cross-scope policy evidence"
    }
    val lesson = requireNotNull(database.episodeDao().findLesson(edge.episodeId, edge.lessonVersion)) {
        "Policy evidence lesson missing"
    }
    require(lesson.state == LearningLessonState.VALID.name) { "Policy evidence lesson is stale" }
    val source = requireNotNull(
        database.episodeDao().findSourceValidity(
            streamId = episode.streamId,
            replayGeneration = episode.replayGeneration,
            scopeKind = episode.scopeKind,
            scopeId = episode.scopeId,
            sourceType = edge.sourceType,
            sourceId = edge.sourceId,
            sourceRevision = edge.sourceRevision,
        ),
    ) { "Policy evidence source validity missing" }
    require(source.state == LearningSourceValidityState.VALID.name)
    require(source.integritySha256 == edge.sourceIntegritySha256)
}

private fun LearningEpisodeEntity.hasSameEpisodeRootAs(other: LearningEpisodeEntity): Boolean =
    copy(
        conversationRevision = other.conversationRevision,
        finalCommandId = other.finalCommandId,
        finalCommandRevision = other.finalCommandRevision,
        resultAssistantMessageId = other.resultAssistantMessageId,
        resultAssistantMessageRevision = other.resultAssistantMessageRevision,
        generationRunId = other.generationRunId,
        executionId = other.executionId,
        taskSignature = other.taskSignature,
        status = other.status,
        boundaryReason = other.boundaryReason,
        revision = other.revision,
        finalizedAtMs = other.finalizedAtMs,
        updatedAtMs = other.updatedAtMs,
    ) == other

private fun LearningPolicyEntity.hasSamePolicyProducerAndScopeAs(other: LearningPolicyEntity): Boolean =
    id == other.id &&
        scopeKind == other.scopeKind &&
        scopeId == other.scopeId &&
        producerModelIdentity == other.producerModelIdentity &&
        producerProviderIdentity == other.producerProviderIdentity &&
        producerProviderKind == other.producerProviderKind &&
        producerConfigurationIdentity == other.producerConfigurationIdentity &&
        producerConfigGeneration == other.producerConfigGeneration &&
        producerPromptIdentity == other.producerPromptIdentity &&
        producerTemplateIdentity == other.producerTemplateIdentity &&
        producerSchemaIdentity == other.producerSchemaIdentity &&
        applicableModelIdentityWire == other.applicableModelIdentityWire &&
        applicableProviderIdentityWire == other.applicableProviderIdentityWire &&
        applicableTemplateIdentity == other.applicableTemplateIdentity &&
        applicableConfigurationIdentity == other.applicableConfigurationIdentity &&
        applicableConfigurationGeneration == other.applicableConfigurationGeneration &&
        applicableCapabilityDigest == other.applicableCapabilityDigest &&
        applicableAuthorityDigest == other.applicableAuthorityDigest &&
        createdAtMs == other.createdAtMs &&
        usageCount == 0L && other.usageCount == 0L &&
        lastUsedAtMs == null && other.lastUsedAtMs == null &&
        observedUtilityDelta == null && other.observedUtilityDelta == null &&
        utilityUncertainty == null && other.utilityUncertainty == null

private const val MAX_TYPED_TRACE_ROWS = 256
private const val MAX_TYPED_POLICY_EVIDENCE = 64
private const val MAX_TYPED_POLICY_LINEAGE = 16
private const val MAX_LINEAGE_DEPTH = 16
private const val SOURCE_INVALIDATION_RETRY_DELAY_MS = 5L * 60L * 1_000L
private const val MAX_POLICY_INVALIDATION_FANOUT = 128
private const val MAX_SOURCE_INVALIDATION_FANOUT = 128
private const val MAX_POLICY_EVIDENCE_AUDIT = 256
private const val MAX_POLICY_LINEAGE_FANOUT = 64
private const val MAX_POLICY_LINEAGE_EDGES = 512
private const val STALE_SOURCE_REASON = "STALE_SOURCE"
private const val SOURCE_INVALIDATION_EVENT_SCHEMA_VERSION = 2

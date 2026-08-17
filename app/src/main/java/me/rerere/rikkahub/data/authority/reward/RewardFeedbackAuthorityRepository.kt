package me.rerere.rikkahub.data.authority.reward

import me.rerere.rikkahub.data.db.entity.RewardFeedbackAuthorityEntity
import me.rerere.rikkahub.data.db.entity.toRevisionEntity
import me.rerere.rikkahub.data.db.projection.RewardFeedbackTargetAuthorityProjection
import me.rerere.rikkahub.data.authority.source.MessageSourceRevisionTransition
import me.rerere.rikkahub.data.authority.source.MessageSourceTransitionInvalidationPort
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.DisabledLearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningScopeConsentSource
import me.rerere.rikkahub.learning.model.DisabledLearningScopeConsentSource
import kotlin.uuid.Uuid

/**
 * Explicit reward authority writer.
 *
 * The public API intentionally accepts only a target ID and a fixed verdict. Scope, command,
 * lineage, message revisions and numeric reward are resolved inside the owning Room transaction.
 */
class RewardFeedbackAuthorityRepository(
    private val store: RewardFeedbackAuthorityStore,
    private val events: RewardFeedbackAuthorityEventPort = DisabledRewardFeedbackAuthorityEventPort,
    private val featureFlags: LearningFeatureFlagSource = DisabledLearningFeatureFlagSource,
    private val scopeConsent: LearningScopeConsentSource = DisabledLearningScopeConsentSource,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MessageSourceTransitionInvalidationPort {
    suspend fun record(
        targetAssistantMessageId: String,
        verdict: RewardFeedbackVerdict,
    ): RewardFeedbackWriteResult = mutate(targetAssistantMessageId, verdict, retract = false)

    suspend fun retract(
        targetAssistantMessageId: String,
        verdict: RewardFeedbackVerdict,
    ): RewardFeedbackWriteResult = mutate(targetAssistantMessageId, verdict, retract = true)

    /**
     * Reconciliation seam for a message tombstone/edit. It accepts no scope or revision: every
     * affected head is found from its stable target ID and compared with the current source head
     * inside the same transaction before a contiguous feedback tombstone is appended.
     */
    suspend fun invalidateIfSourceNoLongerExact(
        targetAssistantMessageId: String,
    ): RewardFeedbackInvalidationResult {
        require(targetAssistantMessageId.isSafeRewardTargetId()) { "Invalid reward target ID" }
        val result = try {
            store.inAuthorityTransaction {
                val heads = listActiveHeadsForTarget(
                    targetMessageId = targetAssistantMessageId,
                    limit = MAX_FEEDBACK_HEADS_PER_TARGET + 1,
                )
                check(heads.size <= MAX_FEEDBACK_HEADS_PER_TARGET) {
                    "Reward feedback target exceeds bounded authority cardinality"
                }
                var tombstoned = 0
                var insertedOutbox = false
                for (previous in heads) {
                    val source = findMessage(
                        previous.scopeKind,
                        previous.scopeId,
                        previous.targetAssistantMessageId,
                    )
                    val remainsExact = source != null &&
                        source.conversationId == previous.conversationId &&
                        source.messageRole == "ASSISTANT" &&
                        source.sourceRevision == previous.targetAssistantMessageRevision &&
                        source.sourceState == "ACTIVE" &&
                        source.payloadIntegritySha256 != null
                    if (remainsExact) continue
                    val updatedAtMs = nowMs().also { require(it >= previous.createdAtMs) }
                    val next = previous.toSourceTombstone(updatedAtMs)
                    if (!updateHeadFenced(previous, next)) {
                        throw RewardFeedbackConcurrentInvalidationException()
                    }
                    insertRevision(next.toRevisionEntity())
                    insertedOutbox = events.appendInCurrentTransaction(next.toAuthorityEvent()) ||
                        insertedOutbox
                    tombstoned += 1
                }
                RewardFeedbackInvalidationResult(heads.size, tombstoned, insertedOutbox)
            }
        } catch (_: RewardFeedbackConcurrentInvalidationException) {
            return RewardFeedbackInvalidationResult(0, 0, false)
        }
        if (result.tombstonedHeads > 0) events.dispatchPostCommit(result.insertedOutbox)
        return result
    }

    /**
     * Called by the source writer after its message CAS but before the owning AppDatabase
     * transaction returns. Any transition makes feedback pinned to the previous revision stale.
     * This seam deliberately performs neither withTransaction nor post-commit dispatch.
     */
    override suspend fun invalidateInCurrentTransaction(
        transition: MessageSourceRevisionTransition,
    ): Boolean = store.inCurrentAuthorityTransaction {
        invalidateActiveHeadsForTransition(
            targetAssistantMessageId = transition.current.messageId,
            updatedAtMs = transition.current.occurredAtMs,
            appendEvent = events::appendInCurrentTransaction,
        ).insertedOutbox
    }

    private suspend fun mutate(
        targetAssistantMessageId: String,
        verdict: RewardFeedbackVerdict,
        retract: Boolean,
    ): RewardFeedbackWriteResult {
        if (!targetAssistantMessageId.isSafeRewardTargetId()) {
            return RewardFeedbackWriteResult.Rejected(RewardFeedbackRejection.INVALID_TARGET)
        }
        val meaning = verdict.toRewardMeaning()
        val transactionResult = store.inAuthorityTransaction {
            val commands = findTerminalCommands(
                targetMessageId = targetAssistantMessageId,
                limit = MAX_COMMAND_CANDIDATES_PER_TARGET + 1,
            )
            if (commands.isEmpty()) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.INVALID_TARGET,
                )
            }
            if (commands.size > MAX_COMMAND_CANDIDATES_PER_TARGET) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.TARGET_NOT_UNIQUE,
                )
            }
            val resolvedTargets = commands.mapNotNull(::resolveTarget)
            if (resolvedTargets.isEmpty()) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.INCOMPLETE_COMMAND_AUTHORITY,
                )
            }
            val exactTargets = buildList {
                for (candidate in resolvedTargets) {
                    val source = findMessage(
                        candidate.scopeKind,
                        candidate.scopeId,
                        candidate.targetMessageId,
                    )
                    if (
                        source != null &&
                        source.conversationId == candidate.conversationId &&
                        source.messageRole == "ASSISTANT" &&
                        source.sourceRevision == candidate.targetMessageRevision &&
                        source.sourceState == "ACTIVE" &&
                        source.payloadIntegritySha256 != null
                    ) add(candidate)
                }
            }
            if (exactTargets.isEmpty()) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.SOURCE_NOT_ACTIVE_EXACT,
                )
            }
            if (exactTargets.size != 1) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.TARGET_NOT_UNIQUE,
                )
            }
            val target = exactTargets.single()
            val targetScope = LearningScope.parseOrNull(target.scopeKind, target.scopeId)
                ?: return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.SCOPE_MISMATCH,
                )
            val flags = runCatching { featureFlags.current() }.getOrNull()
            if (
                !retract && (
                flags?.isValid != true ||
                !flags.effective.capture ||
                !flags.effective.jobs ||
                !scopeConsent.captureAllowed(targetScope)
                )
            ) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.CAPTURE_NOT_AUTHORIZED,
                )
            }
            val anchorSource = findMessage(
                target.scopeKind,
                target.scopeId,
                target.branchAnchorMessageId,
            )
            if (
                anchorSource == null ||
                anchorSource.conversationId != target.conversationId ||
                anchorSource.messageRole != "USER" ||
                anchorSource.sourceRevision != target.branchAnchorMessageRevision ||
                anchorSource.sourceState != "ACTIVE"
            ) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.SCOPE_MISMATCH,
                )
            }

            val feedbackId = canonicalFeedbackId(target, meaning.dimension)
            val previous = findHead(feedbackId)
            if (
                previous != null &&
                (previous.scopeKind != target.scopeKind || previous.scopeId != target.scopeId ||
                    previous.conversationId != target.conversationId ||
                    previous.targetAssistantMessageId != target.targetMessageId ||
                    previous.targetAssistantMessageRevision != target.targetMessageRevision ||
                    previous.dimension != meaning.dimension.name)
            ) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.CONCURRENT_UPDATE,
                )
            }
            if (previous?.sourceState == RewardFeedbackSourceState.TOMBSTONED.name) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.TOMBSTONED,
                )
            }
            if (retract && previous == null) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.INVALID_TARGET,
                )
            }

            val sourceState = if (retract) {
                RewardFeedbackSourceState.TOMBSTONED
            } else {
                RewardFeedbackSourceState.ACTIVE
            }
            val signalKind = if (retract) {
                RewardSignalKind.valueOf(requireNotNull(previous).signalKind)
            } else {
                meaning.signalKind
            }
            val valueMilli = meaning.valueMilli.takeUnless { retract }
            if (
                previous != null && !retract && previous.sourceState == sourceState.name &&
                previous.signalKind == signalKind.name && previous.valueMilli == valueMilli &&
                previous.commandId == target.commandId &&
                previous.commandRevision == target.commandRevision
            ) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Duplicate(
                    feedbackId,
                    previous.sourceRevision,
                )
            }

            val updatedAtMs = nowMs().also { require(it >= 0L) }
            val next = target.toEntity(
                feedbackId = feedbackId,
                meaning = meaning,
                signalKind = signalKind,
                valueMilli = valueMilli,
                state = sourceState,
                sourceRevision = (previous?.sourceRevision ?: 0L) + 1L,
                previousSourceRevision = previous?.sourceRevision,
                createdAtMs = previous?.createdAtMs ?: updatedAtMs,
                updatedAtMs = updatedAtMs,
            )
            val committed = if (previous == null) {
                insertHeadIgnore(next)
            } else {
                updateHeadFenced(previous, next)
            }
            if (!committed) {
                return@inAuthorityTransaction RewardFeedbackWriteResult.Rejected(
                    RewardFeedbackRejection.CONCURRENT_UPDATE,
                )
            }
            insertRevision(next.toRevisionEntity())
            val insertedOutbox = events.appendInCurrentTransaction(next.toAuthorityEvent())
            RewardFeedbackWriteResult.Committed(feedbackId, next.sourceRevision, insertedOutbox)
        }
        if (transactionResult is RewardFeedbackWriteResult.Committed) {
            events.dispatchPostCommit(transactionResult.insertedOutbox)
        }
        return transactionResult
    }
}

private suspend fun RewardFeedbackAuthorityTransaction.invalidateActiveHeadsForTransition(
    targetAssistantMessageId: String,
    updatedAtMs: Long,
    appendEvent: suspend (RewardFeedbackAuthorityEvent) -> Boolean,
): RewardFeedbackInvalidationResult {
    val heads = listActiveHeadsForTarget(
        targetMessageId = targetAssistantMessageId,
        limit = MAX_FEEDBACK_HEADS_PER_TARGET + 1,
    )
    check(heads.size <= MAX_FEEDBACK_HEADS_PER_TARGET) {
        "Reward feedback target exceeds bounded authority cardinality"
    }
    var tombstoned = 0
    var insertedOutbox = false
    for (previous in heads) {
        require(updatedAtMs >= previous.createdAtMs) {
            "Reward feedback invalidation time regressed"
        }
        val next = previous.toSourceTombstone(updatedAtMs)
        if (!updateHeadFenced(previous, next)) {
            throw RewardFeedbackConcurrentInvalidationException()
        }
        insertRevision(next.toRevisionEntity())
        insertedOutbox = appendEvent(next.toAuthorityEvent()) || insertedOutbox
        tombstoned += 1
    }
    return RewardFeedbackInvalidationResult(heads.size, tombstoned, insertedOutbox)
}

private data class ResolvedRewardTarget(
    val scopeKind: String,
    val scopeId: String,
    val conversationId: String,
    val conversationSourceRevision: Long,
    val commandId: String,
    val commandRevision: Long,
    val lineageId: String,
    val branchAnchorMessageId: String,
    val branchAnchorMessageRevision: Long,
    val targetMessageId: String,
    val targetMessageRevision: Long,
)

private class RewardFeedbackConcurrentInvalidationException : IllegalStateException()

private fun resolveTarget(command: RewardFeedbackTargetAuthorityProjection): ResolvedRewardTarget? {
    if (
        command.completionKind != "GENERATION_FINAL_SAVED" ||
        command.state !in setOf("COMPLETED", "FAILED") ||
        command.stateVersion <= 0L
    ) return null
    val assistantId = command.assistantIdSnapshot ?: return null
    val scope = if (command.authoritySubjectId != null) {
        runCatching { LearningScope.AuthoritySubject(command.authoritySubjectId) }.getOrNull()
            ?: return null
    } else {
        runCatching { LearningScope.Assistant(Uuid.parse(assistantId)) }.getOrNull()
            ?: return null
    }
    val lineageId = command.lineageId ?: return null
    val branchAnchorMessageId = command.branchAnchorMessageId ?: return null
    val branchAnchorMessageRevision = command.branchAnchorMessageRevision ?: return null
    val conversationSourceRevision = command.conversationSourceRevision ?: return null
    val resultMessageId = command.resultAssistantMessageId ?: return null
    val resultMessageRevision = command.resultAssistantMessageRevision ?: return null
    return ResolvedRewardTarget(
        scopeKind = scope.kind.name,
        scopeId = scope.storageId,
        conversationId = command.conversationId,
        conversationSourceRevision = conversationSourceRevision,
        commandId = command.commandId,
        commandRevision = command.stateVersion,
        lineageId = lineageId,
        branchAnchorMessageId = branchAnchorMessageId,
        branchAnchorMessageRevision = branchAnchorMessageRevision,
        targetMessageId = resultMessageId,
        targetMessageRevision = resultMessageRevision,
    )
}

private fun canonicalFeedbackId(
    target: ResolvedRewardTarget,
    dimension: RewardDimension,
): String = "reward-feedback-v1:" + LearningCanonicalId.digest(
    domainVersion = "reward-feedback-v1",
    fields = listOf(
        target.scopeKind,
        target.scopeId,
        target.targetMessageId,
        target.targetMessageRevision.toString(),
        dimension.name,
    ),
)

private fun ResolvedRewardTarget.toEntity(
    feedbackId: String,
    meaning: RewardFeedbackMeaning,
    signalKind: RewardSignalKind,
    valueMilli: Int?,
    state: RewardFeedbackSourceState,
    sourceRevision: Long,
    previousSourceRevision: Long?,
    createdAtMs: Long,
    updatedAtMs: Long,
): RewardFeedbackAuthorityEntity {
    val integritySha256 = rewardFeedbackEvidenceDigest(
        feedbackId = feedbackId,
        sourceRevision = sourceRevision,
        dimension = meaning.dimension.name,
        signalKind = signalKind.name,
        valueMilli = valueMilli,
        sourceState = state.name,
        targetMessageId = targetMessageId,
        targetMessageRevision = targetMessageRevision,
    )
    return RewardFeedbackAuthorityEntity(
        feedbackId = feedbackId,
        scopeKind = scopeKind,
        scopeId = scopeId,
        conversationId = conversationId,
        conversationSourceRevision = conversationSourceRevision,
        commandId = commandId,
        commandRevision = commandRevision,
        lineageId = lineageId,
        branchAnchorMessageId = branchAnchorMessageId,
        branchAnchorMessageRevision = branchAnchorMessageRevision,
        targetAssistantMessageId = targetMessageId,
        targetAssistantMessageRevision = targetMessageRevision,
        dimension = meaning.dimension.name,
        signalKind = signalKind.name,
        valueMilli = valueMilli,
        sourceState = state.name,
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        integritySha256 = integritySha256,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}

private fun RewardFeedbackAuthorityEntity.toAuthorityEvent(): RewardFeedbackAuthorityEvent =
    RewardFeedbackAuthorityEvent(
        feedbackId = feedbackId,
        scopeKind = scopeKind,
        scopeId = scopeId,
        conversationId = conversationId,
        conversationSourceRevision = conversationSourceRevision,
        commandId = commandId,
        commandRevision = commandRevision,
        lineageId = lineageId,
        branchAnchorMessageId = branchAnchorMessageId,
        branchAnchorMessageRevision = branchAnchorMessageRevision,
        targetAssistantMessageId = targetAssistantMessageId,
        targetAssistantMessageRevision = targetAssistantMessageRevision,
        dimension = RewardDimension.valueOf(dimension),
        signalKind = RewardSignalKind.valueOf(signalKind),
        valueMilli = valueMilli,
        sourceState = RewardFeedbackSourceState.valueOf(sourceState),
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        occurredAtMs = updatedAtMs,
    )

private fun RewardFeedbackAuthorityEntity.toSourceTombstone(
    updatedAtMs: Long,
): RewardFeedbackAuthorityEntity {
    val nextRevision = sourceRevision + 1L
    val integrity = rewardFeedbackEvidenceDigest(
        feedbackId = feedbackId,
        sourceRevision = nextRevision,
        dimension = dimension,
        signalKind = signalKind,
        valueMilli = null,
        sourceState = RewardFeedbackSourceState.TOMBSTONED.name,
        targetMessageId = targetAssistantMessageId,
        targetMessageRevision = targetAssistantMessageRevision,
    )
    return copy(
        valueMilli = null,
        sourceState = RewardFeedbackSourceState.TOMBSTONED.name,
        sourceRevision = nextRevision,
        previousSourceRevision = sourceRevision,
        integritySha256 = integrity,
        updatedAtMs = updatedAtMs,
    )
}

private fun rewardFeedbackEvidenceDigest(
    feedbackId: String,
    sourceRevision: Long,
    dimension: String,
    signalKind: String,
    valueMilli: Int?,
    sourceState: String,
    targetMessageId: String,
    targetMessageRevision: Long,
): String = LearningCanonicalId.digest(
    domainVersion = "reward-feedback-integrity-v1",
    fields = listOf(
        feedbackId,
        sourceRevision.toString(),
        dimension,
        signalKind,
        valueMilli?.toString(),
        sourceState,
        targetMessageId,
        targetMessageRevision.toString(),
    ),
)

private fun String.isSafeRewardTargetId(): Boolean =
    length in 1..256 && all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == ':' || char == '@'
    }

private const val MAX_FEEDBACK_HEADS_PER_TARGET = 16
private const val MAX_COMMAND_CANDIDATES_PER_TARGET = 16

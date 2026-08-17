package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.data.authority.reward.RewardFeedbackAuthorityEvent
import me.rerere.rikkahub.data.authority.reward.RewardFeedbackAuthorityEventPort
import me.rerere.rikkahub.learning.model.DisabledLearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningScopeConsentSource
import me.rerere.rikkahub.learning.model.DisabledLearningScopeConsentSource
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import kotlin.uuid.Uuid

/** Projects explicit feedback into canonical schema-v3 while the authority transaction is held. */
class LearningRewardFeedbackAuthorityEventPort(
    private val appender: LearningOutboxAppender,
    private val featureFlags: LearningFeatureFlagSource = DisabledLearningFeatureFlagSource,
    private val scopeConsent: LearningScopeConsentSource = DisabledLearningScopeConsentSource,
    private val postCommitWake: () -> Unit = {},
) : RewardFeedbackAuthorityEventPort {
    override suspend fun appendInCurrentTransaction(event: RewardFeedbackAuthorityEvent): Boolean {
        val flags = featureFlags.current()
        if (!flags.isValid || !flags.effective.handoff) return false
        val scope = LearningScope.parseOrNull(event.scopeKind, event.scopeId) ?: return false
        // Consent gates creation only. Once a feedback source exists, every adjacent revision
        // (including an ACTIVE replacement and a TOMBSTONED retraction) is an invalidation fact
        // and must propagate even after capture consent is withdrawn.
        if (!shouldProjectRewardFeedbackAuthorityEvent(event, scopeConsent.captureAllowed(scope))) {
            return false
        }
        return appender.appendInCurrentAuthorityTransaction { streamId ->
            event.toLearningOutboxDraft(streamId)
        } is LearningOutboxAppendResult.Inserted
    }

    override fun dispatchPostCommit(insertedOutbox: Boolean) {
        if (insertedOutbox) runCatching(postCommitWake)
    }
}

internal fun shouldProjectRewardFeedbackAuthorityEvent(
    event: RewardFeedbackAuthorityEvent,
    captureAllowed: Boolean,
): Boolean = event.previousSourceRevision != null || captureAllowed

internal fun RewardFeedbackAuthorityEvent.toLearningOutboxDraft(
    streamId: Uuid,
): LearningOutboxDraft {
    val scope = requireNotNull(LearningScope.parseOrNull(scopeKind, scopeId))
    return LearningOutboxDraft(
        streamId = streamId,
        eventCode = LearningEventCode(
            rawCode = LearningEventType.USER_FEEDBACK_RECORDED.name,
            schemaVersion = REWARD_FEEDBACK_EVENT_SCHEMA_VERSION,
        ),
        source = LearningSourceRef(
            sourceKind = LearningSourceKind.USER_FEEDBACK,
            sourceId = feedbackId,
            sourceRevision = sourceRevision,
            missingRevisionReason = null,
            databaseStreamId = streamId,
            scope = scope,
            occurredAtMs = occurredAtMs,
        ),
        correlation = LearningCorrelation(
            previousSourceRevision = previousSourceRevision,
            sourceStateCode = sourceState.name,
            conversationId = conversationId,
            conversationSourceRevision = conversationSourceRevision,
            commandId = commandId,
            lineageId = lineageId,
            branchAnchorMessageId = branchAnchorMessageId,
            branchAnchorMessageRevision = branchAnchorMessageRevision,
            messageId = targetAssistantMessageId,
            messageRevision = targetAssistantMessageRevision,
        ),
        terminalStateCode = null,
        rewardDimensionCode = dimension.name,
        rewardSignalKindCode = signalKind.name,
        rewardValueMilli = valueMilli,
        executionVerificationStateCode = null,
        createdAtMs = occurredAtMs,
    )
}

private const val REWARD_FEEDBACK_EVENT_SCHEMA_VERSION = 3

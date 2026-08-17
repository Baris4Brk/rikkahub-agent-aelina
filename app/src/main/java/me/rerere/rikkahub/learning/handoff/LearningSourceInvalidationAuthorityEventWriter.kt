package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import me.rerere.rikkahub.data.authority.source.SourceAuthorityObjectKind
import me.rerere.rikkahub.data.authority.source.SourceInvalidationAuthorityEvent
import me.rerere.rikkahub.data.authority.source.SourceInvalidationAuthorityEventPort
import me.rerere.rikkahub.learning.model.DisabledLearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import kotlin.uuid.Uuid

/**
 * Projects a monotonic main-database source transition into the content-free Learning outbox.
 * It is invoked only by ConversationSourceAuthorityWriter while the owning Room transaction holds.
 */
class LearningSourceInvalidationAuthorityEventPort(
    private val appender: LearningOutboxAppender,
    private val featureFlags: LearningFeatureFlagSource = DisabledLearningFeatureFlagSource,
    private val postCommitWake: () -> Unit = {},
) : SourceInvalidationAuthorityEventPort {
    override suspend fun appendInCurrentTransaction(event: SourceInvalidationAuthorityEvent): Boolean {
        val flags = featureFlags.current()
        if (!flags.isValid || !flags.effective.handoff) return false
        // Every value reaching this port is an adjacent, non-CREATED authority transition.
        // Consent may prevent capturing a new source, but it must never suppress invalidation of
        // an already captured revision. In particular UPDATED keeps the new head ACTIVE while
        // superseding the old revision, so filtering ACTIVE here would resurrect stale evidence.
        check(shouldProjectSourceInvalidationAuthorityTransition(event))
        val result = appender.appendInCurrentAuthorityTransaction { streamId ->
            event.toLearningOutboxDraft(streamId)
        }
        return result is LearningOutboxAppendResult.Inserted
    }

    override fun dispatchPostCommit(insertedOutbox: Boolean) {
        if (insertedOutbox) runCatching(postCommitWake)
    }
}

internal fun shouldProjectSourceInvalidationAuthorityTransition(
    event: SourceInvalidationAuthorityEvent,
): Boolean = event.changeKind !=
    me.rerere.rikkahub.data.authority.source.ConversationSourceChangeKind.CREATED

internal fun SourceInvalidationAuthorityEvent.toLearningOutboxDraft(
    streamId: Uuid,
): LearningOutboxDraft {
    val learningScope = when (scope.kind) {
        ConversationSourceScopeKind.ASSISTANT -> LearningScope.Assistant(Uuid.parse(scope.id))
        ConversationSourceScopeKind.AUTHORITY_SUBJECT -> LearningScope.AuthoritySubject(scope.id)
    }
    return LearningOutboxDraft(
        streamId = streamId,
        eventCode = LearningEventCode(
            rawCode = LearningEventType.SOURCE_INVALIDATED.name,
            schemaVersion = SOURCE_INVALIDATED_SCHEMA_VERSION,
        ),
        source = LearningSourceRef(
            sourceKind = when (objectKind) {
                SourceAuthorityObjectKind.CONVERSATION -> LearningSourceKind.CONVERSATION
                SourceAuthorityObjectKind.MESSAGE -> LearningSourceKind.CONVERSATION_MESSAGE
            },
            sourceId = sourceId,
            sourceRevision = sourceRevision,
            missingRevisionReason = null,
            databaseStreamId = streamId,
            scope = learningScope,
            occurredAtMs = occurredAtMs,
        ),
        correlation = LearningCorrelation(
            previousSourceRevision = previousSourceRevision,
            sourceStateCode = sourceState.name,
            conversationId = conversationId,
            conversationSourceRevision = conversationSourceRevision,
            messageId = sourceId.takeIf { objectKind == SourceAuthorityObjectKind.MESSAGE },
            messageRevision = sourceRevision.takeIf {
                objectKind == SourceAuthorityObjectKind.MESSAGE
            },
        ),
        terminalStateCode = null,
        createdAtMs = occurredAtMs,
    )
}

private const val SOURCE_INVALIDATED_SCHEMA_VERSION = 2

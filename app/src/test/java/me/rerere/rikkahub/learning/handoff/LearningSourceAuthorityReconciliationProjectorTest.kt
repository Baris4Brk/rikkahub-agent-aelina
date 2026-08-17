package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.data.authority.source.ConversationSourceChangeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceState
import me.rerere.rikkahub.data.authority.source.SourceAuthorityObjectKind
import me.rerere.rikkahub.data.authority.source.SourceInvalidationAuthorityEvent
import me.rerere.rikkahub.data.db.projection.LearningConversationSourceAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningMessageSourceAuthorityProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class LearningSourceAuthorityReconciliationProjectorTest {
    @Test
    fun `conversation head projection is byte-for-byte canonical with direct writer`() {
        val row = LearningConversationSourceAuthorityProjection(
            scopeKind = "ASSISTANT",
            scopeId = ASSISTANT_ID,
            conversationId = CONVERSATION_ID,
            sourceRevision = 3L,
            previousSourceRevision = 2L,
            sourceState = "ACTIVE",
            changeKind = "UPDATED",
            occurredAtMs = 30L,
            updatedAtMs = 30L,
        )
        val expected = SourceInvalidationAuthorityEvent(
            scope = SCOPE,
            conversationId = CONVERSATION_ID,
            objectKind = SourceAuthorityObjectKind.CONVERSATION,
            sourceId = CONVERSATION_ID,
            sourceRevision = 3L,
            previousSourceRevision = 2L,
            conversationSourceRevision = 3L,
            sourceState = ConversationSourceState.ACTIVE,
            changeKind = ConversationSourceChangeKind.UPDATED,
            occurredAtMs = 30L,
        ).toLearningOutboxDraft(STREAM)

        val actual = projectConversationSourceInvalidationDraft(row, STREAM)

        assertEquals(expected, actual)
        assertEquals(expected.toEntity().eventId, actual?.toEntity()?.eventId)
    }

    @Test
    fun `message head projection binds current conversation authority and direct canonical`() {
        val row = LearningMessageSourceAuthorityProjection(
            scopeKind = "ASSISTANT",
            scopeId = ASSISTANT_ID,
            conversationId = CONVERSATION_ID,
            messageId = MESSAGE_ID,
            sourceRevision = 5L,
            previousSourceRevision = 4L,
            sourceState = "TOMBSTONED",
            changeKind = "DELETED",
            conversationSourceRevision = 9L,
            occurredAtMs = 50L,
            updatedAtMs = 50L,
        )
        val expected = SourceInvalidationAuthorityEvent(
            scope = SCOPE,
            conversationId = CONVERSATION_ID,
            objectKind = SourceAuthorityObjectKind.MESSAGE,
            sourceId = MESSAGE_ID,
            sourceRevision = 5L,
            previousSourceRevision = 4L,
            conversationSourceRevision = 9L,
            sourceState = ConversationSourceState.TOMBSTONED,
            changeKind = ConversationSourceChangeKind.DELETED,
            occurredAtMs = 50L,
        ).toLearningOutboxDraft(STREAM)

        val actual = projectMessageSourceInvalidationDraft(row, STREAM)

        assertEquals(expected, actual)
        assertEquals(expected.toEntity().eventId, actual?.toEntity()?.eventId)
    }

    @Test
    fun `revision one non-active head cannot invent a previous revision`() {
        val row = LearningConversationSourceAuthorityProjection(
            scopeKind = "ASSISTANT",
            scopeId = ASSISTANT_ID,
            conversationId = CONVERSATION_ID,
            sourceRevision = 1L,
            previousSourceRevision = null,
            sourceState = "TOMBSTONED",
            changeKind = "CONVERSATION_DELETED",
            occurredAtMs = 10L,
            updatedAtMs = 10L,
        )

        assertNull(projectConversationSourceInvalidationDraft(row, STREAM))
    }

    private companion object {
        const val ASSISTANT_ID = "00000000-0000-0000-0000-00000000000a"
        const val CONVERSATION_ID = "conversation-source-reconcile"
        const val MESSAGE_ID = "message-source-reconcile"
        val STREAM: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000051")
        val SCOPE = ConversationSourceScope(
            ConversationSourceScopeKind.ASSISTANT,
            ASSISTANT_ID,
        )
    }
}

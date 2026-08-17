package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.data.authority.source.ConversationSourceChangeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceState
import me.rerere.rikkahub.data.authority.source.SourceAuthorityObjectKind
import me.rerere.rikkahub.data.authority.source.SourceInvalidationAuthorityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearningSourceInvalidationAuthorityEventWriterTest {
    @Test
    fun `message transition projects exact adjacent and conversation revisions without content`() {
        val row = event(SourceAuthorityObjectKind.MESSAGE, MESSAGE_ID)
            .toLearningOutboxDraft(STREAM_ID)
            .toEntity()

        assertEquals("SOURCE_INVALIDATED", row.eventType)
        assertEquals(2, row.eventSchemaVersion)
        assertEquals("CONVERSATION_MESSAGE", row.sourceType)
        assertEquals(MESSAGE_ID, row.sourceId)
        assertEquals(8L, row.sourceRevision)
        assertEquals(7L, row.previousSourceRevision)
        assertEquals("SUPERSEDED", row.sourceState)
        assertEquals(CONVERSATION_ID, row.conversationId)
        assertEquals(11L, row.conversationSourceRevision)
        assertEquals(MESSAGE_ID, row.messageId)
        assertEquals(8L, row.messageRevision)
        assertNull(row.terminalState)
    }

    @Test
    fun `conversation transition has no fabricated message pair`() {
        val row = event(SourceAuthorityObjectKind.CONVERSATION, CONVERSATION_ID)
            .toLearningOutboxDraft(STREAM_ID)
            .toEntity()

        assertEquals(CONVERSATION_ID, row.sourceId)
        assertEquals(11L, row.conversationSourceRevision)
        assertNull(row.messageId)
        assertNull(row.messageRevision)
    }

    @Test
    fun `active edit remains an invalidation after capture consent is withdrawn`() {
        val activeEdit = event(SourceAuthorityObjectKind.MESSAGE, MESSAGE_ID).copy(
            sourceState = ConversationSourceState.ACTIVE,
            changeKind = ConversationSourceChangeKind.UPDATED,
        )

        // SourceInvalidationAuthorityEvent can never represent initial capture. Every instance
        // is an adjacent transition, so per-scope capture consent must not suppress it.
        assertTrue(shouldProjectSourceInvalidationAuthorityTransition(activeEdit))
    }

    private fun event(
        kind: SourceAuthorityObjectKind,
        sourceId: String,
    ) = SourceInvalidationAuthorityEvent(
        scope = ConversationSourceScope(
            ConversationSourceScopeKind.ASSISTANT,
            ASSISTANT_ID,
        ),
        conversationId = CONVERSATION_ID,
        objectKind = kind,
        sourceId = sourceId,
        sourceRevision = 8L,
        previousSourceRevision = 7L,
        conversationSourceRevision = 11L,
        sourceState = ConversationSourceState.SUPERSEDED,
        changeKind = ConversationSourceChangeKind.BRANCH_SUPERSEDED,
        occurredAtMs = 100L,
    )

    private companion object {
        val STREAM_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
        const val ASSISTANT_ID = "00000000-0000-0000-0000-000000000002"
        const val CONVERSATION_ID = "00000000-0000-0000-0000-000000000003"
        const val MESSAGE_ID = "00000000-0000-0000-0000-000000000004"
    }
}

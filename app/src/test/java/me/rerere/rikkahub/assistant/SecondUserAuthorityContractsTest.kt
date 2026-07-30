package me.rerere.rikkahub.assistant

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SecondUserAuthorityContractsTest {
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()

    @After
    fun clearRegistry() {
        SecondUserAuthorityRegistry.install(null)
    }

    @Test
    fun `canonical subject binds assistant conversation and epoch`() {
        assertEquals(
            "local-second-user:v1:$assistantId:$conversationId:41",
            SecondUserAdmissionSnapshot.subjectId(assistantId, conversationId, 41L),
        )
    }

    @Test
    fun `registry only recognizes current epoch on trusted local origins`() {
        val active = SecondUserAdmissionSnapshot.create(
            assistantId = assistantId,
            conversationId = conversationId,
            authorityEpoch = 4L,
            origin = ToolCallOrigin.LocalChat,
        )
        SecondUserAuthorityRegistry.install(active)

        assertTrue(
            SecondUserAuthorityRegistry.matches(
                active.subjectId,
                conversationId,
                ToolCallOrigin.LocalChat,
            ),
        )
        assertFalse(
            SecondUserAuthorityRegistry.matches(
                active.subjectId,
                conversationId,
                ToolCallOrigin.Telegram,
            ),
        )
        assertFalse(
            SecondUserAuthorityRegistry.matches(
                SecondUserAdmissionSnapshot.subjectId(assistantId, conversationId, 3L),
                conversationId,
                ToolCallOrigin.LocalChat,
            ),
        )
        assertFalse(
            SecondUserAuthorityRegistry.matches(
                active.subjectId,
                Uuid.random(),
                ToolCallOrigin.LocalChat,
            ),
        )
    }

    @Test
    fun `incomplete config fails closed while repair marker remains pending`() {
        val malformedActive = SecondUserAuthorityConfig(
            assistantId = assistantId,
            authorityEpoch = 1L,
            state = SecondUserAuthorityState.ACTIVE,
        ).normalized()
        assertEquals(SecondUserAuthorityState.UNCONFIGURED, malformedActive.state)
        assertNull(malformedActive.assistantId)
        assertNull(malformedActive.conversationId)

        val repair = SecondUserAuthorityConfig(
            state = SecondUserAuthorityState.PENDING_CONFIRMATION,
            auditId = "legacy-multiple-candidates",
        ).normalized()
        assertEquals(SecondUserAuthorityState.PENDING_CONFIRMATION, repair.state)
        assertEquals("legacy-multiple-candidates", repair.auditId)
    }
}

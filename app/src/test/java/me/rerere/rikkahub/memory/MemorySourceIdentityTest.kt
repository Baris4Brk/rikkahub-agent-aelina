package me.rerere.rikkahub.memory

import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySourceIdentityTest {
    private val assistantId = "00000000-0000-0000-0000-000000000001"

    @Test
    fun `scope binding permits own assistant and global but rejects another assistant`() {
        assertTrue(isValidMemoryScopeBinding(assistantId, assistantId))
        assertTrue(isValidMemoryScopeBinding(MemoryRepository.GLOBAL_MEMORY_ID, assistantId))
        assertFalse(
            isValidMemoryScopeBinding(
                "00000000-0000-0000-0000-000000000002",
                assistantId,
            ),
        )
        assertFalse(isValidMemoryScopeBinding("not-a-scope", assistantId))
    }

    @Test
    fun `one capture keeps user and assistant as content-bound members of one group`() {
        val identities = requireNotNull(
            buildMemorySourceIdentities(
                captureId = "capture-1",
                conversationId = "conversation-1",
                sources = listOf(
                    MemoryCaptureSourceInput("user-1", MemorySourceRole.USER, "question"),
                    MemoryCaptureSourceInput("assistant-1", MemorySourceRole.ASSISTANT, "answer"),
                ),
            ),
        )

        assertEquals(listOf("user-1", "assistant-1"), identities.map { it.messageId })
        assertEquals(setOf("capture-1"), identities.map { it.evidenceGroupId }.toSet())
        assertEquals(setOf(MemorySourceKind.TEXT), identities.map { it.sourceKind }.toSet())
        assertNotEquals(identities[0].consumedTextDigest, identities[1].consumedTextDigest)
    }

    @Test
    fun `same message id with changed text has a different source identity`() {
        assertNotEquals(memorySourceTextDigest("before"), memorySourceTextDigest("after"))
    }

    @Test
    fun `blank-only and oversized source bundles fail closed`() {
        assertNull(
            buildMemorySourceIdentities(
                captureId = "capture",
                conversationId = "conversation",
                sources = listOf(
                    MemoryCaptureSourceInput("message", MemorySourceRole.USER, "  "),
                ),
            ),
        )
        assertNull(
            buildMemorySourceIdentities(
                captureId = "capture",
                conversationId = "conversation",
                sources = (0..MAX_MEMORY_CAPTURE_SOURCE_IDENTITIES).map { index ->
                    MemoryCaptureSourceInput(
                        messageId = "message-$index",
                        role = MemorySourceRole.USER,
                        text = "text-$index",
                    )
                },
            ),
        )
    }
}

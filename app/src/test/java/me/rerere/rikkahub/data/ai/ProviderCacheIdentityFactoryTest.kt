package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCacheIdentityFactoryTest {
    @Test
    fun `identity is deterministic and memory order independent`() {
        val first = identity(memoryIds = listOf(8, 3, 8))
        val second = identity(memoryIds = listOf(3, 8))

        assertEquals(first, second)
    }

    @Test
    fun `conversation assistant scope memory and compiler revision isolate caches`() {
        val baseline = identity()

        assertNotEquals(baseline, identity(conversationId = "conversation-b"))
        assertNotEquals(baseline, identity(assistantId = "assistant-b"))
        assertNotEquals(baseline, identity(scopeId = "__global__"))
        assertNotEquals(baseline, identity(memoryIds = listOf(3, 9)))
        assertNotEquals(baseline, identity(compilerRevision = "memory-prompt-v2"))
    }

    @Test
    fun `missing conversation disables stable warm namespace`() {
        assertNull(
            buildProviderCacheIdentity(
                conversationId = null,
                assistantId = "assistant-secret",
                memoryScopeId = "scope-secret",
                actualMemoryIds = listOf(3, 8),
                compilerRevision = "memory-prompt-v1",
            ),
        )
    }

    @Test
    fun `redacted representation never contains raw identities`() {
        val identity = identity().toString()

        assertFalse(identity.contains("conversation-a"))
        assertFalse(identity.contains("assistant-a"))
        assertFalse(identity.contains("scope-a"))
    }

    private fun identity(
        conversationId: String = "conversation-a",
        assistantId: String = "assistant-a",
        scopeId: String = "scope-a",
        memoryIds: List<Int> = listOf(3, 8),
        compilerRevision: String = "memory-prompt-v1",
    ) = requireNotNull(
        buildProviderCacheIdentity(
            conversationId = conversationId,
            assistantId = assistantId,
            memoryScopeId = scopeId,
            actualMemoryIds = memoryIds,
            compilerRevision = compilerRevision,
        ),
    )
}

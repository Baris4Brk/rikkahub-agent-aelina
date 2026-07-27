package me.rerere.rikkahub.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTokenProviderTest {
    private val provider = ExecutionTokenProvider { "a".repeat(64) }

    @Test
    fun `owner token is 128 bit and binds the full owner tuple`() {
        val local = provider.ownerTokenFor(
            domain = "termux_owner",
            assistantId = "assistant",
            conversationId = "conversation",
            origin = "SystemAssistant",
        )
        val remote = provider.ownerTokenFor(
            domain = "termux_owner",
            assistantId = "assistant",
            conversationId = "conversation",
            origin = "Telegram",
        )

        assertEquals(32, local.length)
        assertTrue(local.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(local, remote)
    }
}

package me.rerere.rikkahub.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryNarrativeIdentityTest {
    @Test
    fun `configured names replace protocol roles in readable memory output`() {
        val identity = resolveMemoryNarrativeIdentity(
            configuredSelfName = "角色甲",
            configuredCompanionName = "",
            assistantName = "角色乙",
        )

        assertEquals("角色甲", identity.selfName)
        assertEquals("角色乙", identity.companionName)
    }

    @Test
    fun `blank configuration falls back without exposing protocol role markers`() {
        val identity = resolveMemoryNarrativeIdentity(
            configuredSelfName = "",
            configuredCompanionName = "",
            assistantName = "",
        )

        assertEquals("你", identity.selfName)
        assertEquals("对话对象", identity.companionName)
    }
}

package me.rerere.rikkahub.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryNarrativeIdentityTest {
    @Test
    fun `configured names replace protocol roles in readable memory output`() {
        val identity = resolveMemoryNarrativeIdentity(
            configuredSelfName = "啥子七",
            configuredCompanionName = "",
            assistantName = "斯啾伊",
        )

        assertEquals("啥子七", identity.selfName)
        assertEquals("斯啾伊", identity.companionName)
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

package me.rerere.ai.provider.providers

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AICoreSystemPrefixTest {
    @Test
    fun completeGatedSystemProjectionIsPreservedInPromptPrefix() {
        val prefix = buildAiCoreSystemPrefix(
            messages = listOf(
                UIMessage.system("stable safety instructions"),
                UIMessage.system("runtime memory trust boundary"),
                UIMessage.user("private user payload"),
            ),
            tools = emptyList(),
        )

        assertTrue(prefix.contains("stable safety instructions"))
        assertTrue(prefix.contains("runtime memory trust boundary"))
        assertFalse(prefix.contains("private user payload"))
    }
}

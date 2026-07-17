package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchPolicyTest {
    @Test
    fun `enabled assistant receives search on every authorized non-keyguard origin`() {
        val assistant = Assistant(enableWebSearch = true)

        InvocationSurfacePolicy.ALL_NON_KEYGUARD.forEach { origin ->
            assertTrue(
                "expected search for $origin",
                WebSearchPolicy.canInject(
                    assistant = assistant,
                    origin = origin,
                    toolSurfaceAvailable = true,
                ),
            )
        }
    }

    @Test
    fun `disabled assistant and keyguard never receive search tools`() {
        InvocationSurfacePolicy.ALL_NON_KEYGUARD.forEach { origin ->
            assertFalse(
                WebSearchPolicy.canInject(
                    assistant = Assistant(enableWebSearch = false),
                    origin = origin,
                    toolSurfaceAvailable = true,
                ),
            )
        }
        assertFalse(
            WebSearchPolicy.canInject(
                assistant = Assistant(enableWebSearch = true),
                origin = ToolCallOrigin.SystemAssistantKeyguard,
                toolSurfaceAvailable = true,
            ),
        )
    }

    @Test
    fun `system assistant requires its current visible authorized surface`() {
        assertFalse(
            WebSearchPolicy.canInject(
                assistant = Assistant(enableWebSearch = true),
                origin = ToolCallOrigin.SystemAssistant,
                toolSurfaceAvailable = false,
            ),
        )
    }
}

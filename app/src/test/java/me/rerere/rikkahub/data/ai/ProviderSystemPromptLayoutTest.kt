package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSystemPromptLayoutTest {
    @Test
    fun `chat completions anchor volatile context inside the current user turn`() {
        val earlierUser = UIMessage.user("earlier request")
        val earlierAnswer = UIMessage.assistant("earlier answer")
        val currentUser = UIMessage.user("current request")
        val layout = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "device snapshot for this request only",
            conversationMessages = listOf(earlierUser, earlierAnswer, currentUser),
            useAnchoredVolatileContext = true,
        )

        val providerMessages = layout.applyVolatileContext(layout.initialMessages)
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            providerMessages.map { it.role },
        )
        assertTrue(providerMessages.first().toText().contains("stable instructions"))
        assertTrue(providerMessages.first().toText().contains("provider_runtime_context"))
        assertTrue(providerMessages.last().toText().startsWith("current request"))
        assertTrue(providerMessages.last().toText().contains("device snapshot for this request only"))
        assertFalse(providerMessages.drop(1).any { it.role == MessageRole.SYSTEM })
    }

    @Test
    fun `same task keeps exact prefix when tool results are appended`() {
        val earlierUser = UIMessage.user("large persisted history start")
        val currentUser = UIMessage.user("current request")
        val firstRequest = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "frozen runtime snapshot",
            conversationMessages = listOf(earlierUser, currentUser),
            useAnchoredVolatileContext = true,
        ).let { layout -> layout.applyVolatileContext(layout.initialMessages) }

        val secondRequest = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "frozen runtime snapshot",
            conversationMessages = listOf(
                earlierUser,
                currentUser,
                UIMessage.assistant("tool call and result"),
            ),
            useAnchoredVolatileContext = true,
        ).let { layout -> layout.applyVolatileContext(layout.initialMessages) }

        assertEquals(
            firstRequest.map { it.role to it.toText() },
            secondRequest.take(firstRequest.size).map { it.role to it.toText() },
        )
    }

    @Test
    fun `next task preserves the long history before the previous current turn`() {
        val oldUser = UIMessage.user("large persisted history start")
        val oldAnswer = UIMessage.assistant("large persisted history continuation")
        val previousUser = UIMessage.user("previous current request")
        val firstRequest = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "runtime snapshot one",
            conversationMessages = listOf(oldUser, oldAnswer, previousUser),
            useAnchoredVolatileContext = true,
        ).let { layout -> layout.applyVolatileContext(layout.initialMessages) }

        val secondRequest = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "runtime snapshot two",
            conversationMessages = listOf(
                oldUser,
                oldAnswer,
                previousUser,
                UIMessage.assistant("previous answer"),
                UIMessage.user("new request"),
            ),
            useAnchoredVolatileContext = true,
        ).let { layout -> layout.applyVolatileContext(layout.initialMessages) }

        // The prior current-user turn deliberately differs because its provider-only runtime
        // suffix was never persisted. Everything before it -- the potentially huge history --
        // remains an exact prefix across independent tasks.
        assertEquals(
            firstRequest.take(3).map { it.role to it.toText() },
            secondRequest.take(3).map { it.role to it.toText() },
        )
        assertTrue(firstRequest[3].toText().contains("runtime snapshot one"))
        assertEquals("previous current request", secondRequest[3].toText())
        assertTrue(secondRequest.last().toText().contains("runtime snapshot two"))
    }

    @Test
    fun `providers that only read first system retain combined system message`() {
        val layout = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "runtime context",
            conversationMessages = listOf(UIMessage.user("question")),
            useAnchoredVolatileContext = false,
        )

        assertEquals(2, layout.initialMessages.size)
        val systemParts = layout.initialMessages.first().parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("stable instructions", "runtime context"), systemParts.map { it.text })
        assertEquals(layout.initialMessages, layout.applyVolatileContext(layout.initialMessages))
    }

    @Test
    fun `blank volatile context does not alter the user message`() {
        val layout = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "",
            conversationMessages = listOf(UIMessage.user("question")),
            useAnchoredVolatileContext = true,
        )

        val providerMessages = layout.applyVolatileContext(layout.initialMessages)
        assertEquals("question", providerMessages.last().toText())
    }

    @Test
    fun `runtime context falls back to a provider-only user turn when none exists`() {
        val layout = ProviderSystemPromptLayout.create(
            stableSystem = "stable instructions",
            volatileSystem = "runtime context",
            conversationMessages = listOf(UIMessage.assistant("continuation")),
            useAnchoredVolatileContext = true,
        )

        val providerMessages = layout.applyVolatileContext(layout.initialMessages)
        assertEquals(MessageRole.USER, providerMessages.last().role)
        assertTrue(providerMessages.last().toText().contains("runtime context"))
    }
}

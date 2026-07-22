package me.rerere.rikkahub.data.ai

import me.rerere.ai.context.ContextTokenEstimator
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationProviderContextPreparerTest {
    @Test
    fun `ordinary chat keeps 600k history when stale metadata reports 100k`() {
        val oldUser = UIMessage.user("earlier request")
        val oldAssistant = UIMessage.assistant("earlier answer")
        val currentUser = UIMessage.user("continue")
        val messages = listOf(oldUser, oldAssistant, currentUser)
        val tokenCounts = mapOf(
            oldUser to 300_000,
            oldAssistant to 299_000,
            currentUser to 1_000,
        )

        val prepared = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { message -> tokenCounts.getValue(message) },
        ).prepareOrdinaryChat(
            messages = messages,
            configuredContextWindowTokens = 1_000_000,
            advertisedContextWindowTokens = 100_000,
        )

        assertEquals(messages, prepared.messages)
        assertEquals(600_000, prepared.estimatedRequestTokens)
        assertEquals(1_000_000, prepared.configuredContextWindowTokens)
        assertFalse(prepared.summaryUsed)
        assertNull(prepared.enforcedWindowTokens)
    }

    @Test
    fun `invalid configured window falls back to one million without pruning history`() {
        val messages = listOf(UIMessage.user("keep manual context"))

        val prepared = GenerationProviderContextPreparer(
            tokenEstimator = ContextTokenEstimator { 600_000 },
        ).prepareOrdinaryChat(
            messages = messages,
            configuredContextWindowTokens = 0,
            advertisedContextWindowTokens = 100_000,
        )

        assertEquals(messages, prepared.messages)
        assertEquals(1_000_000, prepared.configuredContextWindowTokens)
        assertFalse(prepared.summaryUsed)
        assertNull(prepared.enforcedWindowTokens)
    }
}

package me.rerere.rikkahub.service

import me.rerere.ai.context.ContextTokenEstimator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompressionPolicyTest {
    @Test
    fun `implicit disabled Auto compression model follows enabled conversation model`() {
        val staleDefault = Model(modelId = "auto", displayName = "Auto")
        val activeModel = Model(modelId = "deepseek-v4-flash", displayName = "DeepSeek V4 Flash")
        val disabledAutoProvider = ProviderSetting.OpenAI(enabled = false, models = listOf(staleDefault))
        val activeProvider = ProviderSetting.OpenAI(enabled = true, models = listOf(activeModel))

        val binding = resolveCompressionModelBinding(
            configuredModel = staleDefault,
            configuredProvider = disabledAutoProvider,
            configuredModelIsImplicitDefault = true,
            conversationModel = activeModel,
            conversationProvider = activeProvider,
        )

        assertSame(activeModel, binding.model)
        assertSame(activeProvider, binding.provider)
    }

    @Test
    fun `explicitly disabled compression provider remains a clear error`() {
        val model = Model(modelId = "chosen-model", displayName = "Chosen model")
        val disabledProvider = ProviderSetting.OpenAI(enabled = false, models = listOf(model))

        val error = runCatching {
            resolveCompressionModelBinding(
                configuredModel = model,
                configuredProvider = disabledProvider,
                configuredModelIsImplicitDefault = false,
                conversationModel = model,
                conversationProvider = ProviderSetting.OpenAI(enabled = true, models = listOf(model)),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("disabled"))
    }

    @Test
    fun `one million token manual context does not inherit a 100k or 128k chunk cap`() {
        val messages = List(6) { index -> UIMessage.user("message-$index") }
        val chunks = splitManualCompressionMessages(
            messages = messages,
            contextWindowTokens = 1_000_000,
            targetTokens = 2_000,
            tokenEstimator = ContextTokenEstimator { 90_000 },
        )

        assertEquals(1, chunks.size)
        assertEquals(messages, chunks.single())
    }

    @Test
    fun `manual compression only splits when the selected window requires it`() {
        val messages = List(2) { index -> UIMessage.user("message-$index") }
        val chunks = splitManualCompressionMessages(
            messages = messages,
            contextWindowTokens = 1_000_000,
            targetTokens = 2_000,
            tokenEstimator = ContextTokenEstimator { 600_000 },
        )

        assertEquals(2, chunks.size)
        assertEquals(listOf(messages[0]), chunks[0])
        assertEquals(listOf(messages[1]), chunks[1])
    }

    @Test
    fun `recommended keep count leaves something to compress`() {
        assertEquals(0, recommendedManualCompressionKeepRecentMessages(0))
        assertEquals(0, recommendedManualCompressionKeepRecentMessages(1))
        assertEquals(5, recommendedManualCompressionKeepRecentMessages(6))
        assertEquals(8, recommendedManualCompressionKeepRecentMessages(100))
    }

    @Test
    fun `manual compression keeps requested tail in addition to marked summaries`() {
        val kept = List(512) { index -> UIMessage.user("kept-$index") }
        val result = buildManualCompressionMessages(
            compressedSummaries = List(10) { index -> "summary-$index" },
            messagesToKeep = kept,
        )

        assertEquals(522, result.size)
        assertEquals(kept, result.takeLast(512))
        result.take(10).forEachIndexed { index, message ->
            val marker = message.annotations.single() as UIMessageAnnotation.ManualCompressionSummary
            assertEquals(index, marker.batchIndex)
            assertEquals(10, marker.batchCount)
        }
    }
}

package me.rerere.rikkahub.data.ai

import me.rerere.ai.context.ApproximateContextTokenEstimator
import me.rerere.ai.context.ContextTokenEstimator
import me.rerere.ai.provider.DEFAULT_USER_CONTEXT_WINDOW_TOKENS
import me.rerere.ai.ui.UIMessage

internal data class GenerationProviderContextPreparation(
    val messages: List<UIMessage>,
    val estimatedRequestTokens: Int,
    val configuredContextWindowTokens: Int,
    val advertisedContextWindowTokens: Int?,
    val enforcedWindowTokens: Int?,
    val summaryUsed: Boolean,
)

/**
 * Context policy at the [GenerationHandler] provider seam.
 *
 * Ordinary chats preserve the context selected by the user, including the existing manual
 * message limit and manual conversation-compression flows. Both the user's configured window and
 * provider metadata are diagnostic only; neither authorizes automatic compression.
 */
internal class GenerationProviderContextPreparer(
    private val tokenEstimator: ContextTokenEstimator = ApproximateContextTokenEstimator,
) {
    fun prepareOrdinaryChat(
        messages: List<UIMessage>,
        configuredContextWindowTokens: Int = DEFAULT_USER_CONTEXT_WINDOW_TOKENS,
        advertisedContextWindowTokens: Int?,
    ): GenerationProviderContextPreparation = GenerationProviderContextPreparation(
        messages = messages,
        estimatedRequestTokens = messages.sumOf { message ->
            tokenEstimator.estimate(message).coerceAtLeast(0)
        },
        configuredContextWindowTokens = configuredContextWindowTokens
            .takeIf { it > 0 }
            ?: DEFAULT_USER_CONTEXT_WINDOW_TOKENS,
        advertisedContextWindowTokens = advertisedContextWindowTokens?.takeIf { it > 0 },
        enforcedWindowTokens = null,
        summaryUsed = false,
    )
}

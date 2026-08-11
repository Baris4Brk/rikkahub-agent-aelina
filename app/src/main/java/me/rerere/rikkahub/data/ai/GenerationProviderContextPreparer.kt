package me.rerere.rikkahub.data.ai

import kotlin.math.min
import me.rerere.ai.context.ABSOLUTE_CONTEXT_WINDOW_TOKENS
import me.rerere.ai.context.ApproximateContextTokenEstimator
import me.rerere.ai.context.ContextTokenEstimator
import me.rerere.ai.context.MAX_ATOMIC_MEMORY_PROMPT_TOKENS
import me.rerere.ai.context.ProviderContextGateTrace
import me.rerere.ai.context.ProviderContextWindowResolver
import me.rerere.ai.context.ProviderRequestContextGate
import me.rerere.ai.context.ProviderRequestTokenEstimator
import me.rerere.ai.context.ResolvedContextWindow
import me.rerere.ai.context.conservativeContextSafetyMargin
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.DEFAULT_USER_CONTEXT_WINDOW_TOKENS
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage

internal data class GenerationProviderContextPreparation(
    val messages: List<UIMessage>,
    /** Final message + media + tool-schema estimate after provider-only pruning. */
    val estimatedRequestTokens: Int,
    val configuredContextWindowTokens: Int,
    val advertisedContextWindowTokens: Int?,
    val enforcedWindowTokens: Int,
    val effectiveMaxOutputTokens: Int,
    val summaryUsed: Boolean,
    val trace: ProviderContextGateTrace,
)

/**
 * Raised at the provider seam when fitting the request would require an implicit semantic change.
 *
 * The lower-level gate still exposes its projection for diagnostics and focused policy tests, but
 * ordinary generation must never silently remove history/reasoning or shorten the requested model
 * output. The user can then explicitly compress history or adjust the configured budget.
 */
internal class ProviderContextRequiresExplicitAdjustmentException(
    stage: String,
    trace: ProviderContextGateTrace,
) : IllegalStateException(
    "CONTEXT_REQUIRES_EXPLICIT_ADJUSTMENT: stage=$stage " +
        "stripped_reasoning=${trace.strippedHistoricalReasoningParts} " +
        "dropped_turns=${trace.droppedCompletedTurns} " +
        "dropped_messages=${trace.droppedMessages} " +
        "output_clamped=${trace.outputClamped} " +
        "estimated_messages=${trace.originalMessageTokens} " +
        "window=${trace.contextWindowTokens}",
)

internal fun GenerationProviderContextPreparation.requireLosslessProviderContext(
    stage: String,
): GenerationProviderContextPreparation {
    val wouldChangeSemantics = trace.strippedHistoricalReasoningParts > 0 ||
        trace.droppedCompletedTurns > 0 ||
        trace.droppedMessages > 0 ||
        trace.outputClamped
    if (wouldChangeSemantics) {
        throw ProviderContextRequiresExplicitAdjustmentException(stage, trace)
    }
    return this
}

/**
 * Context policy at the [GenerationHandler] provider seam.
 *
 * Provider catalog metadata is advisory. Enforcement uses the user's policy, the absolute app
 * ceiling, and an optional explicitly trusted capability. Pruning is provider-only and atomic at
 * completed-turn boundaries; persisted messages and the active turn are never truncated.
 */
internal class GenerationProviderContextPreparer(
    tokenEstimator: ContextTokenEstimator = ApproximateContextTokenEstimator,
) {
    private val requestEstimator = ProviderRequestTokenEstimator(tokenEstimator)
    private val contextGate = ProviderRequestContextGate(requestEstimator)

    fun resolveWindow(model: Model): ResolvedContextWindow = ProviderContextWindowResolver.resolve(
        configuredPolicyTokens = model.userContextWindowTokens,
        trustedCapabilityTokens = model.trustedContextWindowTokens,
        advertisedTokens = model.contextLength,
    )

    fun resolveWindow(
        configuredContextWindowTokens: Int = DEFAULT_USER_CONTEXT_WINDOW_TOKENS,
        trustedContextWindowTokens: Int? = null,
        advertisedContextWindowTokens: Int? = null,
    ): ResolvedContextWindow = ProviderContextWindowResolver.resolve(
        configuredPolicyTokens = configuredContextWindowTokens,
        trustedCapabilityTokens = trustedContextWindowTokens,
        advertisedTokens = advertisedContextWindowTokens,
    )

    fun estimateToolSchemaTokens(tools: List<Tool>): Int =
        requestEstimator.estimateToolSchemaTokens(tools)

    /**
     * Produces the fixed, conservative budget passed to the whole-item memory compiler.
     *
     * [baseMessages] may contain full history; only its protected system/current-turn projection is
     * charged here because the final gate can drop older completed turns. Returning zero is not an
     * overflow approval: the post-transform [prepareOrdinaryChat] call remains authoritative.
     */
    fun conservativeMemoryBudget(
        resolvedWindow: ResolvedContextWindow,
        requestedOutputTokens: Int?,
        tools: List<Tool>,
        builtInTools: Set<BuiltInTools> = emptySet(),
        baseMessages: List<UIMessage>,
    ): Int {
        val window = resolvedWindow.effectiveTokens.coerceAtMost(ABSOLUTE_CONTEXT_WINDOW_TOKENS)
        val outputReserve = requestedOutputTokens?.takeIf { it > 0 } ?: 4_096
        val fixedMessages = contextGate.estimateProtectedMessageTokens(baseMessages)
        val schemaTokens = estimateToolSchemaTokens(tools) +
            requestEstimator.estimateBuiltInToolTokens(builtInTools)
        val margin = conservativeContextSafetyMargin(window)
        val remaining = (
            window.toLong() - outputReserve - fixedMessages - schemaTokens - margin
            ).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return min(MAX_ATOMIC_MEMORY_PROMPT_TOKENS, remaining)
    }

    fun conservativeMemoryBudget(
        model: Model,
        requestedOutputTokens: Int?,
        tools: List<Tool>,
        baseMessages: List<UIMessage>,
    ): Int = conservativeMemoryBudget(
        resolvedWindow = resolveWindow(model),
        requestedOutputTokens = requestedOutputTokens,
        tools = tools,
        builtInTools = model.tools,
        baseMessages = baseMessages,
    )

    fun prepareOrdinaryChat(
        messages: List<UIMessage>,
        configuredContextWindowTokens: Int = DEFAULT_USER_CONTEXT_WINDOW_TOKENS,
        advertisedContextWindowTokens: Int?,
        trustedContextWindowTokens: Int? = null,
        requestedOutputTokens: Int? = null,
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet(),
    ): GenerationProviderContextPreparation {
        val resolvedWindow = resolveWindow(
            configuredContextWindowTokens = configuredContextWindowTokens,
            trustedContextWindowTokens = trustedContextWindowTokens,
            advertisedContextWindowTokens = advertisedContextWindowTokens,
        )
        val gated = contextGate.enforceOrThrow(
            messages = messages,
            contextWindowTokens = resolvedWindow.effectiveTokens,
            requestedOutputTokens = requestedOutputTokens,
            tools = tools,
            builtInTools = builtInTools,
        )
        val finalEstimate = requestEstimator.estimate(gated.messages, tools, builtInTools)
        return GenerationProviderContextPreparation(
            messages = gated.messages,
            estimatedRequestTokens = finalEstimate.totalInputTokens,
            configuredContextWindowTokens = resolvedWindow.configuredPolicyTokens,
            advertisedContextWindowTokens = resolvedWindow.advertisedTokens,
            enforcedWindowTokens = resolvedWindow.effectiveTokens,
            effectiveMaxOutputTokens = gated.effectiveMaxOutputTokens,
            summaryUsed = false,
            trace = gated.trace,
        )
    }
}

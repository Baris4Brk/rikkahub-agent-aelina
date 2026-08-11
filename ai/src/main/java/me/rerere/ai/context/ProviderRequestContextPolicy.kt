package me.rerere.ai.context

import kotlin.math.ceil
import kotlin.math.min
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.DEFAULT_USER_CONTEXT_WINDOW_TOKENS
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

/** Process-wide safety ceiling. Provider catalog metadata is never allowed to raise this value. */
const val ABSOLUTE_CONTEXT_WINDOW_TOKENS: Int = 1_000_000

/** Conservative, fixed allocation offered to the atomic memory prompt compiler. */
const val MAX_ATOMIC_MEMORY_PROMPT_TOKENS: Int = 1_024

private const val DEFAULT_REQUESTED_OUTPUT_TOKENS: Int = 4_096
private const val MINIMUM_USEFUL_OUTPUT_TOKENS: Int = 64
private const val BUILT_IN_TOOL_RESERVE_TOKENS: Int = 128

enum class ResolvedContextWindowSource {
    USER_POLICY,
    TRUSTED_CAPABILITY,
    ABSOLUTE_APP_CAP,
}

/**
 * A context window resolved from policy and trusted inputs.
 *
 * [advertisedTokens] is deliberately diagnostic-only. In particular, OpenRouter's model catalog
 * can be stale or describe a different upstream route, so it must never silently shrink or expand
 * the enforced request budget.
 */
data class ResolvedContextWindow(
    val effectiveTokens: Int,
    val source: ResolvedContextWindowSource,
    val configuredPolicyTokens: Int,
    val trustedCapabilityTokens: Int?,
    val advertisedTokens: Int?,
)

object ProviderContextWindowResolver {
    fun resolve(
        configuredPolicyTokens: Int,
        trustedCapabilityTokens: Int? = null,
        advertisedTokens: Int? = null,
    ): ResolvedContextWindow {
        val configured = configuredPolicyTokens
            .takeIf { it > 0 }
            ?: DEFAULT_USER_CONTEXT_WINDOW_TOKENS
        val trusted = trustedCapabilityTokens?.takeIf { it > 0 }
        val advertised = advertisedTokens?.takeIf { it > 0 }

        var effective = min(configured, ABSOLUTE_CONTEXT_WINDOW_TOKENS)
        var source = if (configured > ABSOLUTE_CONTEXT_WINDOW_TOKENS) {
            ResolvedContextWindowSource.ABSOLUTE_APP_CAP
        } else {
            ResolvedContextWindowSource.USER_POLICY
        }
        if (trusted != null && trusted < effective) {
            effective = trusted
            source = ResolvedContextWindowSource.TRUSTED_CAPABILITY
        }
        return ResolvedContextWindow(
            effectiveTokens = effective,
            source = source,
            configuredPolicyTokens = configured,
            trustedCapabilityTokens = trusted,
            advertisedTokens = advertised,
        )
    }
}

fun interface ProviderMediaTokenEstimator {
    fun estimate(part: UIMessagePart): Int
}

/**
 * A deliberately conservative fallback for provider-specific media tokenization.
 * Callers with an exact provider counter should inject it instead.
 */
object ConservativeProviderMediaTokenEstimator : ProviderMediaTokenEstimator {
    override fun estimate(part: UIMessagePart): Int = when (part) {
        is UIMessagePart.Image -> 4_096
        is UIMessagePart.Video -> 8_192
        is UIMessagePart.Audio -> 8_192
        is UIMessagePart.Document -> 2_048
        else -> 0
    }
}

data class ProviderMessageTokenEstimate(
    val baseTokens: Int,
    val mediaTokens: Int,
) {
    val totalTokens: Int
        get() = safeTokenSum(baseTokens, mediaTokens)
}

data class ProviderRequestTokenEstimate(
    val baseMessageTokens: Int,
    val mediaTokens: Int,
    val toolSchemaTokens: Int,
) {
    val totalMessageTokens: Int
        get() = safeTokenSum(baseMessageTokens, mediaTokens)

    val totalInputTokens: Int
        get() = safeTokenSum(totalMessageTokens, toolSchemaTokens)
}

/** Shared estimator used by both the hard gate and diagnostics/preflight allocation. */
class ProviderRequestTokenEstimator(
    private val messageEstimator: ContextTokenEstimator = ApproximateContextTokenEstimator,
    private val mediaEstimator: ProviderMediaTokenEstimator = ConservativeProviderMediaTokenEstimator,
) {
    private val schemaJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun estimateMessage(message: UIMessage): ProviderMessageTokenEstimate =
        ProviderMessageTokenEstimate(
            baseTokens = messageEstimator.estimate(message).coerceAtLeast(0),
            mediaTokens = message.parts.safeSumOf(::estimateMediaTokens),
        )

    fun estimateToolSchemaTokens(tools: List<Tool>): Int = tools.safeSumOf { tool ->
        val schema = runCatching {
            tool.parameters()?.let { schemaJson.encodeToString(InputSchema.serializer(), it) }.orEmpty()
        }.getOrElse {
            // A schema that cannot be inspected must not receive a zero-token reservation.
            return@safeSumOf 256
        }
        safeTokenSum(
            estimateText(tool.name),
            estimateText(tool.description),
            estimateText(schema),
            12, // request framing around one tool declaration
        )
    }

    fun estimateBuiltInToolTokens(tools: Set<BuiltInTools>): Int =
        tools.size * BUILT_IN_TOOL_RESERVE_TOKENS

    fun estimate(
        messages: List<UIMessage>,
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet(),
    ): ProviderRequestTokenEstimate {
        val messageEstimates = messages.map(::estimateMessage)
        return ProviderRequestTokenEstimate(
            baseMessageTokens = messageEstimates.safeSumOf(ProviderMessageTokenEstimate::baseTokens),
            mediaTokens = messageEstimates.safeSumOf(ProviderMessageTokenEstimate::mediaTokens),
            toolSchemaTokens = safeTokenSum(
                estimateToolSchemaTokens(tools),
                estimateBuiltInToolTokens(builtInTools),
            ),
        )
    }

    private fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        val emptyFraming = messageEstimator.estimate(UIMessage.system("")).coerceAtLeast(0)
        val framed = messageEstimator.estimate(UIMessage.system(text)).coerceAtLeast(0)
        return (framed - emptyFraming).coerceAtLeast(1)
    }

    private fun estimateMediaTokens(part: UIMessagePart): Int = when (part) {
        is UIMessagePart.Tool -> safeTokenSum(
            mediaEstimator.estimate(part).coerceAtLeast(0),
            part.output.safeSumOf(::estimateMediaTokens),
        )
        else -> mediaEstimator.estimate(part).coerceAtLeast(0)
    }
}

enum class ProviderContextOverflowKind {
    FIXED_PREFIX_TOO_LARGE,
    CURRENT_TURN_TOO_LARGE,
}

data class ProviderContextGateTrace(
    val contextWindowTokens: Int,
    val requestedOutputTokens: Int,
    val effectiveOutputTokens: Int,
    val safetyMarginTokens: Int,
    val toolSchemaTokens: Int,
    val originalMessageTokens: Int,
    val finalMessageTokens: Int,
    val maximumMessageTokens: Int,
    val strippedHistoricalReasoningParts: Int,
    val droppedCompletedTurns: Int,
    val droppedMessages: Int,
    val outputClamped: Boolean,
    val overflowKind: ProviderContextOverflowKind? = null,
)

sealed interface ProviderContextGateResult {
    data class Success(
        val messages: List<UIMessage>,
        val effectiveMaxOutputTokens: Int,
        val trace: ProviderContextGateTrace,
    ) : ProviderContextGateResult

    data class Overflow(
        val kind: ProviderContextOverflowKind,
        val trace: ProviderContextGateTrace,
    ) : ProviderContextGateResult
}

class ProviderContextOverflowException(
    val overflow: ProviderContextGateResult.Overflow,
) : IllegalStateException(
    "CONTEXT_HARD_CAP: ${overflow.kind} " +
        "(${overflow.trace.finalMessageTokens} message tokens, " +
        "${overflow.trace.toolSchemaTokens} schema tokens, " +
        "window ${overflow.trace.contextWindowTokens})",
)

/**
 * Fail-closed provider projection. It never mutates stored messages, creates a summary, truncates
 * the active user turn, or splits a completed turn/tool exchange.
 */
class ProviderRequestContextGate(
    private val estimator: ProviderRequestTokenEstimator = ProviderRequestTokenEstimator(),
) {
    fun enforce(
        messages: List<UIMessage>,
        contextWindowTokens: Int,
        requestedOutputTokens: Int? = null,
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet(),
    ): ProviderContextGateResult {
        require(contextWindowTokens > 0) { "contextWindowTokens must be positive" }
        val enforcedWindow = min(contextWindowTokens, ABSOLUTE_CONTEXT_WINDOW_TOKENS)
        val requestedOutput = requestedOutputTokens
            ?.takeIf { it > 0 }
            ?: DEFAULT_REQUESTED_OUTPUT_TOKENS
        val margin = conservativeContextSafetyMargin(enforcedWindow)
        val schemaTokens = safeTokenSum(
            estimator.estimateToolSchemaTokens(tools),
            estimator.estimateBuiltInToolTokens(builtInTools),
        )
        val originalMessageTokens = estimateMessages(messages)

        fun fits(candidate: List<UIMessage>, outputTokens: Int): Boolean =
            estimateMessages(candidate).toLong() + schemaTokens + margin + outputTokens <=
                enforcedWindow.toLong()

        if (fits(messages, requestedOutput)) {
            return success(
                messages = messages,
                contextWindowTokens = enforcedWindow,
                requestedOutputTokens = requestedOutput,
                effectiveOutputTokens = requestedOutput,
                margin = margin,
                schemaTokens = schemaTokens,
                originalMessageTokens = originalMessageTokens,
                strippedReasoning = 0,
                droppedTurns = 0,
                droppedMessages = 0,
            )
        }

        val protected = protectedMessageIndices(messages)
        val projected = messages.mapIndexed { index, message ->
            if (index in protected) message else message.withoutHistoricalProviderReasoning().message
        }.toMutableList()
        val strippedReasoning = messages.indices.sumOf { index ->
            if (index in protected) 0 else messages[index].withoutHistoricalProviderReasoning().strippedParts
        }
        if (fits(projected, requestedOutput)) {
            return success(
                messages = projected,
                contextWindowTokens = enforcedWindow,
                requestedOutputTokens = requestedOutput,
                effectiveOutputTokens = requestedOutput,
                margin = margin,
                schemaTokens = schemaTokens,
                originalMessageTokens = originalMessageTokens,
                strippedReasoning = strippedReasoning,
                droppedTurns = 0,
                droppedMessages = 0,
            )
        }

        val keep = BooleanArray(messages.size) { true }
        var droppedTurns = 0
        var droppedMessages = 0
        for (turn in completedDroppableTurnIndices(messages, protected)) {
            turn.forEach { index ->
                if (keep[index]) {
                    keep[index] = false
                    droppedMessages++
                }
            }
            droppedTurns++
            val candidate = projected.filterIndexed { index, _ -> keep[index] }
            if (fits(candidate, requestedOutput)) {
                return success(
                    messages = candidate,
                    contextWindowTokens = enforcedWindow,
                    requestedOutputTokens = requestedOutput,
                    effectiveOutputTokens = requestedOutput,
                    margin = margin,
                    schemaTokens = schemaTokens,
                    originalMessageTokens = originalMessageTokens,
                    strippedReasoning = strippedReasoning,
                    droppedTurns = droppedTurns,
                    droppedMessages = droppedMessages,
                )
            }
        }

        val finalMessages = projected.filterIndexed { index, _ -> keep[index] }
        val finalMessageTokens = estimateMessages(finalMessages)
        val maximumOutput = (
            enforcedWindow.toLong() - schemaTokens - margin - finalMessageTokens
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val minimumOutput = min(requestedOutput, MINIMUM_USEFUL_OUTPUT_TOKENS)
        if (maximumOutput >= minimumOutput) {
            val effectiveOutput = min(requestedOutput, maximumOutput)
            return success(
                messages = finalMessages,
                contextWindowTokens = enforcedWindow,
                requestedOutputTokens = requestedOutput,
                effectiveOutputTokens = effectiveOutput,
                margin = margin,
                schemaTokens = schemaTokens,
                originalMessageTokens = originalMessageTokens,
                strippedReasoning = strippedReasoning,
                droppedTurns = droppedTurns,
                droppedMessages = droppedMessages,
            )
        }

        val activeStart = activeTurnStart(messages)
        val fixedPrefixMessages = finalMessages.filter { message ->
            message.role == MessageRole.SYSTEM || message.hasManualCompressionMarker()
        }
        val fixedPrefixTokens = estimateMessages(fixedPrefixMessages)
        val fixedNeeds = fixedPrefixTokens.toLong() + schemaTokens + margin + minimumOutput
        val kind = if (fixedNeeds > enforcedWindow || activeStart < 0) {
            ProviderContextOverflowKind.FIXED_PREFIX_TOO_LARGE
        } else {
            ProviderContextOverflowKind.CURRENT_TURN_TOO_LARGE
        }
        val maximumMessageTokens = (enforcedWindow - schemaTokens - margin - minimumOutput)
            .coerceAtLeast(0)
        return ProviderContextGateResult.Overflow(
            kind = kind,
            trace = ProviderContextGateTrace(
                contextWindowTokens = enforcedWindow,
                requestedOutputTokens = requestedOutput,
                effectiveOutputTokens = minimumOutput,
                safetyMarginTokens = margin,
                toolSchemaTokens = schemaTokens,
                originalMessageTokens = originalMessageTokens,
                finalMessageTokens = finalMessageTokens,
                maximumMessageTokens = maximumMessageTokens,
                strippedHistoricalReasoningParts = strippedReasoning,
                droppedCompletedTurns = droppedTurns,
                droppedMessages = droppedMessages,
                outputClamped = requestedOutput != minimumOutput,
                overflowKind = kind,
            ),
        )
    }

    fun enforceOrThrow(
        messages: List<UIMessage>,
        contextWindowTokens: Int,
        requestedOutputTokens: Int? = null,
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet(),
    ): ProviderContextGateResult.Success = when (
        val result = enforce(
            messages = messages,
            contextWindowTokens = contextWindowTokens,
            requestedOutputTokens = requestedOutputTokens,
            tools = tools,
            builtInTools = builtInTools,
        )
    ) {
        is ProviderContextGateResult.Success -> result
        is ProviderContextGateResult.Overflow -> throw ProviderContextOverflowException(result)
    }

    /** Estimate only non-droppable messages for the pre-memory conservative allocation. */
    fun estimateProtectedMessageTokens(messages: List<UIMessage>): Int {
        val protected = protectedMessageIndices(messages)
        return estimateMessages(messages.filterIndexed { index, _ -> index in protected })
    }

    private fun success(
        messages: List<UIMessage>,
        contextWindowTokens: Int,
        requestedOutputTokens: Int,
        effectiveOutputTokens: Int,
        margin: Int,
        schemaTokens: Int,
        originalMessageTokens: Int,
        strippedReasoning: Int,
        droppedTurns: Int,
        droppedMessages: Int,
    ): ProviderContextGateResult.Success {
        val finalMessageTokens = estimateMessages(messages)
        return ProviderContextGateResult.Success(
            messages = messages,
            effectiveMaxOutputTokens = effectiveOutputTokens,
            trace = ProviderContextGateTrace(
                contextWindowTokens = contextWindowTokens,
                requestedOutputTokens = requestedOutputTokens,
                effectiveOutputTokens = effectiveOutputTokens,
                safetyMarginTokens = margin,
                toolSchemaTokens = schemaTokens,
                originalMessageTokens = originalMessageTokens,
                finalMessageTokens = finalMessageTokens,
                maximumMessageTokens = (
                    contextWindowTokens - schemaTokens - margin - effectiveOutputTokens
                    ).coerceAtLeast(0),
                strippedHistoricalReasoningParts = strippedReasoning,
                droppedCompletedTurns = droppedTurns,
                droppedMessages = droppedMessages,
                outputClamped = requestedOutputTokens != effectiveOutputTokens,
            ),
        )
    }

    private fun estimateMessages(messages: List<UIMessage>): Int =
        messages.safeSumOf { estimator.estimateMessage(it).totalTokens }
}

fun conservativeContextSafetyMargin(contextWindowTokens: Int): Int {
    require(contextWindowTokens > 0)
    val ratioMargin = ceil(contextWindowTokens * 0.10).toInt()
    return min((contextWindowTokens / 4).coerceAtLeast(0), ratioMargin.coerceAtLeast(16))
}

private data class HistoricalSanitization(
    val message: UIMessage,
    val strippedParts: Int,
)

private fun UIMessage.withoutHistoricalProviderReasoning(): HistoricalSanitization {
    var stripped = 0
    fun sanitize(part: UIMessagePart): UIMessagePart? = when (part) {
        is UIMessagePart.Reasoning -> {
            stripped++
            null
        }
        is UIMessagePart.Tool -> part.copy(output = part.output.mapNotNull(::sanitize))
        else -> part
    }
    return HistoricalSanitization(copy(parts = parts.mapNotNull(::sanitize)), stripped)
}

private fun protectedMessageIndices(messages: List<UIMessage>): Set<Int> {
    if (messages.isEmpty()) return emptySet()
    val activeStart = activeTurnStart(messages)
    return messages.indices.filterTo(linkedSetOf()) { index ->
        messages[index].role == MessageRole.SYSTEM ||
            messages[index].hasManualCompressionMarker() ||
            (activeStart >= 0 && index >= activeStart)
    }
}

private fun activeTurnStart(messages: List<UIMessage>): Int {
    val latestUser = messages.indexOfLast { it.role == MessageRole.USER }
    if (latestUser >= 0) return latestUser
    return messages.indexOfLast { it.role != MessageRole.SYSTEM }
}

private fun completedDroppableTurnIndices(
    messages: List<UIMessage>,
    protected: Set<Int>,
): List<List<Int>> {
    val activeStart = activeTurnStart(messages).let { if (it < 0) messages.size else it }
    val groups = mutableListOf<MutableList<Int>>()
    for (index in 0 until activeStart) {
        if (index in protected) continue
        val message = messages[index]
        if (groups.isEmpty() || message.role == MessageRole.USER) {
            groups.add(mutableListOf())
        }
        groups.last().add(index)
    }
    return groups.filter { it.isNotEmpty() }
}

private fun UIMessage.hasManualCompressionMarker(): Boolean =
    annotations.any { it is UIMessageAnnotation.ManualCompressionSummary }

private fun safeTokenSum(vararg values: Int): Int = values.asIterable().safeSumOf { it }

private inline fun <T> Iterable<T>.safeSumOf(selector: (T) -> Int): Int =
    fold(0L) { total, item -> total + selector(item).coerceAtLeast(0) }
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

package me.rerere.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.math.floor
import kotlin.math.max

/** Estimates provider input tokens without coupling context planning to a tokenizer implementation. */
fun interface ContextTokenEstimator {
    fun estimate(message: UIMessage): Int
}

/**
 * Conservative tokenizer-independent fallback. Provider-specific callers can inject an exact estimator.
 * The fixed per-message overhead accounts for roles and request framing.
 */
object ApproximateContextTokenEstimator : ContextTokenEstimator {
    override fun estimate(message: UIMessage): Int {
        return 8 + message.parts.sumOf { it.estimatedTokenCount() }
    }
}

data class ProviderContextPayloadValidation(
    val estimatedInputTokens: Int,
    val maximumInputTokens: Int,
) {
    val fits: Boolean
        get() = estimatedInputTokens <= maximumInputTokens
}

/** Validates the actual post-transform payload while reserving room for model output. */
fun validateProviderContextPayload(
    messages: List<UIMessage>,
    contextWindowTokens: Int,
    requestedOutputTokens: Int?,
    estimator: ContextTokenEstimator = ApproximateContextTokenEstimator,
): ProviderContextPayloadValidation {
    require(contextWindowTokens > 0)
    val outputReserve = (requestedOutputTokens ?: 4_096)
        .coerceAtLeast(1_024)
        .coerceAtMost(max(1_024, contextWindowTokens / 4))
        .coerceAtMost((contextWindowTokens - 1).coerceAtLeast(1))
    return ProviderContextPayloadValidation(
        estimatedInputTokens = messages.sumOf { estimator.estimate(it).coerceAtLeast(0) },
        maximumInputTokens = (contextWindowTokens - outputReserve).coerceAtLeast(1),
    )
}

data class ProviderContextBudget(
    val contextWindowTokens: Int,
    val compressionTriggerTokens: Int,
    val compressionTargetTokens: Int,
    val originalTokens: Int,
    val protectedTokens: Int,
    val summaryReservedTokens: Int,
    val plannedTokens: Int,
) {
    val fitsCompressionTarget: Boolean
        get() = plannedTokens <= compressionTargetTokens
}

/**
 * A transient plan for one provider request. None of the lists are written back to conversation storage.
 *
 * [oldHistoryForSummary] has already had provider reasoning removed. A caller must summarize that list
 * into a new transient message before [assemble] can be used. [activeToolChain] is the complete suffix
 * beginning with the latest user message, so current reasoning/tool calls/tool results remain lossless.
 */
data class ProviderContextPlan(
    val budget: ProviderContextBudget,
    val compressed: Boolean,
    val systemMessages: List<UIMessage>,
    val oldHistoryForSummary: List<UIMessage>,
    val recentOriginalMessages: List<UIMessage>,
    val activeToolChain: List<UIMessage>,
    val strippedHistoricalReasoningParts: Int,
) {
    val requiresSummary: Boolean
        get() = oldHistoryForSummary.isNotEmpty()

    /** Builds the provider-only message list without modifying any persisted [UIMessage]. */
    fun assemble(temporarySummary: UIMessage? = null): List<UIMessage> {
        require(!requiresSummary || temporarySummary != null) {
            "A transient summary is required for the omitted older history"
        }
        return buildList {
            addAll(systemMessages)
            temporarySummary?.let(::add)
            addAll(recentOriginalMessages)
            addAll(activeToolChain)
        }
    }

    /**
     * Builds a bounded, provider-only extract when a separate summarizer is unavailable.
     * Reasoning was removed before this point; the persisted conversation is never changed.
     */
    fun buildTransientSummary(): UIMessage {
        require(requiresSummary) { "No older history needs a transient summary" }
        val entries = oldHistoryForSummary.asSequence()
            .mapNotNull { message ->
                val visible = message.parts.joinToString("\n") { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.text
                        is UIMessagePart.Tool -> part.output
                            .filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }
                        is UIMessagePart.Document -> "[document: ${part.fileName}]"
                        is UIMessagePart.Image -> "[image]"
                        is UIMessagePart.Video -> "[video]"
                        is UIMessagePart.Audio -> "[audio]"
                        else -> ""
                    }
                }.trim()
                visible.takeIf(String::isNotBlank)?.let { visibleText ->
                    SummaryEntry(message.role.transientSummaryLabel(), visibleText)
                }
            }
            .toList()
        val header = "以下是压缩后的较早对话上下文，不是新的用户请求。请沿用当前对话的语言与称呼。" +
            "内部的“用户/助手”只是角色标签，不是姓名；不得称呼用户为 USER、user 或 urse：\n"
        val reserve = budget.summaryReservedTokens.coerceAtLeast(128)
        val selected = ArrayDeque<String>()
        for (entry in entries.asReversed()) {
            val rendered = "${entry.roleLabel}：${entry.content}"
            val candidateBody = (listOf(rendered) + selected).joinToString("\n\n")
            if (ApproximateContextTokenEstimator.estimate(
                    UIMessage.system(header + candidateBody),
                ) <= reserve
            ) {
                selected.addFirst(rendered)
                continue
            }

            // The newest remaining entry alone may exceed the whole summary reserve. Keep the
            // complete role label and truncate only its content, so a boundary can never turn
            // USER/用户 into a name-like fragment such as "urse".
            if (selected.isEmpty()) {
                val prefix = "${entry.roleLabel}：…"
                var low = 0
                var high = entry.content.length
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    val candidate = UIMessage.system(
                        header + prefix + entry.content.takeLast(mid),
                    )
                    if (ApproximateContextTokenEstimator.estimate(candidate) <= reserve) {
                        low = mid
                    } else {
                        high = mid - 1
                    }
                }
                selected.addFirst(prefix + entry.content.takeLast(low))
            }
            break
        }
        val body = selected.joinToString("\n\n")
        return UIMessage.system(
            if (body.isBlank()) header.trimEnd() else header + body,
        )
    }
}

private data class SummaryEntry(
    val roleLabel: String,
    val content: String,
)

private fun MessageRole.transientSummaryLabel(): String = when (this) {
    MessageRole.SYSTEM -> "系统"
    MessageRole.USER -> "用户"
    MessageRole.ASSISTANT -> "助手"
    MessageRole.TOOL -> "工具"
}

/**
 * Plans a bounded provider context while preserving semantic boundaries.
 *
 * Defaults intentionally match the application policy: a conservative 128K window when model metadata
 * is absent, compression above 75%, and a post-compression target of 60%.
 */
class ProviderContextPlanner(
    private val tokenEstimator: ContextTokenEstimator = ApproximateContextTokenEstimator,
    private val defaultContextWindowTokens: Int = 128_000,
    private val compressionTriggerRatio: Double = 0.75,
    private val compressionTargetRatio: Double = 0.60,
    private val summaryTokenReserve: Int = 2_048,
) {
    init {
        require(defaultContextWindowTokens > 0)
        require(compressionTriggerRatio in 0.0..1.0)
        require(compressionTargetRatio in 0.0..compressionTriggerRatio)
        require(summaryTokenReserve >= 0)
    }

    fun plan(
        messages: List<UIMessage>,
        declaredContextTokens: Int?,
    ): ProviderContextPlan {
        val contextWindow = declaredContextTokens?.takeIf { it > 0 }
            ?: defaultContextWindowTokens
        val trigger = floor(contextWindow * compressionTriggerRatio).toInt()
        val target = floor(contextWindow * compressionTargetRatio).toInt()
        val originalTokens = messages.sumOf(::estimate)

        val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }
        val latestUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        val activeIndices = when {
            latestUserIndex >= 0 -> (latestUserIndex until messages.size).toSet()
            messages.isNotEmpty() -> setOf(messages.lastIndex)
            else -> emptySet()
        }
        val activeToolChain = messages.filterIndexed { index, message ->
            index in activeIndices && message.role != MessageRole.SYSTEM
        }

        var strippedReasoningParts = 0
        val sanitizedHistory = messages.mapIndexedNotNull { index, message ->
            if (message.role == MessageRole.SYSTEM || index in activeIndices) {
                null
            } else {
                val sanitized = message.withoutProviderReasoning()
                strippedReasoningParts += sanitized.strippedParts
                sanitized.message.takeIf { it.parts.isNotEmpty() }
            }
        }
        val protectedTokens = systemMessages.sumOf(::estimate) + activeToolChain.sumOf(::estimate)
        val shouldCompress = originalTokens > trigger

        if (!shouldCompress) {
            val planned = protectedTokens + sanitizedHistory.sumOf(::estimate)
            return ProviderContextPlan(
                budget = ProviderContextBudget(
                    contextWindowTokens = contextWindow,
                    compressionTriggerTokens = trigger,
                    compressionTargetTokens = target,
                    originalTokens = originalTokens,
                    protectedTokens = protectedTokens,
                    summaryReservedTokens = 0,
                    plannedTokens = planned,
                ),
                compressed = false,
                systemMessages = systemMessages,
                oldHistoryForSummary = emptyList(),
                recentOriginalMessages = sanitizedHistory,
                activeToolChain = activeToolChain,
                strippedHistoricalReasoningParts = strippedReasoningParts,
            )
        }

        val historyTokens = sanitizedHistory.sumOf(::estimate)
        if (protectedTokens + historyTokens <= target) {
            return ProviderContextPlan(
                budget = ProviderContextBudget(
                    contextWindowTokens = contextWindow,
                    compressionTriggerTokens = trigger,
                    compressionTargetTokens = target,
                    originalTokens = originalTokens,
                    protectedTokens = protectedTokens,
                    summaryReservedTokens = 0,
                    plannedTokens = protectedTokens + historyTokens,
                ),
                compressed = true,
                systemMessages = systemMessages,
                oldHistoryForSummary = emptyList(),
                recentOriginalMessages = sanitizedHistory,
                activeToolChain = activeToolChain,
                strippedHistoricalReasoningParts = strippedReasoningParts,
            )
        }

        val turnGroups = sanitizedHistory.groupIntoCompletedTurns()
        val recentGroups = ArrayDeque<List<UIMessage>>()
        val availableForRecent = (target - protectedTokens - summaryTokenReserve).coerceAtLeast(0)
        var recentTokens = 0
        for (group in turnGroups.asReversed()) {
            val groupTokens = group.sumOf(::estimate)
            if (recentTokens + groupTokens > availableForRecent) break
            recentGroups.addFirst(group)
            recentTokens += groupTokens
        }
        val recentMessages = recentGroups.flatten()
        val summarizedCount = sanitizedHistory.size - recentMessages.size
        val summarySource = sanitizedHistory.take(summarizedCount)
        val plannedTokens = protectedTokens + recentTokens +
            if (summarySource.isNotEmpty()) summaryTokenReserve else 0

        return ProviderContextPlan(
            budget = ProviderContextBudget(
                contextWindowTokens = contextWindow,
                compressionTriggerTokens = trigger,
                compressionTargetTokens = target,
                originalTokens = originalTokens,
                protectedTokens = protectedTokens,
                summaryReservedTokens = if (summarySource.isNotEmpty()) summaryTokenReserve else 0,
                plannedTokens = plannedTokens,
            ),
            compressed = true,
            systemMessages = systemMessages,
            oldHistoryForSummary = summarySource,
            recentOriginalMessages = recentMessages,
            activeToolChain = activeToolChain,
            strippedHistoricalReasoningParts = strippedReasoningParts,
        )
    }

    private fun estimate(message: UIMessage): Int = tokenEstimator.estimate(message).coerceAtLeast(0)
}

private data class SanitizedMessage(
    val message: UIMessage,
    val strippedParts: Int,
)

private fun UIMessage.withoutProviderReasoning(): SanitizedMessage {
    var stripped = 0

    fun sanitize(part: UIMessagePart): UIMessagePart? = when (part) {
        is UIMessagePart.Reasoning -> {
            stripped++
            null
        }
        is UIMessagePart.Tool -> {
            val output = part.output.mapNotNull(::sanitize)
            if (part.output.isNotEmpty() && output.isEmpty()) null else part.copy(output = output)
        }
        else -> part
    }

    return SanitizedMessage(
        message = copy(parts = parts.mapNotNull(::sanitize)),
        strippedParts = stripped,
    )
}

private fun List<UIMessage>.groupIntoCompletedTurns(): List<List<UIMessage>> {
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<MutableList<UIMessage>>()
    for (message in this) {
        if (groups.isEmpty() || (message.role == MessageRole.USER && groups.last().isNotEmpty())) {
            groups.add(mutableListOf())
        }
        groups.last().add(message)
    }
    return groups
}

private fun UIMessagePart.estimatedTokenCount(): Int = when (this) {
    is UIMessagePart.Text -> text.conservativeTokenEstimate()
    is UIMessagePart.Reasoning -> reasoning.conservativeTokenEstimate()
    is UIMessagePart.Image -> url.payloadTokenEstimate()
    is UIMessagePart.Video -> url.payloadTokenEstimate()
    is UIMessagePart.Audio -> url.payloadTokenEstimate()
    is UIMessagePart.Document ->
        url.payloadTokenEstimate() + fileName.conservativeTokenEstimate() + mime.conservativeTokenEstimate()
    is UIMessagePart.Tool -> toolName.conservativeTokenEstimate() + input.payloadTokenEstimate() +
        output.sumOf { it.estimatedTokenCount() }
    is UIMessagePart.ToolCall -> toolName.conservativeTokenEstimate() + arguments.payloadTokenEstimate()
    is UIMessagePart.ToolResult -> toolName.conservativeTokenEstimate() +
        content.toString().payloadTokenEstimate() + arguments.toString().payloadTokenEstimate()
    UIMessagePart.Search -> 1
}

private fun String.payloadTokenEstimate(): Int {
    if (isEmpty()) return 0
    val base = conservativeTokenEstimate()
    val looksEncodedOrStructured = length >= 256 && (
        startsWith("data:") || startsWith("{") || startsWith("[") ||
            count { it == '=' || it == '/' || it == '+' } >= length / 32
        )
    return if (looksEncodedOrStructured) max(base, (length + 1) / 2) else base
}

private fun String.conservativeTokenEstimate(): Int {
    if (isEmpty()) return 0
    var ascii = 0
    var nonAsciiCodePoints = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        if (codePoint <= 0x7F) ascii++ else nonAsciiCodePoints++
        index += Character.charCount(codePoint)
    }
    return ((ascii + 2) / 3) + nonAsciiCodePoints
}

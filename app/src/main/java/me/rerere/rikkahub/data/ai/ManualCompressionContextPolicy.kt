package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext

/**
 * Applies the legacy assistant message-count limit only until the user explicitly compresses.
 *
 * Manual compression establishes a new, user-owned history prefix: summary batch + retained
 * tail. Sliding that prefix by one or two messages on every subsequent turn both discards the
 * summary and defeats provider prefix caching. Once a compression boundary exists, keep the
 * whole post-compression history until the user manually compresses again.
 */
internal fun List<UIMessage>.selectOrdinaryChatContext(messageLimit: Int): List<UIMessage> {
    if (messageLimit <= 0 || size <= messageLimit) return this
    if (hasMarkedManualCompressionBoundary() || hasLegacyManualCompressionPrefix()) return this
    return limitContext(messageLimit)
}

/**
 * Builds the repeat context used after the model has already selected a tool for this turn.
 *
 * The first provider call still receives the user's complete selected history. Chat-completions
 * providers are stateless, however, and previously received that entire history again after every
 * tool result. Keep the current tool transaction plus a stable recent tail and any explicit manual
 * compression summaries. The original conversation remains untouched and fully browsable.
 */
internal fun List<UIMessage>.selectToolLoopContinuationContext(
    recentHistoryMessageLimit: Int = TOOL_LOOP_RECENT_HISTORY_MESSAGES,
): List<UIMessage> {
    if (isEmpty()) return this
    val turnStart = indexOfLast { message ->
        message.role == MessageRole.USER &&
            message.annotations.none { it is UIMessageAnnotation.Steering }
    }
    if (turnStart <= 0) return this

    val requestedStart = (turnStart - recentHistoryMessageLimit.coerceAtLeast(0)).coerceAtLeast(0)
    val alignedStart = (requestedStart..turnStart).firstOrNull { index ->
        this[index].role == MessageRole.USER &&
            this[index].annotations.none { it is UIMessageAnnotation.Steering }
    } ?: turnStart
    if (alignedStart == 0) return this

    val summaryAnchors = subList(0, alignedStart).filter { message ->
        message.annotations.any { it is UIMessageAnnotation.ManualCompressionSummary }
    }
    val retained = subList(alignedStart, size)
    if (summaryAnchors.isEmpty()) return retained

    val retainedIds = retained.mapTo(hashSetOf()) { it.id }
    return summaryAnchors.filterNot { it.id in retainedIds } + retained
}

/**
 * Bounds old, completed tool payloads during very long single-turn runs without changing the
 * persisted conversation. Pending/running tools and the newest tool calls stay byte-for-byte
 * intact so approval and resume semantics cannot be damaged.
 */
internal fun List<UIMessage>.compactCompletedToolHistoryForContinuation(
    recentToolCount: Int = TOOL_LOOP_RECENT_TOOL_COUNT,
): List<UIMessage> {
    val tools = flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Tool>() }
    val protectedIds = buildSet {
        tools.takeLast(recentToolCount.coerceAtLeast(0)).forEach { add(it.toolCallId) }
        tools.filterNot { it.isExecuted }.forEach { add(it.toolCallId) }
    }
    return map { message ->
        if (message.role == MessageRole.USER) return@map message
        val archivesCompletedTool = message.parts.any { part ->
            part is UIMessagePart.Tool && part.toolCallId !in protectedIds && part.isExecuted
        }
        message.copy(parts = message.parts.mapNotNull { part ->
            when {
                part is UIMessagePart.Reasoning && archivesCompletedTool -> null
                part is UIMessagePart.Tool && part.toolCallId !in protectedIds && part.isExecuted ->
                    part.copy(
                        input = "{\"_archived_tool_input\":true}",
                        output = listOf(
                            UIMessagePart.Text(
                                "[completed tool result archived for long-turn context; " +
                                    "tool=${part.toolName.take(96)}]",
                            ),
                        ),
                    )
                else -> part
            }
        })
    }
}

private const val TOOL_LOOP_RECENT_HISTORY_MESSAGES = 32
private const val TOOL_LOOP_RECENT_TOOL_COUNT = 32

private fun List<UIMessage>.hasMarkedManualCompressionBoundary(): Boolean = any { message ->
    message.annotations.any { it is UIMessageAnnotation.ManualCompressionSummary }
}

/**
 * Compatibility for conversations compressed before the annotation existed. Those summaries
 * were inserted at the beginning with a new timestamp, immediately followed by the retained,
 * older tail. A normal chronological conversation cannot have this newer all-user prefix.
 */
private fun List<UIMessage>.hasLegacyManualCompressionPrefix(): Boolean {
    if (size < 2 || first().role != MessageRole.USER) return false
    val summaryTime = first().createdAt
    val oldTailIndex = indexOfFirst { message -> message.createdAt < summaryTime }
    if (oldTailIndex <= 0) return false
    return take(oldTailIndex).all { message -> message.role == MessageRole.USER }
}

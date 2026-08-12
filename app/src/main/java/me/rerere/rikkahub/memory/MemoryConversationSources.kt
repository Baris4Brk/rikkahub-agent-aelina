package me.rerere.rikkahub.memory

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Builds the exact text sources consumed by memory extraction.
 *
 * Reasoning, tool input and media are deliberately excluded. A completed tool's textual output
 * gets its own stable synthetic identity so it can be invalidated without deleting the assistant's
 * final answer (and vice versa).
 */
internal fun memoryCaptureSourcesForMessage(
    message: UIMessage,
): List<MemoryCaptureSourceInput> = buildList {
    val messageRole = when (message.role) {
        MessageRole.USER -> MemorySourceRole.USER
        MessageRole.ASSISTANT -> MemorySourceRole.ASSISTANT
        else -> null
    }
    val visibleText = message.parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { part -> part.text }
        .trim()
    if (messageRole != null && visibleText.isNotEmpty()) {
        add(
            MemoryCaptureSourceInput(
                messageId = message.id.toString(),
                role = messageRole,
                text = visibleText,
            ),
        )
    }
    if (message.role == MessageRole.ASSISTANT) {
        message.parts.forEachIndexed { partIndex, part ->
            val tool = part as? UIMessagePart.Tool ?: return@forEachIndexed
            if (!tool.isExecuted) return@forEachIndexed
            val outputText = tool.output.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { output -> output.text }
                .trim()
            if (outputText.isEmpty()) return@forEachIndexed
            add(
                MemoryCaptureSourceInput(
                    messageId = memoryToolSourceId(
                        assistantMessageId = message.id.toString(),
                        partIndex = partIndex,
                        toolCallId = tool.toolCallId,
                    ),
                    role = MemorySourceRole.TOOL,
                    text = outputText,
                ),
            )
        }
    }
}

internal fun memoryExtractionText(
    sources: List<MemoryCaptureSourceInput>,
    roles: Set<MemorySourceRole>,
): String = sources.asSequence()
    .filter { source -> source.role in roles }
    .joinToString("\n\n") { source ->
        when (source.role) {
            MemorySourceRole.USER -> source.text
            MemorySourceRole.ASSISTANT -> source.text
            MemorySourceRole.TOOL -> "[Tool output]\n${source.text}"
        }
    }
    .trim()

internal fun memoryToolSourceId(
    assistantMessageId: String,
    partIndex: Int,
    toolCallId: String,
): String = buildString {
    append(assistantMessageId)
    append("#tool#")
    append(partIndex)
    append('#')
    append(memorySourceTextDigest(toolCallId).take(16))
}

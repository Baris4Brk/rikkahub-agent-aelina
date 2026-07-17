package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ReasoningSource
import me.rerere.ai.ui.ThinkTagParser
import me.rerere.ai.ui.ThinkTagSegment
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

/** Converts provider text `<think>` blocks into structured reasoning without guessing EOF. */
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformThinkTagsForLatestAssistant(messages, isFinal = false)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformThinkTagsForLatestAssistant(messages, isFinal = true)

}

/**
 * Converts only the message being generated. Earlier assistant messages are persisted history and
 * must remain byte-for-byte unchanged when a later turn finishes.
 */
internal fun transformThinkTagsForLatestAssistant(
    messages: List<UIMessage>,
    isFinal: Boolean,
): List<UIMessage> {
    val message = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
        ?: return messages
    val now = Clock.System.now()
    var changed = false
    val transformed = buildList {
        message.parts.forEach { part ->
            if (part !is UIMessagePart.Text || !part.shouldParseThinkTags(isFinal)) {
                add(part)
                return@forEach
            }

            changed = true
            ThinkTagParser.parse(part.text, isFinal = isFinal).segments.forEach { segment ->
                when (segment) {
                    is ThinkTagSegment.Text -> add(part.copy(text = segment.text))
                    is ThinkTagSegment.Reasoning -> {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = segment.reasoning,
                            createdAt = message.createdAt.toInstant(
                                timeZone = TimeZone.currentSystemDefault(),
                            ),
                            finishedAt = if (segment.closed || isFinal) now else null,
                            source = ReasoningSource.THINK_TAG,
                            malformed = segment.malformed,
                            metadata = part.metadata,
                        )
                        if (!isDuplicateNativeReasoning(lastOrNull(), reasoning)) {
                            add(reasoning)
                        }
                    }
                }
            }
        }
    }
    if (!changed) return messages
    return messages.dropLast(1) + message.copy(parts = transformed)
}

private fun UIMessagePart.Text.shouldParseThinkTags(isFinal: Boolean): Boolean {
    if ("<think>" in text || "</think>" in text) return true
    if (isFinal) return false
    return THINK_TAG_PREFIXES.any(text::endsWith)
}

private fun isDuplicateNativeReasoning(
    previous: UIMessagePart?,
    candidate: UIMessagePart.Reasoning,
): Boolean = previous is UIMessagePart.Reasoning &&
    previous.source == ReasoningSource.PROVIDER_NATIVE &&
    previous.reasoning.trim() == candidate.reasoning.trim()

private val THINK_TAG_PREFIXES = buildSet {
    val tags = listOf("<think>", "</think>")
    tags.forEach { tag ->
        for (length in 1 until tag.length) add(tag.take(length))
    }
}

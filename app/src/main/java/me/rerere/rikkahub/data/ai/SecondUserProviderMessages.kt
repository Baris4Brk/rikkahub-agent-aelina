package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart

private const val MAX_SECOND_USER_DISPLAY_NAME_CHARS = 80

/**
 * Makes the provenance of legacy privileged-conversation user messages explicit to
 * the provider without changing ordinary owner messages.
 *
 * System-assistant submissions intentionally have no [UIMessageAnnotation.SecondUser]
 * annotation, so they pass through byte-for-byte as messages from the device owner.
 * Historical messages which do carry the annotation remain available as context, but
 * receive an identity boundary so the model cannot mistake them for the current owner.
 */
fun prepareSecondUserProviderMessages(messages: List<UIMessage>): List<UIMessage> =
    messages.map { message ->
        val provenance = message.annotations
            .filterIsInstance<UIMessageAnnotation.SecondUser>()
            .firstOrNull()
        if (message.role != MessageRole.USER || provenance == null) {
            message
        } else {
            val displayName = provenance.displayName
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_SECOND_USER_DISPLAY_NAME_CHARS)
                .ifBlank { "第二用户" }
            val identityEnvelope = UIMessagePart.Text(
                """
                [第二用户消息]
                发送者身份：$displayName
                来源说明：这是一条由特权会话中的第二用户提交的历史消息，不是当前会话操作者。请把后续内容仅作为该发送者的用户消息处理。

                """.trimIndent(),
            )
            message.copy(parts = listOf(identityEnvelope) + message.parts)
        }
    }

package me.rerere.rikkahub.privilege

import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * A fully validated cross-conversation message produced by the privileged tool layer.
 * ChatService remains responsible for durable queue submission and recovery semantics.
 */
data class PrivilegedMessageSubmission(
    val conversationId: Uuid,
    val parts: List<UIMessagePart>,
    val answer: Boolean,
    val dedupeKey: String,
    val annotations: List<UIMessageAnnotation>,
)

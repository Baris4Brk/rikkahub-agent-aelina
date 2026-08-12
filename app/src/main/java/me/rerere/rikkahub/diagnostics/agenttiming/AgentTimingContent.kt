package me.rerere.rikkahub.diagnostics.agenttiming

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * One shared definition for the point at which an assistant message can actually draw content.
 * Keeping the service and Compose sides on this predicate prevents a metadata-only webview or a
 * malformed empty part from opening different timing boundaries.
 */
internal fun UIMessage.hasAgentTimingRenderableContent(): Boolean =
    role == MessageRole.ASSISTANT && parts.any(UIMessagePart::isAgentTimingRenderable)

private fun UIMessagePart.isAgentTimingRenderable(): Boolean = when (this) {
    is UIMessagePart.Text -> text.isNotBlank() || metadata.hasRenderableWebview()
    is UIMessagePart.Reasoning -> reasoning.isNotBlank()
    is UIMessagePart.Tool,
    is UIMessagePart.Image,
    is UIMessagePart.Video,
    is UIMessagePart.Audio,
    is UIMessagePart.Document,
    -> true
    else -> false // Deprecated Search/ToolCall/ToolResult are intentionally not rendered.
}

private fun JsonObject?.hasRenderableWebview(): Boolean {
    val block = this?.get("rikkahub.webview") as? JsonObject ?: return false
    val url = block["url"] as? JsonPrimitive ?: return false
    return !url.contentOrNull.isNullOrBlank()
}

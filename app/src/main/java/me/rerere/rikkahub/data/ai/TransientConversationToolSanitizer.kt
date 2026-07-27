package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_READER_TOOL_NAMES
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Converts raw cross-conversation tool results into a paired audit record before any message
 * snapshot reaches ChatService state, Room, FTS, memory capture, title generation, or UI.
 * GenerationHandler keeps its own unsanitized list for the active provider loop.
 */
internal fun List<UIMessage>.sanitizeTransientConversationToolResults(): List<UIMessage> = map { message ->
    message.copy(parts = message.parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolName in TRANSIENT_CONVERSATION_READER_TOOL_NAMES) {
            part.copy(
                input = sanitizeTransientToolInput(part.input),
                output = if (part.output.isEmpty()) {
                    emptyList()
                } else {
                    listOf(UIMessagePart.Text(redactedConversationToolResult(part)))
                },
            )
        } else {
            part
        }
    })
}

private fun sanitizeTransientToolInput(raw: String): String {
    val parsed = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return "{}"
    return buildJsonObject {
        parsed.forEach { (key, value) ->
            if (key == "query") put(key, "[redacted after current task]") else put(key, value)
        }
    }.toString()
}

private fun redactedConversationToolResult(tool: UIMessagePart.Tool): String {
    val raw = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val parsed = runCatching { JsonInstant.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: JsonObject(emptyMap())
    fun string(name: String): String? = parsed[name]?.jsonPrimitive?.contentOrNull
    return buildJsonObject {
        put("ok", string("ok")?.toBooleanStrictOrNull() ?: false)
        put("code", string("code") ?: "REDACTED")
        put("operation", string("operation") ?: tool.toolName)
        string("source_conversation_id")?.let { put("source_conversation_id", it) }
        string("source_title")?.let { put("source_title", it.take(256)) }
        string("count")?.toIntOrNull()?.let { put("count", it) }
        string("character_count")?.toIntOrNull()?.let { put("character_count", it) }
        string("truncated")?.toBooleanStrictOrNull()?.let { put("truncated", it) }
        string("has_more")?.toBooleanStrictOrNull()?.let { put("has_more", it) }
        string("next_before_node_index")?.toIntOrNull()?.let { put("next_before_node_index", it) }
        put("transient", true)
        put("raw_content_saved", false)
        put("message", "Cross-conversation content was available only to the completed task and was not saved.")
    }.toString()
}

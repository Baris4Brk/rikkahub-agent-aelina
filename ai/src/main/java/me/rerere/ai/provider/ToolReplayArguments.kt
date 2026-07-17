package me.rerere.ai.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json

/**
 * Provider-safe representation of persisted tool arguments.
 *
 * This module is deliberately used only while replaying history to a model. The execution path
 * keeps the original [UIMessagePart.Tool.input] string and must parse it strictly before invoking
 * a tool, so this sentinel can never turn a truncated call into an executable empty object.
 */
internal data class ToolReplayArguments private constructor(
    val json: JsonElement,
    val serialized: String,
    val isValid: Boolean,
) {
    companion object {
        fun from(input: String): ToolReplayArguments {
            val normalizedInput = input.ifBlank { "{}" }
            val parsed = runCatching { json.parseToJsonElement(normalizedInput) }.getOrNull()
            val replayJson = parsed ?: INVALID_ARGUMENTS_SENTINEL
            return ToolReplayArguments(
                json = replayJson,
                serialized = replayJson.toString(),
                isValid = parsed != null,
            )
        }

        private val INVALID_ARGUMENTS_SENTINEL: JsonObject = buildJsonObject {
            put("invalid_tool_arguments", true)
            put("reason", "malformed_or_incomplete_json")
        }
    }
}

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val DSML_SIGNATURE = "｜｜DSML｜｜"
private const val TOOL_CALLS_OPEN = "<｜｜DSML｜｜tool_calls>"
private const val TOOL_CALLS_CLOSE = "</｜｜DSML｜｜tool_calls>"
private const val INVOKE_OPEN = "<｜｜DSML｜｜invoke"
private const val INVOKE_CLOSE = "</｜｜DSML｜｜invoke>"
private const val PARAMETER_OPEN = "<｜｜DSML｜｜parameter"
private const val PARAMETER_CLOSE = "</｜｜DSML｜｜parameter>"
private const val MAX_DSML_CHARS = 64 * 1024
private const val MAX_DSML_TOOL_CALLS = 20

private val ATTRIBUTE = Regex("([A-Za-z_][A-Za-z0-9_-]*)\\s*=\\s*\"([^\"]*)\"")
private val TOOL_OR_PARAMETER_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")
private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

internal data class DsmlToolCallRecovery(
    val message: UIMessage,
    val detected: Boolean,
    val malformed: Boolean,
    val recoveredTools: List<UIMessagePart.Tool>,
)

private data class ParsedDsmlTool(
    val name: String,
    val input: String,
)

private data class StartTag(
    val attributes: Map<String, String>,
    val endExclusive: Int,
)

private sealed interface DsmlTextRecovery {
    data class Success(
        val parts: List<UIMessagePart>,
        val tools: List<UIMessagePart.Tool>,
    ) : DsmlTextRecovery

    data object Malformed : DsmlTextRecovery
}

/**
 * Recover the DSML fallback emitted by some OpenAI-compatible models when they serialize a tool
 * call into `content` instead of the protocol's `tool_calls` field. Only a strict, bounded grammar
 * and currently exposed tool names are accepted. Malformed/unknown blocks are removed entirely so
 * protocol text is never persisted as a user-visible answer and the caller can request recovery.
 */
internal fun UIMessage.recoverDsmlToolCalls(
    allowedToolNames: Set<String>,
): DsmlToolCallRecovery {
    if (role != MessageRole.ASSISTANT || parts.none { part ->
            part is UIMessagePart.Text && DSML_SIGNATURE in part.text
        }
    ) {
        return DsmlToolCallRecovery(this, detected = false, malformed = false, recoveredTools = emptyList())
    }

    var recoveredIndex = 0
    val recoveredByPart = parts.map { part ->
        if (part !is UIMessagePart.Text || DSML_SIGNATURE !in part.text) return@map null
        recoverDsmlText(
            text = part.text,
            allowedToolNames = allowedToolNames,
            toolId = {
                "call_dsml_${id}_${recoveredIndex++}"
            },
        )
    }
    val malformed = recoveredByPart.any { it == DsmlTextRecovery.Malformed }
    if (malformed) {
        return DsmlToolCallRecovery(
            message = copy(parts = parts.filterNot { part ->
                part is UIMessagePart.Text && DSML_SIGNATURE in part.text
            }),
            detected = true,
            malformed = true,
            recoveredTools = emptyList(),
        )
    }

    val recoveredTools = recoveredByPart.filterIsInstance<DsmlTextRecovery.Success>()
        .flatMap(DsmlTextRecovery.Success::tools)
    val newParts = buildList {
        parts.forEachIndexed { index, part ->
            val recovered = recoveredByPart[index]
            if (recovered is DsmlTextRecovery.Success) addAll(recovered.parts) else add(part)
        }
    }
    return DsmlToolCallRecovery(
        message = copy(parts = newParts),
        detected = true,
        malformed = false,
        recoveredTools = recoveredTools,
    )
}

private fun recoverDsmlText(
    text: String,
    allowedToolNames: Set<String>,
    toolId: () -> String,
): DsmlTextRecovery {
    if (text.length > MAX_DSML_CHARS) return DsmlTextRecovery.Malformed
    val parts = mutableListOf<UIMessagePart>()
    val tools = mutableListOf<UIMessagePart.Tool>()
    var cursor = 0
    var recoveredAny = false
    while (cursor < text.length) {
        val start = text.indexOf(TOOL_CALLS_OPEN, cursor)
        if (start < 0) {
            if (DSML_SIGNATURE in text.substring(cursor)) return DsmlTextRecovery.Malformed
            text.substring(cursor).takeIf(String::isNotEmpty)?.let { parts += UIMessagePart.Text(it) }
            break
        }
        text.substring(cursor, start).takeIf(String::isNotEmpty)?.let { parts += UIMessagePart.Text(it) }
        val bodyStart = start + TOOL_CALLS_OPEN.length
        val end = text.indexOf(TOOL_CALLS_CLOSE, bodyStart)
        if (end < 0) return DsmlTextRecovery.Malformed
        val parsed = parseDsmlInvocations(text.substring(bodyStart, end), allowedToolNames)
            ?: return DsmlTextRecovery.Malformed
        if (parsed.isEmpty() || tools.size + parsed.size > MAX_DSML_TOOL_CALLS) {
            return DsmlTextRecovery.Malformed
        }
        parsed.forEach { call ->
            val tool = UIMessagePart.Tool(
                toolCallId = toolId(),
                toolName = call.name,
                input = call.input,
            )
            parts += tool
            tools += tool
        }
        recoveredAny = true
        cursor = end + TOOL_CALLS_CLOSE.length
    }
    return if (recoveredAny) {
        DsmlTextRecovery.Success(parts = parts, tools = tools)
    } else {
        DsmlTextRecovery.Malformed
    }
}

private fun parseDsmlInvocations(
    body: String,
    allowedToolNames: Set<String>,
): List<ParsedDsmlTool>? {
    val calls = mutableListOf<ParsedDsmlTool>()
    var cursor = 0
    while (true) {
        cursor = body.skipWhitespace(cursor)
        if (cursor == body.length) return calls
        val invoke = readStartTag(body, cursor, INVOKE_OPEN) ?: return null
        if (invoke.attributes.keys != setOf("name")) return null
        val toolName = invoke.attributes.getValue("name")
        if (!TOOL_OR_PARAMETER_NAME.matches(toolName) || toolName !in allowedToolNames) return null
        val invokeEnd = body.indexOf(INVOKE_CLOSE, invoke.endExclusive)
        if (invokeEnd < 0) return null
        val arguments = parseDsmlParameters(body.substring(invoke.endExclusive, invokeEnd)) ?: return null
        calls += ParsedDsmlTool(name = toolName, input = JsonObject(arguments).toString())
        if (calls.size > MAX_DSML_TOOL_CALLS) return null
        cursor = invokeEnd + INVOKE_CLOSE.length
    }
}

private fun parseDsmlParameters(body: String): Map<String, JsonElement>? {
    val parameters = linkedMapOf<String, JsonElement>()
    var cursor = 0
    while (true) {
        cursor = body.skipWhitespace(cursor)
        if (cursor == body.length) return parameters
        val parameter = readStartTag(body, cursor, PARAMETER_OPEN) ?: return null
        if (parameter.attributes.keys != setOf("name", "string")) return null
        val name = parameter.attributes.getValue("name")
        if (!TOOL_OR_PARAMETER_NAME.matches(name) || name in parameters) return null
        val stringValue = when (parameter.attributes.getValue("string")) {
            "true" -> true
            "false" -> false
            else -> return null
        }
        val parameterEnd = body.indexOf(PARAMETER_CLOSE, parameter.endExclusive)
        if (parameterEnd < 0) return null
        val rawValue = body.substring(parameter.endExclusive, parameterEnd)
        val value = if (stringValue) {
            JsonPrimitive(rawValue)
        } else {
            val parsed = runCatching { Json.parseToJsonElement(rawValue.trim()) }.getOrNull()
                ?: return null
            if (!parsed.isStrictJsonValue()) return null
            parsed
        }
        parameters[name] = value
        cursor = parameterEnd + PARAMETER_CLOSE.length
    }
}

/**
 * kotlinx.serialization deliberately accepts bare literals such as `not-json` as non-string
 * [JsonPrimitive] values. DSML `string="false"` parameters must remain strict JSON so a model
 * cannot smuggle an arbitrary token through the typed argument path.
 */
private fun JsonElement.isStrictJsonValue(): Boolean = when (this) {
    JsonNull -> true
    is JsonObject -> values.all(JsonElement::isStrictJsonValue)
    is JsonArray -> all(JsonElement::isStrictJsonValue)
    is JsonPrimitive -> isString || content == "true" || content == "false" || JSON_NUMBER.matches(content)
}

private fun readStartTag(text: String, start: Int, prefix: String): StartTag? {
    if (!text.startsWith(prefix, start)) return null
    val end = text.indexOf('>', start + prefix.length)
    if (end < 0) return null
    val rawAttributes = text.substring(start + prefix.length, end)
    val attributes = linkedMapOf<String, String>()
    var cursor = 0
    ATTRIBUTE.findAll(rawAttributes).forEach { match ->
        if (rawAttributes.substring(cursor, match.range.first).isNotBlank()) return null
        val name = match.groupValues[1]
        if (name in attributes) return null
        attributes[name] = match.groupValues[2]
        cursor = match.range.last + 1
    }
    if (rawAttributes.substring(cursor).isNotBlank()) return null
    return StartTag(attributes = attributes, endExclusive = end + 1)
}

private fun String.skipWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) index++
    return index
}

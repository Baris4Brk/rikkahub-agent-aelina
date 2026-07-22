package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryQueryRecord
import me.rerere.rikkahub.memory.MemoryWriteInput
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate

data class MemoryQueryInput(
    val query: String,
    val limit: Int = 8,
    val tags: Set<String> = emptySet(),
    val kind: MemoryKind? = null,
    val includeArchived: Boolean = false,
)

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (MemoryWriteInput) -> AssistantMemory,
    onUpdate: suspend (Int, MemoryWriteInput) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    onQuery: suspend (MemoryQueryInput) -> List<MemoryQueryRecord>,
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            Stores durable information across conversations. Actions: create, edit, delete.
            `delete` archives the record so the user can restore it from Memory Center.
            Existing calls that only provide `content` remain valid. Optional metadata:
            title, kind, tags, importance, expiresAtMs. Never store secrets or sensitive traits.
            Similar memories should be merged by editing an existing record.
            Today is ${LocalDate.now().toLocalString(true)}.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("create"); add("edit"); add("delete")
                        })
                    })
                    put("id", buildJsonObject { put("type", "integer") })
                    put("content", buildJsonObject { put("type", "string") })
                    put("title", buildJsonObject { put("type", "string") })
                    put("kind", memoryKindSchema())
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("importance", buildJsonObject {
                        put("type", "number"); put("minimum", 0); put("maximum", 1)
                    })
                    put("expiresAtMs", buildJsonObject { put("type", "integer") })
                },
                required = listOf("action"),
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull
                ?: error("action is required")
            val payload = when (action) {
                "create" -> json.encodeToJsonElement(
                    AssistantMemory.serializer(),
                    onCreation(params.toWriteInput()),
                )

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    json.encodeToJsonElement(
                        AssistantMemory.serializer(),
                        onUpdate(id, params.toWriteInput()),
                    )
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                        put("status", "archived")
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
    Tool(
        name = "memory_query",
        description = "Search the current assistant's full memory scope when injected Top-K memory is insufficient.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                    put("limit", buildJsonObject {
                        put("type", "integer"); put("minimum", 1); put("maximum", 20)
                    })
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("kind", memoryKindSchema())
                    put("includeArchived", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            val params = input.jsonObject
            val request = MemoryQueryInput(
                query = params["query"]?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf(String::isNotEmpty) ?: error("query is required"),
                limit = params["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 20) ?: 8,
                tags = params["tags"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                }?.toSet().orEmpty(),
                kind = params["kind"]?.jsonPrimitive?.contentOrNull?.toMemoryKindOrNull(),
                includeArchived = params["includeArchived"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
            val payload = buildJsonArray {
                onQuery(request).forEach { record ->
                    add(buildJsonObject {
                        put("id", record.id)
                        record.title?.let { put("title", it) }
                        put("content", record.content)
                        put("kind", record.kind.name.lowercase())
                        put("tags", buildJsonArray { record.tags.forEach(::add) })
                        put("source", record.sourceType)
                        put("updatedAt", record.updatedAtMs)
                        put("importance", record.importance)
                        put("score", record.score)
                        put("matchedTerms", buildJsonArray { record.matchedTerms.forEach(::add) })
                        put("reason", record.reason)
                    })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    ),
)

private fun Map<String, kotlinx.serialization.json.JsonElement>.toWriteInput(): MemoryWriteInput =
    MemoryWriteInput(
        title = get("title")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
        content = get("content")?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty) ?: error("content is required"),
        kind = get("kind")?.jsonPrimitive?.contentOrNull?.toMemoryKindOrNull(),
        tags = get("tags")?.jsonArray?.mapNotNull {
            it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        },
        importance = get("importance")?.jsonPrimitive?.floatOrNull,
        expiresAtMs = get("expiresAtMs")?.jsonPrimitive?.longOrNull,
    )

private fun memoryKindSchema() = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray {
        MemoryKind.entries.forEach { add(it.name.lowercase()) }
    })
}

private fun String.toMemoryKindOrNull(): MemoryKind? =
    MemoryKind.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

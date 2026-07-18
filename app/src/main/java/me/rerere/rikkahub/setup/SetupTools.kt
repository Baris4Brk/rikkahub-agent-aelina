package me.rerere.rikkahub.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import kotlin.uuid.Uuid

private val SETUP_CHANGE_TYPES = listOf(
    "assistant_chat_model",
    "assistant_workspace",
    "assistant_tool",
    "assistant_skills",
    "assistant_mcp_servers",
    "assistant_flag",
    "app_flag",
    "app_model",
)

fun createSetupTools(
    invocationContext: ToolInvocationContext,
    coordinator: SetupTransactionCoordinator,
): List<Tool> = listOf(
    Tool(
        name = "setup_plan",
        description = """
            Validate and snapshot 1-20 typed, non-secret RikkaHub configuration changes without
            writing anything. Supported P0 changes are Assistant model/workspace/tool/installed
            skill/existing MCP bindings, safe Assistant flags, and selected non-secret app flags
            or model bindings. Provider credentials, TTS/STT secrets, arbitrary settings keys,
            and installing resources are intentionally unsupported.
        """.trimIndent().replace("\n", " "),
        parameters = ::setupPlanSchema,
        execute = { input ->
            val owner = setupOwner(invocationContext)
                ?: return@Tool setupError(
                    "SETUP_LOCAL_PRIVILEGED_CHAT_REQUIRED",
                    "Setup is available only in the complete unlocked LocalChat privileged conversation.",
                )
            val parsed = parseSetupChanges(input)
            if (parsed is SetupChangesParse.Error) {
                return@Tool setupError(parsed.code, parsed.detail)
            }
            val changes = (parsed as SetupChangesParse.Value).changes
            encodeSetupResult(coordinator.plan(owner, changes))
        },
    ),
    Tool(
        name = "setup_apply",
        description = "Apply one owner-scoped setup plan with per-field CAS, targeted Doctor " +
            "checks, and reverse compensation on failure.",
        parameters = ::transactionIdSchema,
        needsApproval = { true },
        execute = { input ->
            val owner = setupOwner(invocationContext)
                ?: return@Tool setupError(
                    "SETUP_LOCAL_PRIVILEGED_CHAT_REQUIRED",
                    "Setup is available only in the complete unlocked LocalChat privileged conversation.",
                )
            val transactionId = transactionId(input)
                ?: return@Tool setupError("SETUP_TRANSACTION_ID_REQUIRED", "transaction_id is required")
            encodeSetupResult(coordinator.apply(owner, transactionId))
        },
    ),
    Tool(
        name = "setup_verify",
        description = "Re-run targeted read-only Doctor checks for every field and resource in " +
            "an owner-scoped setup plan.",
        parameters = ::transactionIdSchema,
        execute = { input ->
            val owner = setupOwner(invocationContext)
                ?: return@Tool setupError(
                    "SETUP_LOCAL_PRIVILEGED_CHAT_REQUIRED",
                    "Setup is available only in the complete unlocked LocalChat privileged conversation.",
                )
            val transactionId = transactionId(input)
                ?: return@Tool setupError("SETUP_TRANSACTION_ID_REQUIRED", "transaction_id is required")
            encodeSetupResult(coordinator.verify(owner, transactionId))
        },
    ),
)

/** Single policy seam used by ChatService before exposing setup schemas to a model. */
internal fun isSetupToolSurfaceAvailable(context: ToolInvocationContext): Boolean =
    setupOwner(context) != null

private fun setupPlanSchema(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("changes", buildJsonObject {
            put("type", "array")
            put("minItems", 1)
            put("maxItems", 20)
            put("items", buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("type", enumSchema(SETUP_CHANGE_TYPES))
                    put("assistant_id", stringSchema())
                    put("model_id", stringSchema("UUID; empty clears only clearable bindings"))
                    put("workspace_id", stringSchema("Existing workspace UUID; empty clears"))
                    put("tool_type", stringSchema("Serialized LocalToolOption type"))
                    put("enabled", buildJsonObject { put("type", "boolean") })
                    put("field", stringSchema("Named safe field for assistant_flag/app_flag/app_model"))
                    put("skill_names", stringArraySchema())
                    put("server_ids", stringArraySchema())
                })
                put("required", buildJsonArray { add(JsonPrimitive("type")) })
            })
        })
    },
    required = listOf("changes"),
)

private fun transactionIdSchema(): InputSchema = InputSchema.Obj(
    properties = buildJsonObject {
        put("transaction_id", stringSchema("Setup transaction UUID returned by setup_plan"))
    },
    required = listOf("transaction_id"),
)

private fun stringSchema(description: String? = null) = buildJsonObject {
    put("type", "string")
    description?.let { put("description", it) }
}

private fun stringArraySchema() = buildJsonObject {
    put("type", "array")
    put("maxItems", 100)
    put("items", stringSchema())
}

private fun enumSchema(values: List<String>) = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun setupOwner(context: ToolInvocationContext): SetupOwner? {
    val privilege = context.privilege ?: return null
    if (!privilege.isPrivileged || privilege.origin != ToolCallOrigin.LocalChat || context.isHeadless) {
        return null
    }
    if (context.callerAssistantId != null && context.callerAssistantId != privilege.assistantId.toString()) {
        return null
    }
    if (context.callerConversationId != null && context.callerConversationId != privilege.conversationId.toString()) {
        return null
    }
    return SetupOwner(privilege.assistantId.toString(), privilege.conversationId.toString())
}

private sealed interface SetupChangesParse {
    data class Value(val changes: List<SetupChange>) : SetupChangesParse
    data class Error(val code: String, val detail: String) : SetupChangesParse
}

private fun parseSetupChanges(input: kotlinx.serialization.json.JsonElement): SetupChangesParse {
    val root = input as? JsonObject
        ?: return SetupChangesParse.Error("SETUP_INVALID_INPUT", "expected an object")
    if ((root.keys - "changes").isNotEmpty()) {
        return SetupChangesParse.Error("P0_UNSUPPORTED_FIELD", "setup_plan accepts only changes")
    }
    val array = root["changes"] as? JsonArray
        ?: return SetupChangesParse.Error("SETUP_INVALID_INPUT", "changes must be an array")
    val changes = ArrayList<SetupChange>(array.size)
    array.forEachIndexed { index, element ->
        val obj = element as? JsonObject
            ?: return SetupChangesParse.Error("SETUP_INVALID_CHANGE", "change $index must be an object")
        val type = obj.string("type")?.trim()
            ?: return SetupChangesParse.Error("SETUP_INVALID_CHANGE", "change $index requires type")
        val allowed = allowedFields(type)
            ?: return SetupChangesParse.Error(
                "P0_UNSUPPORTED_CHANGE",
                "change type '$type' is outside the P0 typed setup allow-list",
            )
        if ((obj.keys - allowed).isNotEmpty()) {
            return SetupChangesParse.Error(
                "P0_UNSUPPORTED_FIELD",
                "change $index contains fields outside the '$type' allow-list",
            )
        }
        val change = runCatching { parseChange(type, obj) }.getOrElse { error ->
            return SetupChangesParse.Error(
                "SETUP_INVALID_CHANGE",
                error.message?.take(300) ?: "invalid change $index",
            )
        }
        changes += change
    }
    return SetupChangesParse.Value(changes)
}

private fun allowedFields(type: String): Set<String>? = when (type) {
    "assistant_chat_model" -> setOf("type", "assistant_id", "model_id")
    "assistant_workspace" -> setOf("type", "assistant_id", "workspace_id")
    "assistant_tool" -> setOf("type", "assistant_id", "tool_type", "enabled")
    "assistant_skills" -> setOf("type", "assistant_id", "skill_names")
    "assistant_mcp_servers" -> setOf("type", "assistant_id", "server_ids")
    "assistant_flag" -> setOf("type", "assistant_id", "field", "enabled")
    "app_flag" -> setOf("type", "field", "enabled")
    "app_model" -> setOf("type", "field", "model_id")
    else -> null
}

private fun parseChange(type: String, obj: JsonObject): SetupChange = when (type) {
    "assistant_chat_model" -> SetupChange.AssistantChatModel(
        obj.requiredUuid("assistant_id"),
        obj.requiredClearableUuid("model_id"),
    )
    "assistant_workspace" -> SetupChange.AssistantWorkspace(
        obj.requiredUuid("assistant_id"),
        obj.requiredClearableUuid("workspace_id"),
    )
    "assistant_tool" -> SetupChange.AssistantTool(
        obj.requiredUuid("assistant_id"),
        obj.requiredString("tool_type").take(100),
        obj.requiredBoolean("enabled"),
    )
    "assistant_skills" -> SetupChange.AssistantSkills(
        obj.requiredUuid("assistant_id"),
        obj.requiredStringSet("skill_names"),
    )
    "assistant_mcp_servers" -> SetupChange.AssistantMcpServers(
        obj.requiredUuid("assistant_id"),
        obj.requiredStringSet("server_ids").mapTo(linkedSetOf(), Uuid::parse),
    )
    "assistant_flag" -> SetupChange.AssistantFlag(
        obj.requiredUuid("assistant_id"),
        SetupAssistantFlag.entries.firstOrNull { it.wire == obj.requiredString("field") }
            ?: throw IllegalArgumentException("unsupported assistant flag"),
        obj.requiredBoolean("enabled"),
    )
    "app_flag" -> SetupChange.AppFlag(
        SetupAppFlag.entries.firstOrNull { it.wire == obj.requiredString("field") }
            ?: throw IllegalArgumentException("unsupported app flag"),
        obj.requiredBoolean("enabled"),
    )
    "app_model" -> SetupChange.AppModel(
        SetupAppModel.entries.firstOrNull { it.wire == obj.requiredString("field") }
            ?: throw IllegalArgumentException("unsupported app model field"),
        obj.requiredClearableUuid("model_id"),
    )
    else -> throw IllegalArgumentException("unsupported change")
}

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.requiredString(key: String): String =
    string(key)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("$key is required")

private fun JsonObject.requiredUuid(key: String): Uuid =
    Uuid.parse(requiredString(key))

private fun JsonObject.requiredClearableUuid(key: String): Uuid? {
    if (key !in this) throw IllegalArgumentException("$key is required")
    val raw = string(key)?.trim().orEmpty()
    return raw.takeIf { it.isNotEmpty() }?.let(Uuid::parse)
}

private fun JsonObject.requiredBoolean(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull
        ?: throw IllegalArgumentException("$key must be boolean")

private fun JsonObject.requiredStringSet(key: String): Set<String> {
    val array = this[key]?.jsonArray ?: throw IllegalArgumentException("$key must be an array")
    if (array.size > 100) throw IllegalArgumentException("$key may contain at most 100 items")
    return array.map { element ->
        element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
            ?: throw IllegalArgumentException("$key contains an invalid item")
    }.toSet()
}

private fun transactionId(input: kotlinx.serialization.json.JsonElement): String? {
    val obj = input as? JsonObject ?: return null
    if ((obj.keys - "transaction_id").isNotEmpty()) return null
    return obj.string("transaction_id")?.trim()?.takeIf { it.isNotEmpty() }
}

private fun setupError(code: String, detail: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("code", code)
        put("message", detail)
    }.toString()),
)

private fun encodeSetupResult(result: SetupOperationResult): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("ok", result.ok)
        put("code", result.code)
        put("message", result.message)
        result.transaction?.let { transaction ->
            put("transaction", buildJsonObject {
                put("id", transaction.id)
                put("status", transaction.status.name)
                transaction.lastErrorCode?.let { put("last_error_code", it) }
                put("steps", buildJsonArray {
                    transaction.steps.forEach { step ->
                        addJsonObject {
                            put("type", step.type)
                            put("summary", step.summary)
                            put("no_op", step.noOp)
                            put("status", step.status.name)
                            step.code?.let { put("code", it) }
                        }
                    }
                })
            })
        }
        if (result.checks.isNotEmpty()) {
            put("checks", buildJsonArray {
                result.checks.forEach { check ->
                    addJsonObject {
                        put("ok", check.ok)
                        put("code", check.code)
                        put("detail", check.detail.take(500))
                    }
                }
            })
        }
    }.toString()),
)

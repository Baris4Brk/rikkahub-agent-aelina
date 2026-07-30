package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.privilege.CollectionOperation
import me.rerere.rikkahub.privilege.HardDenyDecision
import me.rerere.rikkahub.privilege.HardDenyPolicy
import me.rerere.rikkahub.privilege.MutationOperation
import me.rerere.rikkahub.privilege.PrivilegedAction
import me.rerere.rikkahub.privilege.PrivilegedActionDecision
import me.rerere.rikkahub.privilege.PrivilegedActionGuard
import me.rerere.rikkahub.privilege.PrivilegedManagementBackend
import me.rerere.rikkahub.privilege.PrivilegedManagementRequest
import me.rerere.rikkahub.privilege.PrivilegedManagementResult
import kotlin.uuid.Uuid

private data class ManagementToolSpec(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String> = emptyList(),
)

fun createPrivilegedManagementTools(
    invocationContext: ToolInvocationContext,
    guard: PrivilegedActionGuard,
    backend: PrivilegedManagementBackend,
    /** Preferred shared permanent-denial layer; guard remains for compatible callers/tests. */
    hardDenyPolicy: HardDenyPolicy? = null,
): List<Tool> = managementToolSpecs().map { spec ->
    Tool(
        name = spec.name,
        description = spec.description,
        parameters = {
            InputSchema.Obj(
                properties = spec.properties,
                required = spec.required.takeIf { it.isNotEmpty() },
            )
        },
        execute = { input ->
            val context = invocationContext.privilege
                ?: return@Tool privilegedToolResult(
                    false,
                    "PRIVILEGED_SESSION_REQUIRED",
                    "This tool is only available in the configured privileged conversation.",
                )
            if (!context.isPrivileged) {
                return@Tool privilegedToolResult(
                    false,
                    "PRIVILEGED_SESSION_REQUIRED",
                    "This tool is only available in the configured privileged conversation.",
                )
            }
            val obj = input as? JsonObject
                ?: return@Tool privilegedToolResult(false, "INVALID_INPUT", "Expected a JSON object.")
            val parsed = parseManagementRequest(spec.name, obj)
            if (parsed is ParsedRequest.Error) {
                return@Tool privilegedToolResult(false, parsed.code, parsed.message)
            }
            val request = (parsed as ParsedRequest.Value).request
            val guardedAction = when (request) {
                is PrivilegedManagementRequest.ConversationDelete ->
                    PrivilegedAction.DeleteConversation(request.conversationId)
                else -> null
            }
            if (guardedAction != null) {
                val hardDeny = hardDenyPolicy?.checkPrivilegedAction(guardedAction, context)
                if (hardDeny is HardDenyDecision.Denied) {
                    return@Tool privilegedToolResult(
                        false,
                        hardDeny.code,
                        hardDeny.message,
                    )
                }
                when (val decision = guard.check(guardedAction, context)) {
                    PrivilegedActionDecision.Allowed -> Unit
                    is PrivilegedActionDecision.Denied -> return@Tool privilegedToolResult(
                        false,
                        decision.code,
                        decision.message,
                    )
                }
            }
            val result = backend.execute(request, context)
            privilegedToolResult(result.ok, result.code, result.message, result.data)
        },
    )
}

private fun managementToolSpecs(): List<ManagementToolSpec> = listOf(
    ManagementToolSpec(
        "rikkahub_state_get",
        "Read a redacted summary of RikkaHub assistants, conversations, lorebooks, mode injections, skills, MCP servers, workflows, and safe app settings. Secrets are never returned.",
        properties("section" to stringProperty("Optional section name; omit for all summaries")),
    ),
    ManagementToolSpec(
        "secret_vault_list",
        "List metadata for the current second-user secret slots. Secret values are never returned.",
        properties(),
    ),
    ManagementToolSpec(
        "secret_vault_create_slot",
        "Create an empty second-user secret slot. The user must enter the value in the biometric-protected app page.",
        properties(
            "slot_id" to stringProperty("Stable local slot ID, letters/numbers/dot/dash/underscore"),
            "label" to stringProperty("Safe human-readable label"),
            "purpose" to stringProperty("Safe purpose description; never put a secret here"),
        ),
        listOf("slot_id"),
    ),
    ManagementToolSpec(
        "secret_vault_set_binding",
        "Bind or unbind metadata for a typed local Provider, TTS, ASR, MCP, or Skill adapter. This never reads a value.",
        properties(
            "slot_id" to stringProperty("Existing secret slot ID"),
            "kind" to enumProperty("provider", "tts", "asr", "mcp", "skill"),
            "target_id" to stringProperty("Typed local adapter identifier"),
            "enabled" to booleanProperty("True binds; false unbinds"),
            "allow_pet_sidecar" to booleanProperty("Only valid for Provider or TTS bindings"),
        ),
        listOf("slot_id", "kind", "target_id", "enabled"),
    ),
    ManagementToolSpec(
        "secret_vault_test_binding",
        "Verify that a typed local binding can obtain its secret lease without exposing the value or making a network request.",
        properties(
            "slot_id" to stringProperty("Existing secret slot ID"),
            "kind" to enumProperty("provider", "tts", "asr", "mcp", "skill"),
            "target_id" to stringProperty("Typed local adapter identifier"),
        ),
        listOf("slot_id", "kind", "target_id"),
    ),
    ManagementToolSpec(
        "conversation_create",
        "Create a conversation owned by an existing assistant.",
        properties(
            "assistant_id" to stringProperty("Assistant UUID"),
            "title" to stringProperty("Conversation title, up to 200 characters"),
        ),
        listOf("assistant_id"),
    ),
    ManagementToolSpec(
        "conversation_update",
        "Update safe metadata of an existing conversation. It cannot change the owning assistant.",
        properties(
            "conversation_id" to stringProperty("Conversation UUID"),
            "title" to stringProperty("New title"),
            "pinned" to booleanProperty("Pinned state"),
            "custom_system_prompt" to stringProperty("Conversation-specific system prompt"),
        ),
        listOf("conversation_id"),
    ),
    ManagementToolSpec(
        "conversation_delete",
        "Delete a non-current conversation. The privileged conversation can never delete itself.",
        properties("conversation_id" to stringProperty("Conversation UUID")),
        listOf("conversation_id"),
    ),
    ManagementToolSpec(
        "assistant_update",
        "Update ordinary assistant behavior. Assistant identity, privileged-conversation settings, unrestricted mode, and safety settings are intentionally unavailable.",
        properties(
            "assistant_id" to stringProperty("Assistant UUID"),
            "name" to stringProperty("Display name"),
            "system_prompt" to stringProperty("System prompt"),
            "chat_model_id" to stringProperty("Model UUID; empty string clears the override"),
            "workspace_id" to stringProperty("Existing workspace UUID; empty string clears binding"),
            "enable_memory" to booleanProperty("Enable assistant memory"),
            "use_global_memory" to booleanProperty("Use global rather than assistant-scoped memory"),
            "enable_recent_chats_reference" to booleanProperty("Allow recent-chat reference"),
            "stream_output" to booleanProperty("Stream output"),
            "fast_path_router_enabled" to booleanProperty("Enable the conservative fast-path router"),
            "enable_web_search" to booleanProperty("Enable built-in web search for this assistant"),
        ),
        listOf("assistant_id"),
    ),
    ManagementToolSpec(
        "assistant_toggle_tool",
        "Enable or disable an implemented local-tool option for an assistant after validating the serialized tool type.",
        properties(
            "assistant_id" to stringProperty("Assistant UUID"),
            "tool_type" to stringProperty("LocalToolOption serialized type"),
            "enabled" to booleanProperty("Desired state"),
        ),
        listOf("assistant_id", "tool_type", "enabled"),
    ),
    ManagementToolSpec(
        "assistant_update_skills",
        "Add, remove, or replace an assistant's installed skill bindings.",
        properties(
            "assistant_id" to stringProperty("Assistant UUID"),
            "operation" to enumProperty("add", "remove", "replace"),
            "skill_names" to stringArrayProperty("Installed skill names"),
        ),
        listOf("assistant_id", "operation", "skill_names"),
    ),
    ManagementToolSpec(
        "assistant_update_mcp_servers",
        "Add, remove, or replace an assistant's MCP bindings. Only existing server IDs are accepted.",
        properties(
            "assistant_id" to stringProperty("Assistant UUID"),
            "operation" to enumProperty("add", "remove", "replace"),
            "server_ids" to stringArrayProperty("Existing MCP server UUIDs"),
        ),
        listOf("assistant_id", "operation", "server_ids"),
    ),
    ManagementToolSpec(
        "lorebook_create",
        "Create a lorebook and optionally seed its first keyword-triggered entry.",
        properties(
            "name" to stringProperty("Lorebook name"),
            "description" to stringProperty("Description"),
            "enabled" to booleanProperty("Enabled state"),
            "entry_content" to stringProperty("Optional first entry content"),
            "keywords" to stringArrayProperty("Optional first entry keywords"),
        ),
        listOf("name"),
    ),
    ManagementToolSpec(
        "lorebook_update",
        "Update lorebook metadata and, when supplied, its first entry content or keywords.",
        properties(
            "lorebook_id" to stringProperty("Lorebook UUID"),
            "name" to stringProperty("Name"),
            "description" to stringProperty("Description"),
            "enabled" to booleanProperty("Enabled state"),
            "entry_content" to stringProperty("First entry content"),
            "keywords" to stringArrayProperty("First entry keywords"),
        ),
        listOf("lorebook_id"),
    ),
    ManagementToolSpec(
        "lorebook_delete",
        "Delete a lorebook and remove its assistant bindings.",
        properties("lorebook_id" to stringProperty("Lorebook UUID")),
        listOf("lorebook_id"),
    ),
    ManagementToolSpec(
        "mode_injection_update",
        "Create, update, or delete a mode injection. Deleted IDs are removed from assistant bindings.",
        properties(
            "operation" to enumProperty("create", "update", "delete"),
            "injection_id" to stringProperty("Required for update/delete"),
            "name" to stringProperty("Name"),
            "content" to stringProperty("Injected prompt content"),
            "enabled" to booleanProperty("Enabled state"),
            "priority" to integerProperty("Priority"),
            "position" to enumProperty("before_system_prompt", "after_system_prompt", "top_of_chat", "bottom_of_chat", "at_depth"),
            "role" to enumProperty("user", "assistant"),
        ),
        listOf("operation"),
    ),
    ManagementToolSpec(
        "app_settings_update",
        "Update only the exposed non-secret app settings. Safety, emergency stop, credentials, YOLO, database, and backup internals are unavailable.",
        properties(
            "dynamic_color" to booleanProperty("Dynamic color"),
            "theme_id" to stringProperty("Existing theme identifier"),
            "developer_mode" to booleanProperty("Developer mode"),
            "enable_web_search" to booleanProperty(
                "Compatibility alias: built-in web search for the calling assistant",
            ),
            "chat_model_id" to stringProperty("Global chat model UUID"),
            "fast_model_id" to stringProperty("Fast model UUID"),
            "title_model_id" to stringProperty("Title model UUID; empty string clears it"),
            "enable_suggestion" to booleanProperty("Chat suggestions"),
            "suggestion_model_id" to stringProperty("Suggestion model UUID; empty string clears it"),
            "web_server_enabled" to booleanProperty("Web server enabled"),
            "web_server_port" to integerProperty("Port 1024..65535"),
            "web_server_jwt_enabled" to booleanProperty("JWT authentication enabled"),
            "web_server_localhost_only" to booleanProperty("Bind to localhost only"),
            "ai_log_level" to enumProperty("off", "info", "debug"),
        ),
    ),
)

private sealed interface ParsedRequest {
    data class Value(val request: PrivilegedManagementRequest) : ParsedRequest
    data class Error(val code: String, val message: String) : ParsedRequest
}

private fun parseManagementRequest(name: String, obj: JsonObject): ParsedRequest {
    fun uuid(key: String): Uuid? {
        val raw = obj.string(key)?.trim()
        if (raw.isNullOrEmpty()) return null
        return runCatching { Uuid.parse(raw) }
            .getOrElse { throw IllegalArgumentException("$key must be a UUID.") }
    }
    fun requiredUuid(key: String): Uuid = uuid(key)
        ?: throw IllegalArgumentException("$key must be a UUID.")
    fun collectionOperation(): CollectionOperation = when (obj.string("operation")?.lowercase()) {
        "add" -> CollectionOperation.ADD
        "remove" -> CollectionOperation.REMOVE
        "replace" -> CollectionOperation.REPLACE
        else -> throw IllegalArgumentException("operation must be add, remove, or replace.")
    }
    return try {
        val request = when (name) {
            "rikkahub_state_get" -> PrivilegedManagementRequest.StateGet(obj.string("section")?.trim())
            "secret_vault_list" -> PrivilegedManagementRequest.SecretVaultList
            "secret_vault_create_slot" -> PrivilegedManagementRequest.SecretVaultCreateSlot(
                slotId = obj.string("slot_id")?.trim()?.takeIf { it.isNotEmpty() }?.take(96)
                    ?: throw IllegalArgumentException("slot_id is required."),
                label = obj.string("label")?.trim()?.take(96).orEmpty(),
                purpose = obj.string("purpose")?.trim()?.take(160).orEmpty(),
            )
            "secret_vault_set_binding" -> PrivilegedManagementRequest.SecretVaultSetBinding(
                slotId = obj.string("slot_id")?.trim()?.takeIf { it.isNotEmpty() }?.take(96)
                    ?: throw IllegalArgumentException("slot_id is required."),
                kind = obj.string("kind")?.trim()?.uppercase()
                    ?: throw IllegalArgumentException("kind is required."),
                targetId = obj.string("target_id")?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
                    ?: throw IllegalArgumentException("target_id is required."),
                allowPetSidecar = obj.boolean("allow_pet_sidecar") ?: false,
                enabled = obj.boolean("enabled")
                    ?: throw IllegalArgumentException("enabled is required."),
            )
            "secret_vault_test_binding" -> PrivilegedManagementRequest.SecretVaultTestBinding(
                slotId = obj.string("slot_id")?.trim()?.takeIf { it.isNotEmpty() }?.take(96)
                    ?: throw IllegalArgumentException("slot_id is required."),
                kind = obj.string("kind")?.trim()?.uppercase()
                    ?: throw IllegalArgumentException("kind is required."),
                targetId = obj.string("target_id")?.trim()?.takeIf { it.isNotEmpty() }?.take(160)
                    ?: throw IllegalArgumentException("target_id is required."),
            )
            "conversation_create" -> PrivilegedManagementRequest.ConversationCreate(
                requiredUuid("assistant_id"), obj.string("title")?.trim().orEmpty().take(200)
            )
            "conversation_update" -> PrivilegedManagementRequest.ConversationUpdate(
                requiredUuid("conversation_id"),
                obj.string("title")?.trim()?.take(200),
                obj.boolean("pinned"),
                obj.string("custom_system_prompt")?.take(64 * 1024),
            )
            "conversation_delete" -> PrivilegedManagementRequest.ConversationDelete(requiredUuid("conversation_id"))
            "assistant_update" -> {
                val chatRaw = obj.string("chat_model_id")
                val workspaceRaw = obj.string("workspace_id")
                PrivilegedManagementRequest.AssistantUpdate(
                    assistantId = requiredUuid("assistant_id"),
                    name = obj.string("name")?.trim()?.take(200),
                    systemPrompt = obj.string("system_prompt")?.take(128 * 1024),
                    chatModelId = chatRaw?.takeIf { it.isNotBlank() }?.let { Uuid.parse(it.trim()) },
                    clearChatModel = chatRaw != null && chatRaw.isBlank(),
                    workspaceId = workspaceRaw?.takeIf { it.isNotBlank() }?.let { Uuid.parse(it.trim()) },
                    clearWorkspace = workspaceRaw != null && workspaceRaw.isBlank(),
                    enableMemory = obj.boolean("enable_memory"),
                    useGlobalMemory = obj.boolean("use_global_memory"),
                    enableRecentChatsReference = obj.boolean("enable_recent_chats_reference"),
                    streamOutput = obj.boolean("stream_output"),
                    fastPathRouterEnabled = obj.boolean("fast_path_router_enabled"),
                    enableWebSearch = obj.boolean("enable_web_search"),
                )
            }
            "assistant_toggle_tool" -> PrivilegedManagementRequest.AssistantToggleTool(
                requiredUuid("assistant_id"),
                obj.string("tool_type")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("tool_type is required."),
                obj.boolean("enabled") ?: throw IllegalArgumentException("enabled is required."),
            )
            "assistant_update_skills" -> PrivilegedManagementRequest.AssistantUpdateSkills(
                requiredUuid("assistant_id"), collectionOperation(), obj.stringSet("skill_names")
            )
            "assistant_update_mcp_servers" -> PrivilegedManagementRequest.AssistantUpdateMcpServers(
                requiredUuid("assistant_id"), collectionOperation(), obj.uuidSet("server_ids")
            )
            "lorebook_create" -> PrivilegedManagementRequest.LorebookCreate(
                name = obj.string("name")?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
                    ?: throw IllegalArgumentException("name is required."),
                description = obj.string("description")?.take(2000).orEmpty(),
                enabled = obj.boolean("enabled") ?: true,
                entryContent = obj.string("entry_content")?.take(64 * 1024),
                keywords = obj.stringList("keywords").take(100),
            )
            "lorebook_update" -> PrivilegedManagementRequest.LorebookUpdate(
                requiredUuid("lorebook_id"),
                obj.string("name")?.trim()?.take(200),
                obj.string("description")?.take(2000),
                obj.boolean("enabled"),
                obj.string("entry_content")?.take(64 * 1024),
                obj.arrayOrNull("keywords")?.mapNotNull { it.jsonPrimitive.contentOrNull?.take(200) }?.take(100),
            )
            "lorebook_delete" -> PrivilegedManagementRequest.LorebookDelete(requiredUuid("lorebook_id"))
            "mode_injection_update" -> {
                val operation = when (obj.string("operation")?.lowercase()) {
                    "create" -> MutationOperation.CREATE
                    "update" -> MutationOperation.UPDATE
                    "delete" -> MutationOperation.DELETE
                    else -> throw IllegalArgumentException("operation must be create, update, or delete.")
                }
                val id = uuid("injection_id")
                if (operation != MutationOperation.CREATE && id == null) {
                    throw IllegalArgumentException("injection_id is required for update/delete.")
                }
                PrivilegedManagementRequest.ModeInjectionUpdate(
                    operation, id, obj.string("name")?.trim()?.take(200),
                    obj.string("content")?.take(64 * 1024), obj.boolean("enabled"),
                    obj.int("priority")?.coerceIn(-10_000, 10_000),
                    obj.string("position")?.lowercase(), obj.string("role")?.lowercase(),
                )
            }
            "app_settings_update" -> {
                fun optionalModel(key: String): Pair<Uuid?, Boolean> {
                    val raw = obj.string(key) ?: return null to false
                    return if (raw.isBlank()) null to true else Uuid.parse(raw.trim()) to false
                }
                val title = optionalModel("title_model_id")
                val suggestion = optionalModel("suggestion_model_id")
                PrivilegedManagementRequest.AppSettingsUpdate(
                    dynamicColor = obj.boolean("dynamic_color"),
                    themeId = obj.string("theme_id")?.trim()?.take(200),
                    developerMode = obj.boolean("developer_mode"),
                    enableWebSearch = obj.boolean("enable_web_search"),
                    chatModelId = uuid("chat_model_id"),
                    fastModelId = uuid("fast_model_id"),
                    titleModelId = title.first,
                    clearTitleModel = title.second,
                    enableSuggestion = obj.boolean("enable_suggestion"),
                    suggestionModelId = suggestion.first,
                    clearSuggestionModel = suggestion.second,
                    webServerEnabled = obj.boolean("web_server_enabled"),
                    webServerPort = obj.int("web_server_port"),
                    webServerJwtEnabled = obj.boolean("web_server_jwt_enabled"),
                    webServerLocalhostOnly = obj.boolean("web_server_localhost_only"),
                    aiLogLevel = obj.string("ai_log_level")?.lowercase(),
                )
            }
            else -> throw IllegalArgumentException("Unknown management tool: $name")
        }
        ParsedRequest.Value(request)
    } catch (error: IllegalArgumentException) {
        ParsedRequest.Error("INVALID_ARGUMENT", error.message ?: "Invalid argument.")
    }
}

private fun properties(vararg values: Pair<String, JsonObject>) = buildJsonObject {
    values.forEach { (name, schema) -> put(name, schema) }
}

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string"); put("description", description)
}

private fun booleanProperty(description: String) = buildJsonObject {
    put("type", "boolean"); put("description", description)
}

private fun integerProperty(description: String) = buildJsonObject {
    put("type", "integer"); put("description", description)
}

private fun enumProperty(vararg values: String) = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) }))
}

private fun stringArrayProperty(description: String) = buildJsonObject {
    put("type", "array"); put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.arrayOrNull(key: String) = (this[key] as? JsonArray)
private fun JsonObject.stringList(key: String) = arrayOrNull(key)
    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.take(200) }
    .orEmpty()
private fun JsonObject.stringSet(key: String) = stringList(key).toSet()
private fun JsonObject.uuidSet(key: String) = stringList(key).map { raw ->
    runCatching { Uuid.parse(raw) }.getOrElse { throw IllegalArgumentException("$key contains an invalid UUID.") }
}.toSet()

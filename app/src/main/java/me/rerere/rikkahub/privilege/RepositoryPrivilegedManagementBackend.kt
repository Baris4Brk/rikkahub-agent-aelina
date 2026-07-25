package me.rerere.rikkahub.privilege

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.AiLogLevel
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.theme.findThemeById
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import kotlin.uuid.Uuid

/**
 * Repository-backed implementation of the privileged management seam. Every settings write
 * uses SettingsStore's mutex-protected transform API, while Room-owned data stays behind its
 * repository. The model-facing tool layer never receives these dependencies directly.
 */
class RepositoryPrivilegedManagementBackend(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val workflowRepository: WorkflowRepository,
    private val onConversationDeleted: (Uuid) -> Unit = {},
) : PrivilegedManagementBackend {
    override suspend fun execute(
        request: PrivilegedManagementRequest,
        context: PrivilegedSessionContext,
    ): PrivilegedManagementResult = try {
        when (request) {
            is PrivilegedManagementRequest.StateGet -> stateGet(request, context)
            is PrivilegedManagementRequest.ConversationCreate -> conversationCreate(request)
            is PrivilegedManagementRequest.ConversationUpdate -> conversationUpdate(request)
            is PrivilegedManagementRequest.ConversationDelete -> conversationDelete(request)
            is PrivilegedManagementRequest.AssistantUpdate -> assistantUpdate(request)
            is PrivilegedManagementRequest.AssistantToggleTool -> assistantToggleTool(request)
            is PrivilegedManagementRequest.AssistantUpdateSkills -> assistantUpdateSkills(request)
            is PrivilegedManagementRequest.AssistantUpdateMcpServers -> assistantUpdateMcpServers(request)
            is PrivilegedManagementRequest.LorebookCreate -> lorebookCreate(request)
            is PrivilegedManagementRequest.LorebookUpdate -> lorebookUpdate(request)
            is PrivilegedManagementRequest.LorebookDelete -> lorebookDelete(request)
            is PrivilegedManagementRequest.ModeInjectionUpdate -> modeInjectionUpdate(request)
            is PrivilegedManagementRequest.AppSettingsUpdate -> appSettingsUpdate(request, context)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IllegalArgumentException) {
        PrivilegedManagementResult.failure("INVALID_ARGUMENT", error.message ?: "Invalid argument.")
    } catch (error: Throwable) {
        PrivilegedManagementResult.failure(
            "OPERATION_FAILED",
            error.message?.take(500) ?: error::class.simpleName.orEmpty(),
        )
    }

    private suspend fun stateGet(
        request: PrivilegedManagementRequest.StateGet,
        context: PrivilegedSessionContext,
    ): PrivilegedManagementResult {
        val settings = settingsStore.settingsFlow.value
        val conversations = settings.assistants.flatMap { assistant ->
            conversationRepository.getRecentConversations(assistant.id, 100)
        }
        val section = request.section?.lowercase()?.takeIf { it.isNotBlank() }
        val all = buildJsonObject {
            put("privilege", buildJsonObject {
                put("assistant_id", context.assistantId.toString())
                put("conversation_id", context.conversationId.toString())
                put("identity_name", context.identityName)
                put("origin", context.origin.name)
                put("local_unrestricted", context.unrestrictedOverride)
            })
            put("assistants", buildJsonArray {
                settings.assistants.forEach { assistant ->
                    add(buildJsonObject {
                        put("id", assistant.id.toString())
                        put("name", assistant.name)
                        put("chat_model_id", assistant.chatModelId?.toString())
                        put("workspace_id", assistant.workspaceId?.toString())
                        put("tool_count", assistant.localTools.size)
                        put("skill_count", assistant.enabledSkills.size)
                        put("mcp_server_count", assistant.mcpServers.size)
                        put("enable_web_search", assistant.enableWebSearch)
                        put("privileged_conversation_id", assistant.privilegedConversationId?.toString())
                    })
                }
            })
            put("conversations", buildJsonArray {
                conversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("assistant_id", conversation.assistantId.toString())
                        put("title", conversation.title)
                        put("pinned", conversation.isPinned)
                        put("updated_at", conversation.updateAt.toString())
                    })
                }
            })
            put("lorebooks", buildJsonArray {
                settings.lorebooks.forEach { lorebook ->
                    add(buildJsonObject {
                        put("id", lorebook.id.toString())
                        put("name", lorebook.name)
                        put("description", lorebook.description)
                        put("enabled", lorebook.enabled)
                        put("entry_count", lorebook.entries.size)
                    })
                }
            })
            put("mode_injections", buildJsonArray {
                settings.modeInjections.forEach { injection ->
                    add(buildJsonObject {
                        put("id", injection.id.toString())
                        put("name", injection.name)
                        put("enabled", injection.enabled)
                        put("priority", injection.priority)
                        put("position", injection.position.name)
                        put("role", injection.role.name)
                    })
                }
            })
            put("skills", buildJsonArray {
                skillManager.listSkills().forEach { skill ->
                    add(buildJsonObject {
                        put("name", skill.name)
                        put("description", skill.description)
                        put("auto_load", skill.autoLoad)
                    })
                }
            })
            put("mcp_servers", buildJsonArray {
                settings.mcpServers.forEach { server ->
                    add(buildJsonObject {
                        put("id", server.id.toString())
                        put("name", server.commonOptions.name)
                        put("enabled", server.commonOptions.enable)
                        put("tool_count", server.commonOptions.tools.size)
                    })
                }
            })
            put("workflows", buildJsonArray {
                workflowRepository.listAll().forEach { workflow ->
                    add(buildJsonObject {
                        put("id", workflow.definition.id)
                        put("name", workflow.definition.name)
                        put("description", workflow.definition.description)
                        put("enabled", workflow.definition.enabled)
                        put("action_count", workflow.definition.actions.size)
                    })
                }
            })
            put("app_settings", safeAppSettings(settings))
        }
        val data = if (section == null) all else {
            all[section]?.let { value -> buildJsonObject { put(section, value) } }
                ?: return PrivilegedManagementResult.failure(
                    "UNKNOWN_SECTION",
                    "Unknown section. Use privilege, assistants, conversations, lorebooks, mode_injections, skills, mcp_servers, workflows, or app_settings.",
                )
        }
        return PrivilegedManagementResult.success("STATE", "Redacted state summary.", data)
    }

    private suspend fun conversationCreate(request: PrivilegedManagementRequest.ConversationCreate): PrivilegedManagementResult {
        val settings = settingsStore.settingsFlow.value
        if (settings.assistants.none { it.id == request.assistantId }) {
            return PrivilegedManagementResult.failure("ASSISTANT_NOT_FOUND", "The assistant does not exist.")
        }
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = request.assistantId,
            newConversation = true,
        ).copy(title = request.title)
        conversationRepository.insertConversation(conversation)
        return PrivilegedManagementResult.success(
            "CONVERSATION_CREATED",
            "Conversation created.",
            PrivilegedManagementResult.idData("conversation_id", conversation.id),
        )
    }

    private suspend fun conversationUpdate(request: PrivilegedManagementRequest.ConversationUpdate): PrivilegedManagementResult {
        val existing = conversationRepository.getConversationById(request.conversationId)
            ?: return PrivilegedManagementResult.failure("CONVERSATION_NOT_FOUND", "The conversation does not exist.")
        conversationRepository.updateConversation(
            existing.copy(
                title = request.title ?: existing.title,
                isPinned = request.pinned ?: existing.isPinned,
                customSystemPrompt = request.customSystemPrompt ?: existing.customSystemPrompt,
                updateAt = java.time.Instant.now(),
            )
        )
        return PrivilegedManagementResult.success("CONVERSATION_UPDATED", "Conversation updated.")
    }

    private suspend fun conversationDelete(request: PrivilegedManagementRequest.ConversationDelete): PrivilegedManagementResult {
        val existing = conversationRepository.getConversationById(request.conversationId)
            ?: return PrivilegedManagementResult.failure("CONVERSATION_NOT_FOUND", "The conversation does not exist.")
        // Stop and detach any live runtime before deleting its Room rows. Otherwise an
        // in-flight generation can race the deletion and persist the conversation again.
        onConversationDeleted(existing.id)
        conversationRepository.deleteConversation(existing)
        return PrivilegedManagementResult.success("CONVERSATION_DELETED", "Conversation deleted.")
    }

    private suspend fun assistantUpdate(request: PrivilegedManagementRequest.AssistantUpdate): PrivilegedManagementResult {
        val before = settingsStore.settingsFlow.value
        if (before.assistants.none { it.id == request.assistantId }) {
            return PrivilegedManagementResult.failure("ASSISTANT_NOT_FOUND", "The assistant does not exist.")
        }
        request.chatModelId?.let { id ->
            if (before.findModelById(id) == null) return PrivilegedManagementResult.failure("MODEL_NOT_FOUND", "chat_model_id does not exist.")
        }
        request.workspaceId?.let { id ->
            if (workspaceRepository.getById(id.toString()) == null) {
                return PrivilegedManagementResult.failure("WORKSPACE_NOT_FOUND", "workspace_id does not exist.")
            }
        }
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { assistant ->
                if (assistant.id != request.assistantId) assistant else assistant.copy(
                    name = request.name ?: assistant.name,
                    systemPrompt = request.systemPrompt ?: assistant.systemPrompt,
                    chatModelId = when {
                        request.clearChatModel -> null
                        request.chatModelId != null -> request.chatModelId
                        else -> assistant.chatModelId
                    },
                    workspaceId = when {
                        request.clearWorkspace -> null
                        request.workspaceId != null -> request.workspaceId
                        else -> assistant.workspaceId
                    },
                    enableMemory = request.enableMemory ?: assistant.enableMemory,
                    useGlobalMemory = request.useGlobalMemory ?: assistant.useGlobalMemory,
                    enableRecentChatsReference = request.enableRecentChatsReference ?: assistant.enableRecentChatsReference,
                    streamOutput = request.streamOutput ?: assistant.streamOutput,
                    fastPathRouterEnabled = request.fastPathRouterEnabled ?: assistant.fastPathRouterEnabled,
                    enableWebSearch = request.enableWebSearch ?: assistant.enableWebSearch,
                )
            })
        }
        return PrivilegedManagementResult.success("ASSISTANT_UPDATED", "Assistant updated.")
    }

    private suspend fun assistantToggleTool(request: PrivilegedManagementRequest.AssistantToggleTool): PrivilegedManagementResult {
        val option = localToolOptionsByType()[request.toolType]
            ?: return PrivilegedManagementResult.failure("TOOL_NOT_FOUND", "tool_type is not an implemented LocalToolOption.")
        var found = false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { assistant ->
                if (assistant.id != request.assistantId) assistant else {
                    found = true
                    val next = if (request.enabled) {
                        (assistant.localTools + option).distinct()
                    } else {
                        assistant.localTools - option
                    }
                    assistant.copy(localTools = next)
                }
            })
        }
        return if (found) PrivilegedManagementResult.success("ASSISTANT_TOOL_UPDATED", "Assistant tool binding updated.")
        else PrivilegedManagementResult.failure("ASSISTANT_NOT_FOUND", "The assistant does not exist.")
    }

    private suspend fun assistantUpdateSkills(request: PrivilegedManagementRequest.AssistantUpdateSkills): PrivilegedManagementResult {
        val installed = skillManager.listSkills().map { it.name }.toSet()
        val unknown = request.names - installed
        if (unknown.isNotEmpty()) return PrivilegedManagementResult.failure(
            "SKILL_NOT_FOUND", "Unknown installed skills: ${unknown.sorted().joinToString()}"
        )
        return updateAssistantSet(request.assistantId, "ASSISTANT_SKILLS_UPDATED") { assistant ->
            assistant.copy(enabledSkills = applyCollectionOperation(assistant.enabledSkills, request.names, request.operation))
        }
    }

    private suspend fun assistantUpdateMcpServers(request: PrivilegedManagementRequest.AssistantUpdateMcpServers): PrivilegedManagementResult {
        val existing = settingsStore.settingsFlow.value.mcpServers.map { it.id }.toSet()
        val unknown = request.serverIds - existing
        if (unknown.isNotEmpty()) return PrivilegedManagementResult.failure(
            "MCP_SERVER_NOT_FOUND", "Unknown MCP server IDs: ${unknown.joinToString()}"
        )
        return updateAssistantSet(request.assistantId, "ASSISTANT_MCP_UPDATED") { assistant ->
            assistant.copy(mcpServers = applyCollectionOperation(assistant.mcpServers, request.serverIds, request.operation))
        }
    }

    private suspend fun lorebookCreate(request: PrivilegedManagementRequest.LorebookCreate): PrivilegedManagementResult {
        val id = Uuid.random()
        val entries = request.entryContent?.let { content ->
            listOf(PromptInjection.RegexInjection(name = request.name, content = content, keywords = request.keywords))
        }.orEmpty()
        settingsStore.update { settings ->
            settings.copy(lorebooks = settings.lorebooks + Lorebook(
                id = id, name = request.name, description = request.description,
                enabled = request.enabled, entries = entries,
            ))
        }
        return PrivilegedManagementResult.success(
            "LOREBOOK_CREATED", "Lorebook created.", PrivilegedManagementResult.idData("lorebook_id", id)
        )
    }

    private suspend fun lorebookUpdate(request: PrivilegedManagementRequest.LorebookUpdate): PrivilegedManagementResult {
        var found = false
        settingsStore.update { settings ->
            settings.copy(lorebooks = settings.lorebooks.map { lorebook ->
                if (lorebook.id != request.lorebookId) lorebook else {
                    found = true
                    val entries = if (request.entryContent != null || request.keywords != null) {
                        val first = lorebook.entries.firstOrNull() ?: PromptInjection.RegexInjection(name = lorebook.name)
                        listOf(first.copy(
                            content = request.entryContent ?: first.content,
                            keywords = request.keywords ?: first.keywords,
                        )) + lorebook.entries.drop(1)
                    } else lorebook.entries
                    lorebook.copy(
                        name = request.name ?: lorebook.name,
                        description = request.description ?: lorebook.description,
                        enabled = request.enabled ?: lorebook.enabled,
                        entries = entries,
                    )
                }
            })
        }
        return if (found) PrivilegedManagementResult.success("LOREBOOK_UPDATED", "Lorebook updated.")
        else PrivilegedManagementResult.failure("LOREBOOK_NOT_FOUND", "The lorebook does not exist.")
    }

    private suspend fun lorebookDelete(request: PrivilegedManagementRequest.LorebookDelete): PrivilegedManagementResult {
        val before = settingsStore.settingsFlow.value
        if (before.lorebooks.none { it.id == request.lorebookId }) {
            return PrivilegedManagementResult.failure("LOREBOOK_NOT_FOUND", "The lorebook does not exist.")
        }
        settingsStore.update { settings ->
            settings.copy(
                lorebooks = settings.lorebooks.filterNot { it.id == request.lorebookId },
                assistants = settings.assistants.map { it.copy(lorebookIds = it.lorebookIds - request.lorebookId) },
            )
        }
        return PrivilegedManagementResult.success("LOREBOOK_DELETED", "Lorebook deleted and assistant bindings removed.")
    }

    private suspend fun modeInjectionUpdate(request: PrivilegedManagementRequest.ModeInjectionUpdate): PrivilegedManagementResult {
        val position = request.position?.let(::parsePosition)
        val role = request.role?.let(::parseRole)
        return when (request.operation) {
            MutationOperation.CREATE -> {
                val id = Uuid.random()
                settingsStore.update { settings ->
                    settings.copy(modeInjections = settings.modeInjections + PromptInjection.ModeInjection(
                        id = id,
                        name = request.name ?: "Mode",
                        content = request.content.orEmpty(),
                        enabled = request.enabled ?: true,
                        priority = request.priority ?: 0,
                        position = position ?: InjectionPosition.AFTER_SYSTEM_PROMPT,
                        role = role ?: MessageRole.USER,
                    ))
                }
                PrivilegedManagementResult.success(
                    "MODE_INJECTION_CREATED", "Mode injection created.",
                    PrivilegedManagementResult.idData("injection_id", id),
                )
            }
            MutationOperation.UPDATE -> {
                val id = requireNotNull(request.injectionId)
                var found = false
                settingsStore.update { settings ->
                    settings.copy(modeInjections = settings.modeInjections.map { injection ->
                        if (injection.id != id) injection else {
                            found = true
                            injection.copy(
                                name = request.name ?: injection.name,
                                content = request.content ?: injection.content,
                                enabled = request.enabled ?: injection.enabled,
                                priority = request.priority ?: injection.priority,
                                position = position ?: injection.position,
                                role = role ?: injection.role,
                            )
                        }
                    })
                }
                if (found) PrivilegedManagementResult.success("MODE_INJECTION_UPDATED", "Mode injection updated.")
                else PrivilegedManagementResult.failure("MODE_INJECTION_NOT_FOUND", "The mode injection does not exist.")
            }
            MutationOperation.DELETE -> {
                val id = requireNotNull(request.injectionId)
                val before = settingsStore.settingsFlow.value
                if (before.modeInjections.none { it.id == id }) {
                    PrivilegedManagementResult.failure("MODE_INJECTION_NOT_FOUND", "The mode injection does not exist.")
                } else {
                    settingsStore.update { settings ->
                        settings.copy(
                            modeInjections = settings.modeInjections.filterNot { it.id == id },
                            assistants = settings.assistants.map { it.copy(modeInjectionIds = it.modeInjectionIds - id) },
                        )
                    }
                    PrivilegedManagementResult.success("MODE_INJECTION_DELETED", "Mode injection deleted and assistant bindings removed.")
                }
            }
        }
    }

    private suspend fun appSettingsUpdate(
        request: PrivilegedManagementRequest.AppSettingsUpdate,
        context: PrivilegedSessionContext,
    ): PrivilegedManagementResult {
        val before = settingsStore.settingsFlow.value
        if (request.enableWebSearch != null && before.assistants.none { it.id == context.assistantId }) {
            return PrivilegedManagementResult.failure(
                "ASSISTANT_NOT_FOUND",
                "The calling assistant does not exist.",
            )
        }
        listOfNotNull(request.chatModelId, request.fastModelId, request.titleModelId, request.suggestionModelId).forEach { id ->
            if (before.findModelById(id) == null) return PrivilegedManagementResult.failure("MODEL_NOT_FOUND", "Model $id does not exist.")
        }
        request.themeId?.let { id ->
            if (findThemeById(id, before.customThemes) == null) return PrivilegedManagementResult.failure("THEME_NOT_FOUND", "theme_id does not exist.")
        }
        request.webServerPort?.let { port ->
            if (port !in 1024..65535) return PrivilegedManagementResult.failure("INVALID_PORT", "web_server_port must be 1024..65535.")
        }
        if (request.aiLogLevel != null && request.aiLogLevel !in setOf("off", "info", "debug")) {
            return PrivilegedManagementResult.failure("INVALID_LOG_LEVEL", "ai_log_level must be off, info, or debug.")
        }
        val logLevel = request.aiLogLevel?.let(AiLogLevel::fromPreference)
        settingsStore.update { settings ->
            val updated = settings.copy(
                dynamicColor = request.dynamicColor ?: settings.dynamicColor,
                themeId = request.themeId ?: settings.themeId,
                developerMode = request.developerMode ?: settings.developerMode,
                chatModelId = request.chatModelId ?: settings.chatModelId,
                fastModelId = request.fastModelId ?: settings.fastModelId,
                titleModelId = when {
                    request.clearTitleModel -> null
                    request.titleModelId != null -> request.titleModelId
                    else -> settings.titleModelId
                },
                enableSuggestion = request.enableSuggestion ?: settings.enableSuggestion,
                suggestionModelId = when {
                    request.clearSuggestionModel -> null
                    request.suggestionModelId != null -> request.suggestionModelId
                    else -> settings.suggestionModelId
                },
                webServerEnabled = request.webServerEnabled ?: settings.webServerEnabled,
                webServerPort = request.webServerPort ?: settings.webServerPort,
                webServerJwtEnabled = request.webServerJwtEnabled ?: settings.webServerJwtEnabled,
                // Do not let an assistant re-enable plaintext LAN exposure through the
                // management surface. Paired HTTPS will own this setting in a later phase.
                webServerLocalhostOnly = true,
                aiLogLevel = logLevel ?: settings.aiLogLevel,
            )
            request.enableWebSearch?.let { enabled ->
                updated.withAssistantWebSearch(context.assistantId, enabled)
            } ?: updated
        }
        return PrivilegedManagementResult.success("APP_SETTINGS_UPDATED", "Allowed app settings updated.")
    }

    private suspend fun updateAssistantSet(
        assistantId: Uuid,
        code: String,
        transform: (me.rerere.rikkahub.data.model.Assistant) -> me.rerere.rikkahub.data.model.Assistant,
    ): PrivilegedManagementResult {
        var found = false
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { assistant ->
                if (assistant.id != assistantId) assistant else transform(assistant).also { found = true }
            })
        }
        return if (found) PrivilegedManagementResult.success(code, "Assistant bindings updated.")
        else PrivilegedManagementResult.failure("ASSISTANT_NOT_FOUND", "The assistant does not exist.")
    }

    private fun <T> applyCollectionOperation(current: Set<T>, requested: Set<T>, operation: CollectionOperation): Set<T> =
        when (operation) {
            CollectionOperation.ADD -> current + requested
            CollectionOperation.REMOVE -> current - requested
            CollectionOperation.REPLACE -> requested
        }

    private fun localToolOptionsByType(): Map<String, LocalToolOption> =
        LocalToolOption.PRIVILEGED_IMPLEMENTED.associateBy { option ->
            JsonInstant.encodeToJsonElement(LocalToolOption.serializer(), option)
                .jsonObject.getValue("type").jsonPrimitive.content
        }

    private fun parsePosition(raw: String): InjectionPosition = when (raw.lowercase()) {
        "before_system_prompt" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        "after_system_prompt" -> InjectionPosition.AFTER_SYSTEM_PROMPT
        "top_of_chat" -> InjectionPosition.TOP_OF_CHAT
        "bottom_of_chat" -> InjectionPosition.BOTTOM_OF_CHAT
        "at_depth" -> InjectionPosition.AT_DEPTH
        else -> throw IllegalArgumentException("Unknown injection position: $raw")
    }

    private fun parseRole(raw: String): MessageRole = when (raw.lowercase()) {
        "user" -> MessageRole.USER
        "assistant" -> MessageRole.ASSISTANT
        else -> throw IllegalArgumentException("role must be user or assistant.")
    }

    private fun safeAppSettings(settings: Settings) = buildJsonObject {
        put("dynamic_color", settings.dynamicColor)
        put("theme_id", settings.themeId)
        put("developer_mode", settings.developerMode)
        put("chat_model_id", settings.chatModelId.toString())
        put("fast_model_id", settings.fastModelId.toString())
        put("title_model_id", settings.titleModelId?.toString())
        put("enable_suggestion", settings.enableSuggestion)
        put("suggestion_model_id", settings.suggestionModelId?.toString())
        put("web_server_enabled", settings.webServerEnabled)
        put("web_server_port", settings.webServerPort)
        put("web_server_jwt_enabled", settings.webServerJwtEnabled)
        put("web_server_localhost_only", settings.webServerLocalhostOnly)
        put("ai_log_level", settings.aiLogLevel.preferenceName)
    }
}

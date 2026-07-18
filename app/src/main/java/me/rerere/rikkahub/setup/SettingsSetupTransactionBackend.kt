package me.rerere.rikkahub.setup

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Atomic configuration seam used by the setup transaction module. */
interface SetupConfigurationStore {
    suspend fun snapshot(): Settings

    suspend fun updateIf(
        predicate: (Settings) -> Boolean,
        transform: (Settings) -> Settings,
    ): Boolean
}

interface SetupResourceCatalog {
    suspend fun workspaceExists(id: Uuid): Boolean
    suspend fun installedSkillNames(): Set<String>
}

class SettingsStoreSetupConfigurationStore(
    private val settingsStore: SettingsStore,
) : SetupConfigurationStore {
    override suspend fun snapshot(): Settings = settingsStore.settingsFlow.value

    override suspend fun updateIf(
        predicate: (Settings) -> Boolean,
        transform: (Settings) -> Settings,
    ): Boolean {
        var applied = false
        settingsStore.update { current ->
            if (predicate(current)) {
                applied = true
                transform(current)
            } else {
                current
            }
        }
        return applied
    }
}

class RepositorySetupResourceCatalog(
    private val workspaceRepository: WorkspaceRepository,
    private val skillManager: SkillManager,
) : SetupResourceCatalog {
    override suspend fun workspaceExists(id: Uuid): Boolean =
        workspaceRepository.getById(id.toString()) != null

    override suspend fun installedSkillNames(): Set<String> =
        skillManager.listSkills().mapTo(linkedSetOf()) { it.name }
}

/**
 * Typed Settings adapter. It never accepts a settings key or a secret-bearing value: every read,
 * write, validation, and rollback target is selected by a sealed [SetupChange].
 */
class SettingsSetupTransactionBackend(
    private val configurationStore: SetupConfigurationStore,
    private val resources: SetupResourceCatalog,
) : SetupTransactionBackend {
    override suspend fun prepare(change: SetupChange): SetupPrepareResult {
        val settings = configurationStore.snapshot()
        if (settings.init) {
            return SetupPrepareResult.Rejected("SETTINGS_NOT_READY", "settings are still loading")
        }
        validate(settings, change)?.let { return it }
        val before = readValue(settings, change)
            ?: return SetupPrepareResult.Rejected("SETUP_FIELD_NOT_FOUND", change.key)
        return SetupPrepareResult.Prepared(
            SetupPreparedChange(
                change = change,
                key = change.key,
                type = change.type,
                summary = safeSummary(change),
                before = before,
                after = targetValue(change),
            ),
        )
    }

    override suspend fun compareAndSet(
        change: SetupPreparedChange,
        expected: SetupValue,
        update: SetupValue,
    ): SetupCasResult {
        val applied = configurationStore.updateIf(
            predicate = { current -> readValue(current, change.change) == expected },
            transform = { current -> writeValue(current, change.change, update) },
        )
        return if (applied) SetupCasResult.Applied else SetupCasResult.Conflict
    }

    override suspend fun doctor(change: SetupPreparedChange): SetupDoctorCheck {
        val settings = configurationStore.snapshot()
        if (readValue(settings, change.change) != change.after) {
            return SetupDoctorCheck(
                key = change.key,
                ok = false,
                code = "VALUE_MISMATCH",
                detail = "The current field value no longer matches the planned target.",
            )
        }
        val validation = validate(settings, change.change)
        return if (validation == null) {
            SetupDoctorCheck(change.key, true, "READY", "Field and referenced resources are ready.")
        } else {
            SetupDoctorCheck(change.key, false, validation.code, validation.detail)
        }
    }

    private suspend fun validate(
        settings: Settings,
        change: SetupChange,
    ): SetupPrepareResult.Rejected? {
        change.assistantIdOrNull()?.let { assistantId ->
            if (settings.assistants.none { it.id == assistantId }) {
                return SetupPrepareResult.Rejected("ASSISTANT_NOT_FOUND", "assistant does not exist")
            }
        }
        when (change) {
            is SetupChange.AssistantChatModel -> change.modelId?.let { modelId ->
                validateModel(settings, modelId)?.let { return it }
            }
            is SetupChange.AssistantWorkspace -> change.workspaceId?.let { workspaceId ->
                if (!resources.workspaceExists(workspaceId)) {
                    return SetupPrepareResult.Rejected("WORKSPACE_NOT_FOUND", "workspace does not exist")
                }
            }
            is SetupChange.AssistantTool -> if (toolOptionsByType()[change.toolType] == null) {
                return SetupPrepareResult.Rejected("TOOL_NOT_FOUND", "tool_type is not implemented")
            }
            is SetupChange.AssistantSkills -> {
                val unknown = change.names - resources.installedSkillNames()
                if (unknown.isNotEmpty()) {
                    return SetupPrepareResult.Rejected(
                        "SKILL_NOT_FOUND",
                        "one or more requested skills are not installed",
                    )
                }
            }
            is SetupChange.AssistantMcpServers -> {
                val existing = settings.mcpServers.mapTo(hashSetOf()) { it.id }
                if ((change.serverIds - existing).isNotEmpty()) {
                    return SetupPrepareResult.Rejected(
                        "MCP_SERVER_NOT_FOUND",
                        "one or more requested MCP servers do not exist",
                    )
                }
            }
            is SetupChange.AppModel -> {
                if (change.modelId == null && !change.model.clearable) {
                    return SetupPrepareResult.Rejected(
                        "MODEL_REQUIRED",
                        "${change.model.wire} cannot be cleared",
                    )
                }
                change.modelId?.let { modelId ->
                    validateModel(settings, modelId)?.let { return it }
                }
            }
            is SetupChange.AssistantFlag,
            is SetupChange.AppFlag -> Unit
        }
        return null
    }

    private fun validateModel(settings: Settings, modelId: Uuid): SetupPrepareResult.Rejected? {
        val model = settings.findModelById(modelId)
            ?: return SetupPrepareResult.Rejected("MODEL_NOT_FOUND", "model does not exist")
        val provider = model.findProvider(settings.providers)
            ?: return SetupPrepareResult.Rejected("MODEL_PROVIDER_NOT_FOUND", "model provider does not exist")
        return if (provider.enabled) {
            null
        } else {
            SetupPrepareResult.Rejected("MODEL_PROVIDER_DISABLED", "model provider is disabled")
        }
    }

    private fun readValue(settings: Settings, change: SetupChange): SetupValue? = when (change) {
        is SetupChange.AssistantFlag -> settings.assistant(change.assistantId)?.let { assistant ->
            SetupValue.Bool(when (change.flag) {
                SetupAssistantFlag.ENABLE_MEMORY -> assistant.enableMemory
                SetupAssistantFlag.USE_GLOBAL_MEMORY -> assistant.useGlobalMemory
                SetupAssistantFlag.ENABLE_RECENT_CHATS_REFERENCE -> assistant.enableRecentChatsReference
                SetupAssistantFlag.STREAM_OUTPUT -> assistant.streamOutput
                SetupAssistantFlag.FAST_PATH_ROUTER_ENABLED -> assistant.fastPathRouterEnabled
                SetupAssistantFlag.ENABLE_WEB_SEARCH -> assistant.enableWebSearch
            })
        }
        is SetupChange.AssistantWorkspace ->
            settings.assistant(change.assistantId)?.let { SetupValue.Id(it.workspaceId) }
        is SetupChange.AssistantChatModel ->
            settings.assistant(change.assistantId)?.let { SetupValue.Id(it.chatModelId) }
        is SetupChange.AssistantTool -> settings.assistant(change.assistantId)?.let { assistant ->
            toolOptionsByType()[change.toolType]?.let { option ->
                SetupValue.Bool(option in assistant.localTools)
            }
        }
        is SetupChange.AssistantSkills ->
            settings.assistant(change.assistantId)?.let { SetupValue.Names(it.enabledSkills) }
        is SetupChange.AssistantMcpServers ->
            settings.assistant(change.assistantId)?.let { SetupValue.Ids(it.mcpServers) }
        is SetupChange.AppFlag -> SetupValue.Bool(when (change.flag) {
            SetupAppFlag.DYNAMIC_COLOR -> settings.dynamicColor
            SetupAppFlag.DEVELOPER_MODE -> settings.developerMode
            SetupAppFlag.ENABLE_SUGGESTION -> settings.enableSuggestion
        })
        is SetupChange.AppModel -> SetupValue.Id(when (change.model) {
            SetupAppModel.CHAT_MODEL -> settings.chatModelId
            SetupAppModel.FAST_MODEL -> settings.fastModelId
            SetupAppModel.TITLE_MODEL -> settings.titleModelId
            SetupAppModel.SUGGESTION_MODEL -> settings.suggestionModelId
        })
    }

    private fun targetValue(change: SetupChange): SetupValue = when (change) {
        is SetupChange.AssistantFlag -> SetupValue.Bool(change.enabled)
        is SetupChange.AssistantWorkspace -> SetupValue.Id(change.workspaceId)
        is SetupChange.AssistantChatModel -> SetupValue.Id(change.modelId)
        is SetupChange.AssistantTool -> SetupValue.Bool(change.enabled)
        is SetupChange.AssistantSkills -> SetupValue.Names(change.names.toSet())
        is SetupChange.AssistantMcpServers -> SetupValue.Ids(change.serverIds.toSet())
        is SetupChange.AppFlag -> SetupValue.Bool(change.enabled)
        is SetupChange.AppModel -> SetupValue.Id(change.modelId)
    }

    private fun writeValue(
        settings: Settings,
        change: SetupChange,
        value: SetupValue,
    ): Settings = when (change) {
        is SetupChange.AssistantFlag -> settings.updateAssistant(change.assistantId) { assistant ->
            val enabled = (value as SetupValue.Bool).value
            when (change.flag) {
                SetupAssistantFlag.ENABLE_MEMORY -> assistant.copy(enableMemory = enabled)
                SetupAssistantFlag.USE_GLOBAL_MEMORY -> assistant.copy(useGlobalMemory = enabled)
                SetupAssistantFlag.ENABLE_RECENT_CHATS_REFERENCE ->
                    assistant.copy(enableRecentChatsReference = enabled)
                SetupAssistantFlag.STREAM_OUTPUT -> assistant.copy(streamOutput = enabled)
                SetupAssistantFlag.FAST_PATH_ROUTER_ENABLED ->
                    assistant.copy(fastPathRouterEnabled = enabled)
                SetupAssistantFlag.ENABLE_WEB_SEARCH -> assistant.copy(enableWebSearch = enabled)
            }
        }
        is SetupChange.AssistantWorkspace -> settings.updateAssistant(change.assistantId) {
            it.copy(workspaceId = (value as SetupValue.Id).value)
        }
        is SetupChange.AssistantChatModel -> settings.updateAssistant(change.assistantId) {
            it.copy(chatModelId = (value as SetupValue.Id).value)
        }
        is SetupChange.AssistantTool -> settings.updateAssistant(change.assistantId) { assistant ->
            val option = requireNotNull(toolOptionsByType()[change.toolType])
            val enabled = (value as SetupValue.Bool).value
            assistant.copy(
                localTools = if (enabled) {
                    (assistant.localTools + option).distinct()
                } else {
                    assistant.localTools - option
                },
            )
        }
        is SetupChange.AssistantSkills -> settings.updateAssistant(change.assistantId) {
            it.copy(enabledSkills = (value as SetupValue.Names).value)
        }
        is SetupChange.AssistantMcpServers -> settings.updateAssistant(change.assistantId) {
            it.copy(mcpServers = (value as SetupValue.Ids).value)
        }
        is SetupChange.AppFlag -> {
            val enabled = (value as SetupValue.Bool).value
            when (change.flag) {
                SetupAppFlag.DYNAMIC_COLOR -> settings.copy(dynamicColor = enabled)
                SetupAppFlag.DEVELOPER_MODE -> settings.copy(developerMode = enabled)
                SetupAppFlag.ENABLE_SUGGESTION -> settings.copy(enableSuggestion = enabled)
            }
        }
        is SetupChange.AppModel -> {
            val id = (value as SetupValue.Id).value
            when (change.model) {
                SetupAppModel.CHAT_MODEL -> settings.copy(chatModelId = requireNotNull(id))
                SetupAppModel.FAST_MODEL -> settings.copy(fastModelId = requireNotNull(id))
                SetupAppModel.TITLE_MODEL -> settings.copy(titleModelId = id)
                SetupAppModel.SUGGESTION_MODEL -> settings.copy(suggestionModelId = id)
            }
        }
    }

    private fun safeSummary(change: SetupChange): String = when (change) {
        is SetupChange.AssistantChatModel -> "Update Assistant chat model binding."
        is SetupChange.AssistantWorkspace -> "Update Assistant workspace binding."
        is SetupChange.AssistantTool -> "Update one Assistant local-tool toggle."
        is SetupChange.AssistantSkills -> "Replace Assistant bindings to installed skills."
        is SetupChange.AssistantMcpServers -> "Replace Assistant bindings to existing MCP servers."
        is SetupChange.AssistantFlag -> "Update one safe Assistant behavior flag."
        is SetupChange.AppFlag -> "Update one non-secret app flag."
        is SetupChange.AppModel -> "Update one non-secret global model binding."
    }

    private fun toolOptionsByType(): Map<String, LocalToolOption> = TOOL_OPTIONS_BY_TYPE
}

private val TOOL_OPTIONS_BY_TYPE: Map<String, LocalToolOption> by lazy {
    LocalToolOption.PRIVILEGED_IMPLEMENTED.associateBy { option ->
        JsonInstant.encodeToJsonElement(LocalToolOption.serializer(), option)
            .jsonObject.getValue("type").jsonPrimitive.content
    }
}

private fun Settings.assistant(id: Uuid) = assistants.firstOrNull { it.id == id }

private fun Settings.updateAssistant(
    id: Uuid,
    transform: (me.rerere.rikkahub.data.model.Assistant) -> me.rerere.rikkahub.data.model.Assistant,
): Settings = copy(
    assistants = assistants.map { assistant ->
        if (assistant.id == id) transform(assistant) else assistant
    },
)

private fun SetupChange.assistantIdOrNull(): Uuid? = when (this) {
    is SetupChange.AssistantFlag -> assistantId
    is SetupChange.AssistantWorkspace -> assistantId
    is SetupChange.AssistantChatModel -> assistantId
    is SetupChange.AssistantTool -> assistantId
    is SetupChange.AssistantSkills -> assistantId
    is SetupChange.AssistantMcpServers -> assistantId
    is SetupChange.AppFlag,
    is SetupChange.AppModel -> null
}

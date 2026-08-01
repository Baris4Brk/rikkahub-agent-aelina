package me.rerere.rikkahub.owner

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.asr.ASRProviderSetting
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.owner.db.HostOperationDao
import me.rerere.rikkahub.pet.PetDialogueRepository
import me.rerere.rikkahub.pet.PetOverlaySelection
import me.rerere.rikkahub.plugin.InstalledPluginRecord
import me.rerere.rikkahub.plugin.PluginRegistryStore
import me.rerere.rikkahub.plugin.PluginReviewStatus
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.quickcapture.QuickCaptureAreaMode
import me.rerere.rikkahub.quickcapture.QuickCaptureBackendPreference
import me.rerere.rikkahub.quickcapture.QuickCaptureBubbleEdge
import me.rerere.rikkahub.quickcapture.QuickCaptureTargetMode
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * Shared Settings/registry facade for stable application domains which previously only had UI
 * mutation paths. Both model-facing actions and future ViewModels can use these same typed rules.
 */
class OwnerApplicationControlHandler(
    private val settingsStore: SettingsStore,
    private val plugins: PluginRegistryStore,
    private val safety: AgentSafetySettings,
    private val operations: HostOperationDao,
    private val memories: MemoryRepository,
    private val vault: SecondUserSecretVault,
    private val petDialogues: PetDialogueRepository,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        action.type in ACTION_FIELDS && OwnerActionRegistry.action(request.family, action.type) != null

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val fields = ACTION_FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Action is not handled by the application-control facade.")
        val unknown = action.arguments.keys - fields
        if (unknown.isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "Unsupported fields: ${unknown.sorted().joinToString()}.")
        }
        if (action.arguments.keys.any { it.lowercase() in SECRET_FIELDS }) {
            return invalid("OWNER_SECRET_ARGUMENT_FORBIDDEN", "Use a Vault slot reference instead of a secret value.")
        }
        if (action.type == "safety_emergency_stop_activate" && request.actions.size != 1) {
            return invalid("OWNER_EMERGENCY_STOP_MUST_BE_SINGLE", "Emergency Stop activation must be the only action in its call.")
        }
        return OwnerActionValidation(true, "OWNER_ACTION_VALID", "Action validated by the shared domain facade.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "run_list" -> runList(index, action)
            "run_get" -> runGet(index, action)
            "run_cancel", "run_retry" -> needsUserAction(index, action, "Open the target conversation to control its live Provider request.")
            "quick_capture_get" -> quickCaptureGet(index, action)
            "quick_capture_update" -> settingsMutation(index, action) { current ->
                val before = current.quickCaptureSettings
                current.copy(quickCaptureSettings = before.copy(
                    enabled = action.arguments.boolean("enabled") ?: before.enabled,
                    targetMode = action.arguments.enum("target_mode", before.targetMode),
                    fixedAssistantId = action.arguments.uuid("fixed_assistant_id") ?: before.fixedAssistantId,
                    prompt = action.arguments.string("prompt")?.take(4_096) ?: before.prompt,
                    autoSend = action.arguments.boolean("auto_send") ?: before.autoSend,
                    backend = action.arguments.enum("backend", before.backend),
                    areaMode = action.arguments.enum("area_mode", before.areaMode),
                    bubbleSizeDp = action.arguments.int("bubble_size_dp") ?: before.bubbleSizeDp,
                    bubbleOpacity = action.arguments.float("bubble_opacity") ?: before.bubbleOpacity,
                    bubbleEdge = action.arguments.enum("bubble_edge", before.bubbleEdge),
                    bubbleYFraction = action.arguments.float("bubble_y_fraction") ?: before.bubbleYFraction,
                ).normalized())
            }
            "quick_capture_trigger" -> needsUserAction(index, action, "Quick Capture requires the trusted Android capture surface and any missing system permission.")
            "plugin_list" -> pluginList(index, action)
            "plugin_runtime_set" -> settingsMutation(index, action) { current ->
                current.copy(pluginRuntimeEnabled = action.arguments.boolean("enabled") ?: current.pluginRuntimeEnabled)
            }
            "plugin_approve" -> pluginMutation(index, action) { record ->
                record.copy(
                    enabled = false,
                    reviewStatus = PluginReviewStatus.APPROVED,
                    pendingAddedPermissions = emptySet(),
                    failureTimestampsMs = emptyList(),
                )
            }
            "plugin_set_enabled" -> pluginMutation(index, action) { record ->
                val enabled = action.arguments.boolean("enabled") ?: record.enabled
                require(!enabled || record.reviewStatus == PluginReviewStatus.APPROVED) { "plugin_not_approved" }
                record.copy(enabled = enabled)
            }
            "plugin_bind" -> pluginBind(index, action)
            "plugin_install_managed", "plugin_uninstall" -> needsUserAction(index, action, "The package file must be resolved and verified by the private package installer.")
            "memory_list" -> memoryList(index, request, action)
            "memory_configure_assistant" -> memoryConfigure(index, action)
            "memory_delete" -> memoryDelete(index, action)
            "prompt_library_list", "lorebook_list" -> promptLibraryList(index, action)
            "prompt_injection_upsert" -> promptInjectionUpsert(index, action)
            "prompt_injection_delete" -> promptInjectionDelete(index, action)
            "quick_message_create" -> quickMessageCreate(index, action)
            "quick_message_update" -> quickMessageUpdate(index, action)
            "quick_message_delete" -> quickMessageDelete(index, action)
            "lorebook_upsert" -> lorebookUpsert(index, action)
            "lorebook_delete" -> lorebookDelete(index, action)
            "asr_list" -> asrList(index, action)
            "asr_create" -> asrCreate(index, request, action)
            "asr_update" -> asrUpdate(index, request, action)
            "asr_delete" -> asrDelete(index, request, action)
            "asr_set_default" -> asrSetDefault(index, action)
            "channel_get" -> channelGet(index, action)
            "web_channel_update" -> settingsMutation(index, action) { current ->
                current.copy(
                    webServerEnabled = action.arguments.boolean("enabled") ?: current.webServerEnabled,
                    webServerPort = (action.arguments.int("port") ?: current.webServerPort).coerceIn(1_024, 65_535),
                    webServerJwtEnabled = action.arguments.boolean("jwt_enabled") ?: current.webServerJwtEnabled,
                    webServerLocalhostOnly = action.arguments.boolean("localhost_only") ?: current.webServerLocalhostOnly,
                )
            }
            "telegram_channel_update" -> needsUserAction(index, action, "Telegram credentials must be bound through the Vault-backed Telegram settings adapter.")
            "search_get" -> searchGet(index, action)
            "search_set_enabled" -> settingsMutation(index, action) { current ->
                current.copy(enableWebSearch = action.arguments.boolean("enabled") ?: current.enableWebSearch)
            }
            "search_select" -> settingsMutation(index, action) { current ->
                current.copy(searchServiceSelected = (action.arguments.int("index") ?: current.searchServiceSelected)
                    .coerceIn(0, maxOf(0, current.searchServices.lastIndex)))
            }
            "backup_storage_get" -> backupGet(index, action)
            "backup_local_export", "backup_restore_preserving_owner" -> needsUserAction(
                index, action, "A local archive URI must be selected through the Android document surface before this operation can continue.",
            )
            "app_settings_get", "runtime_get" -> appSettingsGet(index, action)
            "app_settings_update", "runtime_update" -> appSettingsUpdate(index, action)
            "app_display_update" -> displayUpdate(index, action)
            "runtime_permissions_open" -> needsUserAction(index, action, "Android permissions can only be granted on the system permission surface.")
            "safety_get" -> safetyGet(index, action)
            "safety_capabilities_update" -> safetyUpdate(index, action)
            "safety_emergency_stop_activate" -> needsUserAction(index, action, "Use the trusted Emergency Stop surface so every running backend is stopped together.")
            "pet_list" -> petGet(index, action)
            "pet_dialogue_state" -> petDialogueState(index, request, action)
            "pet_select" -> petSelect(index, request, action)
            "pet_configure" -> petConfigure(index, request, action)
            "pet_import_managed", "pet_delete" -> needsUserAction(index, action, "The private pet-package repository must validate and atomically switch the package first.")
            else -> failure(index, action, "OWNER_ACTION_UNSUPPORTED", "Action is not implemented by the application-control facade.")
        }
    }.getOrElse { failure(index, action, it.safeCode(), "The typed application-control action failed.") }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val receipt = applied.compensationReceipt
        return when (receipt) {
            is ControlReceipt.SettingsChanged -> if (settingsStore.settingsFlow.value != receipt.before) {
                OwnerActionValidation(true, "OWNER_STATE_VERIFIED", "Settings were read back after the mutation.")
            } else invalid("OWNER_VERIFY_FAILED", "Settings did not change after the requested mutation.")
            is ControlReceipt.PluginChanged -> if (plugins.get(receipt.before.id) != receipt.before) {
                OwnerActionValidation(true, "OWNER_STATE_VERIFIED", "Plugin registry was read back after the mutation.")
            } else invalid("OWNER_VERIFY_FAILED", "Plugin registry did not change.")
            is ControlReceipt.SafetyChanged -> if (safetySnapshot() != receipt.before) {
                OwnerActionValidation(true, "OWNER_STATE_VERIFIED", "Safety capability settings were read back.")
            } else invalid("OWNER_VERIFY_FAILED", "Safety capability settings did not change.")
            is ControlReceipt.MemoryArchived -> if (memories.getMemoryEntity(receipt.memoryId)?.lifecycleStatus == "ARCHIVED") {
                OwnerActionValidation(true, "OWNER_STATE_VERIFIED", "Memory archive state was read back.")
            } else invalid("OWNER_VERIFY_FAILED", "Memory did not enter the archived state.")
            is ControlReceipt.AsrChanged -> {
                val settings = settingsStore.settingsFlow.value
                val changed = settings.asrProviders != receipt.beforeProviders ||
                    settings.selectedASRProviderId != receipt.beforeSelected ||
                    snapshotAsrBindings(receipt.asrId, receipt.subjectId) != receipt.beforeBindings
                if (changed) OwnerActionValidation(true, "ASR_STATE_VERIFIED", "ASR profile and Vault bindings were read back.")
                else invalid("OWNER_VERIFY_FAILED", "ASR state did not change.")
            }
            else -> OwnerActionValidation(true, "OWNER_READ_VERIFIED", "The requested state was read from its authoritative source.")
        }
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = runCatching {
        when (val receipt = applied.compensationReceipt) {
            null -> OwnerCompensationResult(action.risk == OwnerOperationRisk.READ_ONLY, "NO_MUTATION_TO_RESTORE")
            is ControlReceipt.SettingsChanged -> {
                settingsStore.update { current -> restoreSettingsFields(action.type, current, receipt.before) }
                OwnerCompensationResult(true, "SETTINGS_FIELDS_RESTORED")
            }
            is ControlReceipt.PluginChanged -> {
                plugins.upsert(receipt.before)
                OwnerCompensationResult(true, "PLUGIN_RECORD_RESTORED")
            }
            is ControlReceipt.SafetyChanged -> {
                restoreSafety(receipt.before)
                OwnerCompensationResult(true, "SAFETY_CAPABILITIES_RESTORED")
            }
            is ControlReceipt.MemoryArchived -> {
                memories.restoreMemory(receipt.memoryId)
                OwnerCompensationResult(true, "MEMORY_RESTORED")
            }
            is ControlReceipt.AsrChanged -> {
                settingsStore.update { current -> current.copy(
                    asrProviders = receipt.beforeProviders,
                    selectedASRProviderId = receipt.beforeSelected,
                ) }
                restoreAsrBindings(receipt.asrId, receipt.subjectId, receipt.beforeBindings)
                OwnerCompensationResult(true, "ASR_STATE_RESTORED")
            }
            else -> OwnerCompensationResult(false, "UNKNOWN_COMPENSATION_RECEIPT")
        }
    }.getOrElse { OwnerCompensationResult(false, "OWNER_COMPENSATION_FAILED") }

    private suspend fun runList(index: Int, action: OwnerAction): OwnerAppliedAction {
        val limit = (action.arguments.int("limit") ?: 20).coerceIn(1, 100)
        val rows = operations.observeRecent(limit).first()
        return success(index, action, "OWNER_RUNS_LISTED", "Recent Owner operations read.", buildJsonObject {
            put("items", buildJsonArray { rows.forEach { row -> add(buildJsonObject {
                put("request_id", row.requestId); put("family", row.toolFamily); put("state", row.state)
                put("updated_at_ms", row.updatedAtMs); row.resultCode?.let { put("result_code", it) }
            }) } })
        })
    }

    private suspend fun runGet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("request_id") ?: return failure(index, action, "REQUEST_ID_REQUIRED", "request_id is required.")
        val row = operations.get(id) ?: return failure(index, action, "RUN_NOT_FOUND", "Owner operation was not found.")
        return success(index, action, "OWNER_RUN_READ", "Owner operation read.", buildJsonObject {
            put("request_id", row.requestId); put("family", row.toolFamily); put("state", row.state)
            put("updated_at_ms", row.updatedAtMs); row.resultCode?.let { put("result_code", it) }
        })
    }

    private fun quickCaptureGet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val value = settingsStore.settingsFlow.value.quickCaptureSettings
        return success(index, action, "QUICK_CAPTURE_READ", "Quick Capture settings read.", buildJsonObject {
            put("enabled", value.enabled); put("target_mode", value.targetMode.name); put("auto_send", value.autoSend)
            put("backend", value.backend.name); put("area_mode", value.areaMode.name); put("bubble_size_dp", value.bubbleSizeDp)
        })
    }

    private fun pluginList(index: Int, action: OwnerAction): OwnerAppliedAction = success(
        index, action, "PLUGINS_LISTED", "Plugin registry read.", buildJsonObject {
            put("runtime_enabled", settingsStore.settingsFlow.value.pluginRuntimeEnabled)
            put("items", buildJsonArray { plugins.snapshot().forEach { plugin -> add(buildJsonObject {
                put("plugin_id", plugin.id); put("name", plugin.name.take(160)); put("version", plugin.version.take(80))
                put("enabled", plugin.enabled); put("review", plugin.reviewStatus.name)
            }) } })
        },
    )

    private fun pluginMutation(index: Int, action: OwnerAction, transform: (InstalledPluginRecord) -> InstalledPluginRecord): OwnerAppliedAction {
        val id = action.arguments.string("plugin_id") ?: return failure(index, action, "PLUGIN_ID_REQUIRED", "plugin_id is required.")
        val before = plugins.get(id) ?: return failure(index, action, "PLUGIN_NOT_FOUND", "Plugin does not exist.")
        val after = transform(before)
        if (after == before) {
            return success(index, action, "PLUGIN_ALREADY_CONFIGURED", "Plugin already matches the requested state.")
        }
        plugins.update(id) { after }
        return success(index, action, "PLUGIN_UPDATED", "Plugin registry updated.", receipt = ControlReceipt.PluginChanged(before))
    }

    private suspend fun pluginBind(index: Int, action: OwnerAction): OwnerAppliedAction {
        val pluginId = action.arguments.string("plugin_id") ?: return failure(index, action, "PLUGIN_ID_REQUIRED", "plugin_id is required.")
        if (plugins.get(pluginId) == null) return failure(index, action, "PLUGIN_NOT_FOUND", "Plugin does not exist.")
        val assistantId = action.arguments.uuid("assistant_id") ?: return failure(index, action, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        val enabled = action.arguments.boolean("enabled") ?: true
        return settingsMutation(index, action) { current -> current.copy(assistants = current.assistants.map { assistant ->
            if (assistant.id != assistantId) assistant else assistant.copy(enabledPluginIds = if (enabled) {
                assistant.enabledPluginIds + pluginId
            } else assistant.enabledPluginIds - pluginId)
        }) }
    }

    private suspend fun memoryList(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        val id = action.arguments.uuid("assistant_id")
            ?: runCatching { Uuid.parse(request.assistantId) }.getOrNull()
            ?: return failure(index, action, "ASSISTANT_ID_INVALID", "Owner assistant ID is invalid.")
        val assistants = settings.assistants.filter { it.id == id }
        if (assistants.isEmpty()) return failure(index, action, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        val limit = (action.arguments.int("limit") ?: 20).coerceIn(1, 100)
        val records = memories.getMemoriesOfAssistant(id.toString()).take(limit)
        return success(index, action, "MEMORY_SETTINGS_LISTED", "Assistant memory settings read.", buildJsonObject {
            put("assistant", buildJsonArray { assistants.forEach { assistant ->
                add(buildJsonObject { put("assistant_id", assistant.id.toString()); put("enabled", assistant.enableMemory)
                    put("use_global", assistant.useGlobalMemory); put("recent_chats_reference", assistant.enableRecentChatsReference) })
            } })
            put("items", buildJsonArray { records.forEach { memory -> add(buildJsonObject {
                put("memory_id", memory.id); put("title", memory.title?.take(240).orEmpty())
                put("content", memory.content.take(1_200)); put("kind", memory.kind.name)
            }) } })
        })
    }

    private suspend fun memoryDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.int("memory_id")
            ?: return failure(index, action, "MEMORY_ID_REQUIRED", "memory_id is required.")
        val before = memories.getMemoryEntity(id)
            ?: return failure(index, action, "MEMORY_NOT_FOUND", "Memory record does not exist.")
        if (before.lifecycleStatus == "ARCHIVED") {
            return success(index, action, "MEMORY_ALREADY_ARCHIVED", "Memory is already archived.")
        }
        memories.deleteMemory(id)
        return success(index, action, "MEMORY_ARCHIVED", "Memory was revision-safely archived.", receipt = ControlReceipt.MemoryArchived(id))
    }

    private suspend fun memoryConfigure(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("assistant_id") ?: return failure(index, action, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        if (settingsStore.settingsFlow.value.assistants.none { it.id == id }) return failure(index, action, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        return settingsMutation(index, action) { current -> current.copy(assistants = current.assistants.map { assistant ->
            if (assistant.id != id) assistant else assistant.copy(
                enableMemory = action.arguments.boolean("enabled") ?: assistant.enableMemory,
                useGlobalMemory = action.arguments.boolean("use_global") ?: assistant.useGlobalMemory,
                enableRecentChatsReference = action.arguments.boolean("recent_chats_reference") ?: assistant.enableRecentChatsReference,
            )
        }) }
    }

    private fun promptLibraryList(index: Int, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, action, "PROMPT_LIBRARY_LISTED", "Prompt library read.", buildJsonObject {
            put("quick_messages", buildJsonArray { settings.quickMessages.take(100).forEach { item -> add(buildJsonObject {
                put("message_id", item.id.toString()); put("title", item.title.take(160)); put("content_preview", item.content.take(240))
            }) } })
            put("mode_injections", buildJsonArray { settings.modeInjections.take(100).forEach { item -> add(buildJsonObject {
                put("injection_id", item.id.toString()); put("name", item.name.take(160)); put("enabled", item.enabled)
                put("priority", item.priority); put("position", item.position.name); put("role", item.role.name)
                put("content_length", item.content.length)
            }) } })
            put("lorebooks", buildJsonArray { settings.lorebooks.take(100).forEach { item -> add(buildJsonObject {
                put("lorebook_id", item.id.toString()); put("name", item.name.take(160)); put("enabled", item.enabled)
                put("entry_count", item.entries.size)
            }) } })
        })
    }

    private suspend fun promptInjectionUpsert(index: Int, action: OwnerAction): OwnerAppliedAction {
        val definition = action.arguments["definition"] as? JsonObject
            ?: return failure(index, action, "PROMPT_DEFINITION_REQUIRED", "definition must be an object.")
        val unknown = definition.keys - MODE_INJECTION_FIELDS
        if (unknown.isNotEmpty()) {
            return failure(index, action, "PROMPT_DEFINITION_FIELD_INVALID", "Unsupported prompt definition fields: ${unknown.sorted().joinToString()}.")
        }
        if (definition.string("type")?.equals("mode", ignoreCase = true) == false) {
            return failure(index, action, "PROMPT_DEFINITION_TYPE_INVALID", "Prompt injection type must be mode.")
        }
        validateModeInjectionWire(definition)?.let { issue ->
            return failure(index, action, issue.code, issue.message)
        }
        val parsed = runCatching {
            JsonInstant.decodeFromJsonElement<PromptInjection.ModeInjection>(definition)
        }.getOrNull() ?: return failure(
            index,
            action,
            "PROMPT_DEFINITION_INVALID",
            "Prompt injection could not be decoded. Use camelCase injectDepth, serialized position values, and role=user or assistant.",
        )
        if (!validModeInjection(parsed)) {
            return failure(index, action, "PROMPT_DEFINITION_LIMIT", "Prompt injection exceeds safe field limits.")
        }
        val exists = settingsStore.settingsFlow.value.modeInjections.any { it.id == parsed.id }
        return settingsMutation(index, action, buildJsonObject { put("injection_id", parsed.id.toString()) }) { current ->
            current.copy(modeInjections = if (exists) {
                current.modeInjections.map { if (it.id == parsed.id) parsed else it }
            } else current.modeInjections + parsed)
        }
    }

    private suspend fun promptInjectionDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("injection_id")
            ?: return failure(index, action, "PROMPT_INJECTION_ID_REQUIRED", "injection_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.modeInjections.none { it.id == id }) {
            return failure(index, action, "PROMPT_INJECTION_NOT_FOUND", "Prompt injection does not exist.")
        }
        return settingsMutation(index, action) { current -> current.copy(
            modeInjections = current.modeInjections.filterNot { it.id == id },
            assistants = current.assistants.map { it.copy(modeInjectionIds = it.modeInjectionIds - id) },
        ) }
    }

    private suspend fun lorebookUpsert(index: Int, action: OwnerAction): OwnerAppliedAction {
        val definition = action.arguments["definition"] as? JsonObject
            ?: return failure(index, action, "LOREBOOK_DEFINITION_REQUIRED", "definition must be an object.")
        val unknown = definition.keys - LOREBOOK_FIELDS
        if (unknown.isNotEmpty()) {
            return failure(index, action, "LOREBOOK_DEFINITION_FIELD_INVALID", "Unsupported lorebook fields: ${unknown.sorted().joinToString()}.")
        }
        val entries = definition["entries"] as? JsonArray
        if (entries != null && entries.any { entry ->
                entry !is JsonObject ||
                    (entry.keys - REGEX_INJECTION_FIELDS).isNotEmpty() ||
                    entry.string("type")?.equals("regex", ignoreCase = true) == false
            }
        ) {
            return failure(index, action, "LOREBOOK_ENTRY_FIELD_INVALID", "Lorebook entry contains unsupported fields.")
        }
        val parsed = runCatching { JsonInstant.decodeFromJsonElement<Lorebook>(definition) }.getOrNull()
            ?: return failure(index, action, "LOREBOOK_DEFINITION_INVALID", "Lorebook definition is invalid.")
        if (!validLorebook(parsed)) {
            return failure(index, action, "LOREBOOK_DEFINITION_LIMIT", "Lorebook exceeds safe field, entry or regex limits.")
        }
        val exists = settingsStore.settingsFlow.value.lorebooks.any { it.id == parsed.id }
        return settingsMutation(index, action, buildJsonObject { put("lorebook_id", parsed.id.toString()) }) { current ->
            current.copy(lorebooks = if (exists) {
                current.lorebooks.map { if (it.id == parsed.id) parsed else it }
            } else current.lorebooks + parsed)
        }
    }

    private suspend fun lorebookDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("lorebook_id")
            ?: return failure(index, action, "LOREBOOK_ID_REQUIRED", "lorebook_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.lorebooks.none { it.id == id }) {
            return failure(index, action, "LOREBOOK_NOT_FOUND", "Lorebook does not exist.")
        }
        return settingsMutation(index, action) { current -> current.copy(
            lorebooks = current.lorebooks.filterNot { it.id == id },
            assistants = current.assistants.map { it.copy(lorebookIds = it.lorebookIds - id) },
        ) }
    }

    private suspend fun quickMessageCreate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("message_id") ?: Uuid.random()
        val title = action.arguments.string("title")?.take(200) ?: return failure(index, action, "TITLE_REQUIRED", "title is required.")
        val content = action.arguments.string("content")?.take(16_000) ?: return failure(index, action, "CONTENT_REQUIRED", "content is required.")
        if (settingsStore.settingsFlow.value.quickMessages.any { it.id == id }) return failure(index, action, "QUICK_MESSAGE_EXISTS", "message_id already exists.")
        return settingsMutation(index, action, data = buildJsonObject { put("message_id", id.toString()) }) { current ->
            current.copy(quickMessages = current.quickMessages + QuickMessage(id, title, content))
        }
    }

    private suspend fun quickMessageUpdate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("message_id") ?: return failure(index, action, "MESSAGE_ID_REQUIRED", "message_id is required.")
        if (settingsStore.settingsFlow.value.quickMessages.none { it.id == id }) return failure(index, action, "QUICK_MESSAGE_NOT_FOUND", "Quick message does not exist.")
        return settingsMutation(index, action) { current -> current.copy(quickMessages = current.quickMessages.map { item ->
            if (item.id != id) item else item.copy(
                title = action.arguments.string("title")?.take(200) ?: item.title,
                content = action.arguments.string("content")?.take(16_000) ?: item.content,
            )
        }) }
    }

    private suspend fun quickMessageDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("message_id") ?: return failure(index, action, "MESSAGE_ID_REQUIRED", "message_id is required.")
        if (settingsStore.settingsFlow.value.quickMessages.none { it.id == id }) return failure(index, action, "QUICK_MESSAGE_NOT_FOUND", "Quick message does not exist.")
        return settingsMutation(index, action) { current -> current.copy(
            quickMessages = current.quickMessages.filterNot { it.id == id },
            assistants = current.assistants.map { it.copy(quickMessageIds = it.quickMessageIds - id) },
        ) }
    }

    private fun asrList(index: Int, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, action, "ASR_LISTED", "ASR profiles read without credentials.", buildJsonObject {
            settings.selectedASRProviderId?.let { put("selected_asr_id", it.toString()) }
            put("items", buildJsonArray { settings.asrProviders.forEach { item -> add(buildJsonObject {
                put("asr_id", item.id.toString()); put("name", item.name.take(160)); put("type", item::class.simpleName.orEmpty())
            }) } })
        })
    }

    private suspend fun asrCreate(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("asr_id") ?: Uuid.random()
        if (settingsStore.settingsFlow.value.asrProviders.any { it.id == id }) return failure(index, action, "ASR_EXISTS", "asr_id already exists.")
        val name = action.arguments.string("name")?.take(160)
        val language = action.arguments.string("language")?.take(40).orEmpty()
        val type = action.arguments.string("type")?.uppercase() ?: return failure(index, action, "ASR_TYPE_REQUIRED", "type is required.")
        val provider = when (type) {
            "OPENAI_REALTIME", "OPENAI" -> ASRProviderSetting.OpenAIRealtime(
                id = id, name = name ?: "OpenAI Realtime ASR", apiKey = "",
                websocketUrl = action.arguments.string("websocket_url")?.take(2_048) ?: "wss://api.openai.com/v1/realtime?intent=transcription",
                model = action.arguments.string("model")?.take(200) ?: "gpt-4o-transcribe",
                language = language, sampleRate = (action.arguments.int("sample_rate") ?: 24_000).coerceIn(8_000, 48_000),
            )
            "DASHSCOPE" -> ASRProviderSetting.DashScope(
                id = id, name = name ?: "DashScope ASR", apiKey = "",
                websocketUrl = action.arguments.string("websocket_url")?.take(2_048) ?: "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
                model = action.arguments.string("model")?.take(200) ?: "qwen3-asr-flash-realtime",
                language = language, sampleRate = (action.arguments.int("sample_rate") ?: 16_000).coerceIn(8_000, 48_000),
            )
            "VOLCENGINE" -> ASRProviderSetting.Volcengine(
                id = id, name = name ?: "Volcengine ASR", apiKey = "",
                websocketUrl = action.arguments.string("websocket_url")?.take(2_048) ?: "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel",
                language = language,
            )
            else -> return failure(index, action, "ASR_TYPE_INVALID", "Supported ASR types are OPENAI_REALTIME, DASHSCOPE and VOLCENGINE.")
        }
        return mutateAsr(index, request, action, id, bindRequested = true) { current ->
            current.copy(asrProviders = current.asrProviders + provider, selectedASRProviderId = current.selectedASRProviderId ?: id)
        }
    }

    private suspend fun asrUpdate(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("asr_id") ?: return failure(index, action, "ASR_ID_REQUIRED", "asr_id is required.")
        if (settingsStore.settingsFlow.value.asrProviders.none { it.id == id }) return failure(index, action, "ASR_NOT_FOUND", "ASR profile does not exist.")
        return mutateAsr(index, request, action, id, bindRequested = "vault_slot_id" in action.arguments) { current -> current.copy(asrProviders = current.asrProviders.map { provider ->
            if (provider.id != id) provider else updateAsr(provider, action.arguments)
        }) }
    }

    private suspend fun asrDelete(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("asr_id") ?: return failure(index, action, "ASR_ID_REQUIRED", "asr_id is required.")
        if (settingsStore.settingsFlow.value.asrProviders.none { it.id == id }) return failure(index, action, "ASR_NOT_FOUND", "ASR profile does not exist.")
        return mutateAsr(index, request, action, id, removeBinding = true) { current ->
            val remaining = current.asrProviders.filterNot { it.id == id }
            current.copy(asrProviders = remaining, selectedASRProviderId = current.selectedASRProviderId.takeUnless { it == id } ?: remaining.firstOrNull()?.id)
        }
    }

    private suspend fun asrSetDefault(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("asr_id") ?: return failure(index, action, "ASR_ID_REQUIRED", "asr_id is required.")
        if (settingsStore.settingsFlow.value.asrProviders.none { it.id == id }) return failure(index, action, "ASR_NOT_FOUND", "ASR profile does not exist.")
        return settingsMutation(index, action) { it.copy(selectedASRProviderId = id) }
    }

    private fun channelGet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, action, "CHANNEL_STATE_READ", "Channel state read without credentials.", buildJsonObject {
            put("web_enabled", settings.webServerEnabled); put("web_port", settings.webServerPort)
            put("web_jwt_enabled", settings.webServerJwtEnabled); put("web_localhost_only", settings.webServerLocalhostOnly)
        })
    }

    private fun searchGet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, action, "SEARCH_STATE_READ", "Search configuration read.", buildJsonObject {
            put("enabled", settings.enableWebSearch); put("selected_index", settings.searchServiceSelected)
            put("service_count", settings.searchServices.size)
        })
    }

    private fun backupGet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, action, "BACKUP_STATE_READ", "Backup configuration read without credentials.", buildJsonObject {
            put("reminder_enabled", settings.backupReminderConfig.enabled)
            put("reminder_interval_days", settings.backupReminderConfig.intervalDays)
            put("last_backup_time", settings.backupReminderConfig.lastBackupTime)
            put("webdav_configured", settings.webDavConfig.url.isNotBlank())
            put("s3_configured", settings.s3Config.endpoint.isNotBlank())
        })
    }

    private fun appSettingsGet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, action, "APP_SETTINGS_READ", "Stable app/runtime settings read.", buildJsonObject {
            put("dynamic_color", settings.dynamicColor); put("theme_id", settings.themeId)
            put("developer_mode", settings.developerMode); put("parallel_read_only_tools", settings.parallelReadOnlyToolsEnabled)
            put("max_parallel_read_only_tools", settings.maxParallelReadOnlyTools)
            put("managed_virtual_display", settings.managedVirtualDisplayEnabled); put("plugin_runtime", settings.pluginRuntimeEnabled)
        })
    }

    private suspend fun appSettingsUpdate(index: Int, action: OwnerAction): OwnerAppliedAction = settingsMutation(index, action) { current ->
        current.copy(
            dynamicColor = action.arguments.boolean("dynamic_color") ?: current.dynamicColor,
            themeId = action.arguments.string("theme_id")?.take(100) ?: current.themeId,
            developerMode = action.arguments.boolean("developer_mode") ?: current.developerMode,
            parallelReadOnlyToolsEnabled = action.arguments.boolean("parallel_read_only_tools") ?: current.parallelReadOnlyToolsEnabled,
            maxParallelReadOnlyTools = (action.arguments.int("max_parallel_read_only_tools") ?: current.maxParallelReadOnlyTools).coerceIn(1, 16),
            managedVirtualDisplayEnabled = action.arguments.boolean("managed_virtual_display") ?: current.managedVirtualDisplayEnabled,
            pluginRuntimeEnabled = action.arguments.boolean("plugin_runtime") ?: current.pluginRuntimeEnabled,
        )
    }

    private suspend fun displayUpdate(index: Int, action: OwnerAction): OwnerAppliedAction = settingsMutation(index, action) { current ->
        val before = current.displaySetting
        current.copy(displaySetting = before.copy(
            showTokenUsage = action.arguments.boolean("show_token_usage") ?: before.showTokenUsage,
            showThinkingContent = action.arguments.boolean("show_thinking") ?: before.showThinkingContent,
            enableAutoScroll = action.arguments.boolean("auto_scroll") ?: before.enableAutoScroll,
            fontSizeRatio = (action.arguments.float("font_size_ratio") ?: before.fontSizeRatio).coerceIn(0.75f, 2f),
            enableNotificationOnMessageGeneration = action.arguments.boolean("notification_after_generation") ?: before.enableNotificationOnMessageGeneration,
        ))
    }

    private suspend fun safetyGet(index: Int, action: OwnerAction): OwnerAppliedAction = success(
        index, action, "SAFETY_STATE_READ", "Safety capability state read.", safetySnapshot().toJson(),
    )

    private suspend fun safetyUpdate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val before = safetySnapshot()
        action.arguments.boolean("high_risk_tools")?.let { safety.setHighRiskToolsEnabled(it) }
        action.arguments.boolean("remote_tools")?.let { safety.setRemoteToolCallsEnabled(it) }
        action.arguments.boolean("background_automation")?.let { safety.setBackgroundAutomationEnabled(it) }
        action.arguments.boolean("allow_while_locked")?.let { safety.setAllowWhileDeviceLocked(it) }
        action.arguments.boolean("privileged_bridge")?.let { safety.setPrivilegedBridgeEnabled(it) }
        if (safetySnapshot() == before) {
            return success(index, action, "SAFETY_ALREADY_CONFIGURED", "Safety capability settings already match the requested state.")
        }
        return success(index, action, "SAFETY_CAPABILITIES_UPDATED", "Safety capability settings updated; Emergency Stop was not changed.", receipt = ControlReceipt.SafetyChanged(before))
    }

    private fun petGet(index: Int, action: OwnerAction): OwnerAppliedAction {
        val selection = settingsStore.settingsFlow.value.petOverlaySelection
        return success(index, action, "PET_STATE_READ", "Global pet selection read.", buildJsonObject {
            put("configured", selection != null); selection?.let {
                put("enabled", it.enabled); put("package_id", it.packageId ?: ""); put("profile_id", it.profileId ?: "")
                put("scale", it.scale); put("fps", it.animationFps); put("idle_pool_enabled", it.idlePoolEnabled)
            }
        })
    }

    private suspend fun petDialogueState(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
    ): OwnerAppliedAction {
        val active = petDialogues.observeActive(request.assistantId, request.conversationId).first()
        val pending = petDialogues.observePendingHandoffs(request.assistantId).first()
            .filter { it.privilegedConversationId == request.conversationId }
        val archives = petDialogues.observeArchives(request.assistantId, 100).first()
            .count { it.privilegedConversationId == request.conversationId }
        return success(index, action, "PET_DIALOGUE_STATE_READ", "Current pet sidecar state read.", buildJsonObject {
            put("active", active != null)
            active?.let { dialogue ->
                put("session_id", dialogue.session.sessionId)
                put("local_date", dialogue.session.localDate)
                put("turn_count", dialogue.turns.size)
                put("remaining_turns", (20 - dialogue.turns.size).coerceAtLeast(0))
                put("state_version", dialogue.session.stateVersion)
            }
            put("archive_count", archives)
            put("pending_handoff_count", pending.size)
            put("pending_handoffs", buildJsonArray {
                pending.take(10).forEach { handoff ->
                    add(buildJsonObject {
                        put("request_id", handoff.requestId)
                        put("title", handoff.title.take(160))
                        put("mode", handoff.mode)
                        put("status", handoff.status)
                        put("state_version", handoff.stateVersion)
                    })
                }
            })
        })
    }

    private suspend fun mutateAsr(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        asrId: Uuid,
        bindRequested: Boolean = false,
        removeBinding: Boolean = false,
        transform: (Settings) -> Settings,
    ): OwnerAppliedAction {
        val before = settingsStore.settingsFlow.value
        val beforeBindings = snapshotAsrBindings(asrId, request.authoritySubjectId)
        val after = transform(before)
        val requestedSlot = action.arguments.string("vault_slot_id")?.trim()?.takeIf(String::isNotEmpty)
        if (bindRequested && requestedSlot != null &&
            vault.listMetadata(request.authoritySubjectId).none { it.slotId == requestedSlot }
        ) {
            return failure(index, action, "SECRET_SLOT_MISSING", "Vault slot does not exist for this authority epoch.")
        }
        val bindingAlreadyMatches = when {
            removeBinding -> beforeBindings.values.all { it.isEmpty() }
            bindRequested -> beforeBindings.entries.singleOrNull { it.value.isNotEmpty() }?.key == requestedSlot &&
                beforeBindings.values.sumOf { it.size } == if (requestedSlot == null) 0 else 1
            else -> true
        }
        if (after.asrProviders == before.asrProviders &&
            after.selectedASRProviderId == before.selectedASRProviderId && bindingAlreadyMatches
        ) {
            return success(index, action, "ASR_ALREADY_CONFIGURED", "ASR profile already matches the requested state.", buildJsonObject {
                put("asr_id", asrId.toString())
            })
        }
        return try {
            settingsStore.update { current -> current.copy(
                asrProviders = after.asrProviders,
                selectedASRProviderId = after.selectedASRProviderId,
            ) }
            when {
                removeBinding -> rebindAsr(asrId, request.authoritySubjectId, null)
                bindRequested -> rebindAsr(asrId, request.authoritySubjectId, requestedSlot)
            }
            success(
                index, action, "ASR_UPDATED", "ASR profile and credential binding updated.",
                buildJsonObject { put("asr_id", asrId.toString()) },
                ControlReceipt.AsrChanged(
                    beforeProviders = before.asrProviders,
                    beforeSelected = before.selectedASRProviderId,
                    asrId = asrId,
                    subjectId = request.authoritySubjectId,
                    beforeBindings = beforeBindings,
                ),
            )
        } catch (error: Throwable) {
            settingsStore.update { current -> current.copy(
                asrProviders = before.asrProviders,
                selectedASRProviderId = before.selectedASRProviderId,
            ) }
            runCatching { restoreAsrBindings(asrId, request.authoritySubjectId, beforeBindings) }
            throw error
        }
    }

    private suspend fun snapshotAsrBindings(
        asrId: Uuid,
        subjectId: String,
    ): Map<String, List<SecretBinding>> = vault.listMetadata(subjectId).associate { slot ->
        slot.slotId to slot.bindings.filter { it.kind == SecretBindingKind.ASR && it.targetId == asrId.toString() }
    }

    private suspend fun rebindAsr(asrId: Uuid, subjectId: String, slotId: String?) {
        val slots = vault.listMetadata(subjectId)
        slots.forEach { slot ->
            val retained = slot.bindings.filterNot { it.kind == SecretBindingKind.ASR && it.targetId == asrId.toString() }
            val next = if (slot.slotId == slotId) retained + SecretBinding(SecretBindingKind.ASR, asrId.toString()) else retained
            if (next != slot.bindings) check(vault.updateBindings(slot.slotId, subjectId, next)) { "asr_vault_binding_failed" }
        }
        if (slotId != null && slots.none { it.slotId == slotId }) error("asr_vault_slot_missing")
    }

    private suspend fun restoreAsrBindings(
        asrId: Uuid,
        subjectId: String,
        snapshot: Map<String, List<SecretBinding>>,
    ) {
        vault.listMetadata(subjectId).forEach { slot ->
            val retained = slot.bindings.filterNot { it.kind == SecretBindingKind.ASR && it.targetId == asrId.toString() }
            check(vault.updateBindings(slot.slotId, subjectId, retained + snapshot[slot.slotId].orEmpty())) {
                "asr_vault_binding_restore_failed"
            }
        }
    }

    private suspend fun petSelect(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val packageId = action.arguments.string("package_id")?.take(64) ?: return failure(index, action, "PET_PACKAGE_ID_REQUIRED", "package_id is required.")
        val assistantId = runCatching { Uuid.parse(request.assistantId) }.getOrNull() ?: return failure(index, action, "ASSISTANT_ID_INVALID", "Owner assistant ID is invalid.")
        val conversationId = runCatching { Uuid.parse(request.conversationId) }.getOrNull() ?: return failure(index, action, "CONVERSATION_ID_INVALID", "Owner conversation ID is invalid.")
        return settingsMutation(index, action) { current -> current.copy(petOverlaySelection = PetOverlaySelection(
            ownerAssistantId = assistantId, privilegedConversationId = conversationId,
            enabled = action.arguments.boolean("enabled") ?: true, packageId = packageId,
            profileId = action.arguments.string("profile_id"),
        ).normalized()) }
    }

    private suspend fun petConfigure(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val active = settingsStore.settingsFlow.value.petOverlaySelection
            ?: return failure(index, action, "PET_NOT_SELECTED", "Select a pet package before changing visual settings.")
        if (active.ownerAssistantId.toString() != request.assistantId || active.privilegedConversationId.toString() != request.conversationId) {
            return failure(index, action, "PET_OWNER_MISMATCH", "Pet selection is not bound to the active Owner identity.")
        }
        return settingsMutation(index, action) { current -> current.copy(petOverlaySelection = active.copy(
            enabled = action.arguments.boolean("enabled") ?: active.enabled,
            scale = action.arguments.float("scale") ?: active.scale,
            animationFps = action.arguments.int("fps") ?: active.animationFps,
            normalizedX = action.arguments.float("x") ?: active.normalizedX,
            normalizedY = action.arguments.float("y") ?: active.normalizedY,
            idlePoolEnabled = action.arguments.boolean("idle_pool_enabled") ?: active.idlePoolEnabled,
        ).normalized()) }
    }

    private suspend fun settingsMutation(
        index: Int,
        action: OwnerAction,
        data: JsonObject? = null,
        transform: (Settings) -> Settings,
    ): OwnerAppliedAction {
        val before = settingsStore.settingsFlow.value
        val after = transform(before)
        if (after == before) {
            return success(index, action, "OWNER_SETTINGS_ALREADY_CONFIGURED", "Settings already match the requested state.", data)
        }
        settingsStore.update { after }
        return success(index, action, "OWNER_SETTINGS_UPDATED", "Settings updated through the shared domain facade.", data, ControlReceipt.SettingsChanged(before))
    }

    private fun updateAsr(provider: ASRProviderSetting, args: JsonObject): ASRProviderSetting = when (provider) {
        is ASRProviderSetting.OpenAIRealtime -> provider.copy(
            name = args.string("name")?.take(160) ?: provider.name,
            websocketUrl = args.string("websocket_url")?.take(2_048) ?: provider.websocketUrl,
            model = args.string("model")?.take(200) ?: provider.model,
            language = args.string("language")?.take(40) ?: provider.language,
            sampleRate = (args.int("sample_rate") ?: provider.sampleRate).coerceIn(8_000, 48_000),
        )
        is ASRProviderSetting.DashScope -> provider.copy(
            name = args.string("name")?.take(160) ?: provider.name,
            websocketUrl = args.string("websocket_url")?.take(2_048) ?: provider.websocketUrl,
            model = args.string("model")?.take(200) ?: provider.model,
            language = args.string("language")?.take(40) ?: provider.language,
            sampleRate = (args.int("sample_rate") ?: provider.sampleRate).coerceIn(8_000, 48_000),
        )
        is ASRProviderSetting.Volcengine -> provider.copy(
            name = args.string("name")?.take(160) ?: provider.name,
            websocketUrl = args.string("websocket_url")?.take(2_048) ?: provider.websocketUrl,
            language = args.string("language")?.take(40) ?: provider.language,
        )
    }

    private suspend fun safetySnapshot() = SafetySnapshot(
        highRisk = safety.highRiskToolsEnabledFlow.first(), remote = safety.remoteToolCallsEnabledFlow.first(),
        background = safety.backgroundAutomationEnabledFlow.first(), locked = safety.allowWhileDeviceLockedFlow.first(),
        bridge = safety.privilegedBridgeEnabledFlow.first(), emergency = safety.emergencyStopFlow.first(),
    )

    private suspend fun restoreSafety(value: SafetySnapshot) {
        safety.setHighRiskToolsEnabled(value.highRisk); safety.setRemoteToolCallsEnabled(value.remote)
        safety.setBackgroundAutomationEnabled(value.background); safety.setAllowWhileDeviceLocked(value.locked)
        safety.setPrivilegedBridgeEnabled(value.bridge)
    }

    private fun restoreSettingsFields(type: String, current: Settings, before: Settings): Settings = when (type) {
        "quick_capture_update" -> current.copy(quickCaptureSettings = before.quickCaptureSettings)
        "plugin_runtime_set" -> current.copy(pluginRuntimeEnabled = before.pluginRuntimeEnabled)
        "plugin_bind", "memory_configure_assistant" -> current.copy(assistants = before.assistants)
        "prompt_injection_upsert", "prompt_injection_delete" -> current.copy(modeInjections = before.modeInjections, assistants = before.assistants)
        "quick_message_create", "quick_message_update", "quick_message_delete" -> current.copy(quickMessages = before.quickMessages, assistants = before.assistants)
        "lorebook_upsert", "lorebook_delete" -> current.copy(lorebooks = before.lorebooks, assistants = before.assistants)
        "asr_create", "asr_update", "asr_delete", "asr_set_default" -> current.copy(asrProviders = before.asrProviders, selectedASRProviderId = before.selectedASRProviderId)
        "web_channel_update" -> current.copy(webServerEnabled = before.webServerEnabled, webServerPort = before.webServerPort, webServerJwtEnabled = before.webServerJwtEnabled, webServerLocalhostOnly = before.webServerLocalhostOnly)
        "search_set_enabled", "search_select" -> current.copy(enableWebSearch = before.enableWebSearch, searchServiceSelected = before.searchServiceSelected)
        "app_settings_update", "runtime_update" -> current.copy(dynamicColor = before.dynamicColor, themeId = before.themeId, developerMode = before.developerMode, parallelReadOnlyToolsEnabled = before.parallelReadOnlyToolsEnabled, maxParallelReadOnlyTools = before.maxParallelReadOnlyTools, managedVirtualDisplayEnabled = before.managedVirtualDisplayEnabled, pluginRuntimeEnabled = before.pluginRuntimeEnabled)
        "app_display_update" -> current.copy(displaySetting = before.displaySetting)
        "pet_select", "pet_configure" -> current.copy(petOverlaySelection = before.petOverlaySelection)
        else -> current
    }

    private fun success(index: Int, action: OwnerAction, code: String, message: String, data: JsonObject? = null, receipt: ControlReceipt? = null) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, true, code, message, data), receipt)
    private fun failure(index: Int, action: OwnerAction, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, false, code, message.take(500)))
    private fun needsUserAction(index: Int, action: OwnerAction, message: String) = failure(index, action, "NEEDS_USER_ACTION", message)
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))

    private sealed interface ControlReceipt {
        data class SettingsChanged(val before: Settings) : ControlReceipt
        data class PluginChanged(val before: InstalledPluginRecord) : ControlReceipt
        data class SafetyChanged(val before: SafetySnapshot) : ControlReceipt
        data class MemoryArchived(val memoryId: Int) : ControlReceipt
        data class AsrChanged(
            val beforeProviders: List<ASRProviderSetting>,
            val beforeSelected: Uuid?,
            val asrId: Uuid,
            val subjectId: String,
            val beforeBindings: Map<String, List<SecretBinding>>,
        ) : ControlReceipt
    }

    private data class SafetySnapshot(
        val highRisk: Boolean, val remote: Boolean, val background: Boolean,
        val locked: Boolean, val bridge: Boolean, val emergency: Boolean,
    ) {
        fun toJson() = buildJsonObject {
            put("high_risk_tools", highRisk); put("remote_tools", remote); put("background_automation", background)
            put("allow_while_locked", locked); put("privileged_bridge", bridge); put("emergency_stop", emergency)
        }
    }

    private companion object {
        val SECRET_FIELDS = setOf("secret", "password", "token", "api_key", "headers", "private_key")
        val MODE_INJECTION_FIELDS = setOf("type", "id", "name", "enabled", "priority", "position", "content", "injectDepth", "role")
        val LOREBOOK_FIELDS = setOf("id", "name", "description", "enabled", "entries")
        val REGEX_INJECTION_FIELDS = setOf(
            "type", "id", "name", "enabled", "priority", "position", "content", "injectDepth", "role",
            "keywords", "useRegex", "caseSensitive", "scanDepth", "constantActive",
        )
        val ACTION_FIELDS: Map<String, Set<String>> = mapOf(
            "run_list" to setOf("limit"), "run_get" to setOf("request_id"), "run_cancel" to setOf("conversation_id", "command_id"), "run_retry" to setOf("conversation_id"),
            "quick_capture_get" to emptySet(), "quick_capture_update" to setOf("enabled", "target_mode", "fixed_assistant_id", "prompt", "auto_send", "backend", "area_mode", "bubble_size_dp", "bubble_opacity", "bubble_edge", "bubble_y_fraction"), "quick_capture_trigger" to emptySet(),
            "plugin_list" to emptySet(), "plugin_runtime_set" to setOf("enabled"), "plugin_install_managed" to setOf("managed_file_id"), "plugin_approve" to setOf("plugin_id"), "plugin_set_enabled" to setOf("plugin_id", "enabled"), "plugin_bind" to setOf("plugin_id", "assistant_id", "enabled"), "plugin_uninstall" to setOf("plugin_id"),
            "memory_list" to setOf("assistant_id", "limit"), "memory_configure_assistant" to setOf("assistant_id", "enabled", "use_global", "recent_chats_reference"), "memory_delete" to setOf("memory_id"),
            "prompt_library_list" to emptySet(), "prompt_injection_upsert" to setOf("definition"), "prompt_injection_delete" to setOf("injection_id"), "quick_message_create" to setOf("message_id", "title", "content"), "quick_message_update" to setOf("message_id", "title", "content"), "quick_message_delete" to setOf("message_id"), "lorebook_list" to emptySet(), "lorebook_upsert" to setOf("definition"), "lorebook_delete" to setOf("lorebook_id"),
            "asr_list" to emptySet(), "asr_create" to setOf("asr_id", "type", "name", "websocket_url", "model", "language", "sample_rate", "vault_slot_id"), "asr_update" to setOf("asr_id", "name", "websocket_url", "model", "language", "sample_rate", "vault_slot_id"), "asr_delete" to setOf("asr_id"), "asr_set_default" to setOf("asr_id"),
            "channel_get" to emptySet(), "web_channel_update" to setOf("enabled", "port", "jwt_enabled", "localhost_only"), "telegram_channel_update" to setOf("enabled", "vault_slot_id", "default_chat_id", "whitelist", "assistant_id", "stream_screenshots"),
            "search_get" to emptySet(), "search_set_enabled" to setOf("enabled"), "search_select" to setOf("index"),
            "backup_storage_get" to emptySet(), "backup_local_export" to emptySet(), "backup_restore_preserving_owner" to setOf("managed_file_id"),
            "app_settings_get" to emptySet(), "app_settings_update" to setOf("dynamic_color", "theme_id", "developer_mode", "parallel_read_only_tools", "max_parallel_read_only_tools", "managed_virtual_display", "plugin_runtime"), "app_display_update" to setOf("show_token_usage", "show_thinking", "auto_scroll", "font_size_ratio", "notification_after_generation"),
            "runtime_get" to emptySet(), "runtime_update" to setOf("parallel_read_only_tools", "max_parallel_read_only_tools", "managed_virtual_display", "plugin_runtime"), "runtime_permissions_open" to setOf("permission"),
            "safety_get" to emptySet(), "safety_capabilities_update" to setOf("high_risk_tools", "remote_tools", "background_automation", "allow_while_locked", "privileged_bridge"), "safety_emergency_stop_activate" to emptySet(),
            "pet_list" to emptySet(), "pet_import_managed" to setOf("managed_file_id", "replace"), "pet_select" to setOf("package_id", "profile_id", "enabled"), "pet_configure" to setOf("enabled", "scale", "fps", "x", "y", "idle_pool_enabled"), "pet_delete" to setOf("package_id", "replacement_package_id"), "pet_dialogue_state" to emptySet(),
        )
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean? = string(name)?.toBooleanStrictOrNull()
private fun JsonObject.int(name: String): Int? = string(name)?.toIntOrNull()
private fun JsonObject.float(name: String): Float? = string(name)?.toFloatOrNull()
private fun JsonObject.uuid(name: String): Uuid? = string(name)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
private inline fun <reified T : Enum<T>> JsonObject.enum(name: String, fallback: T): T =
    string(name)?.let { raw -> enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } } ?: fallback
private fun validModeInjection(value: PromptInjection.ModeInjection): Boolean =
    value.name.length <= 200 && value.content.length <= 32_000 && value.priority in -10_000..10_000 &&
        value.injectDepth in 0..1_000 && value.role in setOf(MessageRole.USER, MessageRole.ASSISTANT)
private fun validLorebook(value: Lorebook): Boolean = value.name.length <= 200 &&
    value.description.length <= 2_000 && value.entries.size <= 200 && value.entries.all { entry ->
        entry.name.length <= 200 && entry.content.length <= 32_000 && entry.priority in -10_000..10_000 &&
            entry.injectDepth in 0..1_000 && entry.scanDepth in 1..1_000 && entry.keywords.size <= 32 &&
            entry.keywords.all { it.length <= 500 } && entry.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
            (!entry.useRegex || entry.keywords.all { runCatching { Regex(it) }.isSuccess })
    }

internal data class OwnerPromptDefinitionIssue(val code: String, val message: String)

internal fun validateModeInjectionWire(definition: JsonObject): OwnerPromptDefinitionIssue? {
    fun primitive(name: String) = definition[name] as? JsonPrimitive
    fun wrongType(name: String, expected: String) =
        OwnerPromptDefinitionIssue("PROMPT_${name.uppercase()}_INVALID", "$name must be $expected.")

    definition["id"]?.let { value ->
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (raw == null || runCatching { Uuid.parse(raw) }.isFailure) return wrongType("id", "a UUID string")
    }
    definition["name"]?.let { if (primitive("name")?.isString != true) return wrongType("name", "a string") }
    definition["enabled"]?.let {
        val value = primitive("enabled")
        if (value == null || value.isString || value.booleanOrNull == null) return wrongType("enabled", "a JSON boolean")
    }
    definition["priority"]?.let {
        val value = primitive("priority")
        if (value == null || value.isString || value.intOrNull == null) return wrongType("priority", "a JSON integer")
    }
    definition["content"]?.let { if (primitive("content")?.isString != true) return wrongType("content", "a string") }
    definition["injectDepth"]?.let {
        val value = primitive("injectDepth")
        if (value == null || value.isString || value.intOrNull == null) return wrongType("injectDepth", "a JSON integer")
    }
    definition["position"]?.let {
        val value = primitive("position")?.takeIf { item -> item.isString }?.contentOrNull
        if (value !in OWNER_PROMPT_POSITIONS) return wrongType("position", OWNER_PROMPT_POSITIONS.joinToString("|"))
    }
    definition["role"]?.let {
        val value = primitive("role")?.takeIf { item -> item.isString }?.contentOrNull
        if (value !in OWNER_PROMPT_ROLES) return wrongType("role", "user|assistant")
    }
    return null
}

private val OWNER_PROMPT_POSITIONS = setOf(
    "before_system_prompt",
    "after_system_prompt",
    "top_of_chat",
    "bottom_of_chat",
    "at_depth",
)
private val OWNER_PROMPT_ROLES = setOf("user", "assistant")
private fun Throwable.safeCode(): String = message?.takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }?.uppercase() ?: "OWNER_OPERATION_FAILED"

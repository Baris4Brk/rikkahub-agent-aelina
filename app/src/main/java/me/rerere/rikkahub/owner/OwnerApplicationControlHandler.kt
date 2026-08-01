package me.rerere.rikkahub.owner

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.owner.db.HostOperationDao
import me.rerere.rikkahub.pet.PetOverlaySelection
import me.rerere.rikkahub.plugin.InstalledPluginRecord
import me.rerere.rikkahub.plugin.PluginRegistryStore
import me.rerere.rikkahub.plugin.PluginReviewStatus
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.quickcapture.QuickCaptureAreaMode
import me.rerere.rikkahub.quickcapture.QuickCaptureBackendPreference
import me.rerere.rikkahub.quickcapture.QuickCaptureBubbleEdge
import me.rerere.rikkahub.quickcapture.QuickCaptureTargetMode
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
            "memory_list" -> memoryList(index, action)
            "memory_configure_assistant" -> memoryConfigure(index, action)
            "memory_delete" -> needsUserAction(index, action, "Memory deletion requires a revision-aware Memory repository identifier.")
            "prompt_library_list", "lorebook_list" -> promptLibraryList(index, action)
            "quick_message_create" -> quickMessageCreate(index, action)
            "quick_message_update" -> quickMessageUpdate(index, action)
            "quick_message_delete" -> quickMessageDelete(index, action)
            "asr_list" -> asrList(index, action)
            "asr_create" -> asrCreate(index, action)
            "asr_update" -> asrUpdate(index, action)
            "asr_delete" -> asrDelete(index, action)
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
            "pet_list", "pet_dialogue_state" -> petGet(index, action)
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
        plugins.update(id, transform)
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

    private fun memoryList(index: Int, action: OwnerAction): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        val id = action.arguments.uuid("assistant_id")
        val assistants = settings.assistants.filter { id == null || it.id == id }
        return success(index, action, "MEMORY_SETTINGS_LISTED", "Assistant memory settings read.", buildJsonObject {
            put("items", buildJsonArray { assistants.take((action.arguments.int("limit") ?: 20).coerceIn(1, 100)).forEach { assistant ->
                add(buildJsonObject { put("assistant_id", assistant.id.toString()); put("enabled", assistant.enableMemory)
                    put("use_global", assistant.useGlobalMemory); put("recent_chats_reference", assistant.enableRecentChatsReference) })
            } })
        })
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
            put("lorebooks", buildJsonArray { settings.lorebooks.take(100).forEach { item -> add(buildJsonObject {
                put("lorebook_id", item.id.toString()); put("name", item.name.take(160)); put("enabled", item.enabled)
            }) } })
        })
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

    private suspend fun asrCreate(index: Int, action: OwnerAction): OwnerAppliedAction {
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
        return settingsMutation(index, action, data = buildJsonObject { put("asr_id", id.toString()) }) { current ->
            current.copy(asrProviders = current.asrProviders + provider, selectedASRProviderId = current.selectedASRProviderId ?: id)
        }
    }

    private suspend fun asrUpdate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("asr_id") ?: return failure(index, action, "ASR_ID_REQUIRED", "asr_id is required.")
        if (settingsStore.settingsFlow.value.asrProviders.none { it.id == id }) return failure(index, action, "ASR_NOT_FOUND", "ASR profile does not exist.")
        return settingsMutation(index, action) { current -> current.copy(asrProviders = current.asrProviders.map { provider ->
            if (provider.id != id) provider else updateAsr(provider, action.arguments)
        }) }
    }

    private suspend fun asrDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("asr_id") ?: return failure(index, action, "ASR_ID_REQUIRED", "asr_id is required.")
        if (settingsStore.settingsFlow.value.asrProviders.none { it.id == id }) return failure(index, action, "ASR_NOT_FOUND", "ASR profile does not exist.")
        return settingsMutation(index, action) { current ->
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
        settingsStore.update(transform)
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
        "quick_message_create", "quick_message_update", "quick_message_delete" -> current.copy(quickMessages = before.quickMessages, assistants = before.assistants)
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
        val ACTION_FIELDS: Map<String, Set<String>> = mapOf(
            "run_list" to setOf("limit"), "run_get" to setOf("request_id"), "run_cancel" to setOf("conversation_id", "command_id"), "run_retry" to setOf("conversation_id"),
            "quick_capture_get" to emptySet(), "quick_capture_update" to setOf("enabled", "target_mode", "fixed_assistant_id", "prompt", "auto_send", "backend", "area_mode", "bubble_size_dp", "bubble_opacity", "bubble_edge", "bubble_y_fraction"), "quick_capture_trigger" to emptySet(),
            "plugin_list" to emptySet(), "plugin_runtime_set" to setOf("enabled"), "plugin_install_managed" to setOf("managed_file_id"), "plugin_approve" to setOf("plugin_id"), "plugin_set_enabled" to setOf("plugin_id", "enabled"), "plugin_bind" to setOf("plugin_id", "assistant_id", "enabled"), "plugin_uninstall" to setOf("plugin_id"),
            "memory_list" to setOf("assistant_id", "limit"), "memory_configure_assistant" to setOf("assistant_id", "enabled", "use_global", "recent_chats_reference"), "memory_delete" to setOf("memory_id"),
            "prompt_library_list" to emptySet(), "quick_message_create" to setOf("message_id", "title", "content"), "quick_message_update" to setOf("message_id", "title", "content"), "quick_message_delete" to setOf("message_id"), "lorebook_list" to emptySet(),
            "asr_list" to emptySet(), "asr_create" to setOf("asr_id", "type", "name", "websocket_url", "model", "language", "sample_rate", "vault_slot_id"), "asr_update" to setOf("asr_id", "name", "websocket_url", "model", "language", "sample_rate"), "asr_delete" to setOf("asr_id"), "asr_set_default" to setOf("asr_id"),
            "channel_get" to emptySet(), "web_channel_update" to setOf("enabled", "port", "jwt_enabled", "localhost_only"), "telegram_channel_update" to setOf("enabled", "vault_slot_id"),
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
private fun Throwable.safeCode(): String = message?.takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }?.uppercase() ?: "OWNER_OPERATION_FAILED"

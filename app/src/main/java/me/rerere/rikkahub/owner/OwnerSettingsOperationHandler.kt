package me.rerere.rikkahub.owner

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.ai.tools.local.ConversationExportResult
import me.rerere.rikkahub.data.ai.tools.local.exportConversationToDownloads
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.AssistantRemovalResult
import me.rerere.rikkahub.data.repository.AssistantRemovalService
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecretBindingResolution
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.security.resolveProviderBinding
import kotlin.uuid.Uuid

/** Typed Settings/Room adapter for the high-value assistant, conversation and Provider actions. */
class OwnerSettingsOperationHandler(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversations: ConversationRepository,
    private val assistantRemoval: AssistantRemovalService,
    private val providerManager: ProviderManager,
    private val vault: SecondUserSecretVault,
    private val selfPreservation: OwnerSelfPreservationGuard = OwnerSelfPreservationGuard(),
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        action.type in ACTION_FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        selfPreservation.validate(action)?.let { return it }
        val fields = ACTION_FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported Owner settings action.")
        if ((action.arguments.keys - fields).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "Action contains a field outside its typed contract.")
        }
        OPTIONAL_UUID_FIELDS[action.type].orEmpty().forEach { field ->
            if (action.arguments.string(field) != null && action.arguments.uuid(field) == null) {
                return invalid("OWNER_ID_INVALID", "$field must be a UUID when supplied.")
            }
        }
        val forbidden = action.arguments.keys.any { key ->
            key.lowercase() in setOf("api_key", "secret", "password", "token", "private_key", "headers")
        }
        if (forbidden) return invalid("OWNER_SECRET_ARGUMENT_FORBIDDEN", "Provider secrets must be referenced by Vault slot ID.")
        if (action.type.startsWith("provider_") &&
            action.type !in setOf("provider_list", "provider_create", "provider_set_default")
        ) {
            val id = action.arguments.uuid("provider_id")
                ?: return invalid("PROVIDER_ID_REQUIRED", "provider_id is required.")
            if (settingsStore.settingsFlow.value.providers.none { it.id == id }) {
                return invalid("PROVIDER_NOT_FOUND", "Provider does not exist.")
            }
        }
        action.arguments.string("vault_slot_id")?.takeIf { it.isNotBlank() }?.let { slotId ->
            if (vault.listMetadata(request.authoritySubjectId).none { it.slotId == slotId }) {
                return invalid("SECRET_SLOT_MISSING", "The requested Vault slot does not exist for this authority epoch.")
            }
        }
        return OwnerActionValidation(true, "OWNER_ACTION_VALID", "Action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "assistant_create" -> assistantCreate(index, action)
            "assistant_clone" -> assistantClone(index, action)
            "assistant_delete" -> assistantDelete(index, action)
            "assistant_set_default" -> assistantSetDefault(index, action)
            "assistant_switch_model" -> assistantSwitchModel(index, action)
            "assistant_switch_tts" -> assistantSwitchTts(index, action)
            "conversation_create" -> conversationCreate(index, action)
            "conversation_branch" -> conversationBranch(index, action)
            "conversation_archive" -> conversationArchive(index, action, archived = true)
            "conversation_restore" -> conversationArchive(index, action, archived = false)
            "conversation_search" -> conversationSearch(index, action)
            "conversation_export" -> conversationExport(index, action)
            "conversation_open" -> conversationOpen(index, action)
            "provider_list" -> providerList(index)
            "provider_create" -> providerCreate(index, request, action)
            "provider_update" -> providerUpdate(index, action)
            "provider_delete" -> providerDelete(index, request, action)
            "provider_refresh_models" -> providerRefresh(index, request, action, persist = true)
            "provider_test" -> providerRefresh(index, request, action, persist = false)
            "provider_set_default" -> providerSetDefault(index, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported action.")
        }
    }.getOrElse {
        failure(index, action.type, "OWNER_OPERATION_FAILED", "The typed Owner settings operation failed.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = if (applied.result.ok) {
        OwnerActionValidation(true, "OWNER_ACTION_VERIFIED", "Repository state was verified by the typed operation.")
    } else invalid(applied.result.code, applied.result.message)

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? SettingsReceipt
            ?: return OwnerCompensationResult(
                compensated = action.risk == OwnerOperationRisk.READ_ONLY,
                code = if (action.risk == OwnerOperationRisk.READ_ONLY) {
                    "SETTINGS_NO_COMPENSATION_REQUIRED"
                } else {
                    "SETTINGS_COMPENSATION_UNAVAILABLE"
                },
            )
        return runCatching {
            when (receipt) {
                is SettingsReceipt.CreatedAssistant -> settingsStore.update { current ->
                    current.copy(assistants = current.assistants.filterNot { it.id == receipt.id })
                }
                is SettingsReceipt.PreviousAssistant -> settingsStore.update { current ->
                    current.copy(assistants = current.assistants.map { if (it.id == receipt.value.id) receipt.value else it })
                }
                is SettingsReceipt.PreviousDefaultAssistant -> settingsStore.update { it.copy(assistantId = receipt.id) }
                is SettingsReceipt.PreviousDefaultTts -> settingsStore.update { it.copy(selectedTTSProviderId = receipt.id) }
                is SettingsReceipt.CreatedConversation -> conversations.getConversationById(receipt.id)?.let {
                    conversations.deleteConversation(it)
                }
                is SettingsReceipt.PreviousConversation -> conversations.updateConversation(receipt.value)
                is SettingsReceipt.CreatedProvider -> {
                    settingsStore.update { current ->
                        current.copy(providers = current.providers.filterNot { it.id == receipt.id })
                    }
                    removeProviderBindings(receipt.id, request.authoritySubjectId)
                }
                is SettingsReceipt.PreviousProvider -> settingsStore.update { current ->
                    current.copy(providers = current.providers.map { if (it.id == receipt.value.id) receipt.value else it })
                }
                is SettingsReceipt.PreviousDefaultModel -> settingsStore.update { current ->
                    if (receipt.assistantId == null) {
                        current.copy(chatModelId = requireNotNull(receipt.modelId))
                    } else {
                        current.copy(assistants = current.assistants.map { assistant ->
                            if (assistant.id == receipt.assistantId) assistant.copy(chatModelId = receipt.modelId) else assistant
                        })
                    }
                }
            }
            OwnerCompensationResult(true, "SETTINGS_STATE_RESTORED")
        }.getOrElse { OwnerCompensationResult(false, "SETTINGS_COMPENSATION_FAILED") }
    }

    private suspend fun assistantCreate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val args = action.arguments
        val modelId = args.uuid("model_id")
        val settings = settingsStore.settingsFlow.value
        if (modelId != null && settings.findModelById(modelId) == null) {
            return failure(index, action.type, "MODEL_NOT_FOUND", "Selected model does not exist.")
        }
        val assistant = Assistant(
            id = args.uuid("assistant_id") ?: Uuid.random(),
            name = args.string("name")?.trim()?.take(200).orEmpty(),
            systemPrompt = args.string("system_prompt")?.take(128 * 1024).orEmpty(),
            chatModelId = modelId,
        )
        if (settings.assistants.any { it.id == assistant.id }) {
            return failure(index, action.type, "ASSISTANT_ALREADY_EXISTS", "assistant_id already exists.")
        }
        settingsStore.update { it.copy(assistants = it.assistants + assistant) }
        return success(
            index, action.type, "ASSISTANT_CREATED", "Assistant created.",
            idData("assistant_id", assistant.id), SettingsReceipt.CreatedAssistant(assistant.id),
        )
    }

    private suspend fun assistantClone(index: Int, action: OwnerAction): OwnerAppliedAction {
        val sourceId = action.arguments.uuid("source_assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "source_assistant_id is required.")
        val source = settingsStore.settingsFlow.value.assistants.firstOrNull { it.id == sourceId }
            ?: return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Source assistant does not exist.")
        val clone = source.copy(
            id = action.arguments.uuid("assistant_id") ?: Uuid.random(),
            name = action.arguments.string("name")?.trim()?.take(200)
                ?: "${source.name.ifBlank { "Assistant" }} copy",
            unrestricted = false,
            privilegedConversationId = null,
            secondUserPolicyConfirmed = false,
            allowConversationHistoryRead = false,
            petEnabled = false,
            petBootRestoreEnabled = false,
        )
        if (settingsStore.settingsFlow.value.assistants.any { it.id == clone.id }) {
            return failure(index, action.type, "ASSISTANT_ALREADY_EXISTS", "assistant_id already exists.")
        }
        settingsStore.update { it.copy(assistants = it.assistants + clone) }
        return success(
            index, action.type, "ASSISTANT_CLONED", "Assistant cloned without Owner identity.",
            idData("assistant_id", clone.id), SettingsReceipt.CreatedAssistant(clone.id),
        )
    }

    private suspend fun assistantDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.assistants.size <= 1) return failure(index, action.type, "LAST_ASSISTANT", "The final assistant cannot be removed.")
        val assistant = settings.assistants.firstOrNull { it.id == id }
            ?: return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        return when (assistantRemoval.remove(assistant)) {
            AssistantRemovalResult.Removed -> success(index, action.type, "ASSISTANT_DELETED", "Assistant and unprotected dependent data were deleted.")
            AssistantRemovalResult.RetainedSecondUser -> failure(index, action.type, "OWNER_PERMANENT_PROTECTION", "The protected Owner assistant cannot be deleted.")
            AssistantRemovalResult.NotFound -> failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
    }

    private suspend fun assistantSetDefault(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        if (settingsStore.settingsFlow.value.assistants.none { it.id == id }) {
            return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
        val previous = settingsStore.settingsFlow.value.assistantId
        settingsStore.update { it.copy(assistantId = id) }
        return success(
            index, action.type, "DEFAULT_ASSISTANT_UPDATED", "Default assistant updated.",
            receipt = SettingsReceipt.PreviousDefaultAssistant(previous),
        )
    }

    private suspend fun assistantSwitchModel(index: Int, action: OwnerAction): OwnerAppliedAction {
        val assistantId = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        val modelId = action.arguments.uuid("model_id")
            ?: return failure(index, action.type, "MODEL_ID_REQUIRED", "model_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.findModelById(modelId) == null) return failure(index, action.type, "MODEL_NOT_FOUND", "Model does not exist.")
        if (settings.assistants.none { it.id == assistantId }) return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        settingsStore.update { current ->
            current.copy(assistants = current.assistants.map { assistant ->
                if (assistant.id == assistantId) assistant.copy(chatModelId = modelId) else assistant
            })
        }
        val previous = settings.assistants.first { it.id == assistantId }
        return success(
            index, action.type, "ASSISTANT_MODEL_UPDATED", "Assistant model updated.",
            receipt = SettingsReceipt.PreviousAssistant(previous),
        )
    }

    private suspend fun assistantSwitchTts(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("tts_provider_id")
            ?: return failure(index, action.type, "TTS_ID_REQUIRED", "tts_provider_id is required.")
        if (settingsStore.settingsFlow.value.ttsProviders.none { it.id == id }) {
            return failure(index, action.type, "TTS_NOT_FOUND", "TTS Provider does not exist.")
        }
        val previous = settingsStore.settingsFlow.value.selectedTTSProviderId
        settingsStore.update { it.copy(selectedTTSProviderId = id) }
        return success(
            index, action.type, "TTS_DEFAULT_UPDATED", "Default TTS Provider updated.",
            receipt = SettingsReceipt.PreviousDefaultTts(previous),
        )
    }

    private suspend fun conversationBranch(index: Int, action: OwnerAction): OwnerAppliedAction {
        val sourceId = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        val source = conversations.getConversationById(sourceId)
            ?: return failure(index, action.type, "CONVERSATION_NOT_FOUND", "Conversation does not exist.")
        val branch = source.copy(
            id = action.arguments.uuid("new_conversation_id") ?: Uuid.random(),
            title = action.arguments.string("title")?.trim()?.take(200)
                ?: "${source.title.ifBlank { "Conversation" }} branch",
            messageNodes = source.messageNodes.map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message -> message.copy(id = Uuid.random()) },
                )
            },
            isPinned = false,
            createAt = java.time.Instant.now(),
            updateAt = java.time.Instant.now(),
            newConversation = false,
        )
        if (conversations.existsConversationById(branch.id)) {
            return failure(index, action.type, "CONVERSATION_ALREADY_EXISTS", "new_conversation_id already exists.")
        }
        conversations.insertConversation(branch)
        return success(
            index, action.type, "CONVERSATION_BRANCHED", "Conversation branch created.",
            idData("conversation_id", branch.id), SettingsReceipt.CreatedConversation(branch.id),
        )
    }

    private suspend fun conversationCreate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val assistantId = action.arguments.uuid("assistant_id")
            ?: return failure(index, action.type, "ASSISTANT_ID_REQUIRED", "assistant_id is required.")
        if (settingsStore.settingsFlow.value.assistants.none { it.id == assistantId }) {
            return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
        val conversationId = action.arguments.uuid("conversation_id") ?: Uuid.random()
        if (conversations.existsConversationById(conversationId)) {
            return failure(index, action.type, "CONVERSATION_ALREADY_EXISTS", "conversation_id already exists.")
        }
        val conversation = me.rerere.rikkahub.data.model.Conversation.ofId(
            id = conversationId,
            assistantId = assistantId,
            newConversation = true,
        ).copy(title = action.arguments.string("title")?.trim()?.take(200).orEmpty())
        conversations.insertConversation(conversation)
        return success(
            index, action.type, "CONVERSATION_CREATED", "Conversation created.",
            idData("conversation_id", conversationId), SettingsReceipt.CreatedConversation(conversationId),
        )
    }

    private suspend fun conversationArchive(index: Int, action: OwnerAction, archived: Boolean): OwnerAppliedAction {
        val id = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        val conversation = conversations.getConversationById(id)
            ?: return failure(index, action.type, "CONVERSATION_NOT_FOUND", "Conversation does not exist.")
        val folder = if (archived) OWNER_ARCHIVE_FOLDER else conversation.folderId.takeUnless { it == OWNER_ARCHIVE_FOLDER }.orEmpty()
        conversations.updateConversation(conversation.copy(folderId = folder, updateAt = java.time.Instant.now()))
        return success(
            index,
            action.type,
            if (archived) "CONVERSATION_ARCHIVED" else "CONVERSATION_RESTORED",
            if (archived) "Conversation archived." else "Conversation restored.",
            receipt = SettingsReceipt.PreviousConversation(conversation),
        )
    }

    private suspend fun conversationSearch(index: Int, action: OwnerAction): OwnerAppliedAction {
        val query = action.arguments.string("query")?.trim()?.take(200).orEmpty()
        val limit = action.arguments.int("limit")?.coerceIn(1, 50) ?: 20
        val results = conversations.searchConversations(query).first().take(limit)
        return success(index, action.type, "CONVERSATION_SEARCH_RESULTS", "Conversation search completed.", buildJsonObject {
            put("items", buildJsonArray {
                results.forEach { conversation -> add(buildJsonObject {
                    put("conversation_id", conversation.id.toString())
                    put("assistant_id", conversation.assistantId.toString())
                    put("title", conversation.title.take(200))
                    put("archived", conversation.folderId == OWNER_ARCHIVE_FOLDER)
                }) }
            })
        })
    }

    private suspend fun conversationOpen(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        val conversation = conversations.getConversationById(id)
            ?: return failure(index, action.type, "CONVERSATION_NOT_FOUND", "Conversation does not exist.")
        val previous = settingsStore.settingsFlow.value.assistantId
        settingsStore.update { it.copy(assistantId = conversation.assistantId) }
        OwnerNavigationMailbox.offer(OwnerNavigationTarget.Conversation(id.toString()))
        return success(
            index, action.type, "CONVERSATION_OPEN_REQUESTED", "Conversation navigation requested.",
            receipt = SettingsReceipt.PreviousDefaultAssistant(previous),
        )
    }

    private suspend fun conversationExport(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("conversation_id")
            ?: return failure(index, action.type, "CONVERSATION_ID_REQUIRED", "conversation_id is required.")
        return when (val result = exportConversationToDownloads(context, conversations, id)) {
            is ConversationExportResult.Failure -> failure(index, action.type, result.code, result.message)
            is ConversationExportResult.Success -> success(
                index = index,
                type = action.type,
                code = "CONVERSATION_EXPORTED",
                message = "Conversation exported to Downloads/chat-exports.",
                data = buildJsonObject { put("filename", result.filename) },
            )
        }
    }

    private fun providerList(index: Int): OwnerAppliedAction {
        val settings = settingsStore.settingsFlow.value
        return success(index, "provider_list", "PROVIDER_LIST", "Provider metadata returned.", buildJsonObject {
            put("providers", buildJsonArray {
                settings.providers.forEach { provider -> add(buildJsonObject {
                    put("provider_id", provider.id.toString())
                    put("type", provider.typeName())
                    put("name", provider.name.take(200))
                    put("enabled", provider.enabled)
                    put("model_count", provider.models.size)
                    put("built_in", provider.builtIn)
                }) }
            })
        })
    }

    private suspend fun providerCreate(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val type = action.arguments.string("provider_type")?.lowercase()
            ?: return failure(index, action.type, "PROVIDER_TYPE_REQUIRED", "provider_type is required.")
        val id = action.arguments.uuid("provider_id") ?: Uuid.random()
        if (settingsStore.settingsFlow.value.providers.any { it.id == id }) {
            return failure(index, action.type, "PROVIDER_ALREADY_EXISTS", "provider_id already exists.")
        }
        val name = action.arguments.string("name")?.trim()?.take(200).orEmpty().ifBlank { type.replaceFirstChar(Char::uppercase) }
        val baseUrl = action.arguments.string("base_url")?.trim()?.take(2048)
        val provider: ProviderSetting = when (type) {
            "openai", "openai_compatible" -> ProviderSetting.OpenAI(id = id, name = name, baseUrl = baseUrl ?: "https://api.openai.com/v1")
            "google" -> ProviderSetting.Google(id = id, name = name, baseUrl = baseUrl ?: "https://generativelanguage.googleapis.com/v1beta")
            "claude" -> ProviderSetting.Claude(id = id, name = name, baseUrl = baseUrl ?: "https://api.anthropic.com/v1")
            else -> return failure(index, action.type, "PROVIDER_TYPE_UNSUPPORTED", "Only OpenAI-compatible, Google and Claude are supported.")
        }
        settingsStore.update { it.copy(providers = it.providers + provider) }
        try {
            action.arguments.string("vault_slot_id")?.takeIf { it.isNotBlank() }?.let { slotId ->
                bindProviderSlot(slotId, request.authoritySubjectId, provider.id)
            }
        } catch (failure: Throwable) {
            settingsStore.update { current -> current.copy(providers = current.providers.filterNot { it.id == id }) }
            throw failure
        }
        return success(
            index, action.type, "PROVIDER_CREATED", "Provider created with no plaintext credential in Settings.",
            idData("provider_id", id), SettingsReceipt.CreatedProvider(id),
        )
    }

    private suspend fun providerUpdate(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("provider_id"))
        val old = settingsStore.settingsFlow.value.providers.first { it.id == id }
        val name = action.arguments.string("name")?.trim()?.take(200) ?: old.name
        val enabled = action.arguments.boolean("enabled") ?: old.enabled
        val baseUrl = action.arguments.string("base_url")?.trim()?.take(2048)
        val updated = old.withSafeFields(name, enabled, baseUrl)
        settingsStore.update { current -> current.copy(providers = current.providers.map { if (it.id == id) updated else it }) }
        return success(
            index, action.type, "PROVIDER_UPDATED", "Provider metadata updated.",
            receipt = SettingsReceipt.PreviousProvider(old),
        )
    }

    private suspend fun providerDelete(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("provider_id"))
        val settings = settingsStore.settingsFlow.value
        val provider = settings.providers.first { it.id == id }
        if (provider.builtIn) return failure(index, action.type, "PROVIDER_BUILT_IN", "Built-in Providers cannot be deleted.")
        val modelIds = provider.models.mapTo(hashSetOf()) { it.id }
        if (settings.chatModelId in modelIds || settings.assistants.any { it.chatModelId in modelIds }) {
            return failure(index, action.type, "PROVIDER_IN_USE", "Switch every model binding before deleting this Provider.")
        }
        settingsStore.update { it.copy(providers = it.providers.filterNot { candidate -> candidate.id == id }) }
        vault.listMetadata(request.authoritySubjectId).forEach { slot ->
            val filtered = slot.bindings.filterNot { it.kind == SecretBindingKind.PROVIDER && it.targetId == id.toString() }
            if (filtered.size != slot.bindings.size) vault.updateBindings(slot.slotId, request.authoritySubjectId, filtered)
        }
        return success(index, action.type, "PROVIDER_DELETED", "Unreferenced Provider deleted.")
    }

    private suspend fun providerRefresh(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        persist: Boolean,
    ): OwnerAppliedAction {
        val id = requireNotNull(action.arguments.uuid("provider_id"))
        val configured = settingsStore.settingsFlow.value.providers.first { it.id == id }
        val resolved = when (val secret = vault.resolveProviderBinding(configured, request.authoritySubjectId)) {
            SecretBindingResolution.NotBound -> configured
            is SecretBindingResolution.Ready -> secret.value
            is SecretBindingResolution.Unavailable -> return failure(index, action.type, "PROVIDER_SECRET_UNAVAILABLE", secret.code)
        }
        val fetched = providerManager.getProviderByType(resolved).listModels(resolved).toList()
        if (persist) {
            val oldByModel = configured.models.associateBy { it.modelId }
            val merged = fetched.map { model -> oldByModel[model.modelId]?.let { old -> model.copy(id = old.id) } ?: model }
            settingsStore.update { current ->
                current.copy(providers = current.providers.map { provider ->
                    if (provider.id == id) provider.copyProvider(models = merged) else provider
                })
            }
        }
        return success(index, action.type, if (persist) "PROVIDER_MODELS_REFRESHED" else "PROVIDER_TEST_OK", if (persist) "Provider models refreshed." else "Provider connection test succeeded.", buildJsonObject {
            put("model_count", fetched.size)
            put("models", buildJsonArray { fetched.take(50).forEach { add(it.modelId.take(200)) } })
        }, if (persist) SettingsReceipt.PreviousProvider(configured) else null)
    }

    private suspend fun providerSetDefault(index: Int, action: OwnerAction): OwnerAppliedAction {
        val modelId = action.arguments.uuid("model_id")
            ?: return failure(index, action.type, "MODEL_ID_REQUIRED", "model_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (settings.findModelById(modelId) == null) return failure(index, action.type, "MODEL_NOT_FOUND", "Model does not exist.")
        val assistantId = action.arguments.uuid("assistant_id")
        if (assistantId != null && settings.assistants.none { it.id == assistantId }) {
            return failure(index, action.type, "ASSISTANT_NOT_FOUND", "Assistant does not exist.")
        }
        val previousModel = if (assistantId == null) settings.chatModelId
            else settings.assistants.firstOrNull { it.id == assistantId }?.chatModelId
        settingsStore.update { current ->
            if (assistantId == null) current.copy(chatModelId = modelId) else current.copy(
                assistants = current.assistants.map { assistant ->
                    if (assistant.id == assistantId) assistant.copy(chatModelId = modelId) else assistant
                },
            )
        }
        return success(
            index, action.type, "DEFAULT_MODEL_UPDATED", "Model binding updated.",
            receipt = SettingsReceipt.PreviousDefaultModel(assistantId, previousModel),
        )
    }

    private suspend fun bindProviderSlot(slotId: String, subjectId: String, providerId: Uuid) {
        val slot = vault.listMetadata(subjectId).first { it.slotId == slotId }
        val binding = SecretBinding(SecretBindingKind.PROVIDER, providerId.toString())
        check(vault.updateBindings(slotId, subjectId, (slot.bindings.filterNot {
            it.kind == binding.kind && it.targetId == binding.targetId
        } + binding))) { "provider_vault_binding_failed" }
    }

    private suspend fun removeProviderBindings(providerId: Uuid, subjectId: String) {
        vault.listMetadata(subjectId).forEach { slot ->
            val retained = slot.bindings.filterNot {
                it.kind == SecretBindingKind.PROVIDER && it.targetId == providerId.toString()
            }
            if (retained != slot.bindings) vault.updateBindings(slot.slotId, subjectId, retained)
        }
    }

    private fun ProviderSetting.withSafeFields(name: String, enabled: Boolean, baseUrl: String?): ProviderSetting = when (this) {
        is ProviderSetting.OpenAI -> copy(name = name, enabled = enabled, baseUrl = baseUrl ?: this.baseUrl)
        is ProviderSetting.Google -> copy(name = name, enabled = enabled, baseUrl = baseUrl ?: this.baseUrl)
        is ProviderSetting.Claude -> copy(name = name, enabled = enabled, baseUrl = baseUrl ?: this.baseUrl)
        else -> copyProvider(name = name, enabled = enabled)
    }

    private fun ProviderSetting.typeName(): String = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
        else -> this::class.simpleName.orEmpty().lowercase()
    }

    private fun success(
        index: Int,
        type: String,
        code: String,
        message: String,
        data: JsonObject? = null,
        receipt: Any? = null,
    ) = OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data), receipt)
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message)
    private fun idData(key: String, id: Any) = buildJsonObject { put(key, id.toString()) }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.uuid(key: String): Uuid? = string(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    }
    private fun JsonObject.boolean(key: String): Boolean? = string(key)?.toBooleanStrictOrNull()
    private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()

    private sealed interface SettingsReceipt {
        data class CreatedAssistant(val id: Uuid) : SettingsReceipt
        data class PreviousAssistant(val value: Assistant) : SettingsReceipt
        data class PreviousDefaultAssistant(val id: Uuid) : SettingsReceipt
        data class PreviousDefaultTts(val id: Uuid) : SettingsReceipt
        data class CreatedConversation(val id: Uuid) : SettingsReceipt
        data class PreviousConversation(val value: me.rerere.rikkahub.data.model.Conversation) : SettingsReceipt
        data class CreatedProvider(val id: Uuid) : SettingsReceipt
        data class PreviousProvider(val value: ProviderSetting) : SettingsReceipt
        data class PreviousDefaultModel(val assistantId: Uuid?, val modelId: Uuid?) : SettingsReceipt
    }

    private companion object {
        const val OWNER_ARCHIVE_FOLDER = "__owner_archive__"
        val ACTION_FIELDS = mapOf(
            "assistant_create" to setOf("assistant_id", "name", "system_prompt", "model_id"),
            "assistant_clone" to setOf("source_assistant_id", "assistant_id", "name"),
            "assistant_delete" to setOf("assistant_id"),
            "assistant_set_default" to setOf("assistant_id"),
            "assistant_switch_model" to setOf("assistant_id", "model_id"),
            "assistant_switch_tts" to setOf("tts_provider_id"),
            "conversation_create" to setOf("conversation_id", "assistant_id", "title"),
            "conversation_branch" to setOf("conversation_id", "new_conversation_id", "title"),
            "conversation_archive" to setOf("conversation_id"),
            "conversation_restore" to setOf("conversation_id"),
            "conversation_search" to setOf("query", "limit"),
            "conversation_export" to setOf("conversation_id"),
            "conversation_open" to setOf("conversation_id"),
            "provider_list" to emptySet(),
            "provider_create" to setOf("provider_id", "provider_type", "name", "base_url", "vault_slot_id"),
            "provider_update" to setOf("provider_id", "name", "base_url", "enabled"),
            "provider_delete" to setOf("provider_id"),
            "provider_refresh_models" to setOf("provider_id"),
            "provider_test" to setOf("provider_id"),
            "provider_set_default" to setOf("model_id", "assistant_id"),
        )
        val OPTIONAL_UUID_FIELDS = mapOf(
            "assistant_create" to setOf("assistant_id"),
            "assistant_clone" to setOf("assistant_id"),
            "conversation_create" to setOf("conversation_id"),
            "conversation_branch" to setOf("new_conversation_id"),
            "provider_create" to setOf("provider_id"),
        )
    }
}

sealed interface OwnerNavigationTarget {
    data class Conversation(val conversationId: String) : OwnerNavigationTarget
    data class Screen(val route: String, val targetId: String? = null) : OwnerNavigationTarget
}

object OwnerNavigationMailbox {
    private val pending = java.util.concurrent.atomic.AtomicReference<OwnerNavigationTarget?>(null)
    private val _targets = kotlinx.coroutines.flow.MutableStateFlow<OwnerNavigationTarget?>(null)
    val targets: kotlinx.coroutines.flow.StateFlow<OwnerNavigationTarget?> = _targets
    fun offer(target: OwnerNavigationTarget) {
        pending.set(target)
        _targets.value = target
    }
    fun consume(): OwnerNavigationTarget? = pending.getAndSet(null)
    fun acknowledge(target: OwnerNavigationTarget) {
        pending.compareAndSet(target, null)
        _targets.compareAndSet(target, null)
    }
}

package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecretBindingResolution
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.security.resolveTtsBinding
import me.rerere.rikkahub.tts.PersistentTtsLibrary
import me.rerere.rikkahub.tts.TtsPlaybackOwner
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.provider.GenericHttpBodyEncoding
import me.rerere.tts.provider.GenericHttpHeader
import me.rerere.tts.provider.GenericHttpMethod
import me.rerere.tts.provider.GenericHttpResponseMode
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

class OwnerTtsOperationHandler(
    private val settingsStore: SettingsStore,
    private val vault: SecondUserSecretVault,
    private val library: PersistentTtsLibrary,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.TTS && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val fields = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported TTS action.")
        if ((action.arguments.keys - fields).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "TTS action contains an unsupported field.")
        }
        if (action.type == "tts_create_generic_http" &&
            action.arguments.string("tts_provider_id") != null &&
            action.arguments.uuid("tts_provider_id") == null
        ) {
            return invalid("TTS_ID_INVALID", "tts_provider_id must be a UUID when supplied.")
        }
        if (action.arguments.keys.any { it.lowercase() in setOf("api_key", "secret", "token", "password") }) {
            return invalid("OWNER_SECRET_ARGUMENT_FORBIDDEN", "Use vault_slot_id instead of plaintext credentials.")
        }
        action.arguments.string("vault_slot_id")?.takeIf { it.isNotBlank() }?.let { slotId ->
            if (vault.listMetadata(request.authoritySubjectId).none { it.slotId == slotId }) {
                return invalid("SECRET_SLOT_MISSING", "Vault slot does not exist for this authority epoch.")
            }
        }
        return OwnerActionValidation(true, "TTS_ACTION_VALID", "TTS action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "tts_list" -> list(index)
            "tts_create_generic_http" -> create(index, request, action)
            "tts_update" -> update(index, request, action)
            "tts_delete" -> delete(index, request, action)
            "tts_test" -> test(index, request, action)
            "tts_play" -> play(index, request, action)
            "tts_set_default" -> setDefault(index, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported TTS action.")
        }
    }.getOrElse {
        failure(index, action.type, "TTS_OPERATION_FAILED", "TTS operation failed inside the host runtime.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ) = if (applied.result.ok) OwnerActionValidation(true, "TTS_ACTION_VERIFIED", "TTS state verified.")
    else invalid(applied.result.code, applied.result.message)

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? TtsReceipt
            ?: return OwnerCompensationResult(
                action.risk == OwnerOperationRisk.READ_ONLY,
                if (action.risk == OwnerOperationRisk.READ_ONLY) "TTS_NO_COMPENSATION_REQUIRED" else "TTS_COMPENSATION_UNAVAILABLE",
            )
        return runCatching {
            when (receipt) {
                is TtsReceipt.Created -> {
                    settingsStore.update { current ->
                        current.copy(ttsProviders = current.ttsProviders.filterNot { it.id == receipt.id })
                    }
                    removeBindings(receipt.id, request.authoritySubjectId)
                }
                is TtsReceipt.PreviousProvider -> {
                    settingsStore.update { current ->
                        current.copy(ttsProviders = current.ttsProviders.map {
                            if (it.id == receipt.value.id) receipt.value else it
                        })
                    }
                    restoreBindings(
                        id = receipt.value.id,
                        authoritySubjectId = request.authoritySubjectId,
                        snapshot = receipt.bindings,
                    )
                }
                is TtsReceipt.PreviousDefault -> settingsStore.update {
                    it.copy(selectedTTSProviderId = receipt.id)
                }
            }
            OwnerCompensationResult(true, "TTS_STATE_RESTORED")
        }.getOrElse { OwnerCompensationResult(false, "TTS_COMPENSATION_FAILED") }
    }

    private fun list(index: Int): OwnerAppliedAction = success(index, "tts_list", "TTS_LIST", "TTS Provider metadata returned.", buildJsonObject {
        val settings = settingsStore.settingsFlow.value
        put("selected_tts_provider_id", settings.selectedTTSProviderId.toString())
        put("providers", buildJsonArray {
            settings.ttsProviders.forEach { provider ->
                add(buildJsonObject {
                    put("tts_provider_id", provider.id.toString())
                    put("name", provider.name.take(200))
                    put("type", provider::class.simpleName.orEmpty())
                    put("selected", provider.id == settings.selectedTTSProviderId)
                })
            }
        })
    })

    private suspend fun create(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("tts_provider_id") ?: Uuid.random()
        if (settingsStore.settingsFlow.value.ttsProviders.any { it.id == id }) {
            return failure(index, action.type, "TTS_ALREADY_EXISTS", "tts_provider_id already exists.")
        }
        val provider = action.arguments.toGenericHttp(id)
        settingsStore.update { it.copy(ttsProviders = it.ttsProviders + provider) }
        try {
            action.arguments.string("vault_slot_id")?.takeIf { it.isNotBlank() }?.let { slotId ->
                val slot = vault.listMetadata(request.authoritySubjectId).first { it.slotId == slotId }
                val binding = SecretBinding(SecretBindingKind.TTS, provider.id.toString())
                check(vault.updateBindings(slotId, request.authoritySubjectId, (slot.bindings.filterNot {
                    it.kind == binding.kind && it.targetId == binding.targetId
                } + binding))) {
                    "tts_vault_binding_failed"
                }
            }
        } catch (failure: Throwable) {
            settingsStore.update { current -> current.copy(ttsProviders = current.ttsProviders.filterNot { it.id == id }) }
            throw failure
        }
        return success(index, action.type, "TTS_CREATED", "Generic HTTP TTS Provider created.", buildJsonObject {
            put("tts_provider_id", provider.id.toString())
        }, TtsReceipt.Created(provider.id))
    }

    private suspend fun update(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
    ): OwnerAppliedAction {
        val id = action.arguments.uuid("tts_provider_id")
            ?: return failure(index, action.type, "TTS_ID_REQUIRED", "tts_provider_id is required.")
        val existing = settingsStore.settingsFlow.value.ttsProviders.firstOrNull { it.id == id }
            ?: return failure(index, action.type, "TTS_NOT_FOUND", "TTS Provider does not exist.")
        val bindingSnapshot = snapshotBindings(id, request.authoritySubjectId)
        try {
            if (existing !is TTSProviderSetting.GenericHttp) {
                val name = action.arguments.string("name")?.trim()?.take(200)
                    ?: return failure(index, action.type, "TTS_UPDATE_UNSUPPORTED", "This phase updates only Generic HTTP fields for non-generic Providers.")
                settingsStore.update { current -> current.copy(ttsProviders = current.ttsProviders.map {
                    if (it.id == id) it.copyProvider(name = name) else it
                }) }
            } else {
                val updated = action.arguments.toGenericHttp(id, existing)
                settingsStore.update { current -> current.copy(ttsProviders = current.ttsProviders.map {
                    if (it.id == id) updated else it
                }) }
            }
            if ("vault_slot_id" in action.arguments) {
                val slotId = action.arguments.string("vault_slot_id")?.trim().orEmpty()
                rebind(id, request.authoritySubjectId, slotId.takeIf { it.isNotBlank() })
            }
        } catch (failure: Throwable) {
            settingsStore.update { current -> current.copy(ttsProviders = current.ttsProviders.map {
                if (it.id == id) existing else it
            }) }
            restoreBindings(id, request.authoritySubjectId, bindingSnapshot)
            throw failure
        }
        return success(
            index, action.type, "TTS_UPDATED", "TTS Provider updated.",
            receipt = TtsReceipt.PreviousProvider(existing, bindingSnapshot),
        )
    }

    private suspend fun delete(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("tts_provider_id")
            ?: return failure(index, action.type, "TTS_ID_REQUIRED", "tts_provider_id is required.")
        val settings = settingsStore.settingsFlow.value
        if (id == settings.selectedTTSProviderId) return failure(index, action.type, "TTS_IN_USE", "Switch the default TTS Provider before deleting it.")
        if (settings.ttsProviders.none { it.id == id }) return failure(index, action.type, "TTS_NOT_FOUND", "TTS Provider does not exist.")
        settingsStore.update { it.copy(ttsProviders = it.ttsProviders.filterNot { provider -> provider.id == id }) }
        vault.listMetadata(request.authoritySubjectId).forEach { slot ->
            val filtered = slot.bindings.filterNot { it.kind == SecretBindingKind.TTS && it.targetId == id.toString() }
            if (filtered.size != slot.bindings.size) vault.updateBindings(slot.slotId, request.authoritySubjectId, filtered)
        }
        return success(index, action.type, "TTS_DELETED", "Unreferenced TTS Provider deleted.")
    }

    private suspend fun test(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("tts_provider_id")
            ?: return failure(index, action.type, "TTS_ID_REQUIRED", "tts_provider_id is required.")
        val configured = settingsStore.settingsFlow.value.ttsProviders.firstOrNull { it.id == id }
            ?: return failure(index, action.type, "TTS_NOT_FOUND", "TTS Provider does not exist.")
        val resolved = when (val binding = vault.resolveTtsBinding(configured, request.authoritySubjectId)) {
            SecretBindingResolution.NotBound -> configured
            is SecretBindingResolution.Ready -> binding.value
            is SecretBindingResolution.Unavailable -> return failure(index, action.type, "TTS_SECRET_UNAVAILABLE", binding.code)
        }
        val ownerKey = TtsPlaybackOwner.secondUser(request.assistantId, request.conversationId)
        val entry = library.synthesizeProviderSaveAndQueue(
            provider = resolved,
            text = action.arguments.string("text")?.take(500)?.ifBlank { null } ?: "TTS connection test",
            ownerKey = ownerKey,
        )
        return success(index, action.type, "TTS_TEST_OK", "TTS synthesized, saved and queued for playback.", buildJsonObject {
            put("artifact_id", entry.artifactId)
            put("total_bytes", entry.totalBytes)
        })
    }

    private suspend fun play(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val ownerKey = TtsPlaybackOwner.secondUser(request.assistantId, request.conversationId)
        val artifactId = action.arguments.string("artifact_id")?.trim()
        if (!artifactId.isNullOrBlank()) {
            return if (library.queueReplay(artifactId, ownerKey)) {
                success(index, action.type, "TTS_REPLAY_QUEUED", "Existing TTS artifact queued.")
            } else failure(index, action.type, "TTS_ARTIFACT_NOT_FOUND", "TTS artifact does not exist.")
        }
        val text = action.arguments.string("text")?.take(500)?.takeIf { it.isNotBlank() }
            ?: return failure(index, action.type, "TTS_TEXT_REQUIRED", "text or artifact_id is required.")
        val entry = library.synthesizeSaveAndQueue(text, ownerKey)
        return success(index, action.type, "TTS_SYNTHESIZED", "TTS synthesized, saved and queued.", buildJsonObject {
            put("artifact_id", entry.artifactId)
        })
    }

    private suspend fun setDefault(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.uuid("tts_provider_id")
            ?: return failure(index, action.type, "TTS_ID_REQUIRED", "tts_provider_id is required.")
        if (settingsStore.settingsFlow.value.ttsProviders.none { it.id == id }) {
            return failure(index, action.type, "TTS_NOT_FOUND", "TTS Provider does not exist.")
        }
        val previous = settingsStore.settingsFlow.value.selectedTTSProviderId
        settingsStore.update { it.copy(selectedTTSProviderId = id) }
        return success(
            index, action.type, "TTS_DEFAULT_UPDATED", "Default TTS Provider updated.",
            receipt = TtsReceipt.PreviousDefault(previous),
        )
    }

    private suspend fun removeBindings(id: Uuid, authoritySubjectId: String) {
        vault.listMetadata(authoritySubjectId).forEach { slot ->
            val retained = slot.bindings.filterNot {
                it.kind == SecretBindingKind.TTS && it.targetId == id.toString()
            }
            if (retained != slot.bindings) vault.updateBindings(slot.slotId, authoritySubjectId, retained)
        }
    }

    private suspend fun snapshotBindings(
        id: Uuid,
        authoritySubjectId: String,
    ): Map<String, List<SecretBinding>> = vault.listMetadata(authoritySubjectId).associate { slot ->
        slot.slotId to slot.bindings.filter {
            it.kind == SecretBindingKind.TTS && it.targetId == id.toString()
        }
    }

    private suspend fun restoreBindings(
        id: Uuid,
        authoritySubjectId: String,
        snapshot: Map<String, List<SecretBinding>>,
    ) {
        vault.listMetadata(authoritySubjectId).forEach { slot ->
            val retained = slot.bindings.filterNot {
                it.kind == SecretBindingKind.TTS && it.targetId == id.toString()
            }
            val restored = retained + snapshot[slot.slotId].orEmpty()
            check(vault.updateBindings(slot.slotId, authoritySubjectId, restored)) {
                "tts_vault_binding_restore_failed"
            }
        }
    }

    private suspend fun rebind(id: Uuid, authoritySubjectId: String, slotId: String?) {
        removeBindings(id, authoritySubjectId)
        if (slotId == null) return
        val slot = vault.listMetadata(authoritySubjectId).firstOrNull { it.slotId == slotId }
            ?: error("tts_vault_slot_missing")
        val binding = SecretBinding(SecretBindingKind.TTS, id.toString())
        check(vault.updateBindings(slotId, authoritySubjectId, slot.bindings + binding)) {
            "tts_vault_binding_failed"
        }
    }

    private fun JsonObject.toGenericHttp(
        id: Uuid,
        previous: TTSProviderSetting.GenericHttp? = null,
    ): TTSProviderSetting.GenericHttp = TTSProviderSetting.GenericHttp(
        id = id,
        name = string("name")?.trim()?.take(200) ?: previous?.name ?: "Generic HTTP TTS",
        endpoint = string("endpoint")?.trim()?.take(4096) ?: previous?.endpoint.orEmpty(),
        method = enumValue<GenericHttpMethod>("method") ?: previous?.method ?: GenericHttpMethod.POST,
        bodyEncoding = enumValue<GenericHttpBodyEncoding>("body_encoding") ?: previous?.bodyEncoding ?: GenericHttpBodyEncoding.JSON,
        bodyTemplate = string("body_template")?.take(32 * 1024) ?: previous?.bodyTemplate ?: "{\"text\":\"{{text}}\"}",
        headers = headers() ?: previous?.headers.orEmpty(),
        responseMode = enumValue<GenericHttpResponseMode>("response_mode") ?: previous?.responseMode ?: GenericHttpResponseMode.RAW_AUDIO,
        responseJsonPath = string("response_json_path")?.take(512) ?: previous?.responseJsonPath ?: "audio",
        audioFormat = enumValue<AudioFormat>("audio_format") ?: previous?.audioFormat ?: AudioFormat.MP3,
        voice = string("voice")?.take(200) ?: previous?.voice.orEmpty(),
        language = string("language")?.take(100) ?: previous?.language.orEmpty(),
        allowPrivateNetwork = boolean("allow_private_network") ?: previous?.allowPrivateNetwork ?: false,
        maxResponseBytes = int("max_response_bytes")?.coerceIn(1, 64 * 1024 * 1024)
            ?: previous?.maxResponseBytes ?: 16 * 1024 * 1024,
    )

    private fun JsonObject.headers(): List<GenericHttpHeader>? = (this["headers"] as? JsonArray)?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = obj.string("name")?.trim()?.take(128) ?: return@mapNotNull null
        val value = obj.string("value_template")?.take(4096) ?: return@mapNotNull null
        GenericHttpHeader(name, value)
    }?.take(32)

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(key: String): T? =
        string(key)?.uppercase()?.let { raw -> enumValues<T>().firstOrNull { it.name == raw } }
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.uuid(key: String) = string(key)?.let { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
    private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()
    private fun JsonObject.int(key: String) = string(key)?.toIntOrNull()

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

    private sealed interface TtsReceipt {
        data class Created(val id: Uuid) : TtsReceipt
        data class PreviousProvider(
            val value: TTSProviderSetting,
            val bindings: Map<String, List<SecretBinding>>,
        ) : TtsReceipt
        data class PreviousDefault(val id: Uuid) : TtsReceipt
    }

    private companion object {
        val GENERIC_FIELDS = setOf(
            "name", "endpoint", "method", "body_encoding", "body_template", "headers",
            "response_mode", "response_json_path", "audio_format", "voice", "language",
            "allow_private_network", "max_response_bytes", "vault_slot_id",
        )
        val FIELDS = mapOf(
            "tts_list" to emptySet(),
            "tts_create_generic_http" to GENERIC_FIELDS + "tts_provider_id",
            "tts_update" to GENERIC_FIELDS + "tts_provider_id",
            "tts_delete" to setOf("tts_provider_id"),
            "tts_test" to setOf("tts_provider_id", "text"),
            "tts_play" to setOf("artifact_id", "text"),
            "tts_set_default" to setOf("tts_provider_id"),
        )
    }
}

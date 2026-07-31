package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import kotlin.uuid.Uuid

/** One-call EmotionTTS service + Generic HTTP provider + three-probe playback transaction. */
class OwnerEmotionTtsOperationHandler(
    private val settingsStore: SettingsStore,
    private val serviceHandler: OwnerLocalServiceOperationHandler,
    private val ttsHandler: OwnerTtsOperationHandler,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.SERVICE && action.type == "emotion_tts_setup"

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if ((action.arguments.keys - FIELDS).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "EmotionTTS setup contains an unsupported field.")
        }
        val serviceAction = OwnerAction("emotion_tts_setup", action.arguments.serviceArgs(), action.risk)
        val serviceCheck = serviceHandler.validate(request, serviceAction, context)
        if (!serviceCheck.ok) return serviceCheck
        val ttsAction = OwnerAction("tts_create_generic_http", action.arguments.ttsArgs(), action.risk)
        val ttsCheck = ttsHandler.validate(request.copy(family = OwnerToolFamily.TTS), ttsAction, context)
        if (!ttsCheck.ok) return ttsCheck
        if (action.arguments.ttsArgs()["endpoint"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            return invalid("TTS_ENDPOINT_REQUIRED", "tts_endpoint or health_url is required.")
        }
        return OwnerActionValidation(true, "EMOTION_TTS_SETUP_VALID", "EmotionTTS setup validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        val serviceRequest = request.copy(family = OwnerToolFamily.SERVICE)
        val ttsRequest = request.copy(family = OwnerToolFamily.TTS)
        val serviceAction = OwnerAction("emotion_tts_setup", action.arguments.serviceArgs(), action.risk)
        val serviceApplied = serviceHandler.apply(index, serviceRequest, serviceAction, context)
        if (!serviceApplied.result.ok) return serviceApplied

        val previousDefault = settingsStore.settingsFlow.value.selectedTTSProviderId
        val createAction = OwnerAction("tts_create_generic_http", action.arguments.ttsArgs(), action.risk)
        val created = ttsHandler.apply(index, ttsRequest, createAction, context)
        if (!created.result.ok) {
            serviceHandler.compensate(serviceRequest, serviceAction, serviceApplied, context)
            return created
        }
        val providerId = created.result.data?.get("tts_provider_id")?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (providerId == null) {
            serviceHandler.compensate(serviceRequest, serviceAction, serviceApplied, context)
            return failure(index, action.type, "TTS_CREATE_ID_MISSING", "TTS Provider creation returned no stable ID.")
        }

        val tests = action.arguments.testTexts()
        var tested = 0
        for (text in tests) {
            val testAction = OwnerAction(
                "tts_test",
                buildJsonObject {
                    put("tts_provider_id", providerId.toString())
                    put("text", text.take(500))
                },
                action.risk,
            )
            val result = ttsHandler.apply(index, ttsRequest, testAction, context)
            if (!result.result.ok) {
                rollbackCreatedTts(index, ttsRequest, providerId, previousDefault, action, context)
                serviceHandler.compensate(serviceRequest, serviceAction, serviceApplied, context)
                return failure(index, action.type, "EMOTION_TTS_TEST_FAILED", "EmotionTTS failed one of three synthesis/playback probes.")
            }
            tested++
        }
        if (action.arguments.boolean("set_default") != false) {
            val selected = ttsHandler.apply(
                index,
                ttsRequest,
                OwnerAction("tts_set_default", buildJsonObject { put("tts_provider_id", providerId.toString()) }, action.risk),
                context,
            )
            if (!selected.result.ok) {
                rollbackCreatedTts(index, ttsRequest, providerId, previousDefault, action, context)
                serviceHandler.compensate(serviceRequest, serviceAction, serviceApplied, context)
                return failure(index, action.type, "EMOTION_TTS_DEFAULT_FAILED", "Default TTS switch failed; prior state restored.")
            }
        }
        return OwnerAppliedAction(
            OwnerActionResult(
                index,
                action.type,
                true,
                "EMOTION_TTS_READY",
                "Service, address, Generic HTTP TTS, three synthesis probes, playback queue and default selection completed.",
                buildJsonObject {
                    serviceApplied.result.data?.get("service_id")?.let { put("service_id", it) }
                    put("tts_provider_id", providerId.toString())
                    put("tests_passed", tested)
                    put("default_selected", action.arguments.boolean("set_default") != false)
                },
            ),
            EmotionReceipt(serviceAction, serviceApplied, providerId, previousDefault),
        )
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val id = applied.result.data?.get("tts_provider_id")?.jsonPrimitive?.contentOrNull
        return if (id != null && settingsStore.settingsFlow.value.ttsProviders.any { it.id.toString() == id }) {
            OwnerActionValidation(true, "EMOTION_TTS_VERIFIED", "EmotionTTS Provider and service transaction verified.")
        } else invalid("EMOTION_TTS_VERIFY_FAILED", "EmotionTTS Provider could not be confirmed.")
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? EmotionReceipt
            ?: return OwnerCompensationResult(false, "EMOTION_TTS_RECEIPT_MISSING")
        val ttsRequest = request.copy(family = OwnerToolFamily.TTS)
        rollbackCreatedTts(applied.result.index, ttsRequest, receipt.providerId, receipt.previousDefault, action, context)
        val service = serviceHandler.compensate(
            request.copy(family = OwnerToolFamily.SERVICE),
            receipt.serviceAction,
            receipt.serviceApplied,
            context,
        )
        return OwnerCompensationResult(service.compensated, if (service.compensated) "EMOTION_TTS_ROLLED_BACK" else "EMOTION_TTS_PARTIAL_ROLLBACK")
    }

    private suspend fun rollbackCreatedTts(
        index: Int,
        request: OwnerOperationRequest,
        providerId: Uuid,
        previousDefault: Uuid,
        source: OwnerAction,
        context: PrivilegedSessionContext,
    ) {
        settingsStore.update { it.copy(selectedTTSProviderId = previousDefault) }
        ttsHandler.apply(
            index,
            request,
            OwnerAction("tts_delete", buildJsonObject { put("tts_provider_id", providerId.toString()) }, source.risk),
            context,
        )
    }

    private data class EmotionReceipt(
        val serviceAction: OwnerAction,
        val serviceApplied: OwnerAppliedAction,
        val providerId: Uuid,
        val previousDefault: Uuid,
    )

    private fun JsonObject.serviceArgs() = JsonObject(filterKeys { it in SERVICE_FIELDS })
    private fun JsonObject.ttsArgs(): JsonObject = buildJsonObject {
        fun copy(from: String, to: String) { this@ttsArgs[from]?.let { put(to, it) } }
        copy("tts_name", "name")
        copy("tts_endpoint", "endpoint")
        if (this@ttsArgs["tts_endpoint"] == null) copy("health_url", "endpoint")
        copy("tts_method", "method")
        copy("tts_body_encoding", "body_encoding")
        copy("tts_body_template", "body_template")
        copy("tts_headers", "headers")
        copy("tts_response_mode", "response_mode")
        copy("tts_response_json_path", "response_json_path")
        copy("tts_audio_format", "audio_format")
        copy("tts_voice", "voice")
        copy("tts_language", "language")
        copy("tts_allow_private_network", "allow_private_network")
        copy("tts_max_response_bytes", "max_response_bytes")
        copy("vault_slot_id", "vault_slot_id")
    }
    private fun JsonObject.testTexts(): List<String> = (this["test_texts"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?.filter { it.isNotBlank() }
        ?.take(3)
        ?.takeIf { it.size == 3 }
        ?: listOf("开心地说：服务测试成功。", "平静地说：语音连接正常。", "难过地说：情绪参数测试完成。")
    private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))

    private companion object {
        val SERVICE_FIELDS = setOf(
            "service_id", "runtime", "workspace_id", "name", "command", "executable", "arguments",
            "cwd", "working_dir", "keep_awake", "restart_policy", "health_url",
        )
        val FIELDS = SERVICE_FIELDS + setOf(
            "tts_name", "tts_endpoint", "tts_method", "tts_body_encoding", "tts_body_template",
            "tts_headers", "tts_response_mode", "tts_response_json_path", "tts_audio_format",
            "tts_voice", "tts_language", "tts_allow_private_network", "tts_max_response_bytes",
            "vault_slot_id", "set_default", "test_texts",
        )
    }
}

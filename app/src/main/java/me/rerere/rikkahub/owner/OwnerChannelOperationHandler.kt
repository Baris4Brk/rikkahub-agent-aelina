package me.rerere.rikkahub.owner

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.telegram.TelegramBotConfig
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.data.telegram.TelegramCredentialResolver
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.service.TelegramBotService
import kotlin.uuid.Uuid

/** Vault-backed Telegram configuration shared by the Owner tool and the existing bot runtime. */
class OwnerChannelOperationHandler(
    context: Context,
    private val settingsStore: SettingsStore,
    private val preferences: TelegramBotPreferences,
    private val credentials: TelegramCredentialResolver,
    private val vault: SecondUserSecretVault,
) : OwnerOperationHandler {
    private val appContext = context.applicationContext

    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.CHANNEL && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val unknown = action.arguments.keys - FIELDS.getValue(action.type)
        if (unknown.isNotEmpty()) return invalid("OWNER_UNSUPPORTED_FIELD", "Unsupported channel fields: ${unknown.sorted().joinToString()}.")
        if (action.type == "telegram_channel_update") {
            action.arguments.string("vault_slot_id")?.trim()?.takeIf { it.isNotEmpty() }?.let { slotId ->
                if (vault.listMetadata(request.authoritySubjectId).none { it.slotId == slotId }) {
                    return invalid("SECRET_SLOT_MISSING", "Vault slot does not exist for this authority epoch.")
                }
            }
            action.arguments.string("assistant_id")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                val id = runCatching { Uuid.parse(raw) }.getOrNull()
                    ?: return invalid("ASSISTANT_ID_INVALID", "assistant_id must be a UUID.")
                if (settingsStore.settingsFlow.value.assistants.none { it.id == id }) {
                    return invalid("ASSISTANT_NOT_FOUND", "Telegram target assistant does not exist.")
                }
            }
            val whitelist = action.arguments["whitelist"]
            if (whitelist != null && whitelist !is JsonArray) {
                return invalid("TELEGRAM_WHITELIST_INVALID", "whitelist must be an array of integer Telegram IDs.")
            }
            if (whitelist?.any { it.jsonPrimitive.longOrNull == null } == true) {
                return invalid("TELEGRAM_WHITELIST_INVALID", "whitelist contains a non-integer Telegram ID.")
            }
        }
        return OwnerActionValidation(true, "CHANNEL_ACTION_VALID", "Channel action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = when (action.type) {
        "channel_get" -> read(index, action)
        "telegram_channel_update" -> updateTelegram(index, request, action)
        else -> failure(index, action, "OWNER_ACTION_UNSUPPORTED", "Unsupported channel action.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val receipt = applied.compensationReceipt as? Receipt
            ?: return OwnerActionValidation(true, "CHANNEL_STATE_READ", "Channel state was read from its authoritative stores.")
        val current = preferences.current()
        if (current != receipt.expected) return invalid("CHANNEL_VERIFY_FAILED", "Telegram configuration readback did not match.")
        if (current.enabled && !credentials.credentialAvailable()) {
            return invalid("TELEGRAM_CREDENTIAL_UNAVAILABLE", "Telegram was not enabled because the Vault credential is unavailable.")
        }
        return OwnerActionValidation(true, "CHANNEL_STATE_VERIFIED", "Telegram configuration and credential binding were read back.")
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = runCatching {
        val receipt = applied.compensationReceipt as? Receipt
            ?: return@runCatching OwnerCompensationResult(true, "CHANNEL_NO_MUTATION")
        preferences.update { receipt.before }
        restoreBindings(request.authoritySubjectId, receipt.beforeBindings)
        if (receipt.before.isUsable) TelegramBotService.start(appContext) else TelegramBotService.stop(appContext)
        OwnerCompensationResult(true, "TELEGRAM_CHANNEL_RESTORED")
    }.getOrElse { OwnerCompensationResult(false, "TELEGRAM_CHANNEL_RESTORE_FAILED") }

    private suspend fun read(index: Int, action: OwnerAction): OwnerAppliedAction {
        val telegram = preferences.current()
        val settings = settingsStore.settingsFlow.value
        return success(index, action, "CHANNEL_STATE_READ", "Channel state read without credential plaintext.", buildJsonObject {
            put("web_enabled", settings.webServerEnabled)
            put("web_port", settings.webServerPort)
            put("web_jwt_enabled", settings.webServerJwtEnabled)
            put("web_localhost_only", settings.webServerLocalhostOnly)
            put("telegram_enabled", telegram.enabled)
            put("telegram_credential_configured", telegram.hasCredential)
            put("telegram_credential_source", if (!telegram.vaultSlotId.isNullOrBlank()) "VAULT" else if (telegram.token.isNotBlank()) "LEGACY_LOCAL" else "NONE")
            put("telegram_service_running", TelegramBotService.isRunning)
            put("telegram_whitelist", buildJsonArray { telegram.whitelist.sorted().forEach { add(JsonPrimitive(it)) } })
            telegram.defaultChatId?.let { put("telegram_default_chat_id", it) }
            telegram.assistantId?.let { put("telegram_assistant_id", it) }
            put("telegram_stream_screenshots", telegram.streamScreenshots)
        })
    }

    private suspend fun updateTelegram(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
    ): OwnerAppliedAction {
        val before = preferences.current()
        val beforeBindings = snapshotBindings(request.authoritySubjectId)
        val hasSlotField = "vault_slot_id" in action.arguments
        val requestedSlot = action.arguments.string("vault_slot_id")?.trim()?.takeIf { it.isNotEmpty() }
        val whitelist = (action.arguments["whitelist"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }?.toSet()
        val next = before.copy(
            token = if (hasSlotField && requestedSlot != null) "" else before.token,
            vaultSlotId = if (hasSlotField) requestedSlot else before.vaultSlotId,
            enabled = action.arguments.boolean("enabled") ?: before.enabled,
            defaultChatId = if ("default_chat_id" in action.arguments) action.arguments.longOrNull("default_chat_id") else before.defaultChatId,
            whitelist = whitelist ?: before.whitelist,
            assistantId = if ("assistant_id" in action.arguments) action.arguments.string("assistant_id")?.trim()?.takeIf { it.isNotEmpty() } else before.assistantId,
            streamScreenshots = action.arguments.boolean("stream_screenshots") ?: before.streamScreenshots,
        )
        return try {
            if (hasSlotField) rebind(request.authoritySubjectId, requestedSlot)
            preferences.update { next }
            if (next.enabled && !credentials.credentialAvailable()) {
                error("telegram_vault_credential_unavailable")
            }
            if (next.enabled) TelegramBotService.start(appContext) else TelegramBotService.stop(appContext)
            if (next == before && beforeBindings == snapshotBindings(request.authoritySubjectId)) {
                success(index, action, "TELEGRAM_CHANNEL_ALREADY_CONFIGURED", "Telegram configuration already matched; service state was refreshed.")
            } else {
                success(
                    index, action, "TELEGRAM_CHANNEL_UPDATED", "Telegram channel updated through its Vault-backed runtime adapter.",
                    buildJsonObject {
                        put("enabled", next.enabled)
                        put("credential_source", if (requestedSlot != null || (!hasSlotField && next.vaultSlotId != null)) "VAULT" else if (next.token.isNotBlank()) "LEGACY_LOCAL" else "NONE")
                    },
                    Receipt(before, next, beforeBindings),
                )
            }
        } catch (error: Throwable) {
            preferences.update { before }
            runCatching { restoreBindings(request.authoritySubjectId, beforeBindings) }
            if (before.isUsable) TelegramBotService.start(appContext) else TelegramBotService.stop(appContext)
            failure(index, action, "TELEGRAM_CHANNEL_UPDATE_FAILED", "Telegram credential binding or service update failed.")
        }
    }

    private suspend fun snapshotBindings(subjectId: String): Map<String, List<SecretBinding>> =
        vault.listMetadata(subjectId).associate { slot ->
            slot.slotId to slot.bindings.filter {
                it.kind == SecretBindingKind.CHANNEL && it.targetId == TelegramCredentialResolver.TELEGRAM_BINDING_TARGET
            }
        }

    private suspend fun rebind(subjectId: String, slotId: String?) {
        val slots = vault.listMetadata(subjectId)
        if (slotId != null && slots.none { it.slotId == slotId }) error("telegram_vault_slot_missing")
        slots.forEach { slot ->
            val retained = slot.bindings.filterNot {
                it.kind == SecretBindingKind.CHANNEL && it.targetId == TelegramCredentialResolver.TELEGRAM_BINDING_TARGET
            }
            val next = if (slot.slotId == slotId) retained + SecretBinding(
                SecretBindingKind.CHANNEL,
                TelegramCredentialResolver.TELEGRAM_BINDING_TARGET,
            ) else retained
            if (next != slot.bindings) check(vault.updateBindings(slot.slotId, subjectId, next)) { "telegram_vault_binding_failed" }
        }
    }

    private suspend fun restoreBindings(subjectId: String, snapshot: Map<String, List<SecretBinding>>) {
        vault.listMetadata(subjectId).forEach { slot ->
            val retained = slot.bindings.filterNot {
                it.kind == SecretBindingKind.CHANNEL && it.targetId == TelegramCredentialResolver.TELEGRAM_BINDING_TARGET
            }
            check(vault.updateBindings(slot.slotId, subjectId, retained + snapshot[slot.slotId].orEmpty())) {
                "telegram_vault_binding_restore_failed"
            }
        }
    }

    private fun success(index: Int, action: OwnerAction, code: String, message: String, data: JsonObject? = null, receipt: Receipt? = null) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, true, code, message, data), receipt)
    private fun failure(index: Int, action: OwnerAction, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))

    private data class Receipt(
        val before: TelegramBotConfig,
        val expected: TelegramBotConfig,
        val beforeBindings: Map<String, List<SecretBinding>>,
    )

    private companion object {
        val FIELDS = mapOf(
            "channel_get" to emptySet(),
            "telegram_channel_update" to setOf(
                "enabled", "vault_slot_id", "default_chat_id", "whitelist", "assistant_id", "stream_screenshots",
            ),
        )
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean? = string(name)?.toBooleanStrictOrNull()
private fun JsonObject.longOrNull(name: String): Long? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

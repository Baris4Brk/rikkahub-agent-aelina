package me.rerere.rikkahub.security

import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpVaultSecretReference
import me.rerere.rikkahub.data.ai.mcp.control.McpHeaderRedactor
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/**
 * Result of resolving a typed runtime credential. `NotBound` means the runtime should use its
 * normal configuration; it is not a permission grant. `Unavailable` means an explicit vault
 * binding exists but cannot safely be used, so callers must fail closed rather than falling back
 * to a stale Settings secret.
 */
internal sealed interface SecretBindingResolution<out T> {
    data object NotBound : SecretBindingResolution<Nothing>
    data class Ready<T>(val value: T) : SecretBindingResolution<T>
    data class Unavailable(val code: String) : SecretBindingResolution<Nothing>
}

/**
 * Resolve an AI provider only through a binding owned by the current authority subject. The
 * cloned setting is intentionally local to the immediate provider call; it is never written to
 * Settings, a message, a tool result, diagnostics, or an export.
 */
internal suspend fun SecondUserSecretVault.resolveProviderBinding(
    provider: ProviderSetting,
    subjectId: String,
    petSidecar: Boolean = false,
): SecretBindingResolution<ProviderSetting> = resolveTypedBinding(
    subjectId = subjectId,
    kind = SecretBindingKind.PROVIDER,
    targetId = provider.id.toString(),
    petSidecar = petSidecar,
) { chars -> provider.withVaultApiKey(chars) }

/** Same constrained path for the selected TTS adapter. */
internal suspend fun SecondUserSecretVault.resolveTtsBinding(
    provider: TTSProviderSetting,
    subjectId: String,
    petSidecar: Boolean = false,
): SecretBindingResolution<TTSProviderSetting> = resolveTypedBinding(
    subjectId = subjectId,
    kind = SecretBindingKind.TTS,
    targetId = provider.id.toString(),
    petSidecar = petSidecar,
) { chars -> provider.withVaultApiKey(chars) }

/** Same constrained path for the selected ASR adapter. */
internal suspend fun SecondUserSecretVault.resolveAsrBinding(
    provider: ASRProviderSetting,
    subjectId: String,
): SecretBindingResolution<ASRProviderSetting> = resolveTypedBinding(
    subjectId = subjectId,
    kind = SecretBindingKind.ASR,
    targetId = provider.id.toString(),
    petSidecar = false,
) { chars -> provider.withVaultApiKey(chars) }

/** Resolve only reference-backed sensitive MCP headers for the current authority subject. */
internal suspend fun SecondUserSecretVault.resolveMcpHeaderBindings(
    serverId: Uuid,
    headers: List<Pair<String, String>>,
    subjectId: String,
): SecretBindingResolution<List<Pair<String, String>>> {
    if (headers.none { (_, value) -> McpVaultSecretReference.isReference(value) }) {
        return SecretBindingResolution.NotBound
    }
    val slots = listMetadata(subjectId).associateBy { it.slotId }
    val resolved = ArrayList<Pair<String, String>>(headers.size)
    headers.forEachIndexed { index, (name, value) ->
        val slotId = McpVaultSecretReference.slotIdOrNull(value)
        if (slotId == null) {
            resolved += name to value
            return@forEachIndexed
        }
        if (!McpHeaderRedactor.isSensitive(name)) {
            return SecretBindingResolution.Unavailable("mcp_secret_reference_requires_sensitive_header")
        }
        val slot = slots[slotId]
            ?: return SecretBindingResolution.Unavailable("mcp_secret_slot_missing")
        val binding = SecretBinding(
            kind = SecretBindingKind.MCP,
            targetId = McpVaultSecretReference.bindingTarget(serverId, name, index),
        )
        when (val lease = withLease(slot.slotId, subjectId, binding) { secret ->
            secret.use { chars -> chars.concatToString() }
        }) {
            is SecretLeaseResult.Success -> resolved += name to lease.value
            SecretLeaseResult.SlotMissing ->
                return SecretBindingResolution.Unavailable("mcp_secret_value_not_set")
            SecretLeaseResult.BindingDenied ->
                return SecretBindingResolution.Unavailable("mcp_secret_binding_denied")
            SecretLeaseResult.AuthorityDenied ->
                return SecretBindingResolution.Unavailable("second_user_authority_stale")
            SecretLeaseResult.KeystoreUnavailable ->
                return SecretBindingResolution.Unavailable("secret_keystore_unavailable")
            SecretLeaseResult.Corrupt ->
                return SecretBindingResolution.Unavailable("mcp_secret_slot_corrupt")
        }
    }
    return SecretBindingResolution.Ready(resolved)
}

private suspend fun <T> SecondUserSecretVault.resolveTypedBinding(
    subjectId: String,
    kind: SecretBindingKind,
    targetId: String,
    petSidecar: Boolean,
    copyWithSecret: (CharArray) -> T?,
): SecretBindingResolution<T> {
    val slot = listMetadata(subjectId).firstOrNull { metadata ->
        metadata.bindings.any { binding -> binding.kind == kind && binding.targetId == targetId }
    } ?: return SecretBindingResolution.NotBound
    val binding = slot.bindings.first { it.kind == kind && it.targetId == targetId }
    return when (val result = withLease(
        slotId = slot.slotId,
        subjectId = subjectId,
        binding = binding,
        purpose = if (petSidecar) SecretLeasePurpose.PET_SIDECAR else SecretLeasePurpose.EXECUTION,
    ) { lease ->
        lease.use(copyWithSecret)
    }) {
        is SecretLeaseResult.Success -> result.value?.let { value -> SecretBindingResolution.Ready(value) }
            ?: SecretBindingResolution.Unavailable("secret_adapter_unsupported")
        SecretLeaseResult.SlotMissing -> SecretBindingResolution.Unavailable("secret_value_not_set")
        SecretLeaseResult.BindingDenied -> SecretBindingResolution.Unavailable("secret_binding_denied")
        SecretLeaseResult.AuthorityDenied -> SecretBindingResolution.Unavailable("second_user_authority_stale")
        SecretLeaseResult.KeystoreUnavailable -> SecretBindingResolution.Unavailable("secret_keystore_unavailable")
        SecretLeaseResult.Corrupt -> SecretBindingResolution.Unavailable("secret_slot_corrupt")
    }
}

/** Only remote provider variants that accept an API key participate in the v1 vault bridge. */
internal fun ProviderSetting.legacyApiKeyOrNull(): String? = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
    else -> null
}

internal fun ProviderSetting.clearLegacyApiKey(): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(apiKey = "")
    is ProviderSetting.Google -> copy(apiKey = "")
    is ProviderSetting.Claude -> copy(apiKey = "")
    else -> this
}

private fun ProviderSetting.withVaultApiKey(chars: CharArray): ProviderSetting? = when (this) {
    is ProviderSetting.OpenAI -> copy(apiKey = chars.concatToString())
    is ProviderSetting.Google -> copy(apiKey = chars.concatToString())
    is ProviderSetting.Claude -> copy(apiKey = chars.concatToString())
    else -> null
}

internal fun TTSProviderSetting.legacyApiKeyOrNull(): String? = when (this) {
    is TTSProviderSetting.OpenAI -> apiKey
    is TTSProviderSetting.Gemini -> apiKey
    is TTSProviderSetting.MiniMax -> apiKey
    is TTSProviderSetting.Aura -> apiKey
    is TTSProviderSetting.Qwen -> apiKey
    is TTSProviderSetting.Groq -> apiKey
    is TTSProviderSetting.XAI -> apiKey
    is TTSProviderSetting.MiMo -> apiKey
    is TTSProviderSetting.SystemTTS -> null
    is TTSProviderSetting.GenericHttp -> null
}

internal fun TTSProviderSetting.clearLegacyApiKey(): TTSProviderSetting = when (this) {
    is TTSProviderSetting.OpenAI -> copy(apiKey = "")
    is TTSProviderSetting.Gemini -> copy(apiKey = "")
    is TTSProviderSetting.MiniMax -> copy(apiKey = "")
    is TTSProviderSetting.Aura -> copy(apiKey = "")
    is TTSProviderSetting.Qwen -> copy(apiKey = "")
    is TTSProviderSetting.Groq -> copy(apiKey = "")
    is TTSProviderSetting.XAI -> copy(apiKey = "")
    is TTSProviderSetting.MiMo -> copy(apiKey = "")
    is TTSProviderSetting.SystemTTS -> this
    is TTSProviderSetting.GenericHttp -> copy(runtimeSecret = "")
}

private fun TTSProviderSetting.withVaultApiKey(chars: CharArray): TTSProviderSetting? = when (this) {
    is TTSProviderSetting.OpenAI -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.Gemini -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.MiniMax -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.Aura -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.Qwen -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.Groq -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.XAI -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.MiMo -> copy(apiKey = chars.concatToString())
    is TTSProviderSetting.SystemTTS -> null
    is TTSProviderSetting.GenericHttp -> copy(runtimeSecret = chars.concatToString())
}

internal fun ASRProviderSetting.legacyApiKeyOrNull(): String? = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> apiKey
    is ASRProviderSetting.DashScope -> apiKey
    is ASRProviderSetting.Volcengine -> apiKey
}

internal fun ASRProviderSetting.clearLegacyApiKey(): ASRProviderSetting = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> copy(apiKey = "")
    is ASRProviderSetting.DashScope -> copy(apiKey = "")
    is ASRProviderSetting.Volcengine -> copy(apiKey = "")
}

private fun ASRProviderSetting.withVaultApiKey(chars: CharArray): ASRProviderSetting = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> copy(apiKey = chars.concatToString())
    is ASRProviderSetting.DashScope -> copy(apiKey = chars.concatToString())
    is ASRProviderSetting.Volcengine -> copy(apiKey = chars.concatToString())
}

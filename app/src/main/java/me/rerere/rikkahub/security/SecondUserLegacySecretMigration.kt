package me.rerere.rikkahub.security

import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.mcp.McpVaultSecretReference
import me.rerere.rikkahub.data.ai.mcp.control.McpHeaderRedactor
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.skills.js.SkillSecretsStore

/** A redacted result suitable for the settings page and Doctor; it contains no names or values. */
data class SecondUserLegacySecretMigrationResult(
    val migratedProviders: Int,
    val migratedTtsProviders: Int,
    val migratedAsrProviders: Int,
    val migratedMcpHeaders: Int,
    val pendingEntries: Int,
) {
    val migratedTotal: Int
        get() = migratedProviders + migratedTtsProviders + migratedAsrProviders + migratedMcpHeaders
}

/**
 * User-initiated one-way migration for legacy settings credentials.
 *
 * The old value is cleared only after an AES-GCM vault write and an execution-lease readback
 * both succeed. This covers only adapters with a typed vault execution path; generic JavaScript
 * secrets remain pending because they must be re-entered for a concrete typed host capability.
 */
class SecondUserLegacySecretMigration(
    private val settingsStore: SettingsStore,
    private val vault: SecondUserSecretVault,
    private val legacySkillSecrets: SkillSecretsStore,
) {
    suspend fun migrateForUser(
        authorization: SecretVaultUserAuthorization,
    ): SecondUserLegacySecretMigrationResult {
        val subjectId = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return SecondUserLegacySecretMigrationResult(0, 0, 0, 0, 0)
        val settings = settingsStore.settingsFlow.first { !it.init }
        val existing = vault.listMetadataForUser(authorization)
        var providerCount = 0
        var ttsCount = 0
        var asrCount = 0
        var mcpCount = 0
        var pending = 0
        val migratedProviderIds = mutableSetOf<String>()
        val migratedTtsIds = mutableSetOf<String>()
        val migratedAsrIds = mutableSetOf<String>()
        val migratedMcpHeaders = mutableMapOf<String, Map<Int, String>>()

        settings.providers.forEach { provider ->
            val secret = provider.legacyApiKeyOrNull()?.takeIf(String::isNotBlank) ?: return@forEach
            if (migrateOne(
                    authorization = authorization,
                    existing = existing,
                    subjectId = subjectId,
                    kind = SecretBindingKind.PROVIDER,
                    targetId = provider.id.toString(),
                    label = "Provider credential",
                    value = secret,
                )
            ) {
                migratedProviderIds += provider.id.toString()
                providerCount++
            } else {
                pending++
            }
        }
        settings.ttsProviders.forEach { provider ->
            val secret = provider.legacyApiKeyOrNull()?.takeIf(String::isNotBlank) ?: return@forEach
            if (migrateOne(
                    authorization = authorization,
                    existing = existing,
                    subjectId = subjectId,
                    kind = SecretBindingKind.TTS,
                    targetId = provider.id.toString(),
                    label = "TTS credential",
                    value = secret,
                )
            ) {
                migratedTtsIds += provider.id.toString()
                ttsCount++
            } else {
                pending++
            }
        }
        settings.asrProviders.forEach { provider ->
            val secret = provider.legacyApiKeyOrNull()?.takeIf(String::isNotBlank) ?: return@forEach
            if (migrateOne(
                    authorization = authorization,
                    existing = existing,
                    subjectId = subjectId,
                    kind = SecretBindingKind.ASR,
                    targetId = provider.id.toString(),
                    label = "ASR credential",
                    value = secret,
                )
            ) {
                migratedAsrIds += provider.id.toString()
                asrCount++
            } else {
                pending++
            }
        }
        settings.mcpServers.forEach { server ->
            val replacements = mutableMapOf<Int, String>()
            server.commonOptions.headers.forEachIndexed { index, (name, value) ->
                if (!McpHeaderRedactor.isSensitive(name) || value.isBlank() ||
                    McpVaultSecretReference.isReference(value)
                ) return@forEachIndexed
                if (migrateOne(
                        authorization = authorization,
                        existing = existing,
                        subjectId = subjectId,
                        kind = SecretBindingKind.MCP,
                        targetId = McpVaultSecretReference.bindingTarget(server.id, name, index),
                        label = "MCP credential",
                        value = value,
                    )
                ) {
                    val slotId = findSlotIdForBinding(
                        authorization = authorization,
                        subjectId = subjectId,
                        kind = SecretBindingKind.MCP,
                        targetId = McpVaultSecretReference.bindingTarget(server.id, name, index),
                    )
                    if (slotId != null) {
                        replacements[index] = McpVaultSecretReference.encode(slotId)
                        mcpCount++
                    } else {
                        pending++
                    }
                } else {
                    pending++
                }
            }
            if (replacements.isNotEmpty()) migratedMcpHeaders[server.id.toString()] = replacements
        }
        // Generic JavaScript has no typed secret consumer. Existing entries remain removable but
        // cannot flow back into model-controlled code; the user must re-enter a value later for
        // a concrete typed host capability.
        pending += legacySkillSecrets.list().size

        if (
            migratedProviderIds.isNotEmpty() || migratedTtsIds.isNotEmpty() ||
            migratedAsrIds.isNotEmpty() || migratedMcpHeaders.isNotEmpty()
        ) {
            settingsStore.update { current ->
                current.copy(
                    providers = current.providers.map { provider ->
                        if (provider.id.toString() in migratedProviderIds) provider.clearLegacyApiKey() else provider
                    },
                    ttsProviders = current.ttsProviders.map { provider ->
                        if (provider.id.toString() in migratedTtsIds) provider.clearLegacyApiKey() else provider
                    },
                    asrProviders = current.asrProviders.map { provider ->
                        if (provider.id.toString() in migratedAsrIds) provider.clearLegacyApiKey() else provider
                    },
                    mcpServers = current.mcpServers.map { server ->
                        val replacements = migratedMcpHeaders[server.id.toString()]
                            ?: return@map server
                        server.clone(
                            commonOptions = server.commonOptions.copy(
                                headers = server.commonOptions.headers.mapIndexed { index, header ->
                                    header.first to (replacements[index] ?: header.second)
                                },
                            ),
                        )
                    },
                )
            }
        }
        return SecondUserLegacySecretMigrationResult(providerCount, ttsCount, asrCount, mcpCount, pending)
    }

    private suspend fun migrateOne(
        authorization: SecretVaultUserAuthorization,
        existing: List<SecretSlotMetadata>,
        subjectId: String,
        kind: SecretBindingKind,
        targetId: String,
        label: String,
        value: String,
    ): Boolean {
        val binding = SecretBinding(kind = kind, targetId = targetId)
        val currentBinding = existing.firstOrNull { metadata ->
            metadata.authoritySubjectId == subjectId && binding in metadata.bindings
        }
        if (currentBinding != null) {
            // Never overwrite a value the user has already put in the Vault. If it can be leased,
            // the safe binding is already live and the stale Settings copy may be removed.
            return verifyBinding(currentBinding.slotId, subjectId, binding)
        }
        val slotId = nextSlotId(existing, kind, targetId)
        val now = System.currentTimeMillis()
        val created = vault.createEmptySlot(
            metadata = SecretSlotMetadata(
                slotId = slotId,
                label = label,
                purpose = "legacy-migration",
                authoritySubjectId = subjectId,
                bindings = listOf(binding),
                createdAtMs = now,
                updatedAtMs = now,
            ),
            subjectId = subjectId,
        )
        if (!created) return false
        val chars = value.toCharArray()
        if (!vault.storeForUser(authorization, slotId, chars)) return false
        return verifyBinding(slotId, subjectId, binding)
    }

    private suspend fun verifyBinding(
        slotId: String,
        subjectId: String,
        binding: SecretBinding,
    ): Boolean = when (val result = vault.withLease(slotId, subjectId, binding) { lease ->
        lease.use { chars -> chars.isNotEmpty() }
    }) {
        is SecretLeaseResult.Success -> result.value
        else -> false
    }

    private suspend fun findSlotIdForBinding(
        authorization: SecretVaultUserAuthorization,
        subjectId: String,
        kind: SecretBindingKind,
        targetId: String,
    ): String? = vault.listMetadataForUser(authorization)
        .firstOrNull { metadata ->
            metadata.authoritySubjectId == subjectId &&
                metadata.bindings.any { it.kind == kind && it.targetId == targetId }
        }
        ?.slotId

    private fun nextSlotId(
        existing: List<SecretSlotMetadata>,
        kind: SecretBindingKind,
        targetId: String,
    ): String {
        val base = "legacy.${kind.name.lowercase()}.${targetId}"
            .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            .take(88)
        if (existing.none { it.slotId == base }) return base
        return generateSequence(2) { it + 1 }
            .map { "$base.$it".take(96) }
            .first { candidate -> existing.none { it.slotId == candidate } }
    }
}

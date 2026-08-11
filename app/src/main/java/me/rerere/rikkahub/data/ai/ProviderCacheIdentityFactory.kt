package me.rerere.rikkahub.data.ai

import java.security.MessageDigest
import me.rerere.ai.provider.ProviderCacheIdentity

private const val PROVIDER_CACHE_IDENTITY_DOMAIN = "rikkahub.provider-cache-namespace.v1"

/**
 * Builds the opaque namespace used by local/provider prefix caches.
 *
 * Raw conversation, assistant, scope, and memory identifiers never cross the provider API. A
 * missing conversation identity deliberately disables cross-call warm reuse: background/system
 * calls must opt into a stable, explicitly scoped namespace instead of sharing a process-global
 * cache by accident.
 */
internal fun buildProviderCacheIdentity(
    conversationId: String?,
    assistantId: String,
    memoryScopeId: String,
    actualMemoryIds: Collection<Int>,
    compilerRevision: String,
): ProviderCacheIdentity? {
    val stableConversationId = conversationId?.takeIf(String::isNotBlank) ?: return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateLengthPrefixed(PROVIDER_CACHE_IDENTITY_DOMAIN)
    digest.updateLengthPrefixed(stableConversationId)
    digest.updateLengthPrefixed(assistantId)
    digest.updateLengthPrefixed(memoryScopeId)
    digest.updateLengthPrefixed(compilerRevision)
    actualMemoryIds.distinct().sorted().forEach { memoryId ->
        digest.updateLengthPrefixed(memoryId.toString())
    }
    val opaque = digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return ProviderCacheIdentity.fromOpaqueDigest(
        opaqueSha256 = opaque,
        compilerRevision = compilerRevision,
    )
}

private fun MessageDigest.updateLengthPrefixed(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    update(byteArrayOf(
        (bytes.size ushr 24).toByte(),
        (bytes.size ushr 16).toByte(),
        (bytes.size ushr 8).toByte(),
        bytes.size.toByte(),
    ))
    update(bytes)
}

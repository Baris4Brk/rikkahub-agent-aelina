package me.rerere.rikkahub.data.ai

import java.security.MessageDigest
import me.rerere.ai.provider.ProviderCacheIdentity

private const val PROVIDER_CACHE_IDENTITY_DOMAIN = "rikkahub.provider-cache-namespace.v3"
private const val PROVIDER_CACHE_COMPILER_REVISION = "provider-wire-dream-v3"

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
    memoryProjectionText: String,
    compilerRevision: String,
    /** Exact post-transform, post-hard-gate provider message projection. */
    finalWireProjectionText: String = memoryProjectionText,
    /** Canonical, identifier-free [DreamCacheProjectionDigestInput] material. */
    dreamCacheProjectionCanonicalJson: String? = null,
    dreamCompilerRevision: String? = null,
): ProviderCacheIdentity? {
    val stableConversationId = conversationId?.takeIf(String::isNotBlank) ?: return null
    require((dreamCacheProjectionCanonicalJson == null) == (dreamCompilerRevision == null)) {
        "Dream cache projection and compiler revision must be supplied together"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateDimension("domain", PROVIDER_CACHE_IDENTITY_DOMAIN)
    digest.updateDimension("conversation", stableConversationId)
    digest.updateDimension("assistant", assistantId)
    // Scope is a separate salted dimension. It is never folded into the Dream projection digest,
    // so byte-identical global/private snapshots cannot share a local/provider cache namespace.
    digest.updateDimension(
        "scope_salt_sha256",
        sha256Utf8("$PROVIDER_CACHE_IDENTITY_DOMAIN.scope\u0000$memoryScopeId"),
    )
    digest.updateDimension("memory_compiler_revision", compilerRevision)
    // IDs alone are not a cache identity: an in-place memory edit keeps the same ID while changing
    // the exact prompt bytes. Bind warm reuse to the final atomic memory projection as well.
    digest.updateDimension("memory_projection_sha256", sha256Utf8(memoryProjectionText))
    actualMemoryIds.distinct().sorted().forEach { memoryId ->
        digest.updateDimension("memory_id", memoryId.toString())
    }
    // Strongly bind reuse to what actually survived transformers and the final context hard gate.
    // Hashing first keeps the namespace input bounded without persisting or logging prompt text.
    digest.updateDimension("final_wire_sha256", sha256Utf8(finalWireProjectionText))
    if (dreamCacheProjectionCanonicalJson != null) {
        digest.updateDimension("dream_compiler_revision", checkNotNull(dreamCompilerRevision))
        digest.updateDimension(
            "dream_projection_sha256",
            sha256Utf8(dreamCacheProjectionCanonicalJson),
        )
    } else {
        digest.updateDimension("dream_projection", "absent")
    }
    val opaque = digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return ProviderCacheIdentity.fromOpaqueDigest(
        opaqueSha256 = opaque,
        compilerRevision = PROVIDER_CACHE_COMPILER_REVISION,
    )
}

private fun MessageDigest.updateDimension(label: String, value: String) {
    updateLengthPrefixed(label)
    updateLengthPrefixed(value)
}

private fun sha256Utf8(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
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

package me.rerere.rikkahub.owner

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.execution.ExecutionTokenProvider

/** Opaque request identity used to reject request_id reuse with different arguments. */
fun interface OwnerOperationFingerprinter {
    fun fingerprint(request: OwnerOperationRequest): String
}

/** Production HMAC keeps even low-entropy secret transformation arguments opaque in Room. */
class KeystoreOwnerOperationFingerprinter(
    private val tokens: ExecutionTokenProvider,
) : OwnerOperationFingerprinter {
    override fun fingerprint(request: OwnerOperationRequest): String {
        val digest = ownerOperationCanonicalDigest(request)
        return tokens.ownerTokenFor(
            domain = "ownerop_$digest",
            assistantId = request.assistantId,
            conversationId = request.conversationId,
            origin = request.family.name,
        )
    }
}

/** Deterministic non-secret fallback used by host-independent JVM tests only. */
internal object Sha256OwnerOperationFingerprinter : OwnerOperationFingerprinter {
    override fun fingerprint(request: OwnerOperationRequest): String = ownerOperationCanonicalDigest(request)
}

private fun ownerOperationCanonicalDigest(request: OwnerOperationRequest): String {
    val canonical = buildString {
        append(request.family.name).append('\u0000')
        append(request.authoritySubjectId).append('\u0000')
        append(request.modelId.orEmpty()).append('\u0000')
        append(request.providerId.orEmpty()).append('\u0000')
        request.actions.forEach { action ->
            append(action.type).append('\u0000')
            append(action.risk.name).append('\u0000')
            append(action.arguments.canonicalJson()).append('\u0000')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun JsonElement.canonicalJson(): String = when (this) {
    is JsonObject -> entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
        kotlinx.serialization.json.JsonPrimitive(it.key).toString() + ":" + it.value.canonicalJson()
    }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalJson() }
    else -> toString()
}

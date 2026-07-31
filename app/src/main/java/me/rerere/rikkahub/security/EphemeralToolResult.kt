package me.rerere.rikkahub.security

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

data class EphemeralToolResult(
    val token: String,
    val persistedPlaceholder: String = "[SECRET_REVEALED]",
)

/**
 * One-use, process-memory bridge between a tool result and the immediately following Provider
 * request. Persisted conversation copies contain only the placeholder.
 */
class EphemeralToolResultStore internal constructor(
    private val allowsEgress: (SecretPlaintextSessionBinding, SecretPlaintextSessionBinding) -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        egressGuard: SecretEgressGuard,
        nowMs: () -> Long = System::currentTimeMillis,
    ) : this(egressGuard::allows, nowMs)

    private data class Entry(
        val value: CharArray,
        val binding: SecretPlaintextSessionBinding,
        val expiresAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun issue(
        value: CharArray,
        binding: SecretPlaintextSessionBinding,
    ): EphemeralToolResult {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(value.copyOf(), binding, nowMs() + RESULT_TTL_MS)
        return EphemeralToolResult(token)
    }

    fun materializeForProvider(
        messages: List<UIMessage>,
        target: SecretPlaintextSessionBinding,
    ): List<UIMessage> = messages.map { message ->
        message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            part.copy(output = part.output.map { output ->
                if (output !is UIMessagePart.Text) return@map output
                materializeText(output, target)
            })
        })
    }

    fun clear() {
        entries.values.forEach { it.value.fill('\u0000') }
        entries.clear()
    }

    private fun materializeText(
        output: UIMessagePart.Text,
        target: SecretPlaintextSessionBinding,
    ): UIMessagePart.Text {
        val parsed = runCatching { JsonInstant.parseToJsonElement(output.text).jsonObject }.getOrNull()
            ?: return output
        val tokens = collectTokens(parsed)
        if (tokens.isEmpty()) return output
        var materialized: JsonElement = parsed
        tokens.forEach { token ->
            val entry = entries.remove(token)
            if (entry == null) {
                materialized = replaceToken(materialized, token, null, "SECRET_EPHEMERAL_EXPIRED")
                return@forEach
            }
            try {
                val allowed = nowMs() <= entry.expiresAtMs && allowsEgress(entry.binding, target)
                materialized = replaceToken(
                    element = materialized,
                    token = token,
                    value = entry.value.takeIf { allowed },
                    failureCode = if (allowed) null else "SECRET_EGRESS_DENIED",
                )
            } finally {
                entry.value.fill('\u0000')
            }
        }
        return output.copy(text = materialized.toString())
    }

    private fun collectTokens(element: JsonElement): LinkedHashSet<String> = linkedSetOf<String>().also { out ->
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    (value[EPHEMERAL_TOKEN_FIELD] as? JsonPrimitive)?.contentOrNull?.let(out::add)
                    value.values.forEach(::visit)
                }
                is JsonArray -> value.forEach(::visit)
                else -> Unit
            }
        }
        visit(element)
    }

    private fun replaceToken(
        element: JsonElement,
        token: String,
        value: CharArray?,
        failureCode: String?,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val ownsToken = (element[EPHEMERAL_TOKEN_FIELD] as? JsonPrimitive)?.contentOrNull == token
            buildJsonObject {
                element.forEach { (key, child) ->
                    when {
                        key == EPHEMERAL_TOKEN_FIELD && ownsToken -> Unit
                        key == "value" && ownsToken -> put(
                            key,
                            value?.concatToString() ?: "[SECRET_REVEALED]",
                        )
                        else -> put(key, replaceToken(child, token, value, failureCode))
                    }
                }
                if (ownsToken) {
                    put("ephemeral", true)
                    if (failureCode == null) {
                        put("instruction", "Use this value only for the current requested task. Never quote or expose it.")
                    } else {
                        put("ok", false)
                        put("code", failureCode)
                    }
                }
            }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(replaceToken(it, token, value, failureCode)) }
        }
        is JsonPrimitive -> element
        else -> element
    }

    private fun purgeExpired() {
        val now = nowMs()
        entries.entries.removeIf { (_, value) ->
            (now > value.expiresAtMs).also { expired -> if (expired) value.value.fill('\u0000') }
        }
    }

    companion object {
        const val EPHEMERAL_TOKEN_FIELD = "_ephemeral_secret_token"
        const val RESULT_TTL_MS = 2 * 60 * 1000L
    }
}

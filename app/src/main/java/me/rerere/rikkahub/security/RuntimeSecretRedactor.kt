package me.rerere.rikkahub.security

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/** Process-local known-secret redactor. Its buffers are cleared whenever the session closes. */
class RuntimeSecretRedactor {
    private val values = mutableListOf<CharArray>()

    @Synchronized
    fun remember(value: CharArray) {
        if (value.isEmpty()) return
        if (values.any { it.contentEquals(value) }) return
        if (values.size >= MAX_VALUES) {
            values.removeAt(0).fill('\u0000')
        }
        values += value.copyOf()
    }

    @Synchronized
    fun redact(text: String): String {
        var result = text
        values.forEach { chars ->
            val raw = chars.concatToString()
            val bytes = raw.toByteArray(StandardCharsets.UTF_8)
            try {
                val forms = linkedSetOf(
                    raw,
                    Base64.getEncoder().encodeToString(bytes),
                    Base64.getUrlEncoder().encodeToString(bytes),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                    bytes.joinToString("") { "%02x".format(it) },
                    bytes.joinToString("") { "%02X".format(it) },
                    URLEncoder.encode(raw, StandardCharsets.UTF_8.name()),
                ).filter { it.isNotEmpty() }
                forms.forEach { form -> result = result.replace(form, REDACTED) }
            } finally {
                bytes.fill(0)
            }
        }
        return result
    }

    /** True for a remembered plaintext value or one of the common encodings redacted above. */
    fun containsKnownSecret(text: String): Boolean = redact(text) != text

    fun redactMessages(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        message.copy(parts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(text = redact(part.text))
                is UIMessagePart.Tool -> part.copy(
                    input = redact(part.input),
                    output = part.output.map { output ->
                        if (output is UIMessagePart.Text) output.copy(text = redact(output.text)) else output
                    },
                )
                else -> part
            }
        })
    }

    @Synchronized
    fun clear() {
        values.forEach { it.fill('\u0000') }
        values.clear()
    }

    private companion object {
        const val MAX_VALUES = 128
        const val REDACTED = "[SECRET_REDACTED]"
    }
}

/** Exact binding check for the only allowed plaintext egress path. */
class SecretEgressGuard(
    private val sessions: SecretPlaintextSessionManager,
) {
    fun allows(
        source: SecretPlaintextSessionBinding,
        target: SecretPlaintextSessionBinding,
    ): Boolean = source == target && sessions.isOpenFor(target)
}

/** Explicit wrapper for sensitive action parameters; closing clears the mutable buffer. */
class SensitiveToolArgument private constructor(private val value: CharArray) : AutoCloseable {
    fun <T> use(block: (CharArray) -> T): T = block(value)
    suspend fun <T> useSuspending(block: suspend (CharArray) -> T): T = block(value)
    override fun close() = value.fill('\u0000')

    companion object {
        fun from(value: String): SensitiveToolArgument = SensitiveToolArgument(value.toCharArray())
    }
}

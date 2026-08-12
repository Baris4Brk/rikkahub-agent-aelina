package me.rerere.rikkahub.memory

import java.security.MessageDigest
import me.rerere.rikkahub.data.repository.MemoryRepository
import kotlin.uuid.Uuid

internal const val MAX_MEMORY_CAPTURE_SOURCE_IDENTITIES = 64

/** Shared authorization invariant for every capture and processing boundary. */
internal fun isValidMemoryScopeBinding(scopeId: String, assistantId: String): Boolean {
    val assistant = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return false
    if (scopeId == MemoryRepository.GLOBAL_MEMORY_ID) return true
    return runCatching { Uuid.parse(scopeId) }.getOrNull() == assistant
}

internal fun buildMemorySourceIdentities(
    captureId: String,
    conversationId: String,
    sources: List<MemoryCaptureSourceInput>,
): List<MemorySourceIdentity>? {
    if (captureId.isBlank() || conversationId.isBlank()) return null
    val normalized = sources.mapNotNull { source ->
        val messageId = source.messageId.trim()
        if (messageId.isEmpty() || source.text.isBlank()) return@mapNotNull null
        MemorySourceIdentity(
            conversationId = conversationId,
            messageId = messageId,
            role = source.role,
            consumedTextDigest = memorySourceTextDigest(source.text),
            evidenceGroupId = captureId,
        )
    }.distinctBy { identity ->
        Triple(identity.messageId, identity.role, identity.consumedTextDigest)
    }
    if (normalized.isEmpty() || normalized.size > MAX_MEMORY_CAPTURE_SOURCE_IDENTITIES) return null
    return normalized
}

internal fun effectiveMemorySourceIdentities(
    capture: MemoryCaptureRecord,
): List<MemorySourceIdentity> = capture.sourceIdentities.ifEmpty {
    if (!capture.sourceIdentityFallbackAllowed) return@ifEmpty emptyList()
    buildMemorySourceIdentities(
        captureId = capture.id,
        conversationId = capture.conversationId,
        sources = buildList {
            if (capture.userText.isNotBlank()) {
                add(MemoryCaptureSourceInput(
                    capture.userMessageId,
                    MemorySourceRole.USER,
                    capture.userText,
                ))
            }
            if (capture.assistantText.isNotBlank()) {
                add(MemoryCaptureSourceInput(
                    capture.assistantMessageId,
                    MemorySourceRole.ASSISTANT,
                    capture.assistantText,
                ))
            }
        },
    ).orEmpty()
}

internal fun memorySourceTextDigest(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

internal fun MemorySourceIdentity.isValidForCapture(
    captureId: String,
    conversationId: String,
): Boolean = this.conversationId == conversationId &&
    messageId.isNotBlank() && evidenceGroupId == captureId &&
    consumedTextDigest.length == 64 && consumedTextDigest.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }

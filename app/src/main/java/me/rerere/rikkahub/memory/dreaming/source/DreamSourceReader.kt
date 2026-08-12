package me.rerere.rikkahub.memory.dreaming.source

import me.rerere.rikkahub.memory.MemorySourceKind
import me.rerere.rikkahub.memory.MemorySourceRole
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.requireDreamBoundedText
import me.rerere.rikkahub.memory.dreaming.model.requireDreamValidUnicode
import me.rerere.rikkahub.memory.dreaming.temporal.strictZoneOrNull

data class DreamSourceLocator(
    val scopeId: DreamScopeId,
    val conversationId: String,
    val messageId: String,
    val role: MemorySourceRole,
    val sourceKind: MemorySourceKind,
    val expectedConsumedTextDigest: DreamSha256,
    val evidenceGroupId: String,
) {
    init {
        requireDreamBoundedText(conversationId, 512, "conversationId")
        requireDreamBoundedText(messageId, 512, "messageId")
        requireDreamBoundedText(evidenceGroupId, 512, "evidenceGroupId")
    }
}

data class DreamSourceReadRequest(
    val scopeId: DreamScopeId,
    val frozenNowEpochMs: Long,
    val sourceTimezoneId: String,
    val locators: List<DreamSourceLocator>,
    val maxTotalUtf8Bytes: Int,
) {
    init {
        require(frozenNowEpochMs >= 0L)
        require(strictZoneOrNull(sourceTimezoneId) != null) { "sourceTimezoneId must be a strict IANA zone" }
        require(locators.size <= 4_096)
        require(locators.all { it.scopeId == scopeId })
        require(maxTotalUtf8Bytes in 0..2_000_000)
    }
}

sealed interface DreamSourceReadResult {
    val locator: DreamSourceLocator

    data class Found(
        override val locator: DreamSourceLocator,
        val text: String,
        val sourceTimestampEpochMs: Long,
        val consumedTextDigest: DreamSha256,
    ) : DreamSourceReadResult {
        init {
            require(text.length <= 128_000)
            require(sourceTimestampEpochMs >= 0L)
            requireDreamValidUnicode(text)
        }
    }

    data class Unavailable(
        override val locator: DreamSourceLocator,
        val reason: DreamSourceUnavailableReason,
    ) : DreamSourceReadResult
}

enum class DreamSourceUnavailableReason {
    MISSING,
    DIGEST_MISMATCH,
    TOMBSTONED,
    SCOPE_MISMATCH,
    UNSUPPORTED_KIND,
    BUDGET_EXCEEDED,
}

/** Reads only caller-supplied, host-owned locators. Model text can never create a locator. */
fun interface DreamSourceReader {
    suspend fun read(request: DreamSourceReadRequest): List<DreamSourceReadResult>
}

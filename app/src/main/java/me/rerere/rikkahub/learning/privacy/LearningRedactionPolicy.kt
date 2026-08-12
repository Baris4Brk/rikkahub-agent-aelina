package me.rerere.rikkahub.learning.privacy

import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.storage.LearningJobErrorCode

private const val MAX_OUTBOUND_EVIDENCE = 32

/** Ephemeral aliases for one background attempt. This object must never be persisted. */
class LearningEvidenceAliasTable private constructor(
    private val aliases: Map<LearningSourceRef, String>,
) {
    fun aliasFor(source: LearningSourceRef): String =
        requireNotNull(aliases[source]) { "Source is outside the outbound evidence allowlist" }

    fun containsAlias(alias: String): Boolean = aliases.values.any { it == alias }

    val size: Int
        get() = aliases.size

    override fun toString(): String = "LearningEvidenceAliasTable(size=$size, sources=<redacted>)"

    companion object {
        fun create(sources: List<LearningSourceRef>): LearningEvidenceAliasTable {
            require(sources.size <= MAX_OUTBOUND_EVIDENCE) { "Too many outbound evidence sources" }
            require(sources.distinct().size == sources.size) { "Duplicate outbound evidence source" }
            require(sources.all { it.eligibleForPersistentPolicyEvidence }) {
                "Outbound evidence requires a known authoritative revision"
            }
            return LearningEvidenceAliasTable(
                sources.mapIndexed { index, source -> source to "E${index + 1}" }.toMap(),
            )
        }
    }
}

data class LearningOutboundReceipt(
    val providerIdentityDigest: String,
    val modelIdentityDigest: String,
    val fieldCategories: Set<LearningOutboundFieldCategory>,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val costMicros: Long?,
    val createdAtMs: Long,
) {
    init {
        require(providerIdentityDigest.isLowerSha256()) { "Invalid provider identity digest" }
        require(modelIdentityDigest.isLowerSha256()) { "Invalid model identity digest" }
        require(fieldCategories.isNotEmpty()) { "Outbound receipt requires field categories" }
        require(inputTokens == null || inputTokens >= 0L) { "Negative input token count" }
        require(outputTokens == null || outputTokens >= 0L) { "Negative output token count" }
        require(costMicros == null || costMicros >= 0L) { "Negative cost" }
        require(createdAtMs >= 0L) { "Negative receipt time" }
    }

    override fun toString(): String =
        "LearningOutboundReceipt(fields=$fieldCategories, inputTokens=$inputTokens, " +
            "outputTokens=$outputTokens, costKnown=${costMicros != null}, identities=<redacted>)"
}

enum class LearningOutboundFieldCategory {
    REDACTED_TASK_FEATURES,
    OUTCOME_CLASS,
    EVIDENCE_ALIAS,
    BOUNDED_USER_FEEDBACK,
}

object LearningErrorCodePolicy {
    fun parseOrUnknown(rawCode: String?): LearningJobErrorCode =
        LearningJobErrorCode.entries.firstOrNull { it.name == rawCode }
            ?: LearningJobErrorCode.UNKNOWN
}

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

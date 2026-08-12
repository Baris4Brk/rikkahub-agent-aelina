package me.rerere.rikkahub.learning.retrieval

enum class PolicyRetrievalDropReason {
    SCOPE_MISMATCH,
    STATUS_INELIGIBLE,
    SOURCE_STALE,
    TOOL_SCHEMA_STALE,
    TASK_MISMATCH,
    BELOW_SCORE,
    CANDIDATE_LIMIT,
    LATENCY_BUDGET,
}

data class PolicyRetrievalTrace(
    val queryTermCount: Int,
    val exactCandidateCount: Int,
    val lexicalCandidateCount: Int,
    val selectedCount: Int,
    val estimatedTokens: Int,
    val latencyMicros: Long,
    val dropReasonCounts: Map<PolicyRetrievalDropReason, Int>,
    val selectedOpaqueIds: List<String>,
) {
    init {
        require(queryTermCount in 0..64)
        require(exactCandidateCount >= 0 && lexicalCandidateCount >= 0 && selectedCount >= 0)
        require(estimatedTokens >= 0 && latencyMicros >= 0L)
        require(dropReasonCounts.values.all { it >= 0 })
        require(selectedOpaqueIds.size == selectedCount)
        require(selectedOpaqueIds.all { it.matches(Regex("policy-hit-v1:[0-9a-f]{64}")) })
    }

    override fun toString(): String =
        "PolicyRetrievalTrace(terms=$queryTermCount, exact=$exactCandidateCount, " +
            "lexical=$lexicalCandidateCount, selected=$selectedCount, tokens=$estimatedTokens, " +
            "latencyMicros=$latencyMicros, drops=$dropReasonCounts, ids=<opaque>)"
}

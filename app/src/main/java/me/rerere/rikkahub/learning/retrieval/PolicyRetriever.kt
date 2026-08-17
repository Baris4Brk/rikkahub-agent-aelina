package me.rerere.rikkahub.learning.retrieval

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import me.rerere.rikkahub.execution.ExecutionTokenProvider
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.task.TaskSignatureV1

const val MAX_POLICY_RETRIEVAL_INPUT_CANDIDATES: Int = 128
const val MAX_POLICY_RAW_QUERY_CHARS: Int = 8_192

/** Produces a non-reversible, installation-scoped identifier for content-free diagnostics. */
fun interface PolicyOpaqueIdFactory {
    fun opaqueId(policyId: String): String
}

/** Keystore-backed production implementation; the durable policy ID is never logged or returned. */
class KeystorePolicyOpaqueIdFactory(
    private val tokens: ExecutionTokenProvider,
) : PolicyOpaqueIdFactory {
    override fun opaqueId(policyId: String): String {
        val digest = LearningCanonicalId.digest(
            domainVersion = "policy-shadow-id-v1",
            fields = listOf(policyId),
        )
        val left = tokens.ownerTokenFor("policy_shadow_v1", digest, "opaque", "left")
        val right = tokens.ownerTokenFor("policy_shadow_v1", digest, "opaque", "right")
        return "policy-hit-v1:$left$right".also(::requireOpaquePolicyId)
    }
}

data class PolicyShadowCandidate(
    val policyId: String,
    val scope: LearningScope,
    val taskSignature: TaskSignatureV1,
    val status: LearningPolicyStatus,
    val artifactHash: String,
    val sourceValid: Boolean,
    val toolSchemaValid: Boolean,
    val searchableText: String,
    val estimatedTokens: Int,
    val updatedAtMs: Long,
    /** Exact lifecycle and immutable-content fences captured by the bounded Room read. */
    val stateVersion: Long = 1L,
    val contentRevision: Long = 1L,
) {
    init {
        require(policyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
        require(artifactHash.matches(Regex("[0-9a-f]{64}")))
        require(searchableText.length <= 8_192)
        require(estimatedTokens in 0..4_096)
        require(updatedAtMs >= 0L)
        require(stateVersion > 0L && contentRevision > 0L)
    }

    override fun toString(): String =
        "PolicyShadowCandidate(status=$status, sourceValid=$sourceValid, " +
            "schemaValid=$toolSchemaValid, text=<redacted>, ids=<redacted>)"
}

data class PolicyRetrievalRequest(
    val scope: LearningScope,
    val taskSignature: TaskSignatureV1,
    val query: String,
    val maxCandidates: Int = 5,
    val maxEstimatedTokens: Int = 1_024,
    val maxLatencyMicros: Long = 50_000L,
) {
    init {
        require(query.length <= MAX_POLICY_RAW_QUERY_CHARS) { "Policy query is too large" }
        require(maxCandidates in 1..20)
        require(maxEstimatedTokens in 1..8_192)
        require(maxLatencyMicros in 1L..500_000L)
    }

    override fun toString(): String =
        "PolicyRetrievalRequest(scope=${scope.kind}, candidates=$maxCandidates, " +
            "tokens=$maxEstimatedTokens, query=<redacted>)"
}

data class PolicyRetrievalHit(
    val candidate: PolicyShadowCandidate,
    val exactTaskMatch: Boolean,
    val lexicalScore: Double,
    val rank: Int,
) {
    init {
        require(lexicalScore.isFinite() && lexicalScore in 0.0..1.0)
        require(rank > 0)
    }
}

data class PolicyRetrievalResult(
    val hits: List<PolicyRetrievalHit>,
    val trace: PolicyRetrievalTrace,
)

/**
 * P1 shadow-only retriever. It accepts already bounded rows, re-applies every hard predicate before
 * scoring, and has no provider-request compiler dependency or provider-facing output type.
 */
class PolicyRetriever(
    private val opaqueIds: PolicyOpaqueIdFactory,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    constructor(
        traceKey: ByteArray,
        monotonicNanos: () -> Long = System::nanoTime,
    ) : this(
        opaqueIds = keyedOpaqueIdFactory(traceKey),
        monotonicNanos = monotonicNanos,
    )

    fun retrieve(
        request: PolicyRetrievalRequest,
        candidates: List<PolicyShadowCandidate>,
    ): PolicyRetrievalResult {
        require(candidates.size <= MAX_POLICY_RETRIEVAL_INPUT_CANDIDATES) {
            "Unbounded policy retrieval candidate set"
        }
        val started = monotonicNanos()
        val budgetNanos = request.maxLatencyMicros * 1_000L
        fun elapsedNanos(): Long = (monotonicNanos() - started).coerceAtLeast(0L)
        fun budgetExpired(): Boolean = elapsedNanos() >= budgetNanos
        val query = PolicyFtsManager.prepareQuery(request.query)
        val drops = linkedMapOf<PolicyRetrievalDropReason, Int>()
        var exactCount = 0
        var lexicalCount = 0
        val scored = mutableListOf<Pair<PolicyShadowCandidate, Double>>()
        for ((index, candidate) in candidates.withIndex()) {
            if (budgetExpired()) {
                drops.incrementBy(
                    PolicyRetrievalDropReason.LATENCY_BUDGET,
                    candidates.size - index,
                )
                break
            }
            when {
                candidate.scope != request.scope -> drops.increment(PolicyRetrievalDropReason.SCOPE_MISMATCH)
                candidate.status !in setOf(LearningPolicyStatus.CANDIDATE, LearningPolicyStatus.SHADOW) ->
                    drops.increment(PolicyRetrievalDropReason.STATUS_INELIGIBLE)
                !candidate.sourceValid -> drops.increment(PolicyRetrievalDropReason.SOURCE_STALE)
                !candidate.toolSchemaValid -> drops.increment(PolicyRetrievalDropReason.TOOL_SCHEMA_STALE)
                else -> {
                    val exact = candidate.taskSignature == request.taskSignature
                    if (exact) exactCount += 1
                    val lexical = PolicyFtsManager.lexicalScore(query, candidate.searchableText)
                    if (lexical > 0.0) lexicalCount += 1
                    if (!exact && lexical <= 0.0) {
                        drops.increment(PolicyRetrievalDropReason.BELOW_SCORE)
                    } else {
                        scored += candidate to (if (exact) 2.0 + lexical else lexical)
                    }
                }
            }
        }
        if (budgetExpired()) {
            drops.incrementBy(PolicyRetrievalDropReason.LATENCY_BUDGET, scored.size)
            return emptyResult(started, query, exactCount, lexicalCount, drops)
        }
        val ordered = scored.sortedWith(
            compareByDescending<Pair<PolicyShadowCandidate, Double>> { it.second }
                .thenByDescending { it.first.updatedAtMs }
                .thenBy { it.first.policyId },
        )
        val selected = mutableListOf<PolicyRetrievalHit>()
        val selectedOpaqueIds = mutableListOf<String>()
        var tokens = 0
        for ((index, scoredCandidate) in ordered.withIndex()) {
            if (budgetExpired()) {
                drops.incrementBy(
                    PolicyRetrievalDropReason.LATENCY_BUDGET,
                    ordered.size - index,
                )
                break
            }
            val (candidate, score) = scoredCandidate
            if (selected.size >= request.maxCandidates) {
                drops.incrementBy(
                    PolicyRetrievalDropReason.CANDIDATE_LIMIT,
                    ordered.size - index,
                )
                break
            }
            if (tokens + candidate.estimatedTokens > request.maxEstimatedTokens) {
                drops.increment(PolicyRetrievalDropReason.CANDIDATE_LIMIT)
                continue
            }
            val opaqueId = opaqueIds.opaqueId(candidate.policyId).also(::requireOpaquePolicyId)
            if (budgetExpired()) {
                drops.incrementBy(
                    PolicyRetrievalDropReason.LATENCY_BUDGET,
                    ordered.size - index,
                )
                break
            }
            tokens += candidate.estimatedTokens
            selected += PolicyRetrievalHit(
                candidate = candidate,
                exactTaskMatch = candidate.taskSignature == request.taskSignature,
                lexicalScore = score.coerceAtMost(1.0),
                rank = selected.size + 1,
            )
            selectedOpaqueIds += opaqueId
        }
        val elapsedMicros = elapsedNanos() / 1_000L
        return PolicyRetrievalResult(
            hits = selected,
            trace = PolicyRetrievalTrace(
                queryTermCount = query.terms.size,
                exactCandidateCount = exactCount,
                lexicalCandidateCount = lexicalCount,
                selectedCount = selected.size,
                estimatedTokens = tokens,
                latencyMicros = elapsedMicros,
                dropReasonCounts = drops.toMap(),
                selectedOpaqueIds = selectedOpaqueIds,
            ),
        )
    }

    private fun emptyResult(
        started: Long,
        query: BoundedPolicyQuery,
        exactCount: Int,
        lexicalCount: Int,
        drops: Map<PolicyRetrievalDropReason, Int>,
    ): PolicyRetrievalResult = PolicyRetrievalResult(
        hits = emptyList(),
        trace = PolicyRetrievalTrace(
            queryTermCount = query.terms.size,
            exactCandidateCount = exactCount,
            lexicalCandidateCount = lexicalCount,
            selectedCount = 0,
            estimatedTokens = 0,
            latencyMicros = ((monotonicNanos() - started).coerceAtLeast(0L)) / 1_000L,
            dropReasonCounts = drops,
            selectedOpaqueIds = emptyList(),
        ),
    )
}

private fun MutableMap<PolicyRetrievalDropReason, Int>.increment(reason: PolicyRetrievalDropReason) {
    this[reason] = (this[reason] ?: 0) + 1
}

private fun MutableMap<PolicyRetrievalDropReason, Int>.incrementBy(
    reason: PolicyRetrievalDropReason,
    amount: Int,
) {
    require(amount >= 0)
    if (amount > 0) this[reason] = Math.addExact(this[reason] ?: 0, amount)
}

private fun keyedOpaqueIdFactory(traceKey: ByteArray): PolicyOpaqueIdFactory {
    require(traceKey.size >= 32) { "Policy trace key is too short" }
    val key = traceKey.copyOf()
    return PolicyOpaqueIdFactory { policyId ->
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal(policyId.toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        "policy-hit-v1:$digest"
    }
}

private fun requireOpaquePolicyId(value: String) {
    require(value.matches(Regex("policy-hit-v1:[0-9a-f]{64}"))) {
        "Invalid opaque policy identifier"
    }
}

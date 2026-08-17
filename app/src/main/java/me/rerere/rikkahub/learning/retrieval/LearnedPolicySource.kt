package me.rerere.rikkahub.learning.retrieval

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import kotlin.uuid.Uuid

const val MAX_LEARNED_POLICY_CONTEXT_CANDIDATES: Int = 20
const val MAX_LEARNED_POLICY_CONTEXT_ITEM_CHARS: Int = 4_096
const val MAX_LEARNED_POLICY_CONTEXT_PACKET_CHARS: Int = 16_384
const val MAX_LEARNED_POLICY_CONTEXT_ITEM_ESTIMATED_TOKENS: Int = 4_096
const val MAX_LEARNED_POLICY_CONTEXT_PACKET_ESTIMATED_TOKENS: Int = 8_192

/** A learned Policy is always data-only advice, even after an explicit review/grant. */
enum class LearnedPolicyContextTrust {
    UNTRUSTED_CONTEXT_ONLY,
}

/**
 * Frozen, content-bounded retrieval input. Provider/model/tool applicability is deliberately not
 * represented here: the caller must re-check those non-secret identities after the final provider
 * binding has been resolved. Raw credentials and secret-derived hashes never belong in this type.
 */
data class LearnedPolicyQuery(
    val scope: LearningScope,
    /** Exact Assistant whose provider request may consume the returned advice. */
    val consumingAssistantId: Uuid,
    val taskSignature: TaskSignatureV1,
    val query: String,
    val maxCandidates: Int = 5,
    val maxEstimatedTokens: Int = 1_024,
) {
    init {
        require(query.length <= MAX_POLICY_RAW_QUERY_CHARS) { "Policy query is too large" }
        require(consumingAssistantId.toString() != NIL_UUID) { "Nil consuming Assistant ID" }
        if (scope is LearningScope.Assistant) {
            require(scope.assistantId == consumingAssistantId) {
                "Assistant Policy query has a different consumer"
            }
        }
        require(maxCandidates in 1..MAX_LEARNED_POLICY_CONTEXT_CANDIDATES)
        require(maxEstimatedTokens in 1..MAX_LEARNED_POLICY_CONTEXT_PACKET_ESTIMATED_TOKENS)
    }

    override fun toString(): String =
        "LearnedPolicyQuery(scope=${scope.kind}, candidates=$maxCandidates, " +
            "tokens=$maxEstimatedTokens, query=<redacted>)"
}

/**
 * One reviewed, source-current, schema-current candidate. [renderedFragment] is still untrusted
 * data: [me.rerere.rikkahub.data.ai.compileRecallPrompt] JSON-encodes it and owns every prompt
 * delimiter. The source must never put a system/user role, XML wrapper, or prompt prefix around it.
 */
data class LearnedPolicyContextItem(
    val policyId: String,
    val policyRevision: Long,
    val scope: LearningScope,
    val artifactSha256: String,
    val renderedFragment: String,
    val estimatedTokens: Int,
    /** Higher values are considered first; ties are resolved by rank and stable identity. */
    val priority: Int,
    /** One-based deterministic rank produced by the bounded retrieval query. */
    val rank: Int,
    val policyCompilerRevision: String,
    /** Canonical exact tool schema fingerprints required by this advice. */
    val applicableToolSchemaFingerprints: Set<String> = emptySet(),
    /** Exact non-secret final dispatch applicability. Wildcards are never live. */
    val applicableModelIdentity: String,
    val applicableProviderIdentity: String,
    val applicableTemplateIdentity: String,
    val applicableConfigurationIdentity: String,
    val applicableConfigurationGeneration: Long,
    val applicableCapabilityDigest: String?,
    val applicableAuthorityDigest: String?,
    val trust: LearnedPolicyContextTrust = LearnedPolicyContextTrust.UNTRUSTED_CONTEXT_ONLY,
) {
    init {
        require(policyId.matches(STABLE_POLICY_ID_PATTERN)) { "Invalid Policy ID" }
        require(policyRevision > 0L) { "Invalid Policy revision" }
        require(artifactSha256.matches(SHA256_PATTERN)) { "Invalid Policy artifact digest" }
        require(renderedFragment.isNotBlank()) { "Empty Policy context fragment" }
        require(renderedFragment.length <= MAX_LEARNED_POLICY_CONTEXT_ITEM_CHARS) {
            "Policy context fragment is too large"
        }
        require(estimatedTokens in 1..MAX_LEARNED_POLICY_CONTEXT_ITEM_ESTIMATED_TOKENS)
        require(priority in -10_000..10_000)
        require(rank in 1..MAX_LEARNED_POLICY_CONTEXT_CANDIDATES)
        require(policyCompilerRevision.matches(COMPILER_REVISION_PATTERN)) {
            "Invalid Policy compiler revision"
        }
        require(applicableToolSchemaFingerprints.size <= 16)
        require(applicableToolSchemaFingerprints.all(SHA256_PATTERN::matches))
        require(isCanonicalPolicyIdentityApplicability(applicableModelIdentity))
        require(isCanonicalPolicyIdentityApplicability(applicableProviderIdentity))
        require(applicableTemplateIdentity.matches(SHA256_PATTERN))
        require(applicableConfigurationIdentity.matches(SHA256_PATTERN))
        require(applicableConfigurationGeneration > 0L)
        applicableCapabilityDigest?.let { require(it.matches(SHA256_PATTERN)) }
        applicableAuthorityDigest?.let { require(it.matches(SHA256_PATTERN)) }
        require(trust == LearnedPolicyContextTrust.UNTRUSTED_CONTEXT_ONLY)
    }

    override fun toString(): String =
        "LearnedPolicyContextItem(revision=$policyRevision, priority=$priority, rank=$rank, " +
            "tokens=$estimatedTokens, trust=$trust, text=<redacted>, ids=<redacted>)"
}

/** Canonical non-secret applicability cohort shared by dispatch, Exposure, and evaluation. */
fun LearnedPolicyContextItem.applicabilityCohortDigest(): String =
    me.rerere.rikkahub.learning.model.LearningCanonicalId.digest(
        domainVersion = "policy-dispatch-applicability-cohort-v2",
        fields = listOf(
            applicableModelIdentity,
            applicableProviderIdentity,
            applicableTemplateIdentity,
            applicableConfigurationIdentity,
            applicableConfigurationGeneration.toString(),
            applicableCapabilityDigest.orEmpty(),
            applicableAuthorityDigest.orEmpty(),
            *applicableToolSchemaFingerprints.sorted().toTypedArray(),
        ),
    )

/**
 * Candidate packet bound to one frozen scope/signature. Its constructor enforces the public-source
 * bounds before anything reaches the context compiler.
 */
data class LearnedPolicyCandidatePacket(
    val scope: LearningScope,
    val taskSignature: TaskSignatureV1,
    val candidates: List<LearnedPolicyContextItem>,
    val retrievalRevision: String,
    val truncated: Boolean,
) {
    init {
        require(candidates.size <= MAX_LEARNED_POLICY_CONTEXT_CANDIDATES) {
            "Unbounded Policy candidate packet"
        }
        require(candidates.all { it.scope == scope }) { "Policy packet crosses a scope boundary" }
        require(candidates.map { it.policyId }.distinct().size == candidates.size) {
            "Policy packet contains multiple revisions of one Policy"
        }
        require(candidates.sumOf { it.renderedFragment.length } <=
            MAX_LEARNED_POLICY_CONTEXT_PACKET_CHARS) {
            "Policy candidate packet text is too large"
        }
        require(candidates.sumOf { it.estimatedTokens } <=
            MAX_LEARNED_POLICY_CONTEXT_PACKET_ESTIMATED_TOKENS) {
            "Policy candidate packet estimate is too large"
        }
        require(retrievalRevision.matches(COMPILER_REVISION_PATTERN)) {
            "Invalid Policy retrieval revision"
        }
    }

    override fun toString(): String =
        "LearnedPolicyCandidatePacket(scope=${scope.kind}, candidates=${candidates.size}, " +
            "truncated=$truncated, text=<redacted>, ids=<redacted>)"
}

/** Exact durable grant receipt retained only until final pre-dispatch revalidation. */
data class LearnedPolicyGrantReceipt(
    val authority: PolicyGrantAuthoritySnapshot,
    /** Frozen retrieval signature repeated by the final LearningDB eligibility read. */
    val taskSignature: TaskSignatureV1,
) {
    val grantId: String get() = authority.grantId
    val sourceStreamId: String get() = authority.sourceStreamId
    val scope: LearningScope get() = authority.scope
    val consumingAssistantId: Uuid get() = authority.consumingAssistantId
    val policyId: String get() = authority.policyId
    val policyRevision: Long get() = authority.contentRevision
    val artifactSha256: String get() = authority.artifactSha256
    val stateVersion: Long get() = authority.stateVersion

    init {
        require(authority.state == PolicyGrantAuthorityState.GRANTED)
    }

    override fun toString(): String =
        "LearnedPolicyGrantReceipt(scope=${scope.kind}, revision=$policyRevision, " +
            "taskSignature=<redacted>, ids=<redacted>)"
}

data class LearnedPolicyRetrievalResult(
    val packet: LearnedPolicyCandidatePacket,
    val grantReceipts: List<LearnedPolicyGrantReceipt>,
) {
    init {
        require(packet.candidates.size == grantReceipts.size)
        packet.candidates.zip(grantReceipts).forEach { (policy, receipt) ->
            require(policy.policyId == receipt.policyId)
            require(policy.policyRevision == receipt.policyRevision)
            require(policy.artifactSha256 == receipt.artifactSha256)
            require(policy.scope == receipt.scope && packet.scope == receipt.scope)
        }
    }

    fun select(policyIds: Set<String>): LearnedPolicyRetrievalResult {
        val selected = packet.candidates.zip(grantReceipts)
            .filter { (policy, _) -> policy.policyId in policyIds }
        return LearnedPolicyRetrievalResult(
            packet = packet.copy(candidates = selected.map { it.first }),
            grantReceipts = selected.map { it.second },
        )
    }
}

sealed interface PolicyDispatchSurfaceObservationResult {
    /**
     * Only [eligiblePolicyIds] may proceed to the existing final applicability filter. Capability
     * remains explicitly unknown until a durable baseline schema is introduced.
     */
    data class Ready(
        val eligiblePolicyIds: Set<String>,
        val staleSchemaPolicyIds: Set<String>,
        val capabilityUnknownPolicyIds: Set<String>,
    ) : PolicyDispatchSurfaceObservationResult {
        init {
            require(eligiblePolicyIds.size <= MAX_LEARNED_POLICY_CONTEXT_CANDIDATES)
            require(staleSchemaPolicyIds.size <= MAX_LEARNED_POLICY_CONTEXT_CANDIDATES)
            require(capabilityUnknownPolicyIds.size <= MAX_LEARNED_POLICY_CONTEXT_CANDIDATES)
            require(eligiblePolicyIds.intersect(staleSchemaPolicyIds).isEmpty())
            require(capabilityUnknownPolicyIds.all { it in eligiblePolicyIds })
        }
    }

    data object Unavailable : PolicyDispatchSurfaceObservationResult
}

/**
 * Read-only P2 boundary. Implementations return candidates only; they cannot write a prompt or
 * claim that retrieval/compilation constitutes an actual exposure.
 */
interface LearnedPolicySource {
    suspend fun retrieve(input: LearnedPolicyQuery): LearnedPolicyRetrievalResult

    /**
     * Observes the exact final provider-visible tool schema surface without changing provider
     * bytes. Missing schemas may write a revision/artifact-fenced STALE_SCHEMA transition;
     * unavailable or conflicting observations are omitted from the returned eligible set.
     */
    suspend fun observeFinalDispatchSurface(
        receipts: List<LearnedPolicyGrantReceipt>,
        consumingAssistantId: Uuid,
        availableToolSchemaFingerprints: Set<String>,
        frozenNowMs: Long,
    ): PolicyDispatchSurfaceObservationResult

    /** Revalidates exact authority after final compile/gating and before learned bytes are selected. */
    suspend fun revalidateForDispatch(
        receipts: List<LearnedPolicyGrantReceipt>,
        consumingAssistantId: Uuid,
    ): Boolean
}

private val STABLE_POLICY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val COMPILER_REVISION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"

private fun isCanonicalPolicyIdentityApplicability(wire: String): Boolean =
    wire.startsWith("EXACT_V1:") &&
            wire.removePrefix("EXACT_V1:")
                .matches(Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,159}"))

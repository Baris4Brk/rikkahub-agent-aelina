package me.rerere.rikkahub.learning.retrieval

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.task.TaskSignatureV1

const val MAX_POLICY_EXACT_DB_CANDIDATES: Int = 32
const val MAX_POLICY_FTS_DB_CANDIDATES: Int = 96

/**
 * Stack-local P1 database adapter. Both SQL paths apply scope/status/source/schema/evidence gates
 * before LIMIT; this class only combines the bounded exact and FTS projections deterministically.
 */
internal class RoomPolicyShadowRetriever(
    private val database: LearningDatabase,
    private val retriever: PolicyRetriever,
    private val fts: PolicyFtsManager = PolicyFtsManager(database),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun retrieve(request: PolicyRetrievalRequest): PolicyRetrievalResult {
        val frozenNowMs = clock().coerceAtLeast(0L)
        val freshAfterMs = (frozenNowMs -
            me.rerere.rikkahub.learning.storage.LearningRetentionPolicyV1.DORMANT_POLICY_TTL_MS)
            .coerceAtLeast(0L)
        val exact = database.policyDao().listShadowCandidates(
            scopeKind = request.scope.kind.name,
            scopeId = request.scope.storageId,
            taskSignature = request.taskSignature.value,
            freshAfterMs = freshAfterMs,
            limit = MAX_POLICY_EXACT_DB_CANDIDATES,
        )
        val lexical = fts.searchEligible(
            scope = request.scope,
            query = request.query,
            freshAfterMs = freshAfterMs,
            limit = MAX_POLICY_FTS_DB_CANDIDATES,
        )
        val ordered = LinkedHashMap<String, LearningPolicyEntity>(exact.size + lexical.size)
        exact.forEach { ordered.putIfAbsent(it.id, it) }
        lexical.forEach { ordered.putIfAbsent(it.id, it) }
        require(ordered.size <= MAX_POLICY_RETRIEVAL_INPUT_CANDIDATES)
        return retriever.retrieve(request, ordered.values.map(::toShadowCandidate))
    }
}

internal fun toShadowCandidate(entity: LearningPolicyEntity): PolicyShadowCandidate =
    PolicyShadowCandidate(
        policyId = entity.id,
        scope = requireNotNull(LearningScope.parseOrNull(entity.scopeKind, entity.scopeId)) {
            "Invalid policy scope persisted"
        },
        taskSignature = requireNotNull(TaskSignatureV1.parseOrNull(entity.taskSignature)) {
            "Invalid policy task signature persisted"
        },
        status = requireNotNull(
            LearningPolicyStatus.entries.firstOrNull { it.name == entity.status },
        ) { "Invalid policy status persisted" },
        artifactHash = entity.artifactSha256,
        sourceValid = entity.sourceValid,
        toolSchemaValid = entity.schemaValid,
        searchableText = buildString {
            append(entity.triggerSummary)
            append('\n')
            append(entity.procedureSummary)
            append('\n')
            append(entity.verificationSummary)
            append('\n')
            append(entity.boundarySummary)
            append('\n')
            append(entity.failureModeSummary)
        }.take(8_192),
        estimatedTokens = estimatePolicyTokens(entity),
        updatedAtMs = entity.updatedAtMs,
        stateVersion = entity.stateVersion,
        contentRevision = entity.contentRevision,
    )

private fun estimatePolicyTokens(entity: LearningPolicyEntity): Int {
    val chars = entity.triggerSummary.length + entity.procedureSummary.length +
        entity.verificationSummary.length + entity.boundarySummary.length +
        entity.failureModeSummary.length + 4
    return ((chars + 1) / 2).coerceIn(1, 4_096)
}

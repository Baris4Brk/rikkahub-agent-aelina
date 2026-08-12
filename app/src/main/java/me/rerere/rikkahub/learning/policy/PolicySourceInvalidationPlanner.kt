package me.rerere.rikkahub.learning.policy

internal const val MAX_POLICY_LINEAGE_DEPTH = 16
internal const val MAX_POLICY_LINEAGE_VISITED = 256

enum class PolicyLineageKind {
    DERIVED_FROM,
    SUPERSEDES,
    MERGED_FROM,
    CONTRADICTS,
}

data class PolicyLineageEdge(
    val fromPolicyId: String,
    val toPolicyId: String,
    val kind: PolicyLineageKind,
) {
    init {
        require(fromPolicyId != toPolicyId)
    }
}

data class PolicyEvidenceValiditySnapshot(
    val policyId: String,
    val evidenceId: String,
    val sourceValid: Boolean,
)

data class PolicySourceInvalidationPlan(
    val stalePolicyIds: Set<String>,
    val survivingEvidenceCounts: Map<String, Int>,
)

enum class PolicySourcePropagationFailure {
    CYCLE,
    DEPTH_LIMIT,
    VISITED_LIMIT,
}

sealed interface PolicySourcePropagationResult {
    data class Planned(val plan: PolicySourceInvalidationPlan) : PolicySourcePropagationResult
    data class Rejected(val failure: PolicySourcePropagationFailure) : PolicySourcePropagationResult
}

/**
 * Recomputes each Policy from surviving evidence, then propagates stale only along derivation
 * lineage. A single invalid evidence row does not discard a Policy that still has valid support.
 */
object PolicySourceInvalidationPlanner {
    fun plan(
        evidence: List<PolicyEvidenceValiditySnapshot>,
        lineage: List<PolicyLineageEdge>,
    ): PolicySourcePropagationResult {
        val surviving = evidence.groupBy(PolicyEvidenceValiditySnapshot::policyId)
            .mapValues { (_, rows) -> rows.count(PolicyEvidenceValiditySnapshot::sourceValid) }
        val initiallyStale = evidence.groupBy(PolicyEvidenceValiditySnapshot::policyId)
            .filterValues { rows -> rows.isNotEmpty() && rows.none(PolicyEvidenceValiditySnapshot::sourceValid) }
            .keys
        val adjacency = lineage.filter {
            it.kind in setOf(PolicyLineageKind.DERIVED_FROM, PolicyLineageKind.MERGED_FROM)
        }.groupBy(PolicyLineageEdge::fromPolicyId)
        val stale = linkedSetOf<String>()
        val visiting = hashSetOf<String>()
        val visited = hashSetOf<String>()

        fun visit(policyId: String, depth: Int): PolicySourcePropagationFailure? {
            if (depth > MAX_POLICY_LINEAGE_DEPTH) return PolicySourcePropagationFailure.DEPTH_LIMIT
            if (policyId in visited) return null
            if (!visiting.add(policyId)) return PolicySourcePropagationFailure.CYCLE
            if (visited.size >= MAX_POLICY_LINEAGE_VISITED && policyId !in visited) {
                return PolicySourcePropagationFailure.VISITED_LIMIT
            }
            stale += policyId
            for (edge in adjacency[policyId].orEmpty().sortedBy(PolicyLineageEdge::toPolicyId)) {
                val child = edge.toPolicyId
                visit(child, depth + 1)?.let { return it }
            }
            visiting -= policyId
            visited += policyId
            return null
        }

        for (root in initiallyStale.sorted()) {
            if (root !in visited) visit(root, 0)?.let { return PolicySourcePropagationResult.Rejected(it) }
        }
        return PolicySourcePropagationResult.Planned(
            PolicySourceInvalidationPlan(stale, surviving.toSortedMap()),
        )
    }
}

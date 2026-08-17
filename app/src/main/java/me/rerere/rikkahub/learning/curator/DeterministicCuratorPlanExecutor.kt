package me.rerere.rikkahub.learning.curator

/** Immutable JVM transaction fixture used to prove all-or-nothing apply and rollback semantics. */
data class CuratorMaterializedState(
    val heads: Map<String, CuratorPolicyHead>,
    val lineage: Set<CuratorLineageEdge> = emptySet(),
) {
    init {
        require(heads.size <= MAX_CURATOR_MATERIALIZED_HEADS)
        require(heads.all { (id, head) -> id == head.policyId })
        require(lineage.size <= MAX_CURATOR_MATERIALIZED_LINEAGE)
    }

    override fun toString(): String =
        "CuratorMaterializedState(heads=${heads.size}, lineage=${lineage.size}, content=<redacted>)"
}

sealed interface CuratorTransactionResult {
    data class Applied(val state: CuratorMaterializedState) : CuratorTransactionResult
    data class Duplicate(val state: CuratorMaterializedState) : CuratorTransactionResult
    data class Conflict(val reason: CuratorConflictReason) : CuratorTransactionResult
}

object DeterministicCuratorPlanExecutor {
    fun apply(
        current: CuratorMaterializedState,
        plan: CuratorApplyPlan,
    ): CuratorTransactionResult {
        val checks = plan.mutations.map { mutation -> mutation.checkAgainst(current.heads) }
        if (checks.all { it == MutationFenceCheck.ALREADY_AFTER } &&
            plan.lineage.all { it in current.lineage }
        ) return CuratorTransactionResult.Duplicate(current)
        if (checks.any { it != MutationFenceCheck.READY }) {
            return CuratorTransactionResult.Conflict(CuratorConflictReason.REVISION_CONFLICT)
        }
        val heads = current.heads.toMutableMap()
        plan.mutations.forEach { mutation ->
            val after = requireNotNull(mutation.after)
            heads[after.policyId] = after
        }
        return CuratorTransactionResult.Applied(
            CuratorMaterializedState(
                heads = heads.toSortedMap(),
                lineage = (current.lineage + plan.lineage).toSortedLineageSet(),
            ),
        )
    }

    fun rollback(
        current: CuratorMaterializedState,
        rollback: CuratorRollbackPlan,
    ): CuratorTransactionResult {
        val expectedById = rollback.expectedAppliedHeads.associateBy(CuratorSourceFence::policyId)
        val fencesExact = expectedById.all { (id, fence) ->
            val head = current.heads[id]
            head != null && head.scope == fence.scope && head.revision == fence.expectedRevision &&
                head.artifactSha256 == fence.baseHash
        }
        if (!fencesExact || rollback.lineageToRemove.any { it !in current.lineage }) {
            return CuratorTransactionResult.Conflict(CuratorConflictReason.ROLLBACK_FENCE_CONFLICT)
        }
        if (rollback.mutations.any { it.checkAgainst(current.heads) != MutationFenceCheck.READY }) {
            return CuratorTransactionResult.Conflict(CuratorConflictReason.ROLLBACK_FENCE_CONFLICT)
        }
        val heads = current.heads.toMutableMap()
        rollback.mutations.forEach { mutation ->
            val after = requireNotNull(mutation.after)
            heads[after.policyId] = after
        }
        return CuratorTransactionResult.Applied(
            CuratorMaterializedState(
                heads = heads.toSortedMap(),
                lineage = (current.lineage - rollback.lineageToRemove.toSet()).toSortedLineageSet(),
            ),
        )
    }

    private fun CuratorPlannedMutation.checkAgainst(
        heads: Map<String, CuratorPolicyHead>,
    ): MutationFenceCheck = when (kind) {
        CuratorMutationKind.INSERT -> when (heads[requireNotNull(after).policyId]) {
            null -> MutationFenceCheck.READY
            after -> MutationFenceCheck.ALREADY_AFTER
            else -> MutationFenceCheck.CONFLICT
        }
        CuratorMutationKind.UPDATE,
        CuratorMutationKind.ARCHIVE,
        CuratorMutationKind.RESTORE,
        -> when (heads[requireNotNull(before).policyId]) {
            before -> MutationFenceCheck.READY
            after -> MutationFenceCheck.ALREADY_AFTER
            else -> MutationFenceCheck.CONFLICT
        }
    }

    private fun Set<CuratorLineageEdge>.toSortedLineageSet(): Set<CuratorLineageEdge> =
        sortedWith(
            compareBy(CuratorLineageEdge::parentPolicyId)
                .thenBy(CuratorLineageEdge::childPolicyId)
                .thenBy { it.relation.ordinal },
        ).toCollection(linkedSetOf())

    private enum class MutationFenceCheck { READY, ALREADY_AFTER, CONFLICT }
}

private const val MAX_CURATOR_MATERIALIZED_HEADS = 10_000
private const val MAX_CURATOR_MATERIALIZED_LINEAGE = 40_000

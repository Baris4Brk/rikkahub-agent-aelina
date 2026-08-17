package me.rerere.rikkahub.learning.curator

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Test

class CuratorAtomicCrashRaceContractTest {
    private val scope = LearningScope.Assistant(
        Uuid.parse("11111111-1111-4111-8111-111111111111"),
    )
    private val evidence = CuratorEvidenceRef("evidence-1", scope, 1L, "e".repeat(64))

    @Test
    fun `mixed before and after heads model a crash and are never accepted as duplicate`() {
        val a = head("policy-a", 4L, "a")
        val b = head("policy-b", 7L, "b")
        val output = document("merged")
        val candidate = CuratorDeltaCandidate.Merge(
            candidateId = "candidate-merge",
            sources = listOf(fence(a), fence(b)),
            outputPolicyId = "policy-output",
            outputDocument = output,
            evidence = listOf(evidence),
            diffs = listOf(fullDiff("policy-output", a, output)),
        )
        val plan = ready(candidate, listOf(a, b))
        val partiallyWritten = CuratorMaterializedState(
            heads = mapOf(
                a.policyId to requireNotNull(
                    plan.mutations.single { it.before?.policyId == a.policyId }.after,
                ),
                b.policyId to b,
            ),
        )

        val result = DeterministicCuratorPlanExecutor.apply(partiallyWritten, plan)

        assertEquals(
            CuratorTransactionResult.Conflict(CuratorConflictReason.REVISION_CONFLICT),
            result,
        )
        assertEquals(b, partiallyWritten.heads[b.policyId])
        assertEquals(2, partiallyWritten.heads.size)
    }

    @Test
    fun `UPDATE exact replay is duplicate even though its lineage is empty`() {
        val source = head("policy-a", 4L, "a")
        val changed = "bounded updated procedure"
        val candidate = CuratorDeltaCandidate.Update(
            "candidate-update",
            fence(source),
            listOf(evidence),
            listOf(
                CuratorTargetDiff(
                    source.policyId,
                    listOf(
                        CuratorFieldDiff(
                            CuratorPolicyField.PROCEDURE,
                            CuratorV1Canonicalizer.fieldSha256(
                                CuratorPolicyField.PROCEDURE,
                                source.document.procedure,
                            ),
                            changed,
                        ),
                    ),
                ),
            ),
        )
        val plan = ready(candidate, listOf(source))
        val applied = DeterministicCuratorPlanExecutor.apply(
            CuratorMaterializedState(mapOf(source.policyId to source)),
            plan,
        ) as CuratorTransactionResult.Applied

        assertEquals(
            CuratorTransactionResult.Duplicate(applied.state),
            DeterministicCuratorPlanExecutor.apply(applied.state, plan),
        )
        assertEquals(emptySet<CuratorLineageEdge>(), applied.state.lineage)
    }

    @Test
    fun `lineage race makes rollback conflict without changing any head`() {
        val source = head("policy-a", 4L, "a")
        val replacement = document("replacement")
        val candidate = CuratorDeltaCandidate.Supersede(
            "candidate-supersede",
            fence(source),
            "policy-replacement",
            replacement,
            listOf(evidence),
            listOf(fullDiff("policy-replacement", source, replacement)),
        )
        val plan = ready(candidate, listOf(source))
        val applied = DeterministicCuratorPlanExecutor.apply(
            CuratorMaterializedState(mapOf(source.policyId to source)),
            plan,
        ) as CuratorTransactionResult.Applied
        val lineageRaced = applied.state.copy(lineage = emptySet())

        assertEquals(
            CuratorTransactionResult.Conflict(CuratorConflictReason.ROLLBACK_FENCE_CONFLICT),
            DeterministicCuratorPlanExecutor.rollback(lineageRaced, plan.rollback),
        )
        assertEquals(applied.state.heads, lineageRaced.heads)
    }

    private fun ready(
        candidate: CuratorDeltaCandidate,
        heads: List<CuratorPolicyHead>,
    ): CuratorApplyPlan = (
        DeterministicCuratorDeltaApplier().plan(
            candidate,
            { id -> heads.singleOrNull { it.policyId == id } },
            { id -> evidence.takeIf { it.evidenceId == id } },
        ) as CuratorApplyResult.Ready
        ).plan

    private fun head(id: String, revision: Long, suffix: String) = CuratorPolicyHead(
        id,
        scope,
        revision,
        CuratorPolicyState.REVIEWED,
        document(suffix),
    )

    private fun fence(head: CuratorPolicyHead) = CuratorSourceFence(
        head.policyId,
        head.scope,
        head.revision,
        head.artifactSha256,
    )

    private fun fullDiff(
        targetPolicyId: String,
        base: CuratorPolicyHead,
        output: CuratorPolicyDocument,
    ) = CuratorTargetDiff(
        targetPolicyId,
        CuratorPolicyField.entries.mapNotNull { field ->
            val before = base.document.value(field)
            val after = output.value(field)
            if (before == after) null else CuratorFieldDiff(
                field,
                CuratorV1Canonicalizer.fieldSha256(field, before),
                after,
            )
        },
    )

    private fun document(suffix: String) = CuratorPolicyDocument(
        "trigger-$suffix",
        "procedure-$suffix",
        "verification-$suffix",
        "boundary-$suffix",
        "failure-$suffix",
        listOf("a".repeat(64)),
    )
}

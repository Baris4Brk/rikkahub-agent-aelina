package me.rerere.rikkahub.learning.curator

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicCuratorDeltaApplierTest {
    private val applier = DeterministicCuratorDeltaApplier()
    private val scope = LearningScope.Assistant(
        Uuid.parse("11111111-1111-4111-8111-111111111111"),
    )
    private val evidence = listOf(
        CuratorEvidenceRef("evidence-1", scope, 2L, "e".repeat(64)),
        CuratorEvidenceRef("evidence-2", scope, 3L, "f".repeat(64)),
    )

    @Test
    fun `update applies exact field diff and increments one revision`() {
        val source = head("policy-a", 4L, document("a"))
        val candidate = CuratorDeltaCandidate.Update(
            candidateId = "candidate-update",
            source = fence(source),
            evidence = evidence,
            diffs = listOf(
                targetDiff(
                    source.policyId,
                    listOf(diff(source, CuratorPolicyField.PROCEDURE, "procedure-updated")),
                ),
            ),
        )

        val plan = ready(candidate, listOf(source))

        assertEquals(CuratorDeltaOperation.UPDATE_CANDIDATE, plan.operation)
        assertEquals(1, plan.mutations.size)
        val mutation = plan.mutations.single()
        assertEquals(CuratorMutationKind.UPDATE, mutation.kind)
        assertEquals(4L, mutation.before?.revision)
        assertEquals(5L, mutation.after?.revision)
        assertEquals("procedure-updated", mutation.after?.document?.procedure)
        assertNotEquals(source.artifactSha256, mutation.after?.artifactSha256)
        assertTrue(plan.lineage.isEmpty())
    }

    @Test
    fun `merge inserts deterministic output archives both parents and records lineage`() {
        val a = head("policy-a", 4L, document("a"))
        val b = head("policy-b", 7L, document("b"))
        val merged = document("merged")
        val candidate = CuratorDeltaCandidate.Merge(
            candidateId = "candidate-merge",
            sources = listOf(fence(a), fence(b)),
            outputPolicyId = "policy-merged",
            outputDocument = merged,
            evidence = evidence,
            diffs = listOf(targetDiff("policy-merged", a, merged)),
        )

        val plan = ready(candidate, listOf(a, b))

        assertEquals(listOf(CuratorMutationKind.INSERT, CuratorMutationKind.ARCHIVE, CuratorMutationKind.ARCHIVE), plan.mutations.map { it.kind })
        assertEquals(CuratorPolicyState.ARCHIVED, plan.mutations[1].after?.state)
        assertEquals(setOf("policy-a", "policy-b"), plan.lineage.map { it.parentPolicyId }.toSet())
        assertTrue(plan.lineage.all { it.relation == CuratorLineageRelation.MERGED_FROM })
        assertEquals(
            CuratorPolicyState.ARCHIVED,
            plan.rollback.mutations.single { it.before?.policyId == "policy-merged" }.after?.state,
        )
    }

    @Test
    fun `split emits bounded sorted children and archives source`() {
        val source = head("policy-a", 4L, document("a"))
        val candidate = CuratorDeltaCandidate.Split(
            candidateId = "candidate-split",
            source = fence(source),
            outputs = listOf(
                CuratorDeltaCandidate.SplitOutput("policy-child-b", document("child-b")),
                CuratorDeltaCandidate.SplitOutput("policy-child-a", document("child-a")),
            ),
            evidence = evidence,
            diffs = listOf(
                targetDiff("policy-child-a", source, document("child-a")),
                targetDiff("policy-child-b", source, document("child-b")),
            ),
        )

        val plan = ready(candidate, listOf(source))

        assertEquals(listOf("policy-child-a", "policy-child-b"), plan.mutations.take(2).map { it.after!!.policyId })
        assertEquals(CuratorMutationKind.ARCHIVE, plan.mutations.last().kind)
        assertTrue(plan.lineage.all { it.relation == CuratorLineageRelation.SPLIT_FROM })
        assertTrue(
            plan.rollback.mutations.filter {
                it.before?.policyId in setOf("policy-child-a", "policy-child-b")
            }.all { it.after?.state == CuratorPolicyState.ARCHIVED },
        )
    }

    @Test
    fun `supersede keeps old head as typed superseded and never deletes`() {
        val source = head("policy-a", 4L, document("a"))
        val replacement = document("replacement")
        val candidate = CuratorDeltaCandidate.Supersede(
            candidateId = "candidate-supersede",
            source = fence(source),
            replacementPolicyId = "policy-replacement",
            replacementDocument = replacement,
            evidence = evidence,
            diffs = listOf(targetDiff("policy-replacement", source, replacement)),
        )

        val plan = ready(candidate, listOf(source))

        assertEquals(CuratorMutationKind.INSERT, plan.mutations.first().kind)
        assertEquals(CuratorMutationKind.ARCHIVE, plan.mutations.last().kind)
        assertEquals(CuratorPolicyState.SUPERSEDED, plan.mutations.last().after?.state)
        assertEquals(CuratorLineageRelation.SUPERSEDES, plan.lineage.single().relation)
        assertFalse(CuratorMutationKind.entries.any { it.name == "DELETE" })
    }

    @Test
    fun `revision base hash scope and missing source conflicts are typed`() {
        val source = head("policy-a", 4L, document("a"))
        fun result(fence: CuratorSourceFence, heads: List<CuratorPolicyHead> = listOf(source)) =
            applier.plan(
                CuratorDeltaCandidate.Update(
                    "candidate-update",
                    fence,
                    evidence,
                    listOf(
                        targetDiff(
                            source.policyId,
                            listOf(diff(source, CuratorPolicyField.PROCEDURE, "changed")),
                        ),
                    ),
                ),
                headReader(heads),
                evidenceReader(),
            )

        assertConflict(result(fence(source).copy(expectedRevision = 3L)), CuratorConflictReason.REVISION_CONFLICT)
        assertConflict(result(fence(source).copy(baseHash = "b".repeat(64))), CuratorConflictReason.BASE_HASH_CONFLICT)
        assertConflict(result(fence(source).copy(scope = LearningScope.Assistant(Uuid.random()))), CuratorConflictReason.SCOPE_CONFLICT)
        assertConflict(result(fence(source), emptyList()), CuratorConflictReason.SOURCE_MISSING)
    }

    @Test
    fun `all four operations independently enforce revision and base hash fences`() {
        val a = head("policy-a", 4L, document("a"))
        val b = head("policy-b", 7L, document("b"))
        val revisionFence = fence(a).copy(expectedRevision = 3L)
        val hashFence = fence(a).copy(baseHash = "0".repeat(64))
        listOf(revisionFence to CuratorConflictReason.REVISION_CONFLICT, hashFence to CuratorConflictReason.BASE_HASH_CONFLICT).forEach { (badFence, expected) ->
            val mergeDocument = document("merge")
            val splitOne = document("split-one")
            val splitTwo = document("split-two")
            val replacement = document("replacement")
            val candidates = listOf<CuratorDeltaCandidate>(
                CuratorDeltaCandidate.Update(
                    "candidate-u",
                    badFence,
                    evidence,
                    listOf(targetDiff("policy-a", listOf(diff(a, CuratorPolicyField.TRIGGER, "changed")))),
                ),
                CuratorDeltaCandidate.Merge(
                    "candidate-m",
                    listOf(badFence, fence(b)),
                    "policy-m",
                    mergeDocument,
                    evidence,
                    listOf(targetDiff("policy-m", a, mergeDocument)),
                ),
                CuratorDeltaCandidate.Split(
                    "candidate-s",
                    badFence,
                    listOf(
                        CuratorDeltaCandidate.SplitOutput("policy-s1", splitOne),
                        CuratorDeltaCandidate.SplitOutput("policy-s2", splitTwo),
                    ),
                    evidence,
                    listOf(
                        targetDiff("policy-s1", a, splitOne),
                        targetDiff("policy-s2", a, splitTwo),
                    ),
                ),
                CuratorDeltaCandidate.Supersede(
                    "candidate-x",
                    badFence,
                    "policy-x",
                    replacement,
                    evidence,
                    listOf(targetDiff("policy-x", a, replacement)),
                ),
            )
            candidates.forEach { candidate ->
                assertConflict(
                    applier.plan(candidate, headReader(listOf(a, b)), evidenceReader()),
                    expected,
                )
            }
        }
    }

    @Test
    fun `each operation validates its evidence independently`() {
        val a = head("policy-a", 4L, document("a"))
        val b = head("policy-b", 7L, document("b"))
        val badEvidence = evidence.map { if (it.evidenceId == "evidence-1") it.copy(sourceRevision = 99L) else it }
        val candidates = listOf<CuratorDeltaCandidate>(
            CuratorDeltaCandidate.Update("candidate-u", fence(a), badEvidence, listOf(targetDiff("policy-a", listOf(diff(a, CuratorPolicyField.TRIGGER, "u"))))),
            CuratorDeltaCandidate.Merge("candidate-m", listOf(fence(a), fence(b)), "policy-m", document("m"), badEvidence, listOf(targetDiff("policy-m", a, document("m")))),
            CuratorDeltaCandidate.Split("candidate-s", fence(a), listOf(CuratorDeltaCandidate.SplitOutput("policy-s1", document("s1")), CuratorDeltaCandidate.SplitOutput("policy-s2", document("s2"))), badEvidence, listOf(targetDiff("policy-s1", a, document("s1")), targetDiff("policy-s2", a, document("s2")))),
            CuratorDeltaCandidate.Supersede("candidate-x", fence(a), "policy-x", document("x"), badEvidence, listOf(targetDiff("policy-x", a, document("x")))),
        )
        candidates.forEach { candidate ->
            assertConflict(
                applier.plan(candidate, headReader(listOf(a, b)), evidenceReader()),
                CuratorConflictReason.EVIDENCE_CONFLICT,
            )
        }
    }

    @Test
    fun `evidence from another scope is rejected before delta construction`() {
        val source = head("policy-a", 4L, document("a"))
        val foreign = evidence.map { item ->
            item.copy(scope = LearningScope.Assistant(Uuid.random()))
        }
        val result = applier.plan(
            CuratorDeltaCandidate.Update(
                "candidate-update",
                fence(source),
                foreign,
                listOf(
                    targetDiff(
                        source.policyId,
                        listOf(diff(source, CuratorPolicyField.TRIGGER, "changed")),
                    ),
                ),
            ),
            headReader(listOf(source)),
            CuratorEvidenceReader { id -> foreign.singleOrNull { it.evidenceId == id } },
        )

        assertConflict(result, CuratorConflictReason.SCOPE_CONFLICT)
    }

    @Test
    fun `curator cannot resurrect archived or superseded source heads`() {
        val source = head("policy-a", 4L, document("a"))
        listOf(CuratorPolicyState.ARCHIVED, CuratorPolicyState.SUPERSEDED).forEach { state ->
            val unavailable = source.copy(state = state)
            val result = applier.plan(
                CuratorDeltaCandidate.Update(
                    "candidate-update",
                    fence(unavailable),
                    evidence,
                    listOf(
                        targetDiff(
                            unavailable.policyId,
                            listOf(diff(unavailable, CuratorPolicyField.PROCEDURE, "changed")),
                        ),
                    ),
                ),
                headReader(listOf(unavailable)),
                evidenceReader(),
            )
            assertConflict(result, CuratorConflictReason.SOURCE_STATE_CONFLICT)
        }
    }

    @Test
    fun `each operation validates its diff independently`() {
        val a = head("policy-a", 4L, document("a"))
        val b = head("policy-b", 7L, document("b"))
        val bad = CuratorFieldDiff(CuratorPolicyField.TRIGGER, "0".repeat(64), "changed")
        val candidates = listOf<CuratorDeltaCandidate>(
            CuratorDeltaCandidate.Update("candidate-u", fence(a), evidence, listOf(targetDiff("policy-a", listOf(bad)))),
            CuratorDeltaCandidate.Merge("candidate-m", listOf(fence(a), fence(b)), "policy-m", document("m"), evidence, listOf(targetDiff("policy-m", listOf(bad)))),
            CuratorDeltaCandidate.Split("candidate-s", fence(a), listOf(CuratorDeltaCandidate.SplitOutput("policy-s1", document("s1")), CuratorDeltaCandidate.SplitOutput("policy-s2", document("s2"))), evidence, listOf(targetDiff("policy-s1", listOf(bad)), targetDiff("policy-s2", listOf(bad)))),
            CuratorDeltaCandidate.Supersede("candidate-x", fence(a), "policy-x", document("x"), evidence, listOf(targetDiff("policy-x", listOf(bad)))),
        )
        candidates.forEach { candidate ->
            assertConflict(
                applier.plan(candidate, headReader(listOf(a, b)), evidenceReader()),
                CuratorConflictReason.DIFF_CONFLICT,
            )
        }
    }

    @Test
    fun `planning is deterministic across head storage ordering`() {
        val a = head("policy-a", 4L, document("a"))
        val b = head("policy-b", 7L, document("b"))
        val candidate = CuratorDeltaCandidate.Merge(
            "candidate-merge",
            listOf(fence(b), fence(a)),
            "policy-merged",
            document("merged"),
            evidence,
            listOf(targetDiff("policy-merged", a, document("merged"))),
        )

        val first = ready(candidate, listOf(a, b))
        val second = ready(candidate, listOf(b, a))

        assertEquals(first.planId, second.planId)
        assertEquals(first.mutations, second.mutations)
        assertEquals(first.lineage, second.lineage)
        assertEquals(first.rollback, second.rollback)
    }

    @Test
    fun `rollback is exact fenced and restores before snapshots`() {
        val source = head("policy-a", 4L, document("a"))
        val plan = ready(
            CuratorDeltaCandidate.Update(
                "candidate-update",
                fence(source),
                evidence,
                listOf(targetDiff(source.policyId, listOf(diff(source, CuratorPolicyField.PROCEDURE, "changed")))),
            ),
            listOf(source),
        )
        val applied = plan.mutations.single().after!!

        val validated = applier.validateRollback(plan.rollback) { applied }

        assertEquals(CuratorApplyResult.RollbackReady(plan.rollback), validated)
        assertEquals(source.document, plan.rollback.mutations.single().after?.document)
        assertEquals(source.state, plan.rollback.mutations.single().after?.state)
        assertEquals(applied.revision + 1L, plan.rollback.mutations.single().after?.revision)
    }

    @Test
    fun `rollback refuses revision and hash drift`() {
        val source = head("policy-a", 4L, document("a"))
        val plan = ready(
            CuratorDeltaCandidate.Update(
                "candidate-update",
                fence(source),
                evidence,
                listOf(targetDiff(source.policyId, listOf(diff(source, CuratorPolicyField.PROCEDURE, "changed")))),
            ),
            listOf(source),
        )
        val applied = plan.mutations.single().after!!
        val revisionDrift = applied.copy(revision = applied.revision + 1L)
        // Rollback fences the canonical Policy artifact digest supplied by the storage adapter.
        // That digest intentionally is not recomputed from the compact Curator document because
        // production Policy identity also covers metadata outside this projection.
        val hashDrift = applied.copy(artifactSha256 = "0".repeat(64))

        assertConflict(applier.validateRollback(plan.rollback) { revisionDrift }, CuratorConflictReason.ROLLBACK_FENCE_CONFLICT)
        assertConflict(applier.validateRollback(plan.rollback) { hashDrift }, CuratorConflictReason.ROLLBACK_FENCE_CONFLICT)
    }

    @Test
    fun `immutable transaction applies atomically and exact replay is duplicate`() {
        val a = head("policy-a", 4L, document("a"))
        val b = head("policy-b", 7L, document("b"))
        val output = document("merged")
        val plan = ready(
            CuratorDeltaCandidate.Merge(
                "candidate-merge",
                listOf(fence(a), fence(b)),
                "policy-merged",
                output,
                evidence,
                listOf(targetDiff("policy-merged", a, output)),
            ),
            listOf(a, b),
        )
        val initial = CuratorMaterializedState(mapOf(a.policyId to a, b.policyId to b))

        val applied = DeterministicCuratorPlanExecutor.apply(initial, plan) as
            CuratorTransactionResult.Applied
        val duplicate = DeterministicCuratorPlanExecutor.apply(applied.state, plan)

        assertEquals(CuratorPolicyState.CANDIDATE, applied.state.heads["policy-merged"]?.state)
        assertEquals(CuratorPolicyState.ARCHIVED, applied.state.heads["policy-a"]?.state)
        assertEquals(CuratorTransactionResult.Duplicate(applied.state), duplicate)
        assertEquals(2, applied.state.lineage.size)
    }

    @Test
    fun `all four operations produce exact monotonic rollback plans`() {
        val a = head("policy-a", 4L, document("a"))
        val b = head("policy-b", 7L, document("b"))
        val merge = document("merge")
        val splitOne = document("split-one")
        val splitTwo = document("split-two")
        val replacement = document("replacement")
        val plans = listOf(
            ready(
                CuratorDeltaCandidate.Update(
                    "candidate-u",
                    fence(a),
                    evidence,
                    listOf(targetDiff("policy-a", listOf(diff(a, CuratorPolicyField.PROCEDURE, "changed")))),
                ),
                listOf(a),
            ),
            ready(
                CuratorDeltaCandidate.Merge(
                    "candidate-m",
                    listOf(fence(a), fence(b)),
                    "policy-m",
                    merge,
                    evidence,
                    listOf(targetDiff("policy-m", a, merge)),
                ),
                listOf(a, b),
            ),
            ready(
                CuratorDeltaCandidate.Split(
                    "candidate-s",
                    fence(a),
                    listOf(
                        CuratorDeltaCandidate.SplitOutput("policy-s1", splitOne),
                        CuratorDeltaCandidate.SplitOutput("policy-s2", splitTwo),
                    ),
                    evidence,
                    listOf(
                        targetDiff("policy-s1", a, splitOne),
                        targetDiff("policy-s2", a, splitTwo),
                    ),
                ),
                listOf(a),
            ),
            ready(
                CuratorDeltaCandidate.Supersede(
                    "candidate-x",
                    fence(a),
                    "policy-x",
                    replacement,
                    evidence,
                    listOf(targetDiff("policy-x", a, replacement)),
                ),
                listOf(a),
            ),
        )

        assertEquals(CuratorDeltaOperation.entries, plans.map { it.operation })
        plans.forEach { plan ->
            assertTrue(plan.rollback.expectedAppliedHeads.isNotEmpty())
            assertTrue(plan.rollback.mutations.isNotEmpty())
            assertTrue(plan.rollback.mutations.all { mutation ->
                requireNotNull(mutation.after).revision > requireNotNull(mutation.before).revision
            })
        }
    }

    @Test
    fun `transaction conflict leaves caller state unchanged and rollback is recoverable archive`() {
        val source = head("policy-a", 4L, document("a"))
        val replacementDocument = document("replacement")
        val plan = ready(
            CuratorDeltaCandidate.Supersede(
                "candidate-supersede",
                fence(source),
                "policy-replacement",
                replacementDocument,
                evidence,
                listOf(targetDiff("policy-replacement", source, replacementDocument)),
            ),
            listOf(source),
        )
        val initial = CuratorMaterializedState(mapOf(source.policyId to source))
        val raced = initial.copy(
            heads = mapOf(source.policyId to source.copy(revision = source.revision + 1L)),
        )

        val conflict = DeterministicCuratorPlanExecutor.apply(raced, plan)
        assertEquals(
            CuratorTransactionResult.Conflict(CuratorConflictReason.REVISION_CONFLICT),
            conflict,
        )
        assertEquals(1, raced.heads.size)

        val applied = DeterministicCuratorPlanExecutor.apply(initial, plan) as
            CuratorTransactionResult.Applied
        val rolledBack = DeterministicCuratorPlanExecutor.rollback(applied.state, plan.rollback) as
            CuratorTransactionResult.Applied
        assertEquals(CuratorPolicyState.REVIEWED, rolledBack.state.heads["policy-a"]?.state)
        assertEquals(CuratorPolicyState.ARCHIVED, rolledBack.state.heads["policy-replacement"]?.state)
        assertTrue(rolledBack.state.lineage.isEmpty())
    }

    @Test
    fun `toString redacts candidate policy and diff content`() {
        val source = head("policy-a", 4L, document("sentinel"))
        val candidate = CuratorDeltaCandidate.Update(
            "candidate-update",
            fence(source),
            evidence,
            listOf(targetDiff(source.policyId, listOf(diff(source, CuratorPolicyField.PROCEDURE, "secret-sentinel")))),
        )
        val plan = ready(candidate, listOf(source))

        assertFalse(plan.toString().contains("secret-sentinel"))
        assertFalse(candidate.diffs.single().toString().contains("secret-sentinel"))
        assertFalse(source.toString().contains("sentinel"))
        assertFalse(candidate.toString().contains("secret-sentinel"))
    }

    private fun ready(
        candidate: CuratorDeltaCandidate,
        heads: List<CuratorPolicyHead>,
    ): CuratorApplyPlan = (applier.plan(candidate, headReader(heads), evidenceReader()) as CuratorApplyResult.Ready).plan

    private fun assertConflict(result: CuratorApplyResult, reason: CuratorConflictReason) {
        assertEquals(CuratorApplyResult.Conflict(reason), result)
    }

    private fun headReader(heads: List<CuratorPolicyHead>) = CuratorPolicyHeadReader { id ->
        heads.singleOrNull { it.policyId == id }
    }

    private fun evidenceReader() = CuratorEvidenceReader { id ->
        evidence.singleOrNull { it.evidenceId == id }
    }

    private fun head(id: String, revision: Long, document: CuratorPolicyDocument) =
        CuratorPolicyHead(id, scope, revision, CuratorPolicyState.REVIEWED, document)

    private fun fence(head: CuratorPolicyHead) = CuratorSourceFence(
        head.policyId,
        head.scope,
        head.revision,
        head.artifactSha256,
    )

    private fun diff(
        head: CuratorPolicyHead,
        field: CuratorPolicyField,
        after: String,
    ) = CuratorFieldDiff(
        field,
        CuratorV1Canonicalizer.fieldSha256(field, head.document.value(field)),
        after,
    )

    private fun targetDiff(
        targetPolicyId: String,
        fields: List<CuratorFieldDiff>,
    ) = CuratorTargetDiff(targetPolicyId, fields.sortedBy { it.field.ordinal })

    private fun targetDiff(
        targetPolicyId: String,
        base: CuratorPolicyHead,
        output: CuratorPolicyDocument,
    ) = targetDiff(
        targetPolicyId,
        CuratorPolicyField.entries.mapNotNull { field ->
            val before = base.document.value(field)
            val after = output.value(field)
            if (before == after) null else CuratorFieldDiff(
                field = field,
                beforeSha256 = CuratorV1Canonicalizer.fieldSha256(field, before),
                afterValue = after,
            )
        },
    )

    private fun document(suffix: String) = CuratorPolicyDocument(
        trigger = "trigger-$suffix",
        procedure = "procedure-$suffix",
        verification = "verification-$suffix",
        boundary = "boundary-$suffix",
        failureMode = "failure-$suffix",
        applicableToolSchemaSha256 = listOf("a".repeat(64)),
    )
}

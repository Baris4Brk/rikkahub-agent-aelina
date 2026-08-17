package me.rerere.rikkahub.learning.storage.curator

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.curator.CURATOR_REDACTED_WIRE
import me.rerere.rikkahub.learning.curator.CuratorApplyPlan
import me.rerere.rikkahub.learning.curator.CuratorApplyResult
import me.rerere.rikkahub.learning.curator.CuratorArtifactIdentity
import me.rerere.rikkahub.learning.curator.CuratorDeltaCandidate
import me.rerere.rikkahub.learning.curator.CuratorDeltaOperation
import me.rerere.rikkahub.learning.curator.CuratorEvidenceRef
import me.rerere.rikkahub.learning.curator.CuratorFieldDiff
import me.rerere.rikkahub.learning.curator.CuratorPolicyDocument
import me.rerere.rikkahub.learning.curator.CuratorPolicyField
import me.rerere.rikkahub.learning.curator.CuratorPolicyHead
import me.rerere.rikkahub.learning.curator.CuratorPolicyState
import me.rerere.rikkahub.learning.curator.CuratorSourceFence
import me.rerere.rikkahub.learning.curator.CuratorSourcePolicyKey
import me.rerere.rikkahub.learning.curator.CuratorTargetDiff
import me.rerere.rikkahub.learning.curator.CuratorV1Canonicalizer
import me.rerere.rikkahub.learning.curator.CuratorV1WireCodec
import me.rerere.rikkahub.learning.curator.DeterministicCuratorDeltaApplier
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LEARNING_DATABASE_VERSION
import me.rerere.rikkahub.learning.storage.LEARNING_MIGRATION_7_8
import me.rerere.rikkahub.learning.storage.LEARNING_V8_SCHEMA_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratorDeltaStorageContractTest {
    private val scope = LearningScope.Assistant(
        Uuid.parse("11111111-1111-4111-8111-111111111111"),
    )
    private val evidence = listOf(CuratorEvidenceRef("evidence-1", scope, 2L, "e".repeat(64)))

    @Test
    fun `candidate canonical wire round trips with exact digest and no toString content`() {
        val candidate = updateCandidate("private-procedure")
        val entity = candidate.toProposedEntity(provenance(), 10L)

        assertEquals(candidate, entity.decodeCandidateOrNull())
        assertEquals(entity.candidateSha256, CuratorV1WireCodec.candidateSha256(entity.candidateWire))
        assertFalse(entity.toString().contains("private-procedure"))
        assertFalse(entity.toString().contains(candidate.candidateId))
    }

    @Test
    fun `candidate wire rejects one-byte content tampering`() {
        val entity = updateCandidate("before").toProposedEntity(provenance(), 10L)

        assertThrows(IllegalArgumentException::class.java) {
            entity.copy(candidateWire = entity.candidateWire.replace("before", "after"))
        }
    }

    @Test
    fun `all four delta operations have canonical durable candidate round trips`() {
        val a = sourceHead()
        val b = CuratorPolicyHead(
            "policy-z",
            scope,
            7L,
            CuratorPolicyState.REVIEWED,
            document("z"),
        )
        val mergeOutput = document("merged")
        val splitA = document("split-a")
        val splitB = document("split-b")
        val candidates = listOf<CuratorDeltaCandidate>(
            updateCandidate("updated"),
            CuratorDeltaCandidate.Merge(
                "candidate-merge",
                listOf(fence(a), fence(b)),
                "policy-merged",
                mergeOutput,
                evidence,
                listOf(fullDiff("policy-merged", a, mergeOutput)),
            ),
            CuratorDeltaCandidate.Split(
                "candidate-split",
                fence(a),
                listOf(
                    CuratorDeltaCandidate.SplitOutput("policy-split-a", splitA),
                    CuratorDeltaCandidate.SplitOutput("policy-split-b", splitB),
                ),
                evidence,
                listOf(
                    fullDiff("policy-split-a", a, splitA),
                    fullDiff("policy-split-b", a, splitB),
                ),
            ),
            supersedeCandidate(),
        )

        assertEquals(CuratorDeltaOperation.entries, candidates.map { it.operation })
        candidates.forEach { candidate ->
            val first = CuratorV1WireCodec.encodeCandidate(candidate)
            val decoded = requireNotNull(CuratorV1WireCodec.decodeCandidateOrNull(first))
            assertEquals(candidate.operation, decoded.operation)
            assertEquals(first, CuratorV1WireCodec.encodeCandidate(decoded))
        }
    }

    @Test
    fun `source policy token is exact and collision resistant`() {
        val key = CuratorSourcePolicyKey.encode(listOf("policy-10", "policy-1"))

        assertTrue(CuratorSourcePolicyKey.contains(key, "policy-1"))
        assertTrue(CuratorSourcePolicyKey.contains(key, "policy-10"))
        assertFalse(CuratorSourcePolicyKey.contains(key, "policy"))
        assertFalse(CuratorSourcePolicyKey.contains(key, "policy-100"))
        assertEquals("|policy-1|policy-10|", key)
    }

    @Test
    fun `apply plan wire round trips rollback fences and exact plan identity`() {
        val candidate = supersedeCandidate()
        val plan = ready(candidate, listOf(sourceHead()))
        val approved = candidate.toProposedEntity(provenance(), 10L).copy(
            stateVersion = 2L,
            state = CuratorDeltaStoredState.APPROVED.name,
            updatedAtMs = 11L,
        )
        val applying = approved.withApplyPlan(plan, 12L)

        assertEquals(plan, applying.decodeApplyPlanOrNull())
        assertEquals(plan.planId, applying.applyPlanId)
        assertEquals(
            applying.applyPlanSha256,
            CuratorV1WireCodec.applyPlanSha256(requireNotNull(applying.applyPlanWire)),
        )
        assertEquals(plan.rollback.expectedAppliedHeads, applying.decodeApplyPlanOrNull()!!
            .rollback.expectedAppliedHeads)
    }

    @Test
    fun `apply plan preserves production artifact and exact storage status fences`() {
        val source = sourceHead().copy(
            artifactSha256 = "9".repeat(64),
            storageStateCode = "ACTIVE",
        )
        val candidate = CuratorDeltaCandidate.Update(
            "candidate-production-update",
            fence(source),
            evidence,
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
                            "bounded production update",
                        ),
                    ),
                ),
            ),
        )
        val plan = (
            DeterministicCuratorDeltaApplier(
                CuratorArtifactIdentity { _, _ -> "8".repeat(64) },
            ).plan(candidate, { source }, { evidence.single() }) as CuratorApplyResult.Ready
            ).plan

        val wire = CuratorV1WireCodec.encodeApplyPlan(plan)
        val decoded = requireNotNull(CuratorV1WireCodec.decodeApplyPlanOrNull(wire))

        assertEquals("9".repeat(64), decoded.mutations.single().before?.artifactSha256)
        assertEquals("8".repeat(64), decoded.mutations.single().after?.artifactSha256)
        assertEquals("ACTIVE", decoded.mutations.single().after?.storageStateCode)
    }

    @Test
    fun `lineage persists both endpoint revision and artifact fences`() {
        val plan = ready(supersedeCandidate(), listOf(sourceHead()))

        val edge = plan.toLineageEntities(20L).single()

        assertEquals(4L, edge.parentRevision)
        assertEquals(sourceHead().artifactSha256, edge.parentArtifactSha256)
        assertEquals(1L, edge.childRevision)
        assertEquals(plan.mutations.first().after!!.artifactSha256, edge.childArtifactSha256)
        assertTrue(edge.active)
        assertFalse(edge.toString().contains("policy-source"))
    }

    @Test
    fun `state machine rejects direct apply and wrong revision reason`() {
        val candidate = updateCandidate("changed")
        val proposed = candidate.toProposedEntity(provenance(), 10L)
        val approved = proposed.copy(
            stateVersion = 2L,
            state = CuratorDeltaStoredState.APPROVED.name,
            updatedAtMs = 11L,
        )
        val applying = approved.withApplyPlan(ready(candidate, listOf(sourceHead())), 12L)
        val skippedApplyCommit = applying.copy(
            stateVersion = applying.stateVersion + 1L,
            state = CuratorDeltaStoredState.ROLLING_BACK.name,
            updatedAtMs = 13L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            CuratorDeltaStateMachine.requireTransition(
                applying,
                skippedApplyCommit,
                CuratorDeltaRevisionReason.ROLLBACK_STARTED,
                CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CuratorDeltaStateMachine.requireTransition(
                proposed,
                approved,
                CuratorDeltaRevisionReason.USER_REJECTED,
                CuratorDeltaRevisionActor.USER,
            )
        }
    }

    @Test
    fun `state machine accepts explicit user approval and monotonic revision`() {
        val proposed = updateCandidate("changed").toProposedEntity(provenance(), 10L)
        val approved = proposed.copy(
            stateVersion = 2L,
            state = CuratorDeltaStoredState.APPROVED.name,
            updatedAtMs = 11L,
        )

        CuratorDeltaStateMachine.requireTransition(
            proposed,
            approved,
            CuratorDeltaRevisionReason.USER_APPROVED,
            CuratorDeltaRevisionActor.USER,
        )
        assertEquals(2L, approved.toRevisionEntity(
            CuratorDeltaRevisionReason.USER_APPROVED,
            CuratorDeltaRevisionActor.USER,
        ).stateVersion)
    }

    @Test
    fun `state machine rejects mutation of every immutable candidate provenance field`() {
        val proposed = updateCandidate("changed").toProposedEntity(provenance(), 10L)
        val forged = proposed.copy(
            stateVersion = 2L,
            state = CuratorDeltaStoredState.APPROVED.name,
            producerIdentitySha256 = "d".repeat(64),
            updatedAtMs = 11L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            CuratorDeltaStateMachine.requireTransition(
                proposed,
                forged,
                CuratorDeltaRevisionReason.USER_APPROVED,
                CuratorDeltaRevisionActor.USER,
            )
        }
    }

    @Test
    fun `fixed source redaction destroys candidate and plan bodies but retains audit hashes`() {
        val candidate = supersedeCandidate()
        val plan = ready(candidate, listOf(sourceHead()))
        val approved = candidate.toProposedEntity(provenance(), 10L).copy(
            stateVersion = 2L,
            state = CuratorDeltaStoredState.APPROVED.name,
            updatedAtMs = 11L,
        )
        val applying = approved.withApplyPlan(plan, 12L)
        val redacted = applying.copy(
            stateVersion = applying.stateVersion + 1L,
            state = CuratorDeltaStoredState.REDACTED_SOURCE.name,
            sourcePolicyIdsKey = CURATOR_REDACTED_WIRE,
            candidateWire = CURATOR_REDACTED_WIRE,
            applyPlanWire = CURATOR_REDACTED_WIRE,
            conflictCode = null,
            redactedAtMs = 13L,
            updatedAtMs = 13L,
        )

        assertNull(redacted.decodeCandidateOrNull())
        assertNull(redacted.decodeApplyPlanOrNull())
        assertEquals(applying.candidateSha256, redacted.candidateSha256)
        assertEquals(applying.applyPlanSha256, redacted.applyPlanSha256)
    }

    @Test
    fun `revision receipt contains no candidate or plan wire`() {
        val entity = updateCandidate("secret-marker").toProposedEntity(provenance(), 10L)
        val revision = entity.toRevisionEntity(
            CuratorDeltaRevisionReason.CREATED,
            CuratorDeltaRevisionActor.CURATOR_MODEL,
        )

        assertFalse(revision.toString().contains("secret-marker"))
        assertFalse(
            CuratorDeltaRevisionEntity::class.java.declaredFields.any {
                it.name.contains("wire", ignoreCase = true)
            },
        )
    }

    @Test
    fun `v8 migration owns curator and content-free Stage-D while v9 is additive`() {
        assertEquals(9, LEARNING_DATABASE_VERSION)
        assertEquals(7, LEARNING_MIGRATION_7_8.startVersion)
        assertEquals(8, LEARNING_MIGRATION_7_8.endVersion)
        val sql = LEARNING_V8_SCHEMA_SQL.joinToString("\n")

        listOf(
            "learning_policy_shadow_observations",
            "learning_policy_shadow_observation_items",
            "curator_delta_candidates",
            "curator_delta_revisions",
            "curator_delta_lineage",
            "candidate_sha256",
            "apply_plan_sha256",
            "parent_revision",
            "child_revision",
        ).forEach { assertTrue(it in sql) }
        assertFalse("learning_observed_utility_" in sql)
    }

    @Test
    fun `curator storage exposes only the full derived reset delete and no model delete operation`() {
        assertEquals(
            setOf("deleteAllCandidatesForDerivedReset"),
            CuratorDeltaDao::class.java.methods
                .map { it.name }
                .filter { it.contains("delete", ignoreCase = true) }
                .toSet(),
        )
        assertFalse(LEARNING_V8_SCHEMA_SQL.any { "DELETE FROM" in it.uppercase() })
        assertTrue(
            CuratorDeltaDao::class.java.methods.any { it.name == "redactByPolicySource" },
        )
        assertTrue(
            CuratorDeltaDao::class.java.methods.any { it.name == "redactScopeBeforeErase" },
        )
    }

    @Test
    fun `scope privacy redaction query is bounded exact and content destructive`() {
        assertTrue(
            CuratorDeltaDao::class.java.methods.any {
                it.name == "redactScopeBeforeErase" && it.parameterTypes.size == 5
            },
        )
        val marker = CURATOR_REDACTED_WIRE
        assertEquals("REDACTED_V1", marker)
        assertFalse(marker.contains("policy", ignoreCase = true))
    }

    @Test
    fun `privacy redaction result requires complete bounded batches`() {
        val complete = CuratorDeltaRedactionResult(3, 3, false)
        assertEquals(complete.scanned, complete.redacted)

        assertThrows(IllegalArgumentException::class.java) {
            CuratorDeltaRedactionResult(129, 129, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CuratorDeltaRedactionResult(1, 0, false)
        }
    }

    @Test
    fun `invalid redacted row cannot retain candidate body`() {
        val entity = updateCandidate("secret").toProposedEntity(provenance(), 10L)

        assertThrows(IllegalArgumentException::class.java) {
            entity.copy(
                stateVersion = 2L,
                state = CuratorDeltaStoredState.REDACTED_SOURCE.name,
                redactedAtMs = 11L,
                updatedAtMs = 11L,
            )
        }
    }

    private fun updateCandidate(after: String): CuratorDeltaCandidate.Update {
        val source = sourceHead()
        return CuratorDeltaCandidate.Update(
            candidateId = "candidate-update",
            source = fence(source),
            evidence = evidence,
            diffs = listOf(
                CuratorTargetDiff(
                    source.policyId,
                    listOf(
                        CuratorFieldDiff(
                            CuratorPolicyField.PROCEDURE,
                            CuratorV1Canonicalizer.fieldSha256(
                                CuratorPolicyField.PROCEDURE,
                                source.document.procedure,
                            ),
                            after,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun supersedeCandidate(): CuratorDeltaCandidate.Supersede {
        val source = sourceHead()
        val replacement = document("replacement")
        return CuratorDeltaCandidate.Supersede(
            candidateId = "candidate-supersede",
            source = fence(source),
            replacementPolicyId = "policy-replacement",
            replacementDocument = replacement,
            evidence = evidence,
            diffs = listOf(fullDiff("policy-replacement", source, replacement)),
        )
    }

    private fun ready(
        candidate: CuratorDeltaCandidate,
        heads: List<CuratorPolicyHead>,
    ): CuratorApplyPlan {
        val result = DeterministicCuratorDeltaApplier().plan(
            candidate,
            { id -> heads.singleOrNull { it.policyId == id } },
            { id -> evidence.singleOrNull { it.evidenceId == id } },
        )
        assertTrue(result is CuratorApplyResult.Ready)
        return (result as CuratorApplyResult.Ready).plan
    }

    private fun sourceHead() = CuratorPolicyHead(
        "policy-source",
        scope,
        4L,
        CuratorPolicyState.REVIEWED,
        document("source"),
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
        trigger = "trigger-$suffix",
        procedure = "procedure-$suffix",
        verification = "verification-$suffix",
        boundary = "boundary-$suffix",
        failureMode = "failure-$suffix",
        applicableToolSchemaSha256 = listOf("a".repeat(64)),
    )

    private fun provenance() = CuratorDeltaProvenance("b".repeat(64), "c".repeat(64))
}

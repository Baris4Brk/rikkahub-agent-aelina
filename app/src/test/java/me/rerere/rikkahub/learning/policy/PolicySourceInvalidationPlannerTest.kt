package me.rerere.rikkahub.learning.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicySourceInvalidationPlannerTest {
    @Test
    fun oneInvalidSourceDoesNotStalePolicyWithSurvivingEvidence() {
        val result = PolicySourceInvalidationPlanner.plan(
            evidence = listOf(
                PolicyEvidenceValiditySnapshot("p", "e1", false),
                PolicyEvidenceValiditySnapshot("p", "e2", true),
            ),
            lineage = emptyList(),
        ) as PolicySourcePropagationResult.Planned

        assertFalse("p" in result.plan.stalePolicyIds)
        assertEquals(1, result.plan.survivingEvidenceCounts["p"])
    }

    @Test
    fun lastSourceStalesDerivedLineageWithinBound() {
        val result = PolicySourceInvalidationPlanner.plan(
            evidence = listOf(PolicyEvidenceValiditySnapshot("root", "e", false)),
            lineage = listOf(
                PolicyLineageEdge("root", "child", PolicyLineageKind.DERIVED_FROM),
                PolicyLineageEdge("child", "grandchild", PolicyLineageKind.MERGED_FROM),
                PolicyLineageEdge("root", "unrelated", PolicyLineageKind.CONTRADICTS),
            ),
        ) as PolicySourcePropagationResult.Planned

        assertEquals(setOf("root", "child", "grandchild"), result.plan.stalePolicyIds)
        assertFalse("unrelated" in result.plan.stalePolicyIds)
    }

    @Test
    fun cycleRejectsWholePlan() {
        val result = PolicySourceInvalidationPlanner.plan(
            evidence = listOf(PolicyEvidenceValiditySnapshot("a", "e", false)),
            lineage = listOf(
                PolicyLineageEdge("a", "b", PolicyLineageKind.DERIVED_FROM),
                PolicyLineageEdge("b", "a", PolicyLineageKind.DERIVED_FROM),
            ),
        )
        assertTrue(result is PolicySourcePropagationResult.Rejected)
        assertEquals(
            PolicySourcePropagationFailure.CYCLE,
            (result as PolicySourcePropagationResult.Rejected).failure,
        )
    }

    @Test
    fun lineageBeyondFrozenDepthRejectsWholePlan() {
        val edges = (0..MAX_POLICY_LINEAGE_DEPTH).map { index ->
            PolicyLineageEdge(
                fromPolicyId = "p-$index",
                toPolicyId = "p-${index + 1}",
                kind = PolicyLineageKind.DERIVED_FROM,
            )
        }

        val result = PolicySourceInvalidationPlanner.plan(
            evidence = listOf(PolicyEvidenceValiditySnapshot("p-0", "e", false)),
            lineage = edges,
        )

        assertEquals(
            PolicySourcePropagationFailure.DEPTH_LIMIT,
            (result as PolicySourcePropagationResult.Rejected).failure,
        )
    }
}

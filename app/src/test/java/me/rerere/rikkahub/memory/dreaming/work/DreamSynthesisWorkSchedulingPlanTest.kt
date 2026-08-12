package me.rerere.rikkahub.memory.dreaming.work

import androidx.work.ExistingWorkPolicy
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.runtime.DreamNetworkPolicy
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingCostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamSynthesisWorkSchedulingPlanTest {
    @Test
    fun `scope identity uses full sha256 and coalesces with KEEP`() {
        val first = dreamSynthesisScopeWorkPlan(PRIVATE_SCOPE, runId(1))
        val replay = dreamSynthesisScopeWorkPlan(PRIVATE_SCOPE, runId(2))
        val global = dreamSynthesisScopeWorkPlan(DreamScopeId.Global, runId(3))

        assertEquals(ExistingWorkPolicy.KEEP, first.policy)
        assertEquals(first.uniqueWorkName, replay.uniqueWorkName)
        assertNotEquals(first.uniqueWorkName, global.uniqueWorkName)
        assertEquals(43, dreamSynthesisScopeWorkKey(PRIVATE_SCOPE).length)
        assertEquals(PRIVATE_SCOPE, first.scopeId)
        assertEquals(runId(1), first.runId)
    }

    @Test
    fun `noncanonical run id is rejected before enqueue`() {
        assertThrows(IllegalArgumentException::class.java) {
            dreamSynthesisScopeWorkPlan(PRIVATE_SCOPE, "RUN-1")
        }
    }

    @Test
    fun `settings replacement keeps exact scope identity and uses REPLACE`() {
        val normal = dreamSynthesisScopeWorkPlan(PRIVATE_SCOPE, runId(1))
        val replaced = dreamSynthesisScopeWorkPlan(
            PRIVATE_SCOPE,
            runId(2),
            replaceExisting = true,
        )

        assertEquals(normal.uniqueWorkName, replaced.uniqueWorkName)
        assertEquals(ExistingWorkPolicy.REPLACE, replaced.policy)
    }

    @Test
    fun `scope cancellation includes the legacy and current unique names`() {
        val all = dreamSynthesisAllScopeWorkNames(PRIVATE_SCOPE)

        assertEquals(2, all.distinct().size)
        assertEquals(dreamSynthesisScopeWorkName(PRIVATE_SCOPE), all.first())
        assertTrue(all.last().startsWith("dream_synthesis_scope_"))
    }

    @Test
    fun `all-off recovery cancellation covers every synthesis scan identity`() {
        val names = dreamSynthesisRecoveryWorkNames()

        assertEquals(4, names.distinct().size)
        assertTrue(names.all { it.startsWith("dream_synthesis_scan_") })
        assertTrue(names.any { it.contains("startup") })
        assertTrue(names.any { it.contains("periodic") })
        assertTrue(names.any { it.contains("utc_rollover") })
        assertEquals(setOf("dream_synthesis_scope_work_v1"), dreamSynthesisScopeWorkTags())
    }

    @Test
    fun `cost scan supersedes a coalesced settings scan so constraints cannot stay stale`() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            dreamSynthesisScanExistingPolicy(DreamSynthesisScanReason.COST_POLICY_CHANGED),
        )
        assertEquals(
            ExistingWorkPolicy.KEEP,
            dreamSynthesisScanExistingPolicy(DreamSynthesisScanReason.SETTINGS_CHANGED),
        )
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            dreamSynthesisScanExistingPolicy(DreamSynthesisScanReason.FOLLOW_UP),
        )
    }

    @Test
    fun `conservative global policy maps to network battery charging and idle plan`() {
        val plan = dreamSynthesisConstraintPlan(
            DreamingCostPolicy(
                networkPolicy = DreamNetworkPolicy.UNMETERED,
                requireBatteryNotLow = true,
                requireCharging = true,
                idleThresholdMinutes = 45,
            ),
        )

        assertEquals(DreamNetworkPolicy.UNMETERED, plan.networkPolicy)
        assertTrue(plan.requireBatteryNotLow)
        assertTrue(plan.requireCharging)
        assertEquals(45, plan.initialDelayMinutes)
    }

    private fun runId(value: Int): String =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

    private companion object {
        val PRIVATE_SCOPE = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )
    }
}

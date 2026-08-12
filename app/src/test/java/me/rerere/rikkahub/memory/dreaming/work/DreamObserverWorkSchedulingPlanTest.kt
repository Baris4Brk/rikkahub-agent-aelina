package me.rerere.rikkahub.memory.dreaming.work

import androidx.work.ExistingWorkPolicy
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamObserverWorkSchedulingPlanTest {
    @Test
    fun `scope work uses full sha256 identity and KEEP coalescing`() {
        val first = dreamObserverScopeWorkPlan(PRIVATE_SCOPE, runId(1))
        val replay = dreamObserverScopeWorkPlan(PRIVATE_SCOPE, runId(2))
        val global = dreamObserverScopeWorkPlan(DreamScopeId.Global, runId(3))

        assertEquals(ExistingWorkPolicy.KEEP, first.policy)
        assertEquals(first.uniqueWorkName, replay.uniqueWorkName)
        assertNotEquals(first.uniqueWorkName, global.uniqueWorkName)
        assertEquals(43, dreamObserverScopeWorkKey(PRIVATE_SCOPE).length)
        assertTrue(first.uniqueWorkName.endsWith(dreamObserverScopeWorkKey(PRIVATE_SCOPE)))
        assertEquals(runId(1), first.runId)
        assertEquals(PRIVATE_SCOPE, first.scopeId)
        assertEquals(
            setOf(DreamObserverWorker.KEY_SCOPE_ID, DreamObserverWorker.KEY_RUN_ID),
            dreamObserverWorkPayload(first).keys,
        )
        assertEquals(
            mapOf(
                DreamObserverWorker.KEY_SCOPE_ID to PRIVATE_SCOPE.value,
                DreamObserverWorker.KEY_RUN_ID to runId(1),
            ),
            dreamObserverWorkPayload(first),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `noncanonical run id is rejected before WorkManager payload creation`() {
        dreamObserverScopeWorkPlan(PRIVATE_SCOPE, "RUN-1")
    }

    private fun runId(value: Int): String =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"

    private companion object {
        val PRIVATE_SCOPE: DreamScopeId = DreamScopeId.requireCanonical(
            "123e4567-e89b-12d3-a456-426614174000",
        )
    }
}

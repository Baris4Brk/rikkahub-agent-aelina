package me.rerere.rikkahub.memory

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWorkSchedulingPlanTest {
    @Test
    fun `new captures replace only their delayed debounce trigger`() {
        val debounce = memoryDebounceWorkPlan(scopeId = "assistant-scope", delayMs = 600_000L)
        val processing = memoryProcessingWorkPlan(scopeId = "assistant-scope")

        assertEquals(ExistingWorkPolicy.REPLACE, debounce.policy)
        assertTrue(debounce.dispatchOnly)
        assertEquals(600_000L, debounce.delayMs)
        assertNotEquals(processing.uniqueWorkName, debounce.uniqueWorkName)
    }

    @Test
    fun `immediate processing appends behind an active extraction instead of replacing it`() {
        val processing = memoryProcessingWorkPlan(scopeId = "assistant-scope")

        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, processing.policy)
        assertFalse(processing.dispatchOnly)
        assertEquals(0L, processing.delayMs)
    }

    @Test
    fun `debounce delay never schedules a negative WorkManager delay`() {
        assertEquals(0L, memoryDebounceWorkPlan("assistant-scope", -1L).delayMs)
    }

    @Test
    fun `new scope work names separate known 32-bit hash collisions`() {
        val firstScope = "Aa"
        val secondScope = "BB"

        // This is a documented Java String hash collision and reproduces the old defect.
        assertEquals(firstScope.hashCode(), secondScope.hashCode())
        assertEquals(2112, firstScope.hashCode())

        val firstDebounce = memoryDebounceWorkPlan(firstScope, delayMs = 0L).uniqueWorkName
        val secondDebounce = memoryDebounceWorkPlan(secondScope, delayMs = 0L).uniqueWorkName
        val firstProcessing = memoryProcessingWorkPlan(firstScope).uniqueWorkName
        val secondProcessing = memoryProcessingWorkPlan(secondScope).uniqueWorkName

        assertNotEquals(firstDebounce, secondDebounce)
        assertNotEquals(firstProcessing, secondProcessing)
        assertTrue(firstDebounce.startsWith("memory_v2_debounce_scope_v2_"))
        assertTrue(firstProcessing.startsWith("memory_v2_process_scope_v2_"))
    }

    @Test
    fun `scope work key is full SHA-256 encoded as unpadded Base64URL`() {
        val key = memoryScopeWorkKey("Aa")

        assertEquals("gayq-6lhu4MexA65ZRVeNIY5LGDPuMdRUL6vLK_8JEo", key)
        assertEquals(43, key.length)
        assertTrue(key.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertFalse(key.contains('='))
        assertFalse(key.contains('+'))
        assertFalse(key.contains('/'))
    }

    @Test
    fun `cancel migration covers new names and every persisted 32-bit name`() {
        val firstScopeNames = memoryWorkNamesToCancel("Aa")
        val collidingScopeNames = memoryWorkNamesToCancel("BB")
        val legacyCollisionNames = setOf(
            "memory_v2_debounce_2112",
            "memory_v2_process_2112",
            "memory_v2_2112",
        )

        assertEquals(5, firstScopeNames.size)
        assertTrue(firstScopeNames.contains(memoryDebounceWorkPlan("Aa", 0L).uniqueWorkName))
        assertTrue(firstScopeNames.contains(memoryProcessingWorkPlan("Aa").uniqueWorkName))
        assertTrue(firstScopeNames.containsAll(legacyCollisionNames))
        assertTrue(collidingScopeNames.containsAll(legacyCollisionNames))
        assertEquals(legacyCollisionNames, firstScopeNames.intersect(collidingScopeNames))
        assertNotEquals(firstScopeNames, collidingScopeNames)
    }

    @Test
    fun `manual-only failure does not strand another pending capture`() {
        assertEquals(
            MemoryWorkerFollowUpAction.CONTINUE,
            memoryWorkerFollowUpAction(
                pendingCaptures = 1,
                automaticRetryFailedCaptures = 0,
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `automatic failure still uses WorkManager retry before continuing`() {
        assertEquals(
            MemoryWorkerFollowUpAction.RETRY,
            memoryWorkerFollowUpAction(
                pendingCaptures = 1,
                automaticRetryFailedCaptures = 1,
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `retention has independent startup and daily unique work`() {
        val plan = memoryMaintenanceSchedulePlan()

        assertEquals(ExistingWorkPolicy.KEEP, plan.startupPolicy)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, plan.periodicPolicy)
        assertNotEquals(plan.startupUniqueWorkName, plan.periodicUniqueWorkName)
        assertEquals(24L * 60L * 60L * 1_000L, plan.repeatIntervalMs)
        assertEquals(plan.repeatIntervalMs, plan.initialPeriodicDelayMs)
    }

    @Test
    fun `maintenance drains bounded expiry batches and retries a larger backlog`() {
        assertEquals(
            MemoryMaintenanceFollowUpAction.CONTINUE,
            memoryMaintenanceFollowUpAction(changedRows = 256, completedPasses = 1),
        )
        assertEquals(
            MemoryMaintenanceFollowUpAction.SUCCESS,
            memoryMaintenanceFollowUpAction(changedRows = 0, completedPasses = 2),
        )
        assertEquals(
            MemoryMaintenanceFollowUpAction.RETRY,
            memoryMaintenanceFollowUpAction(
                changedRows = 256,
                completedPasses = MAX_MEMORY_MAINTENANCE_PASSES,
            ),
        )
    }
}

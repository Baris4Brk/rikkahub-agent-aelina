package me.rerere.rikkahub.memory

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
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
}

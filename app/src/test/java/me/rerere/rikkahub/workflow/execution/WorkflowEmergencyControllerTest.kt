package me.rerere.rikkahub.workflow.execution

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEmergencyControllerTest {
    @Test
    fun `pause cancels active runs and rejects new work until resumed`() = runBlocking {
        val controller = WorkflowEmergencyController()
        val started = CompletableDeferred<Unit>()
        val job = async {
            controller.runTracked("workflow-1") {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
                "never"
            }
        }
        started.await()

        val stopped = controller.pauseAndCancelAll()

        assertEquals(1, stopped.affectedCount)
        assertTrue(job.isCancelled)
        assertNull(controller.runTracked("workflow-2") { "blocked" })
        controller.resumeNewRuns()
        assertEquals("allowed", controller.runTracked("workflow-3") { "allowed" })
        assertFalse(controller.isPaused)
    }

    @Test
    fun `completed run is removed by job identity`() = runBlocking {
        val controller = WorkflowEmergencyController()
        assertEquals("done", controller.runTracked("workflow-1") { "done" })
        assertEquals(0, controller.pauseAndCancelAll().affectedCount)
    }
}

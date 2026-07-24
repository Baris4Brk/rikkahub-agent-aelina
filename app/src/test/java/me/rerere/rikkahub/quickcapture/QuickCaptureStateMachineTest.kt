package me.rerere.rikkahub.quickcapture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCaptureStateMachineTest {
    @Test
    fun `normal one tap path follows the fixed capture state order`() {
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.IDLE, QuickCaptureStage.VALIDATING_TARGET))
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.VALIDATING_TARGET, QuickCaptureStage.HIDING_OVERLAY))
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.HIDING_OVERLAY, QuickCaptureStage.CAPTURING))
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.CAPTURING, QuickCaptureStage.PERSISTING))
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.PERSISTING, QuickCaptureStage.SUBMITTING))
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.SUBMITTING, QuickCaptureStage.QUEUED))
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.QUEUED, QuickCaptureStage.RUNNING))
        assertTrue(QuickCaptureStateMachine.allows(QuickCaptureStage.RUNNING, QuickCaptureStage.COMPLETED))
    }

    @Test
    fun `late callbacks cannot jump directly from idle to completed`() {
        assertFalse(QuickCaptureStateMachine.allows(QuickCaptureStage.IDLE, QuickCaptureStage.COMPLETED))
        assertFalse(QuickCaptureStateMachine.allows(QuickCaptureStage.FAILED, QuickCaptureStage.RUNNING))
        assertFalse(QuickCaptureStateMachine.allows(QuickCaptureStage.COMPLETED, QuickCaptureStage.SUBMITTING))
    }
}

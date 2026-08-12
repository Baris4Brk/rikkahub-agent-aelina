package me.rerere.rikkahub.memory.dreaming.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamAppIdleTrackerTest {
    @Test
    fun `unknown and foreground state fail closed`() {
        val tracker = InMemoryDreamAppIdleTracker()
        assertEquals(
            DreamAppIdleDeferralReason.STATE_UNKNOWN,
            (tracker.decisionAt(1_000, 15) as DreamAppIdleDecision.Deferred).reason,
        )

        tracker.onAppForegrounded(1_000)
        assertEquals(
            DreamAppIdleDeferralReason.APP_FOREGROUND,
            (tracker.decisionAt(2_000, 15) as DreamAppIdleDecision.Deferred).reason,
        )

        tracker.onStateUnknown()
        assertEquals(
            DreamAppIdleDeferralReason.STATE_UNKNOWN,
            (tracker.decisionAt(3_000, 15) as DreamAppIdleDecision.Deferred).reason,
        )
    }

    @Test
    fun `background threshold is deterministic and boundary inclusive`() {
        val tracker = InMemoryDreamAppIdleTracker()
        tracker.onAppBackgrounded(1_000)
        val eligibleAt = 1_000 + 15 * 60_000L

        val before = tracker.decisionAt(eligibleAt - 1, 15) as DreamAppIdleDecision.Deferred
        assertEquals(DreamAppIdleDeferralReason.THRESHOLD_NOT_REACHED, before.reason)
        assertEquals(eligibleAt, before.nextEligibleAtEpochMs)
        assertTrue(tracker.decisionAt(eligibleAt, 15) is DreamAppIdleDecision.Eligible)
    }

    @Test
    fun `clock rewind and duplicate rewound transition remain fail closed`() {
        val tracker = InMemoryDreamAppIdleTracker()
        tracker.onAppBackgrounded(2_000)
        assertEquals(
            DreamAppIdleDeferralReason.CLOCK_ROLLBACK,
            (tracker.decisionAt(1_999, 15) as DreamAppIdleDecision.Deferred).reason,
        )

        tracker.onAppBackgrounded(1_000)
        assertEquals(
            DreamAppIdleDeferralReason.STATE_UNKNOWN,
            (tracker.decisionAt(2_000, 15) as DreamAppIdleDecision.Deferred).reason,
        )
    }
}

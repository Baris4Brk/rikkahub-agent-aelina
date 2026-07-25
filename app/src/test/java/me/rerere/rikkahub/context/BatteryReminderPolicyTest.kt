package me.rerere.rikkahub.context

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReminderPolicyTest {
    @Test
    fun `only an uncharged battery at or below 20 percent enters automatic context`() {
        assertTrue(shouldExposeBatteryReminder(percent = 20, charging = false))
        assertTrue(shouldExposeBatteryReminder(percent = 1, charging = false))

        assertFalse(shouldExposeBatteryReminder(percent = 21, charging = false))
        assertFalse(shouldExposeBatteryReminder(percent = 20, charging = true))
        assertFalse(shouldExposeBatteryReminder(percent = null, charging = false))
    }
}

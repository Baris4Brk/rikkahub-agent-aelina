package me.rerere.rikkahub.assistant

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantLocalInvocationPolicyTest {
    @Test
    fun `hardware overlay entry accepts only its explicit invocation action`() {
        assertTrue(
            isSystemAssistantHardwareInvocationAction(
                SYSTEM_ASSISTANT_HARDWARE_INVOCATION_ACTION,
            ),
        )
        assertFalse(isSystemAssistantHardwareInvocationAction(null))
        assertFalse(isSystemAssistantHardwareInvocationAction("android.intent.action.ASSIST"))
        assertFalse(
            isSystemAssistantHardwareInvocationAction(
                "me.rerere.rikkahub.action.OPEN_SECOND_USER_ASSISTANT",
            ),
        )
    }

    @Test
    fun `hardware entry forwards inside its isolated task without resurrecting the main task`() {
        val flags = systemAssistantHardwareOverlayLaunchFlags()

        assertEquals(0, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
    }

    @Test
    fun `only the unlocked Android owner may request a local assistant session`() {
        assertTrue(
            shouldShowLocalSystemAssistant(
                isSystemUser = true,
                isDeviceLocked = false,
                isKeyguardLocked = false,
            )
        )
        assertFalse(
            shouldShowLocalSystemAssistant(
                isSystemUser = false,
                isDeviceLocked = false,
                isKeyguardLocked = false,
            )
        )
        assertFalse(
            shouldShowLocalSystemAssistant(
                isSystemUser = true,
                isDeviceLocked = true,
                isKeyguardLocked = false,
            )
        )
        assertFalse(
            shouldShowLocalSystemAssistant(
                isSystemUser = true,
                isDeviceLocked = false,
                isKeyguardLocked = true,
            )
        )
    }
}

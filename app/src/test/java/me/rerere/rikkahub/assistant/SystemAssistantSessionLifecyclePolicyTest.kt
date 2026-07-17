package me.rerere.rikkahub.assistant

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantSessionLifecyclePolicyTest {
    @Test
    fun `screen off permanently terminates the visible invocation`() {
        assertTrue(shouldTerminateSystemAssistantInvocation(Intent.ACTION_SCREEN_OFF))
        assertFalse(shouldTerminateSystemAssistantInvocation(Intent.ACTION_SCREEN_ON))
        assertFalse(shouldTerminateSystemAssistantInvocation(Intent.ACTION_USER_PRESENT))
        assertFalse(shouldTerminateSystemAssistantInvocation(null))
    }
}

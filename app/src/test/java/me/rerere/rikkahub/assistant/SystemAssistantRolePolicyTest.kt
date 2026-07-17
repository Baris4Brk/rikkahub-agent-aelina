package me.rerere.rikkahub.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantRolePolicyTest {
    @Test
    fun `voice assistant is available across the supported Android range`() {
        assertFalse(supportsSystemAssistantVoiceService(25))
        assertTrue(supportsSystemAssistantVoiceService(26))
        assertTrue(supportsSystemAssistantVoiceService(27))
        assertTrue(supportsSystemAssistantVoiceService(30))
        assertTrue(supportsSystemAssistantVoiceService(31))
        assertTrue(supportsSystemAssistantVoiceService(36))
        assertTrue(supportsSystemAssistantVoiceService(37))
    }

    @Test
    fun `held but inactive role opens settings instead of requesting the role again`() {
        assertTrue(
            shouldRequestSystemAssistantRole(
                roleAvailable = true,
                roleHeld = false,
                voiceServiceActive = false,
            ),
        )
        assertFalse(
            shouldRequestSystemAssistantRole(
                roleAvailable = true,
                roleHeld = true,
                voiceServiceActive = false,
            ),
        )
        assertFalse(
            shouldRequestSystemAssistantRole(
                roleAvailable = true,
                roleHeld = true,
                voiceServiceActive = true,
            ),
        )
    }

    @Test
    fun `accessibility shortcut selection matches exact colon delimited components`() {
        val target =
            "me.rerere.rikkahub/me.rerere.rikkahub.assistant.SystemAssistantAccessibilityButtonService"
        val shortTarget =
            "me.rerere.rikkahub/.assistant.SystemAssistantAccessibilityButtonService"
        val other = "example.reader/.ReaderService"

        assertTrue(isComponentSelected("$other:$shortTarget", target))
        assertTrue(isComponentSelected("$other:$target", shortTarget))
        assertTrue(isComponentSelected(target, target))
        assertFalse(isComponentSelected(null, target))
        assertFalse(isComponentSelected("$target.extra", target))
    }

    @Test
    fun `accessibility service is not reported enabled when the master switch is off`() {
        val target =
            "me.rerere.rikkahub/me.rerere.rikkahub.assistant.SystemAssistantAccessibilityButtonService"

        assertTrue(isAccessibilityServiceEnabled(true, target, target))
        assertFalse(isAccessibilityServiceEnabled(false, target, target))
        assertFalse(isAccessibilityServiceEnabled(true, null, target))
    }

    @Test
    fun `MagicVoice recovery reflects the actual owner-user package state`() {
        assertTrue(
            magicVoiceRecoveryStep(installed = true, enabled = false) ==
                MagicVoiceRecoveryStep.EnablePackage,
        )
        assertTrue(
            magicVoiceRecoveryStep(installed = true, enabled = true) ==
                MagicVoiceRecoveryStep.SelectAssistant,
        )
        assertTrue(
            magicVoiceRecoveryStep(installed = false, enabled = false) ==
                MagicVoiceRecoveryStep.SnapshotRequired,
        )
    }
}

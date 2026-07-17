package me.rerere.rikkahub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RikkaHubAppProcessTest {
    @Test
    fun `voice interactor process is classified as lightweight`() {
        assertTrue(
            isVoiceInteractorProcess(
                packageName = "me.rerere.rikkahub",
                processName = "me.rerere.rikkahub:voice_interactor",
            ),
        )
    }

    @Test
    fun `main and unrelated processes are not classified as voice interactor`() {
        assertFalse(isVoiceInteractorProcess("me.rerere.rikkahub", "me.rerere.rikkahub"))
        assertFalse(isVoiceInteractorProcess("me.rerere.rikkahub", "me.rerere.rikkahub:web"))
        assertFalse(isVoiceInteractorProcess("me.rerere.rikkahub", null))
    }
}

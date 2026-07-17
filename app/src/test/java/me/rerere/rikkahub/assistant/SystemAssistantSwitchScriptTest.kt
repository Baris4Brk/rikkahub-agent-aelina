package me.rerere.rikkahub.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAssistantSwitchScriptTest {
    private val script = File("../scripts/aaak-system-assistant-switch.ps1").readText()

    @Test
    fun `secure settings are always scoped to owner user`() {
        listOf("get", "put", "delete").forEach { operation ->
            assertTrue(
                "$operation must target Android user 0",
                Regex(
                    """"settings",\s*"$operation",\s*"--user",\s*"0",\s*"secure"""",
                ).containsMatchIn(script),
            )
        }
    }

    @Test
    fun `activation restores and verifies a real global recognizer`() {
        val activation = script
            .substringAfter("Set-SecureSetting -Key \"assistant\" -Value \$RikkaComponent")
            .substringBefore("Write-Output \"ACTIVATED:")

        assertTrue(
            activation.contains(
                "Set-SecureSetting -Key \"voice_recognition_service\" " +
                    "-Value \$activationRecognizer",
            ),
        )
        assertTrue(
            activation.contains(
                "Assert-SecureSetting -Key \"voice_recognition_service\" " +
                    "-ExpectedValue \$activationRecognizer",
            ),
        )
    }

    @Test
    fun `restore requires a snapshot and restores a consistent MagicVoice target`() {
        val restore = script
            .substringAfter("if (\$Mode -eq \"Restore\")")
            .substringBefore("\$baseline = Get-VoiceSnapshot")

        assertTrue(restore.contains("Restore requires -SnapshotPath"))
        assertTrue(
            restore.contains(
                "snapshot.voiceInteractionService -ne \$MagicVoiceComponent",
            ),
        )
        assertTrue(restore.contains("-EnableMagicVoice"))
        assertTrue(restore.contains("-AssistantOverride \$MagicVoiceComponent"))
        assertTrue(
            script.contains(
                "Assert-ActiveVoiceService -ExpectedComponent \$Snapshot.voiceInteractionService",
            ),
        )
    }

    @Test
    fun `activation only emits rollback snapshot from a MagicVoice baseline`() {
        val preflight = script
            .substringAfter("\$baseline = Get-VoiceSnapshot")
            .substringBefore("\$activationRecognizer")

        assertTrue(preflight.contains("\$Mode -eq \"Activate\""))
        assertTrue(
            preflight.contains(
                "\$baseline.voiceInteractionService -ne \$MagicVoiceComponent",
            ),
        )
    }

    @Test
    fun `active service assertion binds the exact expected component`() {
        val assertion = script
            .substringAfter("function Assert-ActiveVoiceService")
            .substringBefore("function Restore-VoiceSnapshot")

        assertTrue(assertion.contains("mComponent="))
        assertTrue(assertion.contains("\$component -eq \$ExpectedComponent"))
        assertTrue(assertion.contains("mBound=true"))
        assertTrue(assertion.contains("Start-Sleep"))
    }
}

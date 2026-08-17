package me.rerere.rikkahub.data.sync.backup

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.learning.model.LearningPreferencesV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupSettingsSanitizerTest {
    private val authorized = LearningPreferencesV1(
        handoff = true,
        capture = true,
        jobs = true,
        reflectionShadow = true,
        policyCandidate = true,
        policyRetrievalShadow = true,
        policyInjection = true,
        backgroundWorkAuthorized = true,
        authorizedModelIdentityDigests = setOf("a".repeat(64)),
        allowMeteredNetwork = true,
        allowRemoteReflection = true,
        remoteReflectionProviderIdentityDigest = "b".repeat(64),
        remoteReflectionModelIdentityDigest = "c".repeat(64),
    )

    @Test
    fun `portable export removes learning authorization without changing other settings`() {
        val input = Settings(
            dynamicColor = false,
            learningPreferences = authorized,
            assistants = listOf(
                Assistant(
                    learningCaptureEnabled = true,
                    authoritySubjectLearningCaptureEnabled = true,
                    reviewedPolicyInjectionEnabled = true,
                ),
            ),
        )

        val result = BackupSettingsSanitizer.forPortableArchive(input)

        assertEquals(LearningPreferencesV1(), result.learningPreferences)
        assertFalse(result.dynamicColor)
        assertFalse(result.assistants.single().learningCaptureEnabled)
        assertFalse(result.assistants.single().authoritySubjectLearningCaptureEnabled)
        assertFalse(result.assistants.single().reviewedPolicyInjectionEnabled)
    }

    @Test
    fun `portable restore cannot reactivate source-device learning consent`() {
        val input = Settings(
            learningPreferences = authorized,
            assistants = listOf(
                Assistant(
                    learningCaptureEnabled = true,
                    authoritySubjectLearningCaptureEnabled = true,
                    reviewedPolicyInjectionEnabled = true,
                ),
            ),
        )

        val result = BackupSettingsSanitizer.afterPortableRestore(input)

        assertEquals(LearningPreferencesV1(), result.learningPreferences)
        assertFalse(result.assistants.single().learningCaptureEnabled)
        assertFalse(result.assistants.single().authoritySubjectLearningCaptureEnabled)
        assertFalse(result.assistants.single().reviewedPolicyInjectionEnabled)
    }
}

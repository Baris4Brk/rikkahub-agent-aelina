package me.rerere.rikkahub.data.sync.backup

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.learning.model.LearningPreferencesV1

/**
 * Removes device-local Learning rollout consent from portable settings archives.
 *
 * Provider authorization and background execution consent are deliberately not portable. A
 * restore may bring model/provider configuration across devices, but P1 remains disabled until
 * the user explicitly enables it again on the destination installation. Policy-grant authority
 * is not a Settings field at all: it is restored only as part of the main AppDatabase snapshot,
 * where source-stream binding keeps grants from a different stream inert.
 */
internal object BackupSettingsSanitizer {
    fun forPortableArchive(settings: Settings): Settings = settings.copy(
        learningPreferences = LearningPreferencesV1(),
        assistants = settings.assistants.map { assistant ->
            assistant.copy(
                learningCaptureEnabled = false,
                authoritySubjectLearningCaptureEnabled = false,
                reviewedPolicyInjectionEnabled = false,
            )
        },
    )

    fun afterPortableRestore(settings: Settings): Settings = settings.copy(
        learningPreferences = LearningPreferencesV1(),
        assistants = settings.assistants.map { assistant ->
            assistant.copy(
                learningCaptureEnabled = false,
                authoritySubjectLearningCaptureEnabled = false,
                reviewedPolicyInjectionEnabled = false,
            )
        },
    )
}

package me.rerere.rikkahub.learning.model

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.learning.retention.LearningRetentionPreferencesSource

/** Device-local user retention choices; unavailable/uninitialized settings use conservative v1. */
class SettingsLearningRetentionPreferencesSource(
    private val settingsStore: SettingsStore,
) : LearningRetentionPreferencesSource {
    override fun current(): LearningRetentionPreferencesV1 {
        val settings = settingsStore.settingsFlow.value
        return if (settings.init) LearningRetentionPreferencesV1()
        else settings.learningPreferences.retention.failClosed()
    }
}

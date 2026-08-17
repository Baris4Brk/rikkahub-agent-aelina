package me.rerere.rikkahub.learning.model

import kotlinx.serialization.Serializable

/** Bounded user choices; arbitrary day counts are never persisted or accepted by maintenance. */
@Serializable
enum class LearningRetentionPresetV1 {
    MINIMAL,
    STANDARD,
    EXTENDED,
}

/** User-owned trace/reward retention choices, independent from Learning rollout consent. */
@Serializable
data class LearningRetentionPreferencesV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val tracePreset: LearningRetentionPresetV1 = LearningRetentionPresetV1.STANDARD,
    val rewardPreset: LearningRetentionPresetV1 = LearningRetentionPresetV1.STANDARD,
) {
    fun failClosed(): LearningRetentionPreferencesV1 = takeIf { it.isValid() }
        ?: LearningRetentionPreferencesV1()

    fun isValid(): Boolean = schemaVersion == CURRENT_SCHEMA_VERSION

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

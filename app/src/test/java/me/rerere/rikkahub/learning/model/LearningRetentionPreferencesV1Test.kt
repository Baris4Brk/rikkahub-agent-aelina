package me.rerere.rikkahub.learning.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningRetentionPreferencesV1Test {
    @Test
    fun invalidVersionFailsClosedWithoutChangingValidRolloutPreferences() {
        val invalid = LearningRetentionPreferencesV1(
            schemaVersion = 2,
            tracePreset = LearningRetentionPresetV1.EXTENDED,
            rewardPreset = LearningRetentionPresetV1.MINIMAL,
        )
        assertEquals(LearningRetentionPreferencesV1(), invalid.failClosed())

        val preferences = LearningPreferencesV1(retention = invalid)
        assertEquals(LearningPreferencesV1(), preferences.failClosed())
    }

    @Test
    fun rolloutConfigurationPreservesTheIndependentRetentionChoice() {
        val retention = LearningRetentionPreferencesV1(
            tracePreset = LearningRetentionPresetV1.MINIMAL,
            rewardPreset = LearningRetentionPresetV1.EXTENDED,
        )
        val current = LearningPreferencesV1(retention = retention)

        assertEquals(
            retention,
            LearningRolloutPolicy.configure(current, LearningRolloutStage.CAPTURE).retention,
        )
        assertEquals(
            retention,
            LearningRolloutPolicy.configure(current, LearningRolloutStage.OFF).retention,
        )
    }
}

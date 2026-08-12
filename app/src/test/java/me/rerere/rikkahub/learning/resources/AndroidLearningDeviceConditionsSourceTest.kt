package me.rerere.rikkahub.learning.resources

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLearningDeviceConditionsSourceTest {
    @Test
    fun thermalStatusesMapConservativelyWithoutAndroidRuntimeCalls() {
        assertEquals(
            LearningThermalState.NOMINAL,
            learningThermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_NONE),
        )
        assertEquals(
            LearningThermalState.MODERATE,
            learningThermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_MODERATE),
        )
        assertEquals(
            LearningThermalState.CRITICAL,
            learningThermalStateFromAndroidStatus(PowerManager.THERMAL_STATUS_EMERGENCY),
        )
        assertEquals(
            LearningThermalState.UNKNOWN,
            learningThermalStateFromAndroidStatus(Int.MAX_VALUE),
        )
    }
}

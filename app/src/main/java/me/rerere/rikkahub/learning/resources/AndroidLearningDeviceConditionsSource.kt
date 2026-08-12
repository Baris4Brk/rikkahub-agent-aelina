package me.rerere.rikkahub.learning.resources

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager

/**
 * Fresh Android admission signals for background Learning work.
 *
 * Platform read failures remain UNKNOWN instead of being mistaken for a permissive state. User
 * authorization is deliberately supplied by lambdas so this adapter never owns Settings state.
 */
class AndroidLearningDeviceConditionsSource(
    context: Context,
    private val userAllowsBackgroundWork: () -> Boolean,
    private val userAllowsMeteredNetwork: () -> Boolean,
) : LearningDeviceConditionsSource {
    private val appContext = context.applicationContext ?: context

    override fun snapshot(): LearningDeviceConditions {
        val powerManager = try {
            appContext.getSystemService(PowerManager::class.java)
        } catch (_: Exception) {
            null
        }
        val network = readNetworkState()
        return LearningDeviceConditions(
            userAllowsBackgroundWork = readAuthorization(userAllowsBackgroundWork),
            batterySaver = readBatterySaver(powerManager),
            thermalState = readThermalState(powerManager),
            networkValidated = network.validated,
            networkMetered = network.metered,
            userAllowsMeteredNetwork = readAuthorization(userAllowsMeteredNetwork),
        )
    }

    private fun readNetworkState(): AndroidLearningNetworkState {
        return try {
            val manager = appContext.getSystemService(ConnectivityManager::class.java)
                ?: return AndroidLearningNetworkState.UNKNOWN
            val active = manager.activeNetwork
                ?: return AndroidLearningNetworkState(
                    validated = LearningSignal.NO,
                    metered = LearningSignal.UNKNOWN,
                )
            val capabilities = manager.getNetworkCapabilities(active)
                ?: return AndroidLearningNetworkState.UNKNOWN
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            AndroidLearningNetworkState(
                validated = validated.toLearningSignal(),
                metered = (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                    .toLearningSignal(),
            )
        } catch (_: Exception) {
            AndroidLearningNetworkState.UNKNOWN
        }
    }
}

private data class AndroidLearningNetworkState(
    val validated: LearningSignal,
    val metered: LearningSignal,
) {
    companion object {
        val UNKNOWN = AndroidLearningNetworkState(
            validated = LearningSignal.UNKNOWN,
            metered = LearningSignal.UNKNOWN,
        )
    }
}

private fun readAuthorization(source: () -> Boolean): Boolean = try {
    source()
} catch (_: Exception) {
    false
}

private fun readBatterySaver(powerManager: PowerManager?): LearningSignal {
    powerManager ?: return LearningSignal.UNKNOWN
    return try {
        powerManager.isPowerSaveMode.toLearningSignal()
    } catch (_: Exception) {
        LearningSignal.UNKNOWN
    }
}

private fun readThermalState(powerManager: PowerManager?): LearningThermalState {
    powerManager ?: return LearningThermalState.UNKNOWN
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return LearningThermalState.UNKNOWN
    return try {
        learningThermalStateFromAndroidStatus(powerManager.currentThermalStatus)
    } catch (_: Exception) {
        LearningThermalState.UNKNOWN
    }
}

internal fun learningThermalStateFromAndroidStatus(status: Int): LearningThermalState = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> LearningThermalState.NOMINAL
    PowerManager.THERMAL_STATUS_LIGHT -> LearningThermalState.LIGHT
    PowerManager.THERMAL_STATUS_MODERATE -> LearningThermalState.MODERATE
    PowerManager.THERMAL_STATUS_SEVERE -> LearningThermalState.SEVERE
    PowerManager.THERMAL_STATUS_CRITICAL,
    PowerManager.THERMAL_STATUS_EMERGENCY,
    PowerManager.THERMAL_STATUS_SHUTDOWN,
    -> LearningThermalState.CRITICAL

    else -> LearningThermalState.UNKNOWN
}

private fun Boolean.toLearningSignal(): LearningSignal =
    if (this) LearningSignal.YES else LearningSignal.NO

package me.rerere.rikkahub.learning.resources

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull

enum class LearningResourceKind {
    LANGUAGE_MODEL,
    EMBEDDING,
}

enum class LearningExecutionClass {
    LOCAL_COMPUTE,
    REMOTE_NETWORK,
}

enum class LearningCancellationCapability {
    PROVEN_RELIABLE,
    UNPROVEN,
}

enum class LearningSignal {
    YES,
    NO,
    UNKNOWN,
}

enum class LearningThermalState {
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    UNKNOWN,
}

data class LearningRouteCapabilities(
    val executionClass: LearningExecutionClass,
    val requiresNetwork: Boolean,
    val cancellation: LearningCancellationCapability,
) {
    init {
        require(
            executionClass != LearningExecutionClass.REMOTE_NETWORK || requiresNetwork,
        ) { "Remote route must declare its network dependency" }
        require(
            executionClass != LearningExecutionClass.LOCAL_COMPUTE || !requiresNetwork,
        ) { "Local route cannot impersonate a network route" }
    }
}

data class LearningDeviceConditions(
    val userAllowsBackgroundWork: Boolean,
    val batterySaver: LearningSignal,
    val thermalState: LearningThermalState,
    val networkValidated: LearningSignal,
    val networkMetered: LearningSignal,
    val userAllowsMeteredNetwork: Boolean,
)

fun interface LearningDeviceConditionsSource {
    fun snapshot(): LearningDeviceConditions
}

enum class LearningYieldReason {
    FOREGROUND_ACTIVE,
    FOREGROUND_REGISTRY_DEGRADED,
    USER_DISABLED,
    POWER_STATE_UNKNOWN,
    BATTERY_SAVER,
    THERMAL_UNKNOWN,
    THERMAL_PRESSURE,
    NETWORK_STATE_UNKNOWN,
    NETWORK_UNAVAILABLE,
    METERED_NETWORK_DENIED,
    CANCELLATION_UNPROVEN,
    ADMISSION_TIMEOUT,
    CONDITIONS_UNAVAILABLE,
    PERMIT_CLOSED,
}

sealed interface LearningPermitResult {
    data class Granted(val permit: LearningResourcePermit) : LearningPermitResult

    data class Deferred(val reason: LearningYieldReason) : LearningPermitResult
}

/**
 * Admission control for background work; foreground callers never acquire these permits.
 * A permit covers one provider request or one embedding chunk and must be closed in `finally`.
 */
class LearningResourceGovernor(
    private val foregroundRegistry: LearningForegroundRegistry,
    private val conditionsSource: LearningDeviceConditionsSource,
    maxLanguageModelConcurrency: Int = 1,
    maxEmbeddingConcurrency: Int = 1,
    private val admissionWaitMs: Long = 2_000L,
    private val onYield: (LearningYieldReason) -> Unit = {},
) {
    private val semaphores = mapOf(
        LearningResourceKind.LANGUAGE_MODEL to Semaphore(maxLanguageModelConcurrency),
        LearningResourceKind.EMBEDDING to Semaphore(maxEmbeddingConcurrency),
    )

    init {
        require(maxLanguageModelConcurrency in 1..4)
        require(maxEmbeddingConcurrency in 1..4)
        require(admissionWaitMs in 1L..30_000L)
    }

    suspend fun acquire(
        kind: LearningResourceKind,
        route: LearningRouteCapabilities,
    ): LearningPermitResult {
        if (route.cancellation != LearningCancellationCapability.PROVEN_RELIABLE) {
            return deferred(LearningYieldReason.CANCELLATION_UNPROVEN)
        }
        snapshotFailure()?.let { return deferred(it) }
        val conditions = try {
            conditionsSource.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return deferred(LearningYieldReason.CONDITIONS_UNAVAILABLE)
        }
        admissionFailure(route, conditions)?.let { return deferred(it) }

        val semaphore = semaphores.getValue(kind)
        val acquired = withTimeoutOrNull(admissionWaitMs) {
            semaphore.acquire()
            true
        } == true
        if (!acquired) return deferred(LearningYieldReason.ADMISSION_TIMEOUT)

        val permit = LearningResourcePermit(
            kind = kind,
            admittedForegroundStartEpoch = foregroundRegistry.snapshot.value.foregroundStartEpoch,
            foregroundRegistry = foregroundRegistry,
            conditionsSource = conditionsSource,
            route = route,
            release = semaphore::release,
            onYield = ::recordYieldBestEffort,
        )
        permit.validateBeforeDispatch()?.let { reason ->
            permit.close()
            return deferred(reason)
        }
        return LearningPermitResult.Granted(permit)
    }

    private fun snapshotFailure(): LearningYieldReason? {
        val foreground = foregroundRegistry.snapshot.value
        return when {
            foreground.health != LearningForegroundHealth.HEALTHY ->
                LearningYieldReason.FOREGROUND_REGISTRY_DEGRADED
            foreground.activeCount > 0 -> LearningYieldReason.FOREGROUND_ACTIVE
            else -> null
        }
    }

    private fun deferred(reason: LearningYieldReason): LearningPermitResult.Deferred {
        recordYieldBestEffort(reason)
        return LearningPermitResult.Deferred(reason)
    }

    private fun recordYieldBestEffort(reason: LearningYieldReason) {
        try {
            onYield(reason)
        } catch (_: Exception) {
            // Resource diagnostics are never allowed to change admission behavior.
        }
    }

}

class LearningResourcePermit internal constructor(
    val kind: LearningResourceKind,
    val admittedForegroundStartEpoch: Long,
    private val foregroundRegistry: LearningForegroundRegistry,
    private val conditionsSource: LearningDeviceConditionsSource,
    private val route: LearningRouteCapabilities,
    private val release: () -> Unit,
    private val onYield: (LearningYieldReason) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun validateBeforeDispatch(): LearningYieldReason? {
        if (closed.get()) return LearningYieldReason.PERMIT_CLOSED
        val current = foregroundRegistry.snapshot.value
        val foregroundFailure = when {
            current.health != LearningForegroundHealth.HEALTHY ->
                LearningYieldReason.FOREGROUND_REGISTRY_DEGRADED
            current.activeCount > 0 ||
                current.foregroundStartEpoch > admittedForegroundStartEpoch ->
                LearningYieldReason.FOREGROUND_ACTIVE
            else -> null
        }
        if (foregroundFailure != null) return foregroundFailure.also(::recordYieldBestEffort)
        val conditions = try {
            conditionsSource.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LearningYieldReason.CONDITIONS_UNAVAILABLE.also(::recordYieldBestEffort)
        }
        return admissionFailure(route, conditions)?.also(::recordYieldBestEffort)
    }

    suspend fun awaitForegroundArrival(): LearningForegroundPreemption =
        foregroundRegistry.awaitForegroundAfter(admittedForegroundStartEpoch)

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { release() }
    }

    private fun recordYieldBestEffort(reason: LearningYieldReason) {
        try {
            onYield(reason)
        } catch (_: Exception) {
            // A diagnostic sink cannot change permit validity or resource release.
        }
    }
}

private fun admissionFailure(
    route: LearningRouteCapabilities,
    conditions: LearningDeviceConditions,
): LearningYieldReason? = when {
    !conditions.userAllowsBackgroundWork -> LearningYieldReason.USER_DISABLED
    conditions.batterySaver == LearningSignal.UNKNOWN -> LearningYieldReason.POWER_STATE_UNKNOWN
    conditions.batterySaver == LearningSignal.YES -> LearningYieldReason.BATTERY_SAVER
    conditions.thermalState == LearningThermalState.UNKNOWN -> LearningYieldReason.THERMAL_UNKNOWN
    conditions.thermalState in setOf(
        LearningThermalState.SEVERE,
        LearningThermalState.CRITICAL,
    ) -> LearningYieldReason.THERMAL_PRESSURE
    route.requiresNetwork && conditions.networkValidated == LearningSignal.UNKNOWN ->
        LearningYieldReason.NETWORK_STATE_UNKNOWN
    route.requiresNetwork && conditions.networkValidated != LearningSignal.YES ->
        LearningYieldReason.NETWORK_UNAVAILABLE
    route.requiresNetwork && conditions.networkMetered == LearningSignal.UNKNOWN ->
        LearningYieldReason.NETWORK_STATE_UNKNOWN
    route.requiresNetwork && conditions.networkMetered == LearningSignal.YES &&
        !conditions.userAllowsMeteredNetwork -> LearningYieldReason.METERED_NETWORK_DENIED
    else -> null
}

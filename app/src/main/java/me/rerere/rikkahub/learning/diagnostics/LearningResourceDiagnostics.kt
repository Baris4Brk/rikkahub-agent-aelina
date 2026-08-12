package me.rerere.rikkahub.learning.diagnostics

import me.rerere.rikkahub.learning.resources.LearningYieldReason

/** Content-free adapter from resource admission reasons to the bounded diagnostics ledger. */
class LearningResourceDiagnostics(
    private val store: LearningDiagnosticsStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun recordYield(reason: LearningYieldReason) {
        val state = when (reason) {
            LearningYieldReason.FOREGROUND_ACTIVE -> LearningDiagnosticState.FOREGROUND_ACTIVE
            LearningYieldReason.FOREGROUND_REGISTRY_DEGRADED ->
                LearningDiagnosticState.FOREGROUND_REGISTRY_DEGRADED

            LearningYieldReason.USER_DISABLED -> LearningDiagnosticState.USER_DISABLED
            LearningYieldReason.POWER_STATE_UNKNOWN -> LearningDiagnosticState.POWER_STATE_UNKNOWN
            LearningYieldReason.BATTERY_SAVER -> LearningDiagnosticState.BATTERY_SAVER

            LearningYieldReason.THERMAL_UNKNOWN -> LearningDiagnosticState.THERMAL_UNKNOWN
            LearningYieldReason.THERMAL_PRESSURE -> LearningDiagnosticState.THERMAL_PRESSURE

            LearningYieldReason.NETWORK_STATE_UNKNOWN ->
                LearningDiagnosticState.NETWORK_STATE_UNKNOWN
            LearningYieldReason.NETWORK_UNAVAILABLE -> LearningDiagnosticState.NETWORK_UNAVAILABLE

            LearningYieldReason.METERED_NETWORK_DENIED ->
                LearningDiagnosticState.METERED_NETWORK_DENIED

            LearningYieldReason.ADMISSION_TIMEOUT -> LearningDiagnosticState.CONCURRENCY_LIMIT
            LearningYieldReason.CANCELLATION_UNPROVEN ->
                LearningDiagnosticState.CANCELLATION_UNPROVEN
            LearningYieldReason.CONDITIONS_UNAVAILABLE ->
                LearningDiagnosticState.CONDITIONS_UNAVAILABLE
            LearningYieldReason.PERMIT_CLOSED -> LearningDiagnosticState.PERMIT_CLOSED
        }
        store.record(
            LearningDiagnosticSample(
                recordedAtMs = runCatching { clock().coerceAtLeast(0L) }.getOrDefault(0L),
                code = LearningDiagnosticCode.RESOURCE_YIELD,
                state = state,
            ),
        )
    }
}

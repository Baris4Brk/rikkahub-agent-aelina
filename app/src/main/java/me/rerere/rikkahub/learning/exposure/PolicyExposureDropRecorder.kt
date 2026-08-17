package me.rerere.rikkahub.learning.exposure

import kotlinx.coroutines.CancellationException

/** A bounded observation-only bundle; none of these Policies reached final INJECTED. */
data class PolicyExposureDropObservation(
    val reservation: PolicyExposureReservation,
    val reasonByPolicyId: Map<String, String>,
    /** True only when the whole bundle compiled and was dropped by a later final gate. */
    val compiledBeforeDrop: Boolean,
) {
    init {
        require(reasonByPolicyId.isNotEmpty())
        require(
            reasonByPolicyId.keys == reservation.bundle.policies.mapTo(linkedSetOf()) {
                it.policyId
            },
        ) { "Drop observation must describe the complete observation-only bundle" }
        require(reasonByPolicyId.values.all { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) })
    }
}

/** Best-effort recorder. Failure never changes the already-prepared baseline request. */
suspend fun PolicyExposureStore.recordDropObservation(
    observation: PolicyExposureDropObservation,
    metadata: PolicyExposureMetadata,
    frozenNowEpochMs: Long,
): Boolean = try {
    val reserved = reserve(
        observation.reservation,
        metadata,
        frozenNowEpochMs,
    ) as? PolicyExposureStoreResult.Available ?: return false
    var receipt = reserved.receipt
    if (observation.compiledBeforeDrop && !receipt.hasObserved(PolicyExposureState.COMPILED)) {
        val compiled = observeMilestone(
            reservationId = observation.reservation.key.reservationId,
            expectedStateVersion = receipt.stateVersion,
            state = PolicyExposureState.COMPILED,
            frozenNowEpochMs = frozenNowEpochMs,
        ) as? PolicyExposureStoreResult.Available ?: return false
        receipt = compiled.receipt
    }
    recordDrops(
        reservationId = observation.reservation.key.reservationId,
        expectedStateVersion = receipt.stateVersion,
        reasonByPolicyId = observation.reasonByPolicyId,
        frozenNowEpochMs = frozenNowEpochMs,
    ) is PolicyExposureStoreResult.Available
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    false
}

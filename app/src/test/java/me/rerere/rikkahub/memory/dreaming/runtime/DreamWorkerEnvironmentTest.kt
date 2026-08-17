package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.orchestration.DreamSynthesisRetryReason
import me.rerere.rikkahub.memory.dreaming.orchestration.DreamSynthesisRunResult
import me.rerere.rikkahub.memory.dreaming.store.DreamSynthesisFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DreamWorkerEnvironmentTest {
    private val conservative = DreamingCostPolicy(
        networkPolicy = DreamNetworkPolicy.UNMETERED,
        requireBatteryNotLow = true,
        requireCharging = true,
    )

    @Test
    fun `worker double gate fails closed for unknown or disallowed live state`() {
        assertEquals(
            DreamWorkerDeferralReason.NETWORK_UNAVAILABLE,
            environment(connected = false).deferralFor(conservative),
        )
        assertEquals(
            DreamWorkerDeferralReason.UNMETERED_NETWORK_REQUIRED,
            environment(metered = true).deferralFor(conservative),
        )
        assertEquals(
            DreamWorkerDeferralReason.BATTERY_LOW_OR_UNKNOWN,
            environment(batteryNotLow = false).deferralFor(conservative),
        )
        assertEquals(
            DreamWorkerDeferralReason.CHARGING_REQUIRED,
            environment(charging = false).deferralFor(conservative),
        )
    }

    @Test
    fun `relaxed live policy still requires a connected network`() {
        val relaxed = conservative.copy(
            networkPolicy = DreamNetworkPolicy.CONNECTED,
            requireBatteryNotLow = false,
            requireCharging = false,
        )

        assertNull(
            environment(metered = true, batteryNotLow = false, charging = false)
                .deferralFor(relaxed),
        )
        assertEquals(
            DreamWorkerDeferralReason.NETWORK_UNAVAILABLE,
            environment(connected = false).deferralFor(relaxed),
        )
    }

    @Test
    fun `budget deferral is not converted into a model retry`() {
        val deferred = DreamSynthesisRunResult.PolicyDeferred(
            reason = DreamBudgetDenialReason.DAILY_RUN_LIMIT,
            retryAtEpochMs = 86_400_000L,
        ).toWorkerDirective(retryLimit = 5)
        val modelFailure = DreamSynthesisRunResult.Retry(
            DreamSynthesisRetryReason.MODEL_TEMPORARY_FAILURE,
        ).toWorkerDirective(retryLimit = 2)

        assertEquals(
            DreamSynthesisWorkerDirective.Deferred(
                DreamWorkerDeferralReason.BUDGET_POLICY,
                86_400_000L,
                DreamBudgetDenialReason.DAILY_RUN_LIMIT,
            ),
            deferred,
        )
        assertEquals(
            DreamSynthesisWorkerDirective.Retry(
                2,
                DreamSynthesisRetryReason.MODEL_TEMPORARY_FAILURE,
            ),
            modelFailure,
        )
    }

    @Test
    fun `transient store failure remains retryable for worker recovery`() {
        assertEquals(
            DreamSynthesisWorkerDirective.Retry(
                2,
                DreamSynthesisRetryReason.STORE_TEMPORARY_FAILURE,
            ),
            DreamSynthesisRunResult.Failed(DreamSynthesisFailure.STORE_FAILURE)
                .toWorkerDirective(retryLimit = 2),
        )
    }

    private fun environment(
        connected: Boolean = true,
        metered: Boolean = false,
        batteryNotLow: Boolean = true,
        charging: Boolean = true,
    ) = DreamWorkerEnvironment(
        networkConnected = connected,
        networkMetered = metered,
        batteryNotLow = batteryNotLow,
        charging = charging,
    )
}

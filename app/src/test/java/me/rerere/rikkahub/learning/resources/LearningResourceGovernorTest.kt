package me.rerere.rikkahub.learning.resources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.learning.model.LearningModelCandidate
import me.rerere.rikkahub.learning.model.LearningModelResolution
import me.rerere.rikkahub.learning.model.LearningModelResolutionFailure
import me.rerere.rikkahub.learning.model.LearningModelResolutionPolicy
import me.rerere.rikkahub.learning.model.LearningModelResolver
import me.rerere.rikkahub.learning.model.LearningProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LearningResourceGovernorTest {
    @Test
    fun foregroundLeaseIsNestedIdempotentAndOwnerCompletionRecoversLeaks() {
        val registry = LearningForegroundRegistry()
        val owner = Job()
        val first = registry.enter(LearningForegroundWorkKind.CONVERSATION_EXECUTION, owner)
        val second = registry.enter(LearningForegroundWorkKind.CONVERSATION_EXECUTION)
        assertEquals(2, registry.snapshot.value.activeCount)
        first.close()
        first.close()
        assertEquals(1, registry.snapshot.value.activeCount)
        second.close()
        assertEquals(0, registry.snapshot.value.activeCount)

        val leakedOwner = Job()
        registry.enter(LearningForegroundWorkKind.MANUAL_COMPRESSION, leakedOwner)
        assertEquals(1, registry.snapshot.value.activeCount)
        leakedOwner.complete()
        assertEquals(0, registry.snapshot.value.activeCount)
        assertEquals(LearningForegroundHealth.HEALTHY, registry.snapshot.value.health)
    }

    @Test
    fun foregroundAndDevicePressureFailClosedForBackgroundOnly() = runBlocking {
        val registry = LearningForegroundRegistry()
        val conditions = MutableConditions(allowedConditions())
        val governor = LearningResourceGovernor(registry, conditions, admissionWaitMs = 10)
        val foreground = registry.enter(LearningForegroundWorkKind.PET_DIALOGUE)
        assertEquals(
            LearningPermitResult.Deferred(LearningYieldReason.FOREGROUND_ACTIVE),
            governor.acquire(LearningResourceKind.LANGUAGE_MODEL, LOCAL_ROUTE),
        )
        foreground.close()

        conditions.value = allowedConditions().copy(thermalState = LearningThermalState.UNKNOWN)
        assertEquals(
            LearningPermitResult.Deferred(LearningYieldReason.THERMAL_UNKNOWN),
            governor.acquire(LearningResourceKind.LANGUAGE_MODEL, LOCAL_ROUTE),
        )
        conditions.value = allowedConditions().copy(thermalState = LearningThermalState.SEVERE)
        assertEquals(
            LearningPermitResult.Deferred(LearningYieldReason.THERMAL_PRESSURE),
            governor.acquire(LearningResourceKind.LANGUAGE_MODEL, LOCAL_ROUTE),
        )
    }

    @Test
    fun semaphoreIsBoundedAndLateForegroundCannotBeMissed() = runBlocking {
        val registry = LearningForegroundRegistry()
        val governor = LearningResourceGovernor(
            registry,
            MutableConditions(allowedConditions()),
            admissionWaitMs = 10,
        )
        val first = governor.acquire(
            LearningResourceKind.LANGUAGE_MODEL,
            LOCAL_ROUTE,
        ) as LearningPermitResult.Granted
        assertEquals(
            LearningPermitResult.Deferred(LearningYieldReason.ADMISSION_TIMEOUT),
            governor.acquire(LearningResourceKind.LANGUAGE_MODEL, LOCAL_ROUTE),
        )

        val preemption = async { first.permit.awaitForegroundArrival() }
        val foreground = registry.enter(LearningForegroundWorkKind.CONVERSATION_EXECUTION)
        assertEquals(
            LearningForegroundPreemption.FOREGROUND_STARTED,
            withTimeout(1_000) { preemption.await() },
        )
        assertEquals(
            LearningYieldReason.FOREGROUND_ACTIVE,
            first.permit.validateBeforeDispatch(),
        )
        foreground.close()
        first.permit.close()
    }

    @Test
    fun unprovenCancellationNeverAcquiresBackgroundPermit() = runBlocking {
        val governor = LearningResourceGovernor(
            LearningForegroundRegistry(),
            MutableConditions(allowedConditions()),
        )
        assertEquals(
            LearningPermitResult.Deferred(LearningYieldReason.CANCELLATION_UNPROVEN),
            governor.acquire(
                LearningResourceKind.LANGUAGE_MODEL,
                LOCAL_ROUTE.copy(cancellation = LearningCancellationCapability.UNPROVEN),
            ),
        )
    }

    @Test
    fun conditionsAreRecheckedAfterSemaphoreAcquisitionAndPermitIsReleasedOnRevoke() = runBlocking {
        var firstRead = true
        var current = allowedConditions().copy(userAllowsBackgroundWork = false)
        val conditions = LearningDeviceConditionsSource {
            if (firstRead) {
                firstRead = false
                allowedConditions()
            } else {
                current
            }
        }
        val governor = LearningResourceGovernor(
            LearningForegroundRegistry(),
            conditions,
            admissionWaitMs = 10,
        )

        assertEquals(
            LearningPermitResult.Deferred(LearningYieldReason.USER_DISABLED),
            governor.acquire(LearningResourceKind.LANGUAGE_MODEL, LOCAL_ROUTE),
        )

        current = allowedConditions()
        val next = governor.acquire(LearningResourceKind.LANGUAGE_MODEL, LOCAL_ROUTE)
        assertTrue("failed admission must release the semaphore", next is LearningPermitResult.Granted)
        (next as LearningPermitResult.Granted).permit.close()
    }

    @Test
    fun permitRechecksPowerNetworkAndUserAuthorizationImmediatelyBeforeDispatch() = runBlocking {
        val conditions = MutableConditions(allowedConditions())
        val governor = LearningResourceGovernor(
            LearningForegroundRegistry(),
            conditions,
            admissionWaitMs = 10,
        )
        val granted = governor.acquire(
            LearningResourceKind.LANGUAGE_MODEL,
            REMOTE_ROUTE,
        ) as LearningPermitResult.Granted

        conditions.value = allowedConditions().copy(userAllowsMeteredNetwork = false).copy(
            networkMetered = LearningSignal.YES,
        )
        assertEquals(
            LearningYieldReason.METERED_NETWORK_DENIED,
            granted.permit.validateBeforeDispatch(),
        )
        granted.permit.close()
        assertEquals(
            LearningYieldReason.PERMIT_CLOSED,
            granted.permit.validateBeforeDispatch(),
        )
    }

    @Test
    fun cancellationFromConditionSourceIsNeverConvertedIntoOrdinaryDeferral() = runBlocking {
        val governor = LearningResourceGovernor(
            LearningForegroundRegistry(),
            LearningDeviceConditionsSource { throw CancellationException("cancel") },
        )
        try {
            governor.acquire(LearningResourceKind.LANGUAGE_MODEL, LOCAL_ROUTE)
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
            Unit
        }
    }

    @Test
    fun everyAdmissionOrDispatchYieldIsReportedWithoutChangingTheDecision() = runBlocking {
        val observed = mutableListOf<LearningYieldReason>()
        val conditions = MutableConditions(allowedConditions())
        val governor = LearningResourceGovernor(
            LearningForegroundRegistry(),
            conditions,
            onYield = observed::add,
        )

        assertEquals(
            LearningPermitResult.Deferred(LearningYieldReason.CANCELLATION_UNPROVEN),
            governor.acquire(
                LearningResourceKind.LANGUAGE_MODEL,
                LOCAL_ROUTE.copy(cancellation = LearningCancellationCapability.UNPROVEN),
            ),
        )
        val granted = governor.acquire(
            LearningResourceKind.LANGUAGE_MODEL,
            REMOTE_ROUTE,
        ) as LearningPermitResult.Granted
        conditions.value = allowedConditions().copy(networkValidated = LearningSignal.NO)
        assertEquals(
            LearningYieldReason.NETWORK_UNAVAILABLE,
            granted.permit.validateBeforeDispatch(),
        )
        granted.permit.close()

        assertEquals(
            listOf(
                LearningYieldReason.CANCELLATION_UNPROVEN,
                LearningYieldReason.NETWORK_UNAVAILABLE,
            ),
            observed,
        )
    }

    @Test
    fun remoteRouteNeedsRemoteFlagAndTrustedHostCancellation() {
        val digest = "a".repeat(64)
        val local = LearningModelCandidate(
            providerKind = LearningProviderKind.LOCAL_LITERT,
            providerIdentityDigest = digest,
            modelIdentityDigest = digest,
            configurationDigest = digest,
            userExplicitlyAuthorizedForBackground = true,
        )
        assertEquals(
            LearningModelResolution.Unavailable(LearningModelResolutionFailure.CANCELLATION_UNSAFE),
            LearningModelResolver.resolve(local, LearningModelResolutionPolicy()),
        )
        assertTrue(
            LearningModelResolver.resolve(
                local,
                LearningModelResolutionPolicy(
                    providerIdentityDigestsWithProvenCancellation = setOf(digest),
                ),
            ) is LearningModelResolution.Resolved,
        )

        val remote = local.copy(providerKind = LearningProviderKind.REMOTE)
        assertEquals(
            LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.REMOTE_REFLECTION_DISABLED,
            ),
            LearningModelResolver.resolve(
                remote,
                LearningModelResolutionPolicy(
                    providerIdentityDigestsWithProvenCancellation = setOf(digest),
                ),
            ),
        )
        val resolved = LearningModelResolver.resolve(
            remote,
            LearningModelResolutionPolicy(
                allowRemoteReflection = true,
                providerIdentityDigestsWithProvenCancellation = setOf(digest),
            ),
        ) as LearningModelResolution.Resolved
        assertTrue(resolved.model.route.requiresNetwork)

        assertEquals(
            LearningModelResolution.Unavailable(
                LearningModelResolutionFailure.CANCELLATION_UNSAFE,
            ),
            LearningModelResolver.resolve(
                remote.copy(providerIdentityDigest = "b".repeat(64)),
                LearningModelResolutionPolicy(
                    allowRemoteReflection = true,
                    providerIdentityDigestsWithProvenCancellation = setOf(digest),
                ),
            ),
        )
    }

    private class MutableConditions(
        var value: LearningDeviceConditions,
    ) : LearningDeviceConditionsSource {
        override fun snapshot(): LearningDeviceConditions = value
    }

    private companion object {
        val LOCAL_ROUTE = LearningRouteCapabilities(
            executionClass = LearningExecutionClass.LOCAL_COMPUTE,
            requiresNetwork = false,
            cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
        )
        val REMOTE_ROUTE = LearningRouteCapabilities(
            executionClass = LearningExecutionClass.REMOTE_NETWORK,
            requiresNetwork = true,
            cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
        )

        fun allowedConditions() = LearningDeviceConditions(
            userAllowsBackgroundWork = true,
            batterySaver = LearningSignal.NO,
            thermalState = LearningThermalState.NOMINAL,
            networkValidated = LearningSignal.YES,
            networkMetered = LearningSignal.NO,
            userAllowsMeteredNetwork = false,
        )
    }
}

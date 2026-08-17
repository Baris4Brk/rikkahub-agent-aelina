package me.rerere.rikkahub.learning.retention

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningRetentionPreferencesV1
import me.rerere.rikkahub.learning.resources.LearningDeviceConditions
import me.rerere.rikkahub.learning.resources.LearningDeviceConditionsSource
import me.rerere.rikkahub.learning.resources.LearningForegroundRegistry
import me.rerere.rikkahub.learning.resources.LearningForegroundWorkKind
import me.rerere.rikkahub.learning.resources.LearningResourceGovernor
import me.rerere.rikkahub.learning.resources.LearningSignal
import me.rerere.rikkahub.learning.resources.LearningThermalState
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningRetentionMaintenanceCoordinatorTest {
    @Test
    fun maintenanceRunsWithoutProviderConsentAndRequestsOnlyBoundedFollowUp() = runBlocking {
        var calls = 0
        val coordinator = coordinator(
            runtime = LearningRetentionMaintenancePort { request ->
                calls += 1
                assertEquals(MAX_RETENTION_BATCH_SIZE, request.batchSize)
                LearningRetentionRuntimeResult.Completed(
                    LearningRetentionMaintenanceReceipt(
                        mutationCount = MAX_RETENTION_BATCH_SIZE,
                        workMayRemain = true,
                    ),
                )
            },
            registry = LearningForegroundRegistry(),
            userAllowsProviderWork = false,
        )

        assertEquals(LearningRetentionCoordinatorResult.WORK_REMAINS, coordinator.runOneBatch())
        assertEquals(1, calls)
    }

    @Test
    fun noDatabaseIsIdleAndPermitIsReleasedForNextCycle() = runBlocking {
        val registry = LearningForegroundRegistry()
        val coordinator = coordinator(
            runtime = LearningRetentionMaintenancePort {
                LearningRetentionRuntimeResult.NoDerivedDatabase
            },
            registry = registry,
        )

        assertEquals(LearningRetentionCoordinatorResult.IDLE, coordinator.runOneBatch())
        assertEquals(LearningRetentionCoordinatorResult.IDLE, coordinator.runOneBatch())
    }

    @Test
    fun outboxOnlyFullPagePropagatesBoundedFollowUpThroughCoordinator() = runBlocking {
        val coordinator = coordinator(
            runtime = LearningRetentionMaintenancePort {
                LearningRetentionRuntimeResult.Completed(
                    LearningRetentionMaintenanceReceipt(
                        mutationCount = 3,
                        workMayRemain = true,
                        deletedPrimaryOutboxRows = 3,
                        primaryOutboxWorkMayRemain = true,
                    ),
                )
            },
            registry = LearningForegroundRegistry(),
        )

        assertEquals(LearningRetentionCoordinatorResult.WORK_REMAINS, coordinator.runOneBatch())
    }

    @Test
    fun foregroundDefersWithoutOpeningRuntime() = runBlocking {
        val registry = LearningForegroundRegistry()
        var calls = 0
        val coordinator = coordinator(
            runtime = LearningRetentionMaintenancePort {
                calls += 1
                LearningRetentionRuntimeResult.Completed(
                    LearningRetentionMaintenanceReceipt(0, false),
                )
            },
            registry = registry,
        )
        val foreground = registry.enter(LearningForegroundWorkKind.CONVERSATION_EXECUTION)
        try {
            assertEquals(LearningRetentionCoordinatorResult.DEFERRED, coordinator.runOneBatch())
            assertEquals(0, calls)
        } finally {
            foreground.close()
        }
    }

    private fun coordinator(
        runtime: LearningRetentionMaintenancePort,
        registry: LearningForegroundRegistry,
        userAllowsProviderWork: Boolean = true,
    ): LearningRetentionMaintenanceCoordinator = LearningRetentionMaintenanceCoordinator(
        runtime = runtime,
        preferences = LearningRetentionPreferencesSource { LearningRetentionPreferencesV1() },
        resources = LearningResourceGovernor(
            foregroundRegistry = registry,
            conditionsSource = LearningDeviceConditionsSource {
                LearningDeviceConditions(
                    userAllowsBackgroundWork = userAllowsProviderWork,
                    batterySaver = LearningSignal.NO,
                    thermalState = LearningThermalState.NOMINAL,
                    networkValidated = LearningSignal.YES,
                    networkMetered = LearningSignal.NO,
                    userAllowsMeteredNetwork = false,
                )
            },
            admissionWaitMs = 10L,
        ),
    )
}

package me.rerere.rikkahub.learning.retention

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.model.LearningRetentionPreferencesV1
import me.rerere.rikkahub.learning.resources.LearningCancellationCapability
import me.rerere.rikkahub.learning.resources.LearningExecutionClass
import me.rerere.rikkahub.learning.resources.LearningPermitResult
import me.rerere.rikkahub.learning.resources.LearningResourceGovernor
import me.rerere.rikkahub.learning.resources.LearningResourceKind
import me.rerere.rikkahub.learning.resources.LearningRouteCapabilities

const val MAX_RETENTION_BATCH_SIZE: Int = 128

data class LearningRetentionMaintenanceRequest(
    val preferences: LearningRetentionPreferencesV1,
    val batchSize: Int = MAX_RETENTION_BATCH_SIZE,
) {
    init {
        require(preferences.isValid())
        require(batchSize in 1..MAX_RETENTION_BATCH_SIZE)
    }
}

data class LearningRetentionMaintenanceReceipt(
    /** Aggregate derived-database and primary-outbox mutations. */
    val mutationCount: Int,
    /** At least one independent bounded category filled its complete page. */
    val workMayRemain: Boolean,
    /** Subtotal retained for diagnostics and to prove primary retention reached the worker. */
    val deletedPrimaryOutboxRows: Int = 0,
    /** The primary outbox page filled its bound and therefore needs a bounded follow-up. */
    val primaryOutboxWorkMayRemain: Boolean = false,
) {
    init {
        require(mutationCount >= 0)
        require(deletedPrimaryOutboxRows in 0..mutationCount)
        require(!primaryOutboxWorkMayRemain || deletedPrimaryOutboxRows > 0)
        require(!primaryOutboxWorkMayRemain || workMayRemain)
        require(!workMayRemain || mutationCount > 0)
    }
}

sealed interface LearningRetentionRuntimeResult {
    data class Completed(val receipt: LearningRetentionMaintenanceReceipt) :
        LearningRetentionRuntimeResult

    data object NoDerivedDatabase : LearningRetentionRuntimeResult
    data object Unavailable : LearningRetentionRuntimeResult
}

/** Unit-confined LearningDatabase boundary; implementations must not leak Room handles. */
fun interface LearningRetentionMaintenancePort {
    suspend fun sweepOnce(
        request: LearningRetentionMaintenanceRequest,
    ): LearningRetentionRuntimeResult
}

fun interface LearningRetentionPreferencesSource {
    fun current(): LearningRetentionPreferencesV1
}

enum class LearningRetentionCoordinatorResult {
    IDLE,
    DID_WORK,
    WORK_REMAINS,
    RETRY,
    DEFERRED,
}

/**
 * Low-priority admission for one bounded local maintenance page. It is intentionally independent
 * from provider/model consent, while foreground, battery, thermal and cancellation gates remain
 * mandatory. No network route can be supplied by callers.
 */
class LearningRetentionMaintenanceCoordinator(
    private val runtime: LearningRetentionMaintenancePort,
    private val preferences: LearningRetentionPreferencesSource,
    private val resources: LearningResourceGovernor,
) {
    suspend fun runOneBatch(): LearningRetentionCoordinatorResult {
        val safePreferences = try {
            preferences.current().failClosed()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LearningRetentionCoordinatorResult.RETRY
        }
        val permitResult = resources.acquire(
            kind = LearningResourceKind.MAINTENANCE,
            route = MAINTENANCE_ROUTE,
        )
        val permit = when (permitResult) {
            is LearningPermitResult.Granted -> permitResult.permit
            is LearningPermitResult.Deferred -> return LearningRetentionCoordinatorResult.DEFERRED
        }
        return try {
            if (permit.validateBeforeDispatch() != null) {
                LearningRetentionCoordinatorResult.DEFERRED
            } else {
                when (val result = runtime.sweepOnce(
                    LearningRetentionMaintenanceRequest(safePreferences),
                )) {
                    is LearningRetentionRuntimeResult.Completed -> when {
                        result.receipt.workMayRemain ->
                            LearningRetentionCoordinatorResult.WORK_REMAINS
                        result.receipt.mutationCount > 0 ->
                            LearningRetentionCoordinatorResult.DID_WORK
                        else -> LearningRetentionCoordinatorResult.IDLE
                    }
                    LearningRetentionRuntimeResult.NoDerivedDatabase ->
                        LearningRetentionCoordinatorResult.IDLE
                    LearningRetentionRuntimeResult.Unavailable ->
                        LearningRetentionCoordinatorResult.RETRY
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LearningRetentionCoordinatorResult.RETRY
        } finally {
            permit.close()
        }
    }

    private companion object {
        val MAINTENANCE_ROUTE = LearningRouteCapabilities(
            executionClass = LearningExecutionClass.LOCAL_COMPUTE,
            requiresNetwork = false,
            cancellation = LearningCancellationCapability.PROVEN_RELIABLE,
            requiresUserBackgroundAuthorization = false,
        )
    }
}

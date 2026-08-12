package me.rerere.rikkahub.memory.dreaming.runtime

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.dao.DreamDao
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSynthesisMode
import me.rerere.rikkahub.memory.dreaming.model.DreamingFeatureFlags
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import me.rerere.rikkahub.memory.dreaming.orchestration.DreamEpochClock
import me.rerere.rikkahub.memory.dreaming.orchestration.DreamSynthesisOrchestrator
import me.rerere.rikkahub.memory.dreaming.orchestration.DreamSynthesisRunResult
import me.rerere.rikkahub.memory.dreaming.store.BeginDreamSynthesisRequest
import me.rerere.rikkahub.memory.dreaming.store.DreamSynthesisFailure
import me.rerere.rikkahub.memory.dreaming.temporal.strictZoneOrNull

/** Source zone for a brand-new run. The first claim persists it; resumes never consult this seam. */
fun interface DreamInitialSourceTimezoneSource {
    suspend fun sourceTimezoneIdFor(scopeId: DreamScopeId): String
}

/** Deterministic test/recovery fallback; production DI must use the persisted device IANA source. */
object UtcDreamInitialSourceTimezoneSource : DreamInitialSourceTimezoneSource {
    override suspend fun sourceTimezoneIdFor(scopeId: DreamScopeId): String = "UTC"
}

/** Production sampler. It is called only for a new PENDING run; Room freezes the returned ID. */
object DeviceDreamInitialSourceTimezoneSource : DreamInitialSourceTimezoneSource {
    override suspend fun sourceTimezoneIdFor(scopeId: DreamScopeId): String =
        ZoneId.systemDefault().id
}

/** Fresh device state sampled by the Worker immediately before runtime admission. */
data class DreamWorkerEnvironment(
    val networkConnected: Boolean,
    val networkMetered: Boolean,
    val batteryNotLow: Boolean,
    val charging: Boolean,
)

enum class DreamWorkerDeferralReason {
    POLICY_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    UNMETERED_NETWORK_REQUIRED,
    BATTERY_LOW_OR_UNKNOWN,
    CHARGING_REQUIRED,
    APP_IDLE_REQUIRED,
    BUDGET_POLICY,
}

sealed interface DreamSynthesisWorkerDirective {
    data object Complete : DreamSynthesisWorkerDirective
    data object Fail : DreamSynthesisWorkerDirective
    data class Retry(val retryLimit: Int) : DreamSynthesisWorkerDirective
    data class Deferred(
        val reason: DreamWorkerDeferralReason,
        val retryAtEpochMs: Long?,
        val budgetDenialReason: DreamBudgetDenialReason? = null,
    ) : DreamSynthesisWorkerDirective
}

/**
 * Identity-only WorkManager bridge. Work constraints are only the first gate; this class re-reads
 * current flags/policy and checks fresh environment+idle state before it can reach the provider.
 */
class DreamSynthesisRuntime(
    private val dreamDao: DreamDao,
    private val featureFlags: DreamingFeatureFlagSource,
    private val timezoneSource: DreamInitialSourceTimezoneSource,
    private val orchestrator: DreamSynthesisOrchestrator,
    private val clock: DreamEpochClock,
    private val policySource: DreamingCostPolicySource = DisabledDreamingPreferencesSource,
    private val idleTracker: DreamAppIdleTracker = UnknownDreamAppIdleTracker,
) {
    suspend fun runForWorker(
        scopeId: DreamScopeId,
        runId: String,
        workAttempt: Int,
        environment: DreamWorkerEnvironment,
    ): DreamSynthesisWorkerDirective {
        require(workAttempt >= 0)
        requireCanonicalDreamRunId(runId)
        val flags = readFlags(scopeId)
            ?: return DreamSynthesisWorkerDirective.Deferred(
                DreamWorkerDeferralReason.POLICY_UNAVAILABLE,
                null,
            )
        if (!flags.allowsSynthesisGeneration()) return DreamSynthesisWorkerDirective.Complete
        val policy = readPolicy()
            ?: return DreamSynthesisWorkerDirective.Deferred(
                DreamWorkerDeferralReason.POLICY_UNAVAILABLE,
                null,
            )
        environment.deferralFor(policy)?.let { reason ->
            return DreamSynthesisWorkerDirective.Deferred(reason, null)
        }
        val now = clock.nowEpochMs()
        if (now < 0L) {
            return DreamSynthesisWorkerDirective.Deferred(
                DreamWorkerDeferralReason.POLICY_UNAVAILABLE,
                null,
            )
        }
        when (val idle = idleTracker.decisionAt(now, policy.idleThresholdMinutes)) {
            is DreamAppIdleDecision.Eligible -> Unit
            is DreamAppIdleDecision.Deferred -> return DreamSynthesisWorkerDirective.Deferred(
                DreamWorkerDeferralReason.APP_IDLE_REQUIRED,
                idle.nextEligibleAtEpochMs,
            )
        }
        return synthesizeWithFlags(
            scopeId = scopeId,
            runId = runId,
            flags = flags,
            firstProviderAttempt = workAttempt == 0,
        ).toWorkerDirective(policy.retryLimit)
    }

    /** Direct deterministic seam retained for M4 tests; production Worker uses [runForWorker]. */
    suspend fun synthesize(scopeId: DreamScopeId, runId: String): DreamSynthesisRunResult {
        requireCanonicalDreamRunId(runId)
        val flags = readFlags(scopeId)
            ?: return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.STORE_FAILURE)
        if (!flags.allowsSynthesisGeneration()) return DreamSynthesisRunResult.Disabled
        return synthesizeWithFlags(scopeId, runId, flags, firstProviderAttempt = true)
    }

    private suspend fun synthesizeWithFlags(
        scopeId: DreamScopeId,
        runId: String,
        flags: DreamingFeatureFlags,
        firstProviderAttempt: Boolean,
    ): DreamSynthesisRunResult {
        val existing = try {
            dreamDao.getRunById(runId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.STORE_FAILURE)
        }
        if (existing != null && existing.scopeId != scopeId.value) {
            return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.INPUT_REJECTED)
        }
        val sampledNow = clock.nowEpochMs()
        if (sampledNow < 0L) return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.STORE_FAILURE)
        val attemptNow = maxOf(sampledNow, existing?.updatedAtMs ?: 0L)
        val mode = when (existing?.mode) {
            null -> if (flags.deepRebuild) DreamSynthesisMode.FULL else DreamSynthesisMode.INCREMENTAL
            DreamRunMode.INCREMENTAL.name -> DreamSynthesisMode.INCREMENTAL
            DreamRunMode.FULL.name -> DreamSynthesisMode.FULL
            else -> return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.INPUT_REJECTED)
        }
        val timezoneId = existing?.sourceTimezoneId ?: try {
            timezoneSource.sourceTimezoneIdFor(scopeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.INPUT_REJECTED)
        }
        if (strictZoneOrNull(timezoneId) == null) {
            return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.INPUT_REJECTED)
        }
        if (existing != null && existing.status != "PENDING" &&
            (existing.startedAtMs == null || existing.sourceTimezoneId == null)
        ) {
            return DreamSynthesisRunResult.Failed(DreamSynthesisFailure.INPUT_REJECTED)
        }

        return orchestrator.run(
            request = BeginDreamSynthesisRequest(
                scopeId = scopeId,
                runId = runId,
                leaseOwner = dreamSynthesisLeaseOwner(runId),
                attemptNowEpochMs = attemptNow,
                sourceTimezoneId = timezoneId,
                mode = mode,
            ),
            firstProviderAttempt = firstProviderAttempt,
        )
    }

    private suspend fun readFlags(scopeId: DreamScopeId): DreamingFeatureFlags? = try {
        featureFlags.flagsFor(scopeId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun readPolicy(): DreamingCostPolicy? = try {
        policySource.costPolicy().validatedOrNull()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

internal fun DreamWorkerEnvironment.deferralFor(
    policy: DreamingCostPolicy,
): DreamWorkerDeferralReason? = when {
    !networkConnected -> DreamWorkerDeferralReason.NETWORK_UNAVAILABLE
    policy.networkPolicy == DreamNetworkPolicy.UNMETERED && networkMetered ->
        DreamWorkerDeferralReason.UNMETERED_NETWORK_REQUIRED
    policy.requireBatteryNotLow && !batteryNotLow ->
        DreamWorkerDeferralReason.BATTERY_LOW_OR_UNKNOWN
    policy.requireCharging && !charging -> DreamWorkerDeferralReason.CHARGING_REQUIRED
    else -> null
}

internal fun DreamSynthesisRunResult.toWorkerDirective(
    retryLimit: Int,
): DreamSynthesisWorkerDirective {
    require(retryLimit in 0..MAX_DREAMING_RETRY_LIMIT)
    return when (this) {
        is DreamSynthesisRunResult.Completed,
        is DreamSynthesisRunResult.AlreadyTerminal,
        DreamSynthesisRunResult.Disabled,
        -> DreamSynthesisWorkerDirective.Complete

        is DreamSynthesisRunResult.PolicyDeferred -> DreamSynthesisWorkerDirective.Deferred(
            DreamWorkerDeferralReason.BUDGET_POLICY,
            retryAtEpochMs,
            reason,
        )

        is DreamSynthesisRunResult.Retry -> DreamSynthesisWorkerDirective.Retry(retryLimit)
        is DreamSynthesisRunResult.Failed -> DreamSynthesisWorkerDirective.Fail
    }
}

internal fun dreamSynthesisLeaseOwner(runId: String): String {
    requireCanonicalDreamRunId(runId)
    return "dream-synthesis-$runId"
}

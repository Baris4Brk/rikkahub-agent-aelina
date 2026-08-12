package me.rerere.rikkahub.memory.dreaming.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSynthesisRuntime
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSynthesisWorkerDirective
import me.rerere.rikkahub.memory.dreaming.runtime.DreamWorkerDeferralReason
import me.rerere.rikkahub.memory.dreaming.runtime.DreamWorkerEnvironment
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Work payload is identity-only. Every mutable policy and authority value is reread at runtime. */
class DreamSynthesisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val runtime: DreamSynthesisRuntime by inject()
    private val scheduler: DreamSynthesisWorkScheduler by inject()

    override suspend fun doWork(): Result {
        val scopeId = DreamScopeId.parseOrNull(inputData.getString(KEY_SCOPE_ID))
            ?: return Result.failure()
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        try {
            requireCanonicalDreamRunId(runId)
        } catch (_: Exception) {
            return Result.failure()
        }

        return try {
            when (
                val directive = runtime.runForWorker(
                    scopeId = scopeId,
                    runId = runId,
                    workAttempt = runAttemptCount,
                    environment = readDreamWorkerEnvironment(applicationContext),
                )
            ) {
                DreamSynthesisWorkerDirective.Complete -> Result.success()
                DreamSynthesisWorkerDirective.Fail -> Result.failure()
                is DreamSynthesisWorkerDirective.Retry -> {
                    if (runAttemptCount < directive.retryLimit) Result.retry()
                    else Result.failure()
                }

                is DreamSynthesisWorkerDirective.Deferred -> {
                    scheduler.enqueueDirtyScan(
                        reason = if (directive.reason == DreamWorkerDeferralReason.BUDGET_POLICY &&
                            directive.retryAtEpochMs != null
                        ) {
                            DreamSynthesisScanReason.UTC_BUDGET_ROLLOVER
                        } else {
                            DreamSynthesisScanReason.FOLLOW_UP
                        },
                        earliestAtEpochMs = directive.retryAtEpochMs,
                    )
                    // Policy deferral is not a model failure and must not increment Work retries.
                    Result.success()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Dream synthesis worker failed", error)
            // The current policy could not be read reliably, so a hard-coded retry would bypass it.
            Result.failure()
        }
    }

    companion object {
        const val KEY_SCOPE_ID = "dream_synthesis_scope_id"
        const val KEY_RUN_ID = "dream_synthesis_run_id"
        private const val TAG = "DreamSynthesisWorker"
    }
}

/** Unknown platform state is represented as false and therefore fails closed when required. */
internal fun readDreamWorkerEnvironment(context: Context): DreamWorkerEnvironment {
    val network = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        val capabilities = active?.let(manager::getNetworkCapabilities)
        val connected = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connected to (manager?.isActiveNetworkMetered ?: true)
    } catch (_: Exception) {
        false to true
    }
    val battery = try {
        val manager = context.getSystemService(BatteryManager::class.java)
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val notLow = capacity in (DREAM_BATTERY_LOW_PERCENT + 1)..100
        notLow to (manager?.isCharging == true)
    } catch (_: Exception) {
        false to false
    }
    return DreamWorkerEnvironment(
        networkConnected = network.first,
        networkMetered = network.second,
        batteryNotLow = battery.first,
        charging = battery.second,
    )
}

private const val DREAM_BATTERY_LOW_PERCENT = 15

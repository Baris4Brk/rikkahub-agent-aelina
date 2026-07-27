package me.rerere.rikkahub.data.execution

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import me.rerere.rikkahub.execution.ManagedExecutionCoordinator
import me.rerere.rikkahub.execution.ManagedExecutionRequest

sealed interface CancellationOutcome {
    data class Cancelled(val record: ExecutionRecord) : CancellationOutcome
    data class Unconfirmed(val record: ExecutionRecord, val reasonCode: String) : CancellationOutcome
    data class AlreadyTerminal(val record: ExecutionRecord) : CancellationOutcome
    data object Missing : CancellationOutcome
}

/** Per-execution single-flight two-phase cancellation with an independent post-stop probe. */
class CancellationCoordinator(
    private val scope: CoroutineScope,
    private val repository: ExecutionRepository,
    private val runtimeProbe: ExecutionRuntimeProbe,
    private val callerResolver: ManagedExecutionCallerResolver,
    private val managedCoordinator: ManagedExecutionCoordinator,
) {
    private val inFlight = ConcurrentHashMap<String, Deferred<CancellationOutcome>>()

    fun cancel(executionId: String): Deferred<CancellationOutcome> = synchronized(inFlight) {
        inFlight[executionId]?.takeIf { it.isActive } ?: scope.async(
            start = CoroutineStart.LAZY,
        ) {
            cancelInternal(executionId)
        }.also { created ->
            inFlight[executionId] = created
            created.invokeOnCompletion { inFlight.remove(executionId, created) }
            created.start()
        }
    }

    suspend fun cancelAndAwait(executionId: String): CancellationOutcome = cancel(executionId).await()

    private suspend fun cancelInternal(executionId: String): CancellationOutcome {
        val initial = repository.get(executionId) ?: return CancellationOutcome.Missing
        if (ExecutionStatus.fromWire(initial.status).isTerminal) {
            return CancellationOutcome.AlreadyTerminal(initial)
        }
        when (val requested = repository.transition(
            id = executionId,
            target = ExecutionStatus.cancel_requested,
            verificationState = VerificationState.DATABASE_CONFIRMED,
            mutationId = "cancel-request:$executionId",
            source = ExecutionStateSource.USER,
            reasonCode = "cancel_requested",
        )) {
            is ExecutionTransitionResult.Terminal -> return CancellationOutcome.AlreadyTerminal(requested.record)
            is ExecutionTransitionResult.Missing -> return CancellationOutcome.Missing
            is ExecutionTransitionResult.Invalid,
            is ExecutionTransitionResult.Conflict,
            -> return unconfirmed(executionId, "cancel_request_conflict")
            is ExecutionTransitionResult.Applied -> Unit
        }

        dispatchStop(executionId, force = false)
        when (val firstProbe = runtimeProbe.probe(repository.get(executionId) ?: return CancellationOutcome.Missing)) {
            is RuntimeProbeResult.Exited -> return confirmCancelled(executionId, "graceful_stop_confirmed")
            is RuntimeProbeResult.Missing -> if (firstProbe.authoritative) {
                return confirmCancelled(executionId, "graceful_stop_confirmed")
            } else {
                return unconfirmed(executionId, "runtime_missing_unconfirmed")
            }
            is RuntimeProbeResult.Alive -> Unit
            is RuntimeProbeResult.Recovering -> return unconfirmed(executionId, firstProbe.reasonCode)
            is RuntimeProbeResult.Unreachable -> return unconfirmed(executionId, firstProbe.reasonCode)
            is RuntimeProbeResult.Unsupported -> return unconfirmed(executionId, firstProbe.reasonCode)
        }

        when (val terminating = repository.transition(
            id = executionId,
            target = ExecutionStatus.terminating,
            verificationState = VerificationState.RUNTIME_CONFIRMED,
            mutationId = "cancel-force:$executionId",
            source = ExecutionStateSource.USER,
            reasonCode = "force_stop_started",
        )) {
            is ExecutionTransitionResult.Terminal -> return CancellationOutcome.AlreadyTerminal(terminating.record)
            is ExecutionTransitionResult.Missing -> return CancellationOutcome.Missing
            is ExecutionTransitionResult.Invalid,
            is ExecutionTransitionResult.Conflict,
            -> return unconfirmed(executionId, "force_stop_transition_conflict")
            is ExecutionTransitionResult.Applied -> Unit
        }
        dispatchStop(executionId, force = true)
        return when (val finalProbe = runtimeProbe.probe(
            repository.get(executionId) ?: return CancellationOutcome.Missing,
        )) {
            is RuntimeProbeResult.Exited -> confirmCancelled(executionId, "force_stop_confirmed")
            is RuntimeProbeResult.Missing -> if (finalProbe.authoritative) {
                confirmCancelled(executionId, "force_stop_confirmed")
            } else {
                unconfirmed(executionId, "runtime_missing_unconfirmed")
            }
            is RuntimeProbeResult.Alive -> unconfirmed(executionId, "runtime_still_alive")
            is RuntimeProbeResult.Recovering -> unconfirmed(executionId, finalProbe.reasonCode)
            is RuntimeProbeResult.Unreachable -> unconfirmed(executionId, finalProbe.reasonCode)
            is RuntimeProbeResult.Unsupported -> unconfirmed(executionId, finalProbe.reasonCode)
        }
    }

    private suspend fun dispatchStop(executionId: String, force: Boolean) {
        val record = repository.get(executionId) ?: return
        val caller = callerResolver.resolve(record) ?: return
        managedCoordinator.dispatch(ManagedExecutionRequest.Stop(caller, executionId, force))
    }

    private suspend fun confirmCancelled(
        executionId: String,
        reasonCode: String,
    ): CancellationOutcome {
        return when (val result = repository.transition(
            id = executionId,
            target = ExecutionStatus.cancelled,
            verificationState = VerificationState.RUNTIME_CONFIRMED,
            mutationId = "cancel-confirmed:$executionId:$reasonCode",
            source = ExecutionStateSource.PROBE,
            reasonCode = reasonCode,
            cancellationResult = "STOPPED_CONFIRMED",
            probeAtMs = System.currentTimeMillis(),
        )) {
            is ExecutionTransitionResult.Applied -> CancellationOutcome.Cancelled(result.record)
            is ExecutionTransitionResult.Terminal -> if (
                ExecutionStatus.fromWire(result.record.status) == ExecutionStatus.cancelled
            ) {
                CancellationOutcome.Cancelled(result.record)
            } else {
                CancellationOutcome.AlreadyTerminal(result.record)
            }
            else -> unconfirmed(executionId, "cancel_confirmation_conflict")
        }
    }

    private suspend fun unconfirmed(
        executionId: String,
        reasonCode: String,
    ): CancellationOutcome {
        val current = repository.get(executionId) ?: return CancellationOutcome.Missing
        if (ExecutionStatus.fromWire(current.status).isTerminal) {
            return CancellationOutcome.AlreadyTerminal(current)
        }
        val target = if (ExecutionStatus.fromWire(current.status) == ExecutionStatus.terminating) {
            ExecutionStatus.terminating
        } else {
            ExecutionStatus.cancel_requested
        }
        val updated = repository.transition(
            id = executionId,
            target = target,
            verificationState = VerificationState.STALE,
            mutationId = "cancel-unconfirmed:$executionId:${current.stateVersion}:$reasonCode",
            source = ExecutionStateSource.PROBE,
            reasonCode = reasonCode.take(160),
            cancellationResult = "STOP_UNCONFIRMED",
            probeAtMs = System.currentTimeMillis(),
        )
        val record = (updated as? ExecutionTransitionResult.Applied)?.record
            ?: repository.get(executionId)
            ?: return CancellationOutcome.Missing
        return CancellationOutcome.Unconfirmed(record, reasonCode)
    }
}

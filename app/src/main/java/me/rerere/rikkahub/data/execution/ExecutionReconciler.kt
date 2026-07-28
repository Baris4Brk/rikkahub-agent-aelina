package me.rerere.rikkahub.data.execution

data class ExecutionProbeUpdate(
    val executionId: String,
    val probe: RuntimeProbeResult,
    val record: ExecutionRecord?,
    val continuity: RuntimeContinuity,
    val conflict: Boolean = false,
)

/** Applies probe evidence with the exact pre-probe state version; stale evidence is discarded. */
class ExecutionReconciler(
    private val repository: ExecutionRepository,
    private val probe: ExecutionRuntimeProbe,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val metrics: ExecutionConsistencyMetrics? = null,
) {
    suspend fun reconcileAll(): List<ExecutionProbeUpdate> = repository.getInFlight()
        .filter { ExecutionKind.fromWire(it.executionKind) == ExecutionKind.MANAGED_PROCESS }
        .map { reconcile(it.id) }

    suspend fun reconcile(executionId: String): ExecutionProbeUpdate {
        repeat(MAX_REPROBES) {
            val before = repository.get(executionId)
                ?: return ExecutionProbeUpdate(
                    executionId,
                    RuntimeProbeResult.Unsupported("execution_missing"),
                    null,
                    RuntimeContinuity.UNKNOWN,
                )
            if (ExecutionStatus.fromWire(before.status).isTerminal) {
                return ExecutionProbeUpdate(
                    executionId,
                    RuntimeProbeResult.Unsupported("execution_already_terminal"),
                    before,
                    RuntimeContinuity.UNKNOWN,
                )
            }
            val observed = probe.probe(before)
            val plan = planExecutionProbeMutation(before, observed, nowMs())
            when (val result = repository.mutateObserved(plan.mutation)) {
                is ExecutionMutationResult.Applied -> return ExecutionProbeUpdate(
                    executionId = executionId,
                    probe = observed,
                    record = result.record,
                    continuity = plan.continuity,
                )
                is ExecutionMutationResult.Duplicate -> return ExecutionProbeUpdate(
                    executionId,
                    observed,
                    result.record,
                    plan.continuity,
                )
                is ExecutionMutationResult.Terminal -> return ExecutionProbeUpdate(
                    executionId,
                    observed,
                    result.record,
                    RuntimeContinuity.UNKNOWN,
                    conflict = true,
                )
                is ExecutionMutationResult.Conflict -> {
                    metrics?.recordStaleProbeDiscard()
                    // Discard evidence and probe again from the new version.
                }
                is ExecutionMutationResult.Missing -> return ExecutionProbeUpdate(
                    executionId,
                    observed,
                    null,
                    RuntimeContinuity.UNKNOWN,
                    conflict = true,
                )
                is ExecutionMutationResult.Invalid -> return ExecutionProbeUpdate(
                    executionId,
                    observed,
                    before,
                    RuntimeContinuity.UNKNOWN,
                    conflict = true,
                )
            }
        }
        val current = repository.get(executionId)
        return ExecutionProbeUpdate(
            executionId = executionId,
            probe = RuntimeProbeResult.Unreachable("probe_cas_conflict"),
            record = current,
            continuity = RuntimeContinuity.UNKNOWN,
            conflict = true,
        )
    }

    private companion object {
        const val MAX_REPROBES = 3
    }
}

internal data class ProbeMutationPlan(
    val mutation: ExecutionMutation,
    val continuity: RuntimeContinuity,
)

internal fun planExecutionProbeMutation(
    record: ExecutionRecord,
    observed: RuntimeProbeResult,
    probedAt: Long,
): ProbeMutationPlan {
    val current = ExecutionStatus.fromWire(record.status)
    var continuity = RuntimeContinuity.UNKNOWN
    val target: ExecutionStatus
    val verification: VerificationState
    val reason: String
    var marker: String? = null
    when (observed) {
        is RuntimeProbeResult.Alive -> {
            marker = observed.runtimeInstanceMarker
            continuity = when {
                record.runtimeInstanceMarker == null || marker == null -> RuntimeContinuity.UNKNOWN
                record.runtimeInstanceMarker == marker -> RuntimeContinuity.SAME_INSTANCE
                else -> RuntimeContinuity.RESTARTED
            }
            target = if (current in setOf(
                    ExecutionStatus.cancel_requested,
                    ExecutionStatus.terminating,
                )
            ) current else ExecutionStatus.running
            verification = VerificationState.RUNTIME_CONFIRMED
            reason = when {
                current in setOf(ExecutionStatus.cancel_requested, ExecutionStatus.terminating) ->
                    "runtime_alive_after_cancel"
                continuity == RuntimeContinuity.RESTARTED -> "workspace_process_restarted"
                else -> "runtime_alive"
            }
        }
        is RuntimeProbeResult.Exited -> {
            continuity = RuntimeContinuity.SAME_INSTANCE
            target = if (current in setOf(
                    ExecutionStatus.cancel_requested,
                    ExecutionStatus.terminating,
                )
            ) {
                record.requestedTerminalStatus()
            } else if (observed.exitCode == 0) {
                ExecutionStatus.succeeded
            } else {
                ExecutionStatus.failed
            }
            verification = VerificationState.RUNTIME_CONFIRMED
            reason = when (target) {
                ExecutionStatus.cancelled -> "runtime_exit_after_cancel"
                ExecutionStatus.timed_out -> "runtime_exit_after_timeout"
                ExecutionStatus.succeeded -> "runtime_exit_zero"
                else -> "runtime_exit_nonzero"
            }
        }
        is RuntimeProbeResult.Missing -> if (observed.authoritative) {
            if (current in setOf(ExecutionStatus.cancel_requested, ExecutionStatus.terminating)) {
                target = record.requestedTerminalStatus()
                verification = VerificationState.RUNTIME_CONFIRMED
                continuity = RuntimeContinuity.SAME_INSTANCE
                reason = if (target == ExecutionStatus.timed_out) {
                    "runtime_missing_after_timeout"
                } else {
                    "runtime_missing_after_cancel"
                }
            } else {
                target = ExecutionStatus.orphaned
                verification = VerificationState.UNKNOWN
                continuity = RuntimeContinuity.LOST
                reason = if (ExecutionRuntime.fromWire(record.runtime) == ExecutionRuntime.WORKSPACE &&
                    CompletionPolicy.fromWire(record.completionPolicy) == CompletionPolicy.DETACH_BACKGROUND
                ) {
                    "workspace_never_lost"
                } else {
                    "runtime_authoritatively_missing"
                }
            }
        } else {
            target = current
            verification = VerificationState.STALE
            reason = "runtime_missing_unconfirmed"
        }
        is RuntimeProbeResult.Recovering -> {
            target = current
            verification = VerificationState.RECONCILING
            reason = observed.reasonCode
        }
        is RuntimeProbeResult.Unreachable -> {
            target = current
            verification = VerificationState.STALE
            reason = observed.reasonCode
        }
        is RuntimeProbeResult.Unsupported -> {
            target = ExecutionStatus.orphaned
            verification = VerificationState.UNKNOWN
            reason = observed.reasonCode
        }
    }
    val resultKey = when (observed) {
        is RuntimeProbeResult.Alive -> "alive:${observed.runtimeInstanceMarker.orEmpty()}"
        is RuntimeProbeResult.Exited -> "exited:${observed.exitCode}"
        is RuntimeProbeResult.Missing -> "missing:${observed.authoritative}"
        is RuntimeProbeResult.Recovering -> "recovering:${observed.reasonCode}"
        is RuntimeProbeResult.Unreachable -> "unreachable:${observed.reasonCode}"
        is RuntimeProbeResult.Unsupported -> "unsupported:${observed.reasonCode}"
    }
    return ProbeMutationPlan(
        mutation = ExecutionMutation(
            executionId = record.id,
            mutationId = "probe:${record.id}:${record.stateVersion}:$resultKey".take(500),
            expectedVersion = record.stateVersion,
            source = ExecutionStateSource.PROBE,
            reasonCode = reason.take(160),
            targetStatus = target,
            verificationState = verification,
            runtimeInstanceMarker = marker,
            cancellationResult = "STOPPED_CONFIRMED".takeIf {
                target == ExecutionStatus.cancelled || target == ExecutionStatus.timed_out
            },
            probeAtMs = probedAt,
        ),
        continuity = continuity,
    )
}

private fun ExecutionRecord.requestedTerminalStatus(): ExecutionStatus =
    if (RequestedTerminalOutcome.fromWire(requestedTerminalOutcome) ==
        RequestedTerminalOutcome.TIMED_OUT
    ) {
        ExecutionStatus.timed_out
    } else {
        ExecutionStatus.cancelled
    }

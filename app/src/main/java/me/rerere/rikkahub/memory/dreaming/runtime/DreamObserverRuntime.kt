package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.model.DreamRun
import me.rerere.rikkahub.memory.dreaming.model.DreamRunFailureCode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunMode
import me.rerere.rikkahub.memory.dreaming.model.DreamRunStatus
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeState
import me.rerere.rikkahub.memory.dreaming.model.requireCanonicalDreamRunId
import me.rerere.rikkahub.memory.dreaming.store.DreamObserverStore
import me.rerere.rikkahub.memory.dreaming.store.DreamRunFinishOutcome
import me.rerere.rikkahub.memory.dreaming.store.DreamRunOwnerRequest
import me.rerere.rikkahub.memory.dreaming.store.DreamStoreRejection
import me.rerere.rikkahub.memory.dreaming.store.FinishDreamRunRequest
import me.rerere.rikkahub.memory.dreaming.store.FinishDreamRunResult
import me.rerere.rikkahub.memory.dreaming.store.MAX_DIRTY_DREAM_SCOPES_PER_SCAN
import me.rerere.rikkahub.memory.dreaming.store.PruneObserverChangesRequest
import me.rerere.rikkahub.memory.dreaming.store.PruneObserverChangesResult
import me.rerere.rikkahub.memory.dreaming.store.ReadObserverReplayResult
import me.rerere.rikkahub.memory.dreaming.store.RecoverExpiredDreamRunsRequest
import me.rerere.rikkahub.memory.dreaming.store.StartDreamRunRequest
import me.rerere.rikkahub.memory.dreaming.store.StartDreamRunResult
import me.rerere.rikkahub.memory.dreaming.work.DreamObserverWorkScheduler
import kotlin.uuid.Uuid

/**
 * M2 Observer runtime: validates and checkpoints the durable journal without a model or network.
 * Scheduling is deliberately outside the correctness boundary; a later dirty scan can always
 * reconstruct work from `memoryEpoch > observerCheckpointEpoch`.
 */
class DreamObserverRuntime(
    private val store: DreamObserverStore,
    private val scheduler: DreamObserverWorkScheduler,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val runIdGenerator: () -> String = { Uuid.random().toString() },
) {
    suspend fun scanDirtyScopes(
        limit: Int = DEFAULT_DREAM_OBSERVER_SCAN_LIMIT,
    ): DreamObserverScanResult {
        require(limit in 1..MAX_DIRTY_DREAM_SCOPES_PER_SCAN)
        val now = requireClock(nowMs())
        val recovered = store.recoverExpiredRuns(RecoverExpiredDreamRunsRequest(now))
        val dirty = store.findDirtyScopes(limit)
        dirty.forEach { state ->
            // A restarted process resumes the durable active identity; only an unleased dirty
            // scope allocates a new run. The owner is derived from this ID and is also stable.
            val runId = state.activeRunId ?: runIdGenerator()
            requireCanonicalDreamRunId(runId)
            scheduler.enqueueScope(state.scopeId, runId)
        }
        return DreamObserverScanResult(
            scheduledScopes = dirty.map { it.scopeId },
            recoveredRunCount = recovered.recoveredRuns.size,
            saturated = dirty.size == limit,
        )
    }

    suspend fun observe(
        scopeId: DreamScopeId,
        runId: String,
    ): DreamObserverPassResult {
        requireCanonicalDreamRunId(runId)
        val clock = PassClock(nowMs)
        val owner = dreamObserverLeaseOwner(runId)
        val started = store.startRun(
            StartDreamRunRequest(
                runId = runId,
                scopeId = scopeId,
                mode = DreamRunMode.OBSERVER_REPLAY,
                leaseOwner = owner,
                nowMs = clock.next(),
                leaseDurationMs = DREAM_OBSERVER_LEASE_DURATION_MS,
            ),
        )
        val running = when (started) {
            is StartDreamRunResult.Started -> started.run
            is StartDreamRunResult.Resumed -> started.run
            is StartDreamRunResult.Terminal -> return terminalResultWithCleanup(started.run)
            is StartDreamRunResult.Rejected -> {
                if (started.reason == DreamStoreRejection.LEASE_EXPIRED) {
                    store.recoverExpiredRuns(RecoverExpiredDreamRunsRequest(clock.next()))
                    return DreamObserverPassResult(
                        directive = DreamObserverWorkerDirective.RESCAN,
                        run = store.readRun(runId),
                        rejection = started.reason,
                    )
                }
                return rejectionResult(started.reason)
            }
        }

        val replay = store.readReplay(
            DreamRunOwnerRequest(
                runId = running.runId,
                scopeId = running.scopeId,
                leaseOwner = owner,
                nowMs = clock.next(),
            ),
        )
        if (replay is ReadObserverReplayResult.Rejected &&
            replay.reason !in FINISHABLE_REPLAY_REJECTIONS
        ) {
            return rejectionResult(replay.reason, running)
        }

        // A success request is also the fail-closed terminalization path for epoch drift and a
        // journal gap. Room commits CONFLICT and releases the lease without moving the checkpoint.
        val finish = store.finish(
            FinishDreamRunRequest(
                runId = running.runId,
                scopeId = running.scopeId,
                leaseOwner = owner,
                outcome = DreamRunFinishOutcome.SUCCEEDED,
                nowMs = clock.next(),
            ),
        )
        return when (finish) {
            is FinishDreamRunResult.Finished -> finishResult(finish.run, finish.scopeState)
            is FinishDreamRunResult.Rejected -> {
                // Duplicate delivery may observe the first worker's committed terminal result.
                val durable = store.readRun(runId)
                if (durable?.status?.isTerminal == true) terminalResultWithCleanup(durable)
                else rejectionResult(finish.reason, durable ?: running)
            }
        }
    }

    private suspend fun terminalResultWithCleanup(run: DreamRun): DreamObserverPassResult {
        if (run.status != DreamRunStatus.SUCCEEDED) return terminalResult(run)
        val state = store.readScopeState(run.scopeId) ?: return terminalResult(run)
        return finishResult(run, state)
    }

    private suspend fun finishResult(
        run: DreamRun,
        state: DreamScopeState,
    ): DreamObserverPassResult {
        if (run.status != DreamRunStatus.SUCCEEDED) return terminalResult(run)
        var pruned = 0
        if (state.observerCheckpointEpoch > 0L) {
            val prune = store.pruneChanges(
                PruneObserverChangesRequest(
                    scopeId = state.scopeId,
                    expectedMemoryEpoch = state.memoryEpoch,
                    expectedObserverCheckpointEpoch = state.observerCheckpointEpoch,
                    throughEpochInclusive = state.observerCheckpointEpoch,
                ),
            )
            if (prune is PruneObserverChangesResult.Pruned) pruned = prune.deletedCount
            // A concurrent mutation or older protected run may reject pruning. Checkpoint success
            // remains valid; a future pass can safely retry cleanup.
        }
        return DreamObserverPassResult(
            directive = DreamObserverWorkerDirective.COMPLETE,
            run = run,
            prunedChangeCount = pruned,
        )
    }

    private fun terminalResult(run: DreamRun): DreamObserverPassResult {
        val directive = when (run.status) {
            DreamRunStatus.SUCCEEDED -> DreamObserverWorkerDirective.COMPLETE
            DreamRunStatus.CONFLICT -> when (run.failureCode) {
                DreamRunFailureCode.MEMORY_EPOCH_CONFLICT,
                DreamRunFailureCode.OBSERVER_CHECKPOINT_CONFLICT,
                -> DreamObserverWorkerDirective.RESCAN

                DreamRunFailureCode.JOURNAL_GAP -> DreamObserverWorkerDirective.BLOCKED
                else -> DreamObserverWorkerDirective.RESCAN
            }

            DreamRunStatus.FAILED -> when (run.failureCode) {
                DreamRunFailureCode.LEASE_EXPIRED,
                DreamRunFailureCode.STORE_FAILURE,
                -> DreamObserverWorkerDirective.RESCAN

                else -> DreamObserverWorkerDirective.BLOCKED
            }

            DreamRunStatus.CANCELLED,
            DreamRunStatus.DISCARDED,
            -> DreamObserverWorkerDirective.BLOCKED

            DreamRunStatus.PENDING,
            DreamRunStatus.RUNNING,
            -> DreamObserverWorkerDirective.RETRY
        }
        return DreamObserverPassResult(directive = directive, run = run)
    }

    private fun rejectionResult(
        rejection: DreamStoreRejection,
        run: DreamRun? = null,
    ): DreamObserverPassResult {
        val directive = when (rejection) {
            DreamStoreRejection.ACTIVE_RUN_CONFLICT,
            DreamStoreRejection.OWNER_MISMATCH,
            DreamStoreRejection.LEASE_EXPIRED,
            DreamStoreRejection.STATUS_MISMATCH,
            DreamStoreRejection.CLOCK_ROLLBACK,
            DreamStoreRejection.NOT_FOUND,
            -> DreamObserverWorkerDirective.RETRY

            DreamStoreRejection.MEMORY_EPOCH_CONFLICT,
            DreamStoreRejection.OBSERVER_CHECKPOINT_CONFLICT,
            -> DreamObserverWorkerDirective.RESCAN

            DreamStoreRejection.JOURNAL_GAP,
            DreamStoreRejection.RUN_ID_CONFLICT,
            DreamStoreRejection.SCOPE_MISMATCH,
            DreamStoreRejection.PRUNE_WATERMARK_CONFLICT,
            -> DreamObserverWorkerDirective.BLOCKED
        }
        return DreamObserverPassResult(directive = directive, run = run, rejection = rejection)
    }
}

data class DreamObserverScanResult(
    val scheduledScopes: List<DreamScopeId>,
    val recoveredRunCount: Int,
    val saturated: Boolean,
)

data class DreamObserverPassResult(
    val directive: DreamObserverWorkerDirective,
    val run: DreamRun?,
    val rejection: DreamStoreRejection? = null,
    val prunedChangeCount: Int = 0,
)

enum class DreamObserverWorkerDirective {
    COMPLETE,
    RETRY,
    RESCAN,
    BLOCKED,
}

internal fun dreamObserverLeaseOwner(runId: String): String {
    requireCanonicalDreamRunId(runId)
    return "dream-observer-$runId"
}

private class PassClock(private val source: () -> Long) {
    private var latest = -1L

    fun next(): Long {
        val sampled = requireClock(source())
        latest = maxOf(latest, sampled)
        return latest
    }
}

private fun requireClock(value: Long): Long {
    require(value >= 0L) { "dream_observer_clock_negative" }
    return value
}

private val FINISHABLE_REPLAY_REJECTIONS = setOf(
    DreamStoreRejection.MEMORY_EPOCH_CONFLICT,
    DreamStoreRejection.OBSERVER_CHECKPOINT_CONFLICT,
    DreamStoreRejection.JOURNAL_GAP,
)

const val DEFAULT_DREAM_OBSERVER_SCAN_LIMIT = 128
const val DREAM_OBSERVER_LEASE_DURATION_MS = 5L * 60_000L

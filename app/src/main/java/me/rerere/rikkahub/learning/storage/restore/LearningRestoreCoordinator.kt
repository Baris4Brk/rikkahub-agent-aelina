package me.rerere.rikkahub.learning.storage.restore

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LearningRestoreBlockReason {
    PREFLIGHT_REJECTED,
    LEARNING_DATABASE_PATH_UNSAFE,
    MAIN_DATABASE_WRITE_GATE_MISSING,
    MAIN_DATABASE_WRITE_GATE_UNAVAILABLE,
    SCHEDULER_DID_NOT_QUIESCE,
    ARCHIVE_CHANGED_AFTER_PREFLIGHT,
    RUNTIME_FENCE_FAILED,
}

enum class LearningRestoreFailureReason {
    MAIN_WRITE_GATE_SEAL_FAILED,
    QUARANTINE_FAILED,
    MAIN_DATABASE_RESTORE_FAILED,
    RUNTIME_FINALIZATION_FAILED,
    CANCELLED_AFTER_IRREVERSIBLE_FENCE,
}

sealed interface LearningRestoreResult {
    /** Settings/files-only restores have no Learning side effect. */
    data object NoOp : LearningRestoreResult

    /** No authoritative main-database bytes were changed by this coordinator. */
    data class Blocked(
        val reason: LearningRestoreBlockReason,
        val preflightFailure: LearningRestorePreflightFailure? = null,
        val pathFailure: LearningOwnedDatabasePathFailure? = null,
    ) : LearningRestoreResult

    /** The main restore completed, but this process must never reopen either Room graph. */
    class ProcessRestartRequired internal constructor(
        val runtimeFence: LearningRestoreRuntimeFence,
        val quarantine: LearningQuarantineBatch,
    ) : LearningRestoreResult {
        override fun toString(): String =
            "ProcessRestartRequired(generation=${runtimeFence.generation}, " +
                "quarantined=${quarantine.fileCount})"
    }

    /**
     * An irreversible boundary was crossed. Learning remains DEGRADED and the main write gate and
     * quarantine stay sealed until a process restart; old files are never restored in-process.
     */
    class FailedRestartRequired internal constructor(
        val reason: LearningRestoreFailureReason,
        val runtimeFence: LearningRestoreRuntimeFence,
        val quarantine: LearningQuarantineBatch?,
        val quarantineFailure: LearningQuarantineFailure? = null,
    ) : LearningRestoreResult {
        override fun toString(): String =
            "FailedRestartRequired(reason=$reason, generation=${runtimeFence.generation}, " +
                "quarantined=${quarantine?.fileCount ?: 0})"
    }
}

/** New runtime generation established while closing Learning Room. */
data class LearningRestoreRuntimeFence(val generation: Long) {
    init {
        require(generation > 0L) { "Runtime generation must be positive" }
    }

    /** A callback created by an older runtime generation may not commit derived output. */
    fun fencesCallback(callbackGeneration: Long): Boolean = callbackGeneration < generation
}

/**
 * Adapter boundary for [me.rerere.rikkahub.learning.runtime.LearningRuntimeFacade].
 *
 * `beginIrreversibleRestore` must atomically wait for current Learning operations, increment the
 * generation, close Learning Room, and latch the process in RESTORING before it returns. If it
 * throws, it must not have crossed that boundary. Neither terminal method may permit a reopen.
 */
interface LearningRuntimeRestorePort {
    suspend fun beginIrreversibleRestore(): LearningRestoreRuntimeFence

    suspend fun remainClosedUntilProcessRestart(fence: LearningRestoreRuntimeFence)

    suspend fun remainDegradedUntilProcessRestart(
        fence: LearningRestoreRuntimeFence,
        reason: LearningRestoreFailureReason,
    )
}

/** Stops new consumer/worker scheduling and waits until every current job has actually exited. */
fun interface LearningRestoreSchedulerPort {
    suspend fun stopAndAwaitIdle()

    /** Called only when the irreversible runtime fence was not crossed. */
    suspend fun resumeAfterAbortedRestore() = Unit
}

enum class MainDatabaseRestoreGateBlockReason {
    MISSING,
    BUSY,
    UNSUPPORTED,
    FAILED,
}

sealed interface MainDatabaseRestoreGateAccess {
    data class Acquired(val lease: MainDatabaseRestoreGateLease) : MainDatabaseRestoreGateAccess

    data class Blocked(val reason: MainDatabaseRestoreGateBlockReason) :
        MainDatabaseRestoreGateAccess
}

/**
 * A real application-wide gate must stop new main-database writes and await every active writer
 * before returning [MainDatabaseRestoreGateAccess.Acquired].
 */
fun interface MainDatabaseRestoreWriteGate {
    suspend fun acquireAndAwaitNoWriters(): MainDatabaseRestoreGateAccess
}

interface MainDatabaseRestoreGateLease {
    /** Keeps the gate closed for the lifetime of the current process. Must not throw. */
    fun sealUntilProcessRestart()

    /** Releases the gate only when no irreversible restore boundary was crossed. */
    fun releaseBeforeRestore()
}

/** Safe default while WebDAV/S3 have no application-wide main-database write gate. */
object MissingMainDatabaseRestoreWriteGate : MainDatabaseRestoreWriteGate {
    override suspend fun acquireAndAwaitNoWriters(): MainDatabaseRestoreGateAccess =
        MainDatabaseRestoreGateAccess.Blocked(MainDatabaseRestoreGateBlockReason.MISSING)
}

/**
 * Sync integration seam. The implementation must restore [archive]'s exact
 * [LearningRestorePreflight.VerifiedDatabase.archiveFile] and checksum identity, run the existing
 * main database reconciler, and return only after both have succeeded.
 */
fun interface VerifiedMainDatabaseRestoreAction {
    suspend fun restore(archive: LearningRestorePreflight.VerifiedDatabase)
}

/**
 * Coordinates a fail-closed reset of rebuildable Learning state around authoritative DB restore.
 *
 * There is one process mutex shared across all coordinator instances. Once the runtime fence is
 * crossed, cancellation or failure never rolls back the quarantine and never releases the main
 * write gate. A complete process restart is required in every such outcome.
 */
class LearningRestoreCoordinator(
    private val ownedPaths: LearningOwnedDatabasePathValidation,
    private val scheduler: LearningRestoreSchedulerPort,
    private val runtime: LearningRuntimeRestorePort,
    private val mainWriteGate: MainDatabaseRestoreWriteGate = MissingMainDatabaseRestoreWriteGate,
    private val quarantineFactory: (LearningOwnedDatabasePaths) -> LearningRestoreQuarantine =
        { paths -> LearningRestoreQuarantine(paths) },
) {
    suspend fun restore(
        preflight: LearningRestorePreflight,
        restoreMainDatabase: VerifiedMainDatabaseRestoreAction,
    ): LearningRestoreResult {
        when (preflight) {
            LearningRestorePreflight.NoDatabaseSelected -> return LearningRestoreResult.NoOp
            is LearningRestorePreflight.Rejected -> {
                return LearningRestoreResult.Blocked(
                    reason = LearningRestoreBlockReason.PREFLIGHT_REJECTED,
                    preflightFailure = preflight.failure,
                )
            }

            is LearningRestorePreflight.VerifiedDatabase -> Unit
        }

        return processRestoreMutex.withLock {
            restoreVerified(preflight, restoreMainDatabase)
        }
    }

    private suspend fun restoreVerified(
        preflight: LearningRestorePreflight.VerifiedDatabase,
        restoreMainDatabase: VerifiedMainDatabaseRestoreAction,
    ): LearningRestoreResult {
        val paths = when (ownedPaths) {
            is LearningOwnedDatabasePathValidation.Invalid -> {
                return LearningRestoreResult.Blocked(
                    reason = LearningRestoreBlockReason.LEARNING_DATABASE_PATH_UNSAFE,
                    pathFailure = ownedPaths.failure,
                )
            }

            is LearningOwnedDatabasePathValidation.Valid -> ownedPaths.paths
        }

        val gateAccess = try {
            mainWriteGate.acquireAndAwaitNoWriters()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LearningRestoreResult.Blocked(
                LearningRestoreBlockReason.MAIN_DATABASE_WRITE_GATE_UNAVAILABLE,
            )
        }
        val gateLease = when (gateAccess) {
            is MainDatabaseRestoreGateAccess.Blocked -> {
                return LearningRestoreResult.Blocked(
                    reason = if (gateAccess.reason == MainDatabaseRestoreGateBlockReason.MISSING) {
                        LearningRestoreBlockReason.MAIN_DATABASE_WRITE_GATE_MISSING
                    } else {
                        LearningRestoreBlockReason.MAIN_DATABASE_WRITE_GATE_UNAVAILABLE
                    },
                )
            }

            is MainDatabaseRestoreGateAccess.Acquired -> gateAccess.lease
        }

        val released = AtomicBoolean(false)
        fun releaseGateBeforeRestore() {
            if (released.compareAndSet(false, true)) {
                runCatching { gateLease.releaseBeforeRestore() }
            }
        }

        suspend fun abortBeforeRuntimeFence() {
            withContext(NonCancellable) {
                runCatching { scheduler.resumeAfterAbortedRestore() }
                releaseGateBeforeRestore()
            }
        }

        try {
            scheduler.stopAndAwaitIdle()
        } catch (cancelled: CancellationException) {
            abortBeforeRuntimeFence()
            throw cancelled
        } catch (_: Exception) {
            abortBeforeRuntimeFence()
            return LearningRestoreResult.Blocked(LearningRestoreBlockReason.SCHEDULER_DID_NOT_QUIESCE)
        }

        if (!preflight.isArchiveIdentityCurrent()) {
            abortBeforeRuntimeFence()
            return LearningRestoreResult.Blocked(
                LearningRestoreBlockReason.ARCHIVE_CHANGED_AFTER_PREFLIGHT,
            )
        }

        val fence = try {
            currentCoroutineContext().ensureActive()
            runtime.beginIrreversibleRestore()
        } catch (cancelled: CancellationException) {
            // Port contract says a throwing begin did not cross the irreversible boundary.
            abortBeforeRuntimeFence()
            throw cancelled
        } catch (_: Exception) {
            abortBeforeRuntimeFence()
            return LearningRestoreResult.Blocked(LearningRestoreBlockReason.RUNTIME_FENCE_FAILED)
        }

        try {
            gateLease.sealUntilProcessRestart()
        } catch (_: Exception) {
            remainDegraded(fence, LearningRestoreFailureReason.MAIN_WRITE_GATE_SEAL_FAILED)
            return LearningRestoreResult.FailedRestartRequired(
                reason = LearningRestoreFailureReason.MAIN_WRITE_GATE_SEAL_FAILED,
                runtimeFence = fence,
                quarantine = null,
            )
        }

        var quarantine: LearningQuarantineBatch? = null
        try {
            val quarantined = quarantineFactory(paths).quarantineExactFiles()
            quarantine = quarantined
            restoreMainDatabase.restore(preflight)
            try {
                withContext(NonCancellable) {
                    runtime.remainClosedUntilProcessRestart(fence)
                }
            } catch (_: Exception) {
                remainDegraded(fence, LearningRestoreFailureReason.RUNTIME_FINALIZATION_FAILED)
                return LearningRestoreResult.FailedRestartRequired(
                    reason = LearningRestoreFailureReason.RUNTIME_FINALIZATION_FAILED,
                    runtimeFence = fence,
                    quarantine = quarantine,
                )
            }
            return LearningRestoreResult.ProcessRestartRequired(fence, quarantined)
        } catch (cancelled: CancellationException) {
            remainDegraded(fence, LearningRestoreFailureReason.CANCELLED_AFTER_IRREVERSIBLE_FENCE)
            throw cancelled
        } catch (error: LearningQuarantineException) {
            remainDegraded(fence, LearningRestoreFailureReason.QUARANTINE_FAILED)
            return LearningRestoreResult.FailedRestartRequired(
                reason = LearningRestoreFailureReason.QUARANTINE_FAILED,
                runtimeFence = fence,
                quarantine = error.partialBatch,
                quarantineFailure = error.failure,
            )
        } catch (_: Exception) {
            remainDegraded(fence, LearningRestoreFailureReason.MAIN_DATABASE_RESTORE_FAILED)
            return LearningRestoreResult.FailedRestartRequired(
                reason = LearningRestoreFailureReason.MAIN_DATABASE_RESTORE_FAILED,
                runtimeFence = fence,
                quarantine = quarantine,
            )
        }
    }

    private suspend fun remainDegraded(
        fence: LearningRestoreRuntimeFence,
        reason: LearningRestoreFailureReason,
    ) {
        withContext(NonCancellable) {
            runCatching { runtime.remainDegradedUntilProcessRestart(fence, reason) }
        }
    }

    private companion object {
        /** Process-wide even when WebDAV, S3, and local restore construct different coordinators. */
        val processRestoreMutex = Mutex()
    }
}

package me.rerere.rikkahub.learning.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.learning.handoff.LearningOutboxReader
import me.rerere.rikkahub.learning.handoff.LearningReconciliationScanner
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticCode
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticSample
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticState
import me.rerere.rikkahub.learning.diagnostics.LearningDiagnosticsStore
import me.rerere.rikkahub.learning.jobs.LearningDrainResult
import me.rerere.rikkahub.learning.jobs.LearningJobClock
import me.rerere.rikkahub.learning.jobs.LearningJobClockRollbackException
import me.rerere.rikkahub.learning.jobs.LearningJobCoordinator
import me.rerere.rikkahub.learning.jobs.LearningJobHandlerRegistry
import me.rerere.rikkahub.learning.jobs.P1LearningRuntimeBindings
import me.rerere.rikkahub.learning.jobs.P1LearningRuntimeDependencyFactory
import me.rerere.rikkahub.learning.jobs.UnconfiguredP1LearningRuntimeDependencyFactory
import me.rerere.rikkahub.learning.jobs.P1DerivedJobCatchUp
import me.rerere.rikkahub.learning.jobs.P1DerivedJobCatchUpResult
import me.rerere.rikkahub.learning.jobs.NoOpP1DerivedJobCatchUp
import me.rerere.rikkahub.learning.jobs.LearningJobStartupRecoveryResult
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.privacy.LearningDerivedEraseFailureCode
import me.rerere.rikkahub.learning.privacy.LearningDerivedEraseUnavailableException
import me.rerere.rikkahub.learning.privacy.LearningEphemeralScopeEraser
import me.rerere.rikkahub.learning.retrieval.PolicyOpaqueIdFactory
import me.rerere.rikkahub.learning.retrieval.PolicyRetrievalRequest
import me.rerere.rikkahub.learning.retrieval.PolicyRetriever
import me.rerere.rikkahub.learning.retrieval.PolicyShadowFeatureGate
import me.rerere.rikkahub.learning.retrieval.PolicyShadowRuntimePort
import me.rerere.rikkahub.learning.retrieval.PolicyShadowRuntimeResult
import me.rerere.rikkahub.learning.retrieval.RoomPolicyShadowRetriever
import me.rerere.rikkahub.learning.retrieval.ensurePolicyFtsSchema
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningDerivedDataEraseStore
import me.rerere.rikkahub.learning.storage.LearningScopeEraseResult
import me.rerere.rikkahub.learning.storage.LEARNING_MIGRATION_1_2
import me.rerere.rikkahub.learning.storage.LEARNING_MIGRATION_2_3
import me.rerere.rikkahub.learning.storage.restore.LearningRestoreFailureReason
import me.rerere.rikkahub.learning.storage.restore.LearningRestoreRuntimeFence
import me.rerere.rikkahub.learning.storage.restore.LearningRuntimeRestorePort
import kotlin.uuid.Uuid

enum class LearningRuntimeState {
    CLOSED,
    READY,
    RESTORING,
    DEGRADED,
    DISABLED,
}

enum class LearningRuntimeErrorCode {
    DATABASE_OPEN_FAILED,
    DATABASE_OPERATION_FAILED,
    DATABASE_RETRY_BACKOFF,
    FLAG_SOURCE_FAILED,
    RESTORE_IN_PROGRESS,
    RESTORE_FAILED_RESTART_REQUIRED,
    RUNTIME_NOT_CONFIGURED,
    WRONG_PROCESS,
}

sealed interface LearningRuntimeAccess {
    data object Ready : LearningRuntimeAccess

    data object Disabled : LearningRuntimeAccess

    data class Unavailable(val errorCode: LearningRuntimeErrorCode) : LearningRuntimeAccess
}

internal fun interface LearningRuntimeInitializer {
    /** Must finish all database work before returning and must never retain [database] or its DAOs. */
    suspend fun initialize(database: LearningDatabase, runtimeGeneration: Long, frozenNowMs: Long)
}

/** Short-lived handle. Late callbacks must check [isCurrent] before committing derived output. */
class LearningRuntimeSession internal constructor(
    val generation: Long,
    private val currentGeneration: () -> Long,
    private val restoreLatched: () -> Boolean,
) {
    private val active = AtomicBoolean(true)

    fun isCurrent(): Boolean =
        active.get() && !restoreLatched() && generation == currentGeneration()

    internal fun expire() {
        active.set(false)
    }

    override fun toString(): String =
        "LearningRuntimeSession(generation=$generation)"
}

/**
 * Lazy, process-local access to the rebuildable Learning database.
 *
 * The mutex plus structured [coroutineScope] is the restore quiescence boundary. Learning code is
 * forbidden from using GlobalScope or retaining [LearningRuntimeSession]/Room objects after the
 * operation returns. A successful main-database restore permanently latches this facade closed;
 * only a new process may open the derived database again.
 */
class LearningRuntimeFacade internal constructor(
    context: Context,
    private val isEnabled: () -> Boolean,
    private val initializer: LearningRuntimeInitializer = LearningRuntimeInitializer { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryBackoffMs: Long = 30_000L,
    private val isMainProcess: () -> Boolean = { isCurrentMainProcess(context.applicationContext) },
    private val outboxReader: LearningOutboxReader? = null,
    private val reconciliationScanner: LearningReconciliationScanner? = null,
    private val monotonicMs: () -> Long = SystemClock::elapsedRealtime,
    /** Fresh for this OS process; never persisted or reused as a durable identity. */
    private val processSessionId: Uuid = Uuid.random(),
    private val diagnosticsStore: LearningDiagnosticsStore? = null,
    private val jobHandlerRegistry: LearningJobHandlerRegistry? = null,
    private val p1RuntimeDependencyFactory: P1LearningRuntimeDependencyFactory =
        UnconfiguredP1LearningRuntimeDependencyFactory,
    private val policyShadowFeatureGate: PolicyShadowFeatureGate? = null,
    private val policyOpaqueIds: PolicyOpaqueIdFactory? = null,
    private val sqliteOpenHelperFactory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory? = null,
) : LearningRuntimeMaintenancePort, PolicyShadowRuntimePort {
    private val applicationContext = context.applicationContext
    private val mutex = Mutex()
    private val restoreFenceLock = Any()
    private val mutableState = MutableStateFlow(LearningRuntimeState.CLOSED)
    private val restoreLatched = AtomicBoolean(false)
    @Volatile
    private var latchedRestoreState = LearningRuntimeState.RESTORING
    private val runtimeGeneration = AtomicLong(1L)
    private var database: LearningDatabase? = null
    private var initializedDatabase: LearningDatabase? = null
    private var initializedJobHandlerRegistry: LearningJobHandlerRegistry? = null
    private var initializedP1CatchUp: P1DerivedJobCatchUp = NoOpP1DerivedJobCatchUp
    private var nextOpenAttemptAtMs: Long = 0L

    init {
        require(retryBackoffMs in 1_000L..10L * 60L * 1_000L) { "Unsafe retry backoff" }
        require(processSessionId != Uuid.parse("00000000-0000-0000-0000-000000000000")) {
            "Learning process session UUID cannot be nil"
        }
    }

    val state: StateFlow<LearningRuntimeState> = mutableState.asStateFlow()

    fun currentGeneration(): Long = runtimeGeneration.get()

    override suspend fun runMaintenance(
        request: LearningRuntimeMaintenanceRequest,
    ): LearningRuntimeMaintenanceResult {
        val configuredOutboxReader = outboxReader
            ?: return LearningRuntimeMaintenanceResult.Unavailable(
                LearningRuntimeErrorCode.RUNTIME_NOT_CONFIGURED,
            )
        val configuredScanner = reconciliationScanner
            ?: return LearningRuntimeMaintenanceResult.Unavailable(
                LearningRuntimeErrorCode.RUNTIME_NOT_CONFIGURED,
            )
        var drainResult: LearningDrainResult? = null
        val access = withDatabase { session ->
            val openedDatabase = checkNotNull(database) {
                "Learning database unavailable inside runtime operation"
            }
            val frozenNowMs = clock().coerceAtLeast(0L)
            val p1Maintenance = initializedP1CatchUp.catchUp(openedDatabase, frozenNowMs)
            val cycleResult = runLearningRuntimeMaintenanceCycle(
                database = openedDatabase,
                session = session,
                request = request,
                outboxReader = configuredOutboxReader,
                reconciliationScanner = configuredScanner,
                frozenNowMs = frozenNowMs,
                wallClockMs = { clock().coerceAtLeast(0L) },
                monotonicMs = monotonicMs,
                processSessionId = processSessionId,
                jobHandlerRegistry = checkNotNull(initializedJobHandlerRegistry) {
                    "Learning job registry unavailable inside runtime operation"
                },
            )
            drainResult = cycleResult.withP1Maintenance(p1Maintenance)
            diagnosticsStore?.let { store ->
                recordMaintenanceHealthBestEffort(
                    database = openedDatabase,
                    outboxReader = configuredOutboxReader,
                    store = store,
                    recordedAtMs = frozenNowMs,
                )
            }
            // Old-timeline quarantine is recoverable until the derived DB proves complete
            // bootstrap for the exact restored stream. Cleanup failure is fail-closed and must
            // never affect Chat or the maintenance result.
            runCatching {
                val checkpoint = openedDatabase.checkpointDao().listAll().singleOrNull()
                val bootstrapHead = checkpoint?.bootstrapHeadSeq
                if (checkpoint != null && checkpoint.bootstrapState == "COMPLETE" &&
                    bootstrapHead != null && checkpoint.lastContiguousSeq >= bootstrapHead
                ) {
                    me.rerere.rikkahub.learning.storage.restore.ColdRestoreRebuildFinalizer
                        .completeIfProven(
                            context = applicationContext,
                            streamId = checkpoint.streamId,
                            bootstrapHeadSeq = bootstrapHead,
                            lastContiguousSeq = checkpoint.lastContiguousSeq,
                        )
                }
            }
        }
        return when (access) {
            LearningRuntimeAccess.Ready -> LearningRuntimeMaintenanceResult.Completed(
                checkNotNull(drainResult) { "Maintenance completed without a drain result" },
            )

            LearningRuntimeAccess.Disabled -> LearningRuntimeMaintenanceResult.Disabled
            is LearningRuntimeAccess.Unavailable -> LearningRuntimeMaintenanceResult.Unavailable(
                access.errorCode,
            )
        }
    }

    /** P1 shadow retrieval returns only a content-free trace and never touches provider bytes. */
    override suspend fun retrieveShadow(
        request: PolicyRetrievalRequest,
    ): PolicyShadowRuntimeResult {
        if (policyShadowFeatureGate?.enabled() != true) return PolicyShadowRuntimeResult.Disabled
        val configuredOpaqueIds = policyOpaqueIds ?: return PolicyShadowRuntimeResult.Unavailable
        var trace: me.rerere.rikkahub.learning.retrieval.PolicyRetrievalTrace? = null
        val access = withDatabase {
            val opened = checkNotNull(database) {
                "Learning database unavailable inside shadow retrieval"
            }
            trace = RoomPolicyShadowRetriever(
                database = opened,
                retriever = PolicyRetriever(configuredOpaqueIds, monotonicNanos = {
                    monotonicMs() * 1_000_000L
                }),
            ).retrieve(request).trace
        }
        return when (access) {
            LearningRuntimeAccess.Ready -> PolicyShadowRuntimeResult.Completed(
                checkNotNull(trace) { "Shadow retrieval completed without trace" },
            )
            LearningRuntimeAccess.Disabled -> PolicyShadowRuntimeResult.Disabled
            is LearningRuntimeAccess.Unavailable -> PolicyShadowRuntimeResult.Unavailable
        }
    }

    /**
     * Runs one structured Learning operation. The callback and result are deliberately Unit-only:
     * neither a session nor a Room/DAO handle can be returned through this API.
     */
    suspend fun withDatabase(
        operation: suspend (LearningRuntimeSession) -> Unit,
    ): LearningRuntimeAccess = mutex.withLock {
        if (restoreLatched.get()) {
            publishLatchedRestoreState()
            return@withLock LearningRuntimeAccess.Unavailable(
                latchedRestoreErrorCode(),
            )
        }
        val mainProcess = try {
            isMainProcess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!mainProcess) {
            closeLocked()
            mutableState.value = LearningRuntimeState.DISABLED
            return@withLock LearningRuntimeAccess.Unavailable(LearningRuntimeErrorCode.WRONG_PROCESS)
        }
        val enabled = try {
            isEnabled()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            closeLocked()
            mutableState.value = LearningRuntimeState.DEGRADED
            return@withLock LearningRuntimeAccess.Unavailable(
                LearningRuntimeErrorCode.FLAG_SOURCE_FAILED,
            )
        }
        if (!enabled) {
            closeLocked()
            mutableState.value = LearningRuntimeState.DISABLED
            return@withLock LearningRuntimeAccess.Disabled
        }
        val nowMs = try {
            clock()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            closeLocked()
            mutableState.value = LearningRuntimeState.DEGRADED
            return@withLock LearningRuntimeAccess.Unavailable(
                LearningRuntimeErrorCode.DATABASE_OPERATION_FAILED,
            )
        }.coerceAtLeast(0L)
        if (nowMs < nextOpenAttemptAtMs) {
            mutableState.value = LearningRuntimeState.DEGRADED
            return@withLock LearningRuntimeAccess.Unavailable(
                LearningRuntimeErrorCode.DATABASE_RETRY_BACKOFF,
            )
        }

        val opened = database ?: try {
            val candidate = newDatabaseCandidate()
            try {
                // Room build is lazy. Force schema validation before publishing READY.
                candidate.openHelper.writableDatabase
                database = candidate
                candidate
            } catch (cancelled: CancellationException) {
                closeCandidateBestEffort(candidate)
                throw cancelled
            } catch (failure: Exception) {
                closeCandidateBestEffort(candidate)
                throw failure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markFatalLocked(nowMs)
            return@withLock LearningRuntimeAccess.Unavailable(
                if (restoreLatched.get()) {
                    latchedRestoreErrorCode()
                } else {
                    LearningRuntimeErrorCode.DATABASE_OPEN_FAILED
                },
            )
        }

        if (initializedDatabase !== opened) {
            try {
                // Fence work owned by a dead process before any phase-specific initializer can
                // inspect or execute jobs. P0 still has no job handler and therefore never claims
                // work; this is recovery only, not a fabricated successful execution path.
                val recovery = LearningJobCoordinator(
                    database = opened,
                    processSessionId = processSessionId,
                    clock = LearningJobClock { clock().coerceAtLeast(0L) },
                ).recoverOnStartup()
                when (recovery) {
                    is LearningJobStartupRecoveryResult.ClockRollback -> {
                        diagnosticsStore?.record(
                            LearningDiagnosticSample(
                                recordedAtMs = nowMs,
                                code = LearningDiagnosticCode.JOB_RETRY,
                                state = LearningDiagnosticState.CLOCK_ROLLBACK,
                            ),
                        )
                        throw LearningJobClockRollbackException()
                    }

                    is LearningJobStartupRecoveryResult.Recovered -> {
                        val lostLeases = recovery.otherProcessSessions + recovery.expiredLeases
                        if (lostLeases > 0) {
                            diagnosticsStore?.record(
                                LearningDiagnosticSample(
                                    recordedAtMs = nowMs,
                                    code = LearningDiagnosticCode.LEASE_LOST,
                                    state = LearningDiagnosticState.RETRY,
                                    primaryValue = lostLeases.toLong(),
                                ),
                            )
                        }
                        if (recovery.exhaustedAttempts > 0) {
                            diagnosticsStore?.record(
                                LearningDiagnosticSample(
                                    recordedAtMs = nowMs,
                                    code = LearningDiagnosticCode.DEAD_LETTER,
                                    state = LearningDiagnosticState.DEAD_LETTER,
                                    primaryValue = recovery.exhaustedAttempts.toLong(),
                                ),
                            )
                        }
                    }
                }
                initializer.initialize(opened, runtimeGeneration.get(), nowMs)
                val p1Dependencies = p1RuntimeDependencyFactory.create(opened)
                initializedJobHandlerRegistry = jobHandlerRegistry ?: P1LearningRuntimeBindings
                    .createRegistry(opened, p1Dependencies)
                initializedP1CatchUp = p1Dependencies.catchUp
                initializedDatabase = opened
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SQLiteException) {
                markFatalLocked(nowMs)
                return@withLock LearningRuntimeAccess.Unavailable(
                    LearningRuntimeErrorCode.DATABASE_OPERATION_FAILED,
                )
            }
            // Lost leases, checkpoint contention and programming errors are not evidence that the
            // derived database is corrupt. Let their typed/domain failure reach the caller instead
            // of closing the database and entering an expensive retry loop.
        }
        if (restoreLatched.get()) {
            publishLatchedRestoreState()
            return@withLock LearningRuntimeAccess.Unavailable(
                latchedRestoreErrorCode(),
            )
        }
        val sessionGeneration = runtimeGeneration.get()
        mutableState.value = LearningRuntimeState.READY
        if (restoreLatched.get()) {
            publishLatchedRestoreState()
            return@withLock LearningRuntimeAccess.Unavailable(
                latchedRestoreErrorCode(),
            )
        }
        val session = LearningRuntimeSession(
            generation = sessionGeneration,
            currentGeneration = runtimeGeneration::get,
            restoreLatched = restoreLatched::get,
        )
        try {
            coroutineScope { operation(session) }
            if (session.isCurrent()) {
                LearningRuntimeAccess.Ready
            } else {
                publishLatchedRestoreState()
                LearningRuntimeAccess.Unavailable(latchedRestoreErrorCode())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            val failureNowMs = try {
                clock().coerceAtLeast(nowMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                nowMs
            }
            markFatalLocked(failureNowMs)
            LearningRuntimeAccess.Unavailable(
                if (restoreLatched.get()) {
                    latchedRestoreErrorCode()
                } else {
                    LearningRuntimeErrorCode.DATABASE_OPERATION_FAILED
                },
            )
        } finally {
            session.expire()
        }
        // Domain conflicts and programmer errors deliberately propagate. They are not corruption.
    }

    /** Stops new work, waits for the current operation, then permanently fences this process. */
    suspend fun beginRestore(): Long {
        val nextGeneration = establishRestoreFence()
        // Establish the process fence before waiting for the currently structured operation.
        // New entrants now fail closed even while the restore caller is queued on [mutex].
        publishLatchedRestoreState()
        return mutex.withLock {
            closeLocked()
            nextGeneration
        }
    }

    /** A successful or failed main-database restore still requires process restart. */
    suspend fun remainClosedAfterRestore() {
        establishRestoreFence()
        if (latchedRestoreState != LearningRuntimeState.DEGRADED) {
            latchedRestoreState = LearningRuntimeState.RESTORING
        }
        publishLatchedRestoreState()
        mutex.withLock {
            closeLocked()
            publishLatchedRestoreState()
        }
    }

    /** Irreversible restore failure: remain fenced and visibly degraded until process restart. */
    suspend fun remainDegradedAfterRestore() {
        establishRestoreFence()
        latchedRestoreState = LearningRuntimeState.DEGRADED
        publishLatchedRestoreState()
        mutex.withLock {
            closeLocked()
            publishLatchedRestoreState()
        }
    }

    suspend fun close() {
        mutex.withLock {
            closeLocked()
            mutableState.value = if (restoreLatched.get()) {
                latchedRestoreState
            } else {
                runtimeGeneration.incrementAndGet()
                LearningRuntimeState.CLOSED
            }
        }
    }

    /**
     * User-confirmed exact-scope erasure under the same mutex that fences maintenance and restore.
     * This path intentionally works while rollout flags are disabled, so turning Learning off can
     * never make already-derived data impossible to erase. No Room object escapes this call.
     */
    suspend fun eraseDerivedScope(
        scope: LearningScope,
        frozenNowMs: Long,
        ephemeralEraser: LearningEphemeralScopeEraser,
    ): LearningScopeEraseResult = mutex.withLock {
        require(frozenNowMs >= 0L)
        if (restoreLatched.get()) {
            throw LearningDerivedEraseUnavailableException(
                LearningDerivedEraseFailureCode.RESTORE_IN_PROGRESS,
            )
        }
        val mainProcess = try {
            isMainProcess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!mainProcess) {
            throw LearningDerivedEraseUnavailableException(
                LearningDerivedEraseFailureCode.WRONG_PROCESS,
            )
        }
        val published = database
        val opened = published ?: try {
            newDatabaseCandidate().also { candidate ->
                try {
                    candidate.openHelper.writableDatabase
                } catch (cancelled: CancellationException) {
                    closeCandidateBestEffort(candidate)
                    throw cancelled
                } catch (failure: Exception) {
                    closeCandidateBestEffort(candidate)
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw LearningDerivedEraseUnavailableException(
                LearningDerivedEraseFailureCode.DATABASE_OPEN_FAILED,
            )
        }
        val temporary = published == null
        try {
            if (!ephemeralEraser.clearForScope(scope)) {
                throw LearningDerivedEraseUnavailableException(
                    LearningDerivedEraseFailureCode.DATABASE_OPERATION_FAILED,
                )
            }
            LearningDerivedDataEraseStore(opened).eraseScope(scope, frozenNowMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (typed: LearningDerivedEraseUnavailableException) {
            throw typed
        } catch (_: Exception) {
            if (!temporary) markFatalLocked(frozenNowMs)
            throw LearningDerivedEraseUnavailableException(
                LearningDerivedEraseFailureCode.DATABASE_OPERATION_FAILED,
            )
        } finally {
            if (temporary) closeCandidateBestEffort(opened)
        }
    }

    private fun markFatalLocked(nowMs: Long) {
        closeLocked()
        if (restoreLatched.get()) {
            publishLatchedRestoreState()
            return
        }
        runtimeGeneration.incrementAndGet()
        nextOpenAttemptAtMs = try {
            Math.addExact(nowMs, retryBackoffMs)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        mutableState.value = LearningRuntimeState.DEGRADED
    }

    private fun closeLocked() {
        val closing = database
        database = null
        initializedDatabase = null
        initializedJobHandlerRegistry = null
        initializedP1CatchUp = NoOpP1DerivedJobCatchUp
        try {
            closing?.close()
        } catch (_: Exception) {
            // Derived-state shutdown is best effort; the generation/restore fence is authoritative.
        }
    }

    private fun closeCandidateBestEffort(candidate: LearningDatabase) {
        try {
            candidate.close()
        } catch (_: Exception) {
            // The candidate was never published; its original open failure remains authoritative.
        }
    }

    private fun newDatabaseCandidate(): LearningDatabase = Room.databaseBuilder(
        applicationContext,
        LearningDatabase::class.java,
        LearningDatabase.FILE_NAME,
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .also { builder ->
            // The `simple` tokenizer is provided by the bundled SQLite build. Framework-SQLite
            // fixtures intentionally omit the derived FTS projection instead of failing DB open.
            sqliteOpenHelperFactory?.let { factory ->
                builder.openHelperFactory(factory)
                builder.addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            ensurePolicyFtsSchema(db)
                        }
                    },
                )
            }
        }
        .addMigrations(LEARNING_MIGRATION_1_2, LEARNING_MIGRATION_2_3)
        .build()

    private fun publishLatchedRestoreState() {
        mutableState.value = latchedRestoreState
    }

    private fun establishRestoreFence(): Long = synchronized(restoreFenceLock) {
        if (restoreLatched.compareAndSet(false, true)) {
            latchedRestoreState = LearningRuntimeState.RESTORING
            runtimeGeneration.incrementAndGet()
        } else {
            runtimeGeneration.get()
        }
    }

    private fun latchedRestoreErrorCode(): LearningRuntimeErrorCode =
        if (latchedRestoreState == LearningRuntimeState.DEGRADED) {
            LearningRuntimeErrorCode.RESTORE_FAILED_RESTART_REQUIRED
        } else {
            LearningRuntimeErrorCode.RESTORE_IN_PROGRESS
        }
}

private suspend fun recordMaintenanceHealthBestEffort(
    database: LearningDatabase,
    outboxReader: LearningOutboxReader,
    store: LearningDiagnosticsStore,
    recordedAtMs: Long,
) {
    try {
        val descriptor = outboxReader.inspect()
        val checkpoint = database.checkpointDao().listAll().singleOrNull()
        val lag = checkpoint?.takeIf { it.streamId == descriptor.streamId.toString() }
            ?.let { (descriptor.headSequence - it.lastContiguousSeq).coerceAtLeast(0L) }
            ?: descriptor.headSequence.coerceAtLeast(0L)
        store.record(
            LearningDiagnosticSample(
                recordedAtMs = recordedAtMs,
                code = LearningDiagnosticCode.OUTBOX_BACKLOG,
                state = if (lag == 0L) LearningDiagnosticState.IDLE else LearningDiagnosticState.RETRY,
                primaryValue = lag,
            ),
        )
        store.record(
            LearningDiagnosticSample(
                recordedAtMs = recordedAtMs,
                code = LearningDiagnosticCode.CHECKPOINT_LAG,
                state = if (lag == 0L) LearningDiagnosticState.READY else LearningDiagnosticState.REQUIRED,
                primaryValue = lag,
            ),
        )
        val jobDao = database.jobDao()
        val active = jobDao.countActive()
        val retries = jobDao.countRetry()
        val deadLetters = jobDao.countDeadLetter()
        if (retries > 0L) {
            store.record(
                LearningDiagnosticSample(
                    recordedAtMs = recordedAtMs,
                    code = LearningDiagnosticCode.JOB_RETRY,
                    state = LearningDiagnosticState.RETRY,
                    primaryValue = retries,
                ),
            )
        }
        store.record(
            LearningDiagnosticSample(
                recordedAtMs = recordedAtMs,
                code = LearningDiagnosticCode.JOB_STATE,
                state = if (active == 0L) LearningDiagnosticState.IDLE else LearningDiagnosticState.RUNNING,
                primaryValue = active,
                secondaryValue = retries,
            ),
        )
        if (deadLetters > 0L) {
            store.record(
                LearningDiagnosticSample(
                    recordedAtMs = recordedAtMs,
                    code = LearningDiagnosticCode.DEAD_LETTER,
                    state = LearningDiagnosticState.DEAD_LETTER,
                    primaryValue = deadLetters,
                ),
            )
        }
        store.record(
            LearningDiagnosticSample(
                recordedAtMs = recordedAtMs,
                code = LearningDiagnosticCode.DATABASE_STATE,
                state = LearningDiagnosticState.READY,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Health collection is intentionally non-authoritative and never changes drain outcome.
    }
}

private fun LearningDrainResult.withP1Maintenance(
    result: P1DerivedJobCatchUpResult,
): LearningDrainResult = when {
    result !is P1DerivedJobCatchUpResult.Completed -> this
    this == LearningDrainResult.RETRY || this == LearningDrainResult.DISABLED -> this
    result.workMayRemain -> LearningDrainResult.WORK_REMAINS
    result.didWork && this == LearningDrainResult.IDLE -> LearningDrainResult.DID_WORK
    else -> this
}

/** Narrow adapter used by the restore coordinator; it never exposes Room or a DAO. */
class LearningRuntimeFacadeRestorePort(
    private val facade: LearningRuntimeFacade,
) : LearningRuntimeRestorePort {
    override suspend fun beginIrreversibleRestore(): LearningRestoreRuntimeFence =
        LearningRestoreRuntimeFence(facade.beginRestore())

    override suspend fun remainClosedUntilProcessRestart(fence: LearningRestoreRuntimeFence) {
        requireCurrentFence(fence)
        facade.remainClosedAfterRestore()
    }

    override suspend fun remainDegradedUntilProcessRestart(
        fence: LearningRestoreRuntimeFence,
        reason: LearningRestoreFailureReason,
    ) {
        requireCurrentFence(fence)
        // The allowlisted reason belongs to the restore result/diagnostics, never to this latch.
        facade.remainDegradedAfterRestore()
    }

    private fun requireCurrentFence(fence: LearningRestoreRuntimeFence) {
        check(fence.generation == facade.currentGeneration()) {
            "Restore runtime fence is stale"
        }
    }
}

private fun isCurrentMainProcess(context: Context): Boolean {
    val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        activityManager?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
    }
    return processName == context.packageName
}

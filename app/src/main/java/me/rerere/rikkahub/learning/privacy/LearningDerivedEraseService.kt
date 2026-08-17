package me.rerere.rikkahub.learning.privacy

import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.learning.model.LearningScope

@JvmInline
value class LearningEraseConfirmationToken internal constructor(internal val value: String) {
    override fun toString(): String = "LearningEraseConfirmationToken(<redacted>)"
}

internal interface LearningEraseConfirmationAuthority {
    suspend fun issue(scope: LearningScope, frozenNowMs: Long): LearningEraseConfirmationToken

    suspend fun consume(
        scope: LearningScope,
        token: LearningEraseConfirmationToken,
        frozenNowMs: Long,
    ): Boolean
}

/** Process-local, scope-bound, expiring, single-use authority. Raw strings never authorize erase. */
internal class ProcessLearningEraseConfirmationAuthority(
    private val random: SecureRandom = SecureRandom(),
    private val ttlMs: Long = DEFAULT_CONFIRMATION_TTL_MS,
) : LearningEraseConfirmationAuthority {
    private data class Grant(val scope: LearningScope, val expiresAtMs: Long)

    private val mutex = Mutex()
    private val grants = linkedMapOf<String, Grant>()

    init {
        require(ttlMs in 1_000L..MAX_CONFIRMATION_TTL_MS)
    }

    override suspend fun issue(
        scope: LearningScope,
        frozenNowMs: Long,
    ): LearningEraseConfirmationToken = mutex.withLock {
        require(frozenNowMs >= 0L)
        grants.entries.removeAll { it.value.expiresAtMs < frozenNowMs }
        val bytes = ByteArray(32)
        var raw: String
        do {
            random.nextBytes(bytes)
            raw = bytes.joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        } while (raw in grants)
        grants[raw] = Grant(
            scope = scope,
            expiresAtMs = Math.addExact(frozenNowMs, ttlMs),
        )
        bytes.fill(0)
        LearningEraseConfirmationToken(raw)
    }

    override suspend fun consume(
        scope: LearningScope,
        token: LearningEraseConfirmationToken,
        frozenNowMs: Long,
    ): Boolean = mutex.withLock {
        if (frozenNowMs < 0L) return@withLock false
        val grant = grants.remove(token.value) ?: return@withLock false
        grant.scope == scope && frozenNowMs <= grant.expiresAtMs
    }
}

data class LearningEraseReceipt(
    val erasedEpisodes: Int,
    val erasedTraceFeatures: Int,
    val erasedLessons: Int,
    val erasedRewards: Int,
    val erasedPolicies: Int,
    val retainedAuditTombstones: Int,
    val erasedSourceValidityRows: Int = 0,
    val erasedJobs: Int = 0,
    val erasedInboxEvents: Int = 0,
    val erasedPolicyExposures: Int = 0,
    val erasedPolicyShadowObservations: Int = 0,
    /** Existing AppDatabase LEARNED definitions redacted; fence-only claims are not counted. */
    val erasedMainDatabaseWorkflows: Int = 0,
    val erasedObservedUtilityEvaluationReceipts: Int = 0,
    val erasedObservedUtilityAssignments: Int = 0,
    val erasedProviderConfigCohorts: Int = 0,
) {
    init {
        require(
            listOf(
                erasedEpisodes,
                erasedTraceFeatures,
                erasedLessons,
                erasedRewards,
                erasedPolicies,
                retainedAuditTombstones,
                erasedSourceValidityRows,
                erasedJobs,
                erasedInboxEvents,
                erasedPolicyExposures,
                erasedPolicyShadowObservations,
                erasedMainDatabaseWorkflows,
                erasedObservedUtilityEvaluationReceipts,
                erasedObservedUtilityAssignments,
                erasedProviderConfigCohorts,
            ).all { it >= 0 },
        )
    }
}

enum class LearningDerivedEraseFailureCode {
    CONFIRMATION_INVALID,
    EPHEMERAL_CLEAR_FAILED,
    WRONG_PROCESS,
    RESTORE_IN_PROGRESS,
    DATABASE_OPEN_FAILED,
    DATABASE_OPERATION_FAILED,
}

class LearningDerivedEraseUnavailableException(
    val failureCode: LearningDerivedEraseFailureCode,
) : IllegalStateException("learning_derived_erase_${failureCode.name.lowercase()}")

/** Storage implementation must erase the exact scope in one LearningDatabase transaction. */
fun interface LearningDerivedEraseStore {
    suspend fun eraseScope(
        scope: LearningScope,
        frozenNowMs: Long,
    ): LearningEraseReceipt
}

fun interface LearningEphemeralSnapshotHandle {
    /** Return false if any matching snapshot could not be synchronously cleared. */
    fun clearForScope(scope: LearningScope): Boolean
}

fun interface LearningEphemeralScopeEraser {
    /** Invoked while the runtime lifecycle mutex is held, before the exact-scope DB transaction. */
    fun clearForScope(scope: LearningScope): Boolean
}

class LearningEphemeralScopeRegistry internal constructor() : LearningEphemeralScopeEraser {
    private val lock = Any()
    private val handles = linkedSetOf<LearningEphemeralSnapshotHandle>()

    fun register(handle: LearningEphemeralSnapshotHandle): AutoCloseable = synchronized(lock) {
        handles += handle
        AutoCloseable { synchronized(lock) { handles -= handle } }
    }

    override fun clearForScope(scope: LearningScope): Boolean {
        val snapshot = synchronized(lock) { handles.toList() }
        var allCleared = true
        snapshot.forEach { handle ->
            val cleared = try {
                handle.clearForScope(scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!cleared) allCleared = false
        }
        return allCleared
    }
}

/**
 * User-only destructive operation. It is intentionally not a Tool and requires a confirmation
 * token issued by the settings UI immediately before execution.
 */
class LearningDerivedEraseService internal constructor(
    private val store: LearningDerivedEraseStore,
    private val confirmationAuthority: LearningEraseConfirmationAuthority =
        ProcessLearningEraseConfirmationAuthority(),
    private val ephemeralRegistry: LearningEphemeralScopeRegistry =
        LearningEphemeralScopeRegistry(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val operationMutex = Mutex()

    suspend fun issueConfirmation(scope: LearningScope): LearningEraseConfirmationToken =
        confirmationAuthority.issue(scope, clockMs().coerceAtLeast(0L))

    fun registerEphemeralHandle(handle: LearningEphemeralSnapshotHandle): AutoCloseable =
        ephemeralRegistry.register(handle)

    private suspend fun requireConfirmation(
        scope: LearningScope,
        token: LearningEraseConfirmationToken,
        frozenNowMs: Long,
    ) {
        if (!confirmationAuthority.consume(scope, token, frozenNowMs)) {
            throw LearningDerivedEraseUnavailableException(
                LearningDerivedEraseFailureCode.CONFIRMATION_INVALID,
            )
        }
    }

    suspend fun eraseConfirmed(
        scope: LearningScope,
        confirmationToken: LearningEraseConfirmationToken,
    ): LearningEraseReceipt = operationMutex.withLock {
        val frozenNowMs = clockMs().coerceAtLeast(0L)
        requireConfirmation(scope, confirmationToken, frozenNowMs)
        if (!ephemeralRegistry.clearForScope(scope)) {
            throw LearningDerivedEraseUnavailableException(
                LearningDerivedEraseFailureCode.EPHEMERAL_CLEAR_FAILED,
            )
        }
        store.eraseScope(scope, frozenNowMs)
    }

}

private const val DEFAULT_CONFIRMATION_TTL_MS = 2L * 60L * 1_000L
private const val MAX_CONFIRMATION_TTL_MS = 10L * 60L * 1_000L

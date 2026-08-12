package me.rerere.rikkahub.learning.provenance

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceRef
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

private const val MAX_SOURCE_SNAPSHOT_CHARS = 8_000
private const val MAX_SOURCE_SNAPSHOT_TTL_MS = 5L * 60L * 1_000L

data class LearningSourceSnapshotRequest(
    val source: LearningSourceRef,
    val expectedScope: LearningScope,
    val maxChars: Int,
    val frozenNowMs: Long,
    val expiresAtMs: Long,
) {
    init {
        require(source.scope == expectedScope) { "Cross-scope learning source read" }
        require(maxChars in 1..MAX_SOURCE_SNAPSHOT_CHARS) { "Unsafe source snapshot size" }
        require(frozenNowMs >= 0L) { "Negative source snapshot clock" }
        require(expiresAtMs >= frozenNowMs) { "Source snapshot already expired" }
        require(expiresAtMs - frozenNowMs <= MAX_SOURCE_SNAPSHOT_TTL_MS) {
            "Source snapshot lifetime is too long"
        }
    }

    override fun toString(): String =
        "LearningSourceSnapshotRequest(maxChars=$maxChars, frozenNowMs=$frozenNowMs, " +
            "expiresAtMs=$expiresAtMs, source=<redacted>, scope=<redacted>)"
}

enum class LearningSourceReadFailure {
    NOT_FOUND,
    SCOPE_MISMATCH,
    REVISION_MISMATCH,
    TOMBSTONED,
    REVISION_UNKNOWN,
    TOO_LARGE,
    EXPIRED,
    CLOCK_ROLLBACK,
    SNAPSHOT_MISMATCH,
    UNAVAILABLE,
}

sealed interface LearningSourceSnapshotResult {
    data class Available(val snapshot: LearningEphemeralSourceSnapshot) : LearningSourceSnapshotResult

    data class Unavailable(val reason: LearningSourceReadFailure) : LearningSourceSnapshotResult
}

interface LearningSourceSnapshotResolver {
    /** Implementations validate scope, revision and tombstone in the authority read transaction. */
    suspend fun resolve(request: LearningSourceSnapshotRequest): LearningSourceSnapshotResult

    /** Rechecks the same authority immediately before provider call and before local commit. */
    suspend fun revalidate(source: LearningSourceRef): LearningSourceReadFailure?
}

/**
 * Production-safe default while no authority-specific resolver has been explicitly wired.
 *
 * It never reaches into Conversation, Dreaming, Memory, or any other authority store. Keeping the
 * default as an explicit fail-closed adapter prevents feature-flag or DI mistakes from turning a
 * missing integration into an unscoped source read.
 */
object NoOpLearningSourceSnapshotResolver : LearningSourceSnapshotResolver {
    override suspend fun resolve(
        request: LearningSourceSnapshotRequest,
    ): LearningSourceSnapshotResult = LearningSourceSnapshotResult.Unavailable(
        LearningSourceReadFailure.UNAVAILABLE,
    )

    override suspend fun revalidate(source: LearningSourceRef): LearningSourceReadFailure =
        LearningSourceReadFailure.UNAVAILABLE
}

class LearningEphemeralSourceSnapshot internal constructor(
    private val source: LearningSourceRef,
    val alias: String,
    text: CharArray,
    internal val expiresAtMs: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    /** Ownership is transferred to this object; the resolver must not retain [text]. */
    private val characters = text

    init {
        require(alias.matches(Regex("E[1-9][0-9]{0,2}"))) { "Invalid evidence alias" }
        require(characters.size <= MAX_SOURCE_SNAPSHOT_CHARS) { "Source snapshot is too large" }
        require(expiresAtMs >= 0L) { "Negative source snapshot expiry" }
    }

    internal val characterCount: Int
        get() = characters.size

    internal fun matches(request: LearningSourceSnapshotRequest): Boolean =
        source == request.source && source.scope == request.expectedScope

    internal suspend fun revalidateWith(
        resolver: LearningSourceSnapshotResolver,
    ): LearningSourceReadFailure? = resolver.revalidate(source)

    fun useText(block: (String) -> Unit) {
        check(!closed.get()) { "Source snapshot was closed" }
        block(String(characters))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) characters.fill('\u0000')
    }

    internal fun isClearedForTest(): Boolean = characters.all { it == '\u0000' }

    override fun toString(): String =
        "LearningEphemeralSourceSnapshot(alias=$alias, chars=${characters.size}, source=<redacted>)"
}

class LearningSourceSnapshotGuard(
    private val resolver: LearningSourceSnapshotResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs source-dependent derivation and its durable commit as two distinct phases.
     *
     * The snapshot is available only to [derive]. It is wiped before the second clock and
     * authority check, and [commit] is unreachable unless those checks still pass. Callers must
     * not persist inside [derive]; all durable output belongs in [commit].
     */
    suspend fun withValidatedSnapshot(
        request: LearningSourceSnapshotRequest,
        derive: suspend (LearningEphemeralSourceSnapshot) -> Unit,
        commit: suspend () -> Unit,
    ): LearningGuardedSourceResult {
        val resolved = try {
            resolver.resolve(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.UNAVAILABLE)
        }
        val snapshot = (resolved as? LearningSourceSnapshotResult.Available)?.snapshot
            ?: return LearningGuardedSourceResult.Unavailable(
                (resolved as LearningSourceSnapshotResult.Unavailable).reason,
            )
        try {
            if (
                !snapshot.matches(request) ||
                snapshot.expiresAtMs > request.expiresAtMs
            ) {
                return LearningGuardedSourceResult.Unavailable(
                    LearningSourceReadFailure.SNAPSHOT_MISMATCH,
                )
            }
            if (snapshot.characterCount > request.maxChars) {
                return LearningGuardedSourceResult.Unavailable(
                    LearningSourceReadFailure.TOO_LARGE,
                )
            }
            val beforeProviderNowMs = readClockOrNull()
                ?: return LearningGuardedSourceResult.Unavailable(
                    LearningSourceReadFailure.UNAVAILABLE,
                )
            if (beforeProviderNowMs < request.frozenNowMs) {
                return LearningGuardedSourceResult.Unavailable(
                    LearningSourceReadFailure.CLOCK_ROLLBACK,
                )
            }
            if (beforeProviderNowMs >= snapshot.expiresAtMs) {
                return LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.EXPIRED)
            }
            revalidateOrUnavailable(snapshot)?.let { reason ->
                return LearningGuardedSourceResult.Unavailable(reason)
            }
            derive(snapshot)
            // The authority text is not needed for validation or commit. Wipe it before either.
            snapshot.close()
            val beforeCommitNowMs = readClockOrNull()
                ?: return LearningGuardedSourceResult.Unavailable(
                    LearningSourceReadFailure.UNAVAILABLE,
                )
            if (beforeCommitNowMs < beforeProviderNowMs) {
                return LearningGuardedSourceResult.Unavailable(
                    LearningSourceReadFailure.CLOCK_ROLLBACK,
                )
            }
            if (beforeCommitNowMs >= snapshot.expiresAtMs) {
                return LearningGuardedSourceResult.Unavailable(LearningSourceReadFailure.EXPIRED)
            }
            revalidateOrUnavailable(snapshot)?.let { reason ->
                return LearningGuardedSourceResult.Unavailable(reason)
            }
            commit()
            return LearningGuardedSourceResult.Completed
        } finally {
            snapshot.close()
        }
    }

    private fun readClockOrNull(): Long? = try {
        clock()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun revalidateOrUnavailable(
        snapshot: LearningEphemeralSourceSnapshot,
    ): LearningSourceReadFailure? = try {
        snapshot.revalidateWith(resolver)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LearningSourceReadFailure.UNAVAILABLE
    }
}

sealed interface LearningGuardedSourceResult {
    data object Completed : LearningGuardedSourceResult

    data class Unavailable(val reason: LearningSourceReadFailure) : LearningGuardedSourceResult
}

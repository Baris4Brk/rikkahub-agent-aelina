package me.rerere.rikkahub.data.ai

import java.util.concurrent.CancellationException
import java.util.ArrayDeque
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.runtime.ABSOLUTE_DREAM_RUNTIME_MAX_CHARS
import me.rerere.rikkahub.memory.dreaming.runtime.ABSOLUTE_DREAM_RUNTIME_MAX_CLAIMS
import me.rerere.rikkahub.memory.dreaming.runtime.ABSOLUTE_DREAM_RUNTIME_MAX_TOKENS
import me.rerere.rikkahub.memory.dreaming.runtime.ABSOLUTE_DREAM_RUNTIME_MAX_UTF8_BYTES
import me.rerere.rikkahub.memory.dreaming.runtime.DisabledDreamingFeatureFlagSource
import me.rerere.rikkahub.memory.dreaming.runtime.DreamContextCompileRequest
import me.rerere.rikkahub.memory.dreaming.runtime.DreamContextCompileResult
import me.rerere.rikkahub.memory.dreaming.runtime.DreamContextCompiler
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeCompileLimits
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeCompileStatus
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeHardBoundStatus
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeDropReason
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeTokenEstimator
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjection
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionReadRequest
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionReader
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionUnavailableReason
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingFeatureFlagSource

internal const val MAX_DREAM_GENERATION_CONTEXT_TOKENS = 1_024
internal const val MAX_DREAM_GENERATION_CONTEXT_CHARS = 8 * 1_024
internal const val MAX_DREAM_GENERATION_CONTEXT_UTF8_BYTES = 24 * 1_024
internal const val MAX_DREAM_GENERATION_CONTEXT_CLAIMS = 8

enum class DreamGenerationContextStatus {
    FEATURE_DISABLED,
    NO_TRUSTED_BUDGET,
    PROJECTION_UNAVAILABLE,
    SNAPSHOT_REJECTED,
    EMPTY,
    COMPILED,
    LOCAL_FAILURE,
}

/**
 * One provider-call projection. Claim refs remain process-local and are exposed only to the
 * provider-bound usage seam; aggregate diagnostics must use [diagnostic] instead.
 */
data class DreamGenerationContext(
    val status: DreamGenerationContextStatus,
    val compileResult: DreamContextCompileResult?,
) {
    val renderedSection: String
        get() = compileResult
            ?.takeIf { it.status == DreamRuntimeCompileStatus.COMPILED }
            ?.renderedSection
            .orEmpty()

    val isCompiled: Boolean
        get() = renderedSection.isNotEmpty()

    val diagnostic: DreamRuntimeRequestDiagnostic
        get() {
            val compiled = compileResult
            return DreamRuntimeRequestDiagnostic(
                status = status,
                compileStatus = compiled?.status,
                hardBoundStatus = compiled?.hardBoundStatus,
                actualClaimCount = compiled?.actualClaimCount ?: 0,
                estimatedTokens = compiled?.estimatedTokens ?: 0,
                dropReasonCounts = compiled?.dropped
                    ?.groupingBy { it.reason.name }
                    ?.eachCount()
                    .orEmpty(),
                compilerRevision = compiled?.compilerRevision,
                presentOnFinalWire = false,
                finalHardGatePassed = false,
            )
        }
}

/** Privacy-safe aggregate only: no scope, Snapshot/Claim IDs, statements, or hashes. */
data class DreamRuntimeRequestDiagnostic(
    val status: DreamGenerationContextStatus,
    val compileStatus: DreamRuntimeCompileStatus?,
    val hardBoundStatus: DreamRuntimeHardBoundStatus?,
    val actualClaimCount: Int,
    val estimatedTokens: Int,
    val dropReasonCounts: Map<String, Int>,
    val compilerRevision: String?,
    val presentOnFinalWire: Boolean,
    val finalHardGatePassed: Boolean,
)

fun interface DreamRuntimeDiagnosticsSink {
    fun record(diagnostic: DreamRuntimeRequestDiagnostic)
}

object NoOpDreamRuntimeDiagnosticsSink : DreamRuntimeDiagnosticsSink {
    override fun record(diagnostic: DreamRuntimeRequestDiagnostic) = Unit
}

/**
 * Explicit provider-bound usage seam. There is no Dream lastAccess column to impersonate: a
 * production implementation may update a dedicated usage table, while the default records
 * nothing. Implementations must not persist raw refs in general request diagnostics.
 */
fun interface DreamRuntimeUsageRecorder {
    suspend fun record(request: DreamRuntimeUsageRequest)
}

data class DreamRuntimeUsageRequest(
    val scopeId: DreamScopeId,
    val frozenNowEpochMs: Long,
    val actualClaimRefs: List<me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeClaimRef>,
    val compilerRevision: String,
    val isProviderRetry: Boolean,
) {
    init {
        require(frozenNowEpochMs >= 0L)
        require(actualClaimRefs.isNotEmpty())
        require(actualClaimRefs == actualClaimRefs.distinct())
        require(compilerRevision.isNotBlank())
    }
}

object NoOpDreamRuntimeUsageRecorder : DreamRuntimeUsageRecorder {
    override suspend fun record(request: DreamRuntimeUsageRequest) = Unit
}

object UnavailableDreamSnapshotProjectionReader : DreamSnapshotProjectionReader {
    override suspend fun read(
        request: DreamSnapshotProjectionReadRequest,
    ): DreamSnapshotProjection = DreamSnapshotProjection.Unavailable(
        DreamSnapshotProjectionUnavailableReason.FEATURE_NOT_READY,
    )
}

/**
 * Fail-closed orchestration kept outside the Android handler so all read-before-use invariants are
 * JVM-testable. In particular, `use=false` returns before [projectionReader] is touched.
 */
class DreamGenerationContextPlanner(
    private val featureFlags: DreamingFeatureFlagSource = DisabledDreamingFeatureFlagSource,
    private val projectionReader: DreamSnapshotProjectionReader =
        UnavailableDreamSnapshotProjectionReader,
) {
    suspend fun prepare(
        scopeId: DreamScopeId,
        frozenNowEpochMs: Long,
        trustedTokenBudget: Int,
        tokenEstimator: DreamRuntimeTokenEstimator,
    ): DreamGenerationContext {
        val flags = try {
            featureFlags.flagsFor(scopeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DreamGenerationContext(DreamGenerationContextStatus.LOCAL_FAILURE, null)
        }
        if (!flags.schemaReady || !flags.use || flags.shadow) {
            return DreamGenerationContext(DreamGenerationContextStatus.FEATURE_DISABLED, null)
        }

        val maxTokens = trustedTokenBudget
            .coerceAtMost(MAX_DREAM_GENERATION_CONTEXT_TOKENS)
            .coerceAtMost(ABSOLUTE_DREAM_RUNTIME_MAX_TOKENS)
        if (maxTokens <= 0) {
            return DreamGenerationContext(DreamGenerationContextStatus.NO_TRUSTED_BUDGET, null)
        }

        val projection = try {
            projectionReader.read(
                DreamSnapshotProjectionReadRequest(
                    scopeId = scopeId,
                    frozenNowEpochMs = frozenNowEpochMs,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DreamGenerationContext(DreamGenerationContextStatus.LOCAL_FAILURE, null)
        }

        val compiled = try {
            DreamContextCompiler.compile(
                DreamContextCompileRequest(
                    useDreams = true,
                    expectedScopeId = scopeId,
                    projection = projection,
                    frozenNowEpochMs = frozenNowEpochMs,
                    limits = DreamRuntimeCompileLimits(
                        maxTokens = maxTokens,
                        maxChars = minOf(
                            MAX_DREAM_GENERATION_CONTEXT_CHARS,
                            ABSOLUTE_DREAM_RUNTIME_MAX_CHARS,
                        ),
                        maxUtf8Bytes = minOf(
                            MAX_DREAM_GENERATION_CONTEXT_UTF8_BYTES,
                            ABSOLUTE_DREAM_RUNTIME_MAX_UTF8_BYTES,
                        ),
                        maxClaims = minOf(
                            MAX_DREAM_GENERATION_CONTEXT_CLAIMS,
                            ABSOLUTE_DREAM_RUNTIME_MAX_CLAIMS,
                        ),
                    ),
                    tokenEstimator = tokenEstimator,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return DreamGenerationContext(DreamGenerationContextStatus.LOCAL_FAILURE, null)
        }

        val status = when (compiled.status) {
            DreamRuntimeCompileStatus.COMPILED -> DreamGenerationContextStatus.COMPILED
            DreamRuntimeCompileStatus.SNAPSHOT_REJECTED ->
                DreamGenerationContextStatus.SNAPSHOT_REJECTED
            DreamRuntimeCompileStatus.EMPTY -> DreamGenerationContextStatus.EMPTY
            DreamRuntimeCompileStatus.DISABLED -> DreamGenerationContextStatus.FEATURE_DISABLED
            DreamRuntimeCompileStatus.INVALID_REQUEST,
            DreamRuntimeCompileStatus.TOKEN_ESTIMATOR_FAILED,
            -> DreamGenerationContextStatus.LOCAL_FAILURE
        }
        return DreamGenerationContext(status, compiled)
    }
}

class DreamFinalWireIntegrityException : IllegalStateException(
    "Compiled Dream runtime context was not preserved by the final provider hard gate",
)

/**
 * Confirms that a compiled section survived as one intact text value after every transformer and
 * the final context gate. Empty/non-compiled results intentionally impose no wire requirement.
 */
internal fun DreamGenerationContext.requirePresentOnFinalWire(
    messages: List<UIMessage>,
): Boolean {
    val section = renderedSection
    if (section.isEmpty()) return false
    val present = messages.asSequence()
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<UIMessagePart.Text>()
        .any { section in it.text }
    if (!present) throw DreamFinalWireIntegrityException()
    return true
}

internal fun DreamRuntimeDiagnosticsSink.recordSafely(
    diagnostic: DreamRuntimeRequestDiagnostic,
) {
    try {
        record(diagnostic)
    } catch (_: Exception) {
        // Diagnostics are an optional sidecar and cannot affect provider dispatch.
    }
}

const val DEFAULT_DREAM_RUNTIME_TELEMETRY_CAPACITY = 128
const val MAX_DREAM_RUNTIME_TELEMETRY_CAPACITY = 1_024

enum class DreamRuntimeAggregateEventKind {
    COMPILE_DIAGNOSTIC,
    PROVIDER_USAGE,
}

enum class DreamRuntimeProviderAttemptKind {
    PRIMARY,
    RETRY,
}

enum class DreamRuntimeFinalWireStatus {
    ABSENT,
    PRESENT,
}

enum class DreamRuntimeFinalHardGateStatus {
    NOT_PASSED,
    PASSED,
}

/**
 * One privacy-safe, bounded-ring entry. Every property is an enum, boolean, or non-negative
 * aggregate count. It deliberately has no timestamp, scope dimension, identifier, hash, revision,
 * title, statement, prompt, or other free-form String.
 */
data class DreamRuntimeAggregateEvent(
    val kind: DreamRuntimeAggregateEventKind,
    val contextStatus: DreamGenerationContextStatus? = null,
    val compileStatus: DreamRuntimeCompileStatus? = null,
    val hardBoundStatus: DreamRuntimeHardBoundStatus? = null,
    val providerAttemptKind: DreamRuntimeProviderAttemptKind? = null,
    val claimCount: Int = 0,
    val estimatedTokens: Int = 0,
    val finalWireStatus: DreamRuntimeFinalWireStatus = DreamRuntimeFinalWireStatus.ABSENT,
    val finalHardGateStatus: DreamRuntimeFinalHardGateStatus =
        DreamRuntimeFinalHardGateStatus.NOT_PASSED,
    val dropReasonCounts: Map<DreamRuntimeDropReason, Int> = emptyMap(),
) {
    init {
        require(claimCount >= 0)
        require(estimatedTokens >= 0)
        require(dropReasonCounts.values.all { it >= 0 })
        require(
            (kind == DreamRuntimeAggregateEventKind.PROVIDER_USAGE) ==
                (providerAttemptKind != null),
        )
    }
}

/** Immutable read model; its maps have a finite enum keyspace and contain aggregate counts only. */
data class DreamRuntimeTelemetrySnapshot(
    val compileDiagnosticCount: Long,
    val providerUsageCount: Long,
    val compiledClaimCount: Long,
    val dispatchedClaimCount: Long,
    val compiledEstimatedTokens: Long,
    val finalWirePresentCount: Long,
    val finalHardGatePassedCount: Long,
    val providerRetryUsageCount: Long,
    val contextStatusCounts: Map<DreamGenerationContextStatus, Long>,
    val compileStatusCounts: Map<DreamRuntimeCompileStatus, Long>,
    val hardBoundStatusCounts: Map<DreamRuntimeHardBoundStatus, Long>,
    val dropReasonCounts: Map<DreamRuntimeDropReason, Long>,
    val recentEvents: List<DreamRuntimeAggregateEvent>,
)

/**
 * Thread-safe production sidecar for M6 diagnostics and actual-wire usage.
 *
 * [record] receives runtime refs only long enough to read `size`; the request and its scope/refs
 * are never retained. Diagnostics likewise discard compiler revision and unknown reason strings.
 * The recent ring is bounded, while lifetime totals use only finite enum maps and saturating Long
 * counters, so adversarial inputs cannot create unbounded dimensions or wrap counters.
 */
class BoundedDreamRuntimeTelemetryStore(
    private val capacity: Int = DEFAULT_DREAM_RUNTIME_TELEMETRY_CAPACITY,
) : DreamRuntimeDiagnosticsSink, DreamRuntimeUsageRecorder {
    private val lock = Any()
    private val recent = ArrayDeque<DreamRuntimeAggregateEvent>(capacity)
    private var compileDiagnosticCount = 0L
    private var providerUsageCount = 0L
    private var compiledClaimCount = 0L
    private var dispatchedClaimCount = 0L
    private var compiledEstimatedTokens = 0L
    private var finalWirePresentCount = 0L
    private var finalHardGatePassedCount = 0L
    private var providerRetryUsageCount = 0L
    private val contextStatusCounts = mutableMapOf<DreamGenerationContextStatus, Long>()
    private val compileStatusCounts = mutableMapOf<DreamRuntimeCompileStatus, Long>()
    private val hardBoundStatusCounts = mutableMapOf<DreamRuntimeHardBoundStatus, Long>()
    private val dropReasonCounts = mutableMapOf<DreamRuntimeDropReason, Long>()

    init {
        require(capacity in 1..MAX_DREAM_RUNTIME_TELEMETRY_CAPACITY)
    }

    override fun record(diagnostic: DreamRuntimeRequestDiagnostic) {
        val safeClaimCount = diagnostic.actualClaimCount.coerceAtLeast(0)
        val safeEstimatedTokens = diagnostic.estimatedTokens.coerceAtLeast(0)
        val safeDrops = diagnostic.dropReasonCounts.mapNotNull { (rawReason, rawCount) ->
            val reason = DreamRuntimeDropReason.entries.firstOrNull { it.name == rawReason }
                ?: return@mapNotNull null
            reason to rawCount.coerceAtLeast(0)
        }.toMap()
        val event = DreamRuntimeAggregateEvent(
            kind = DreamRuntimeAggregateEventKind.COMPILE_DIAGNOSTIC,
            contextStatus = diagnostic.status,
            compileStatus = diagnostic.compileStatus,
            hardBoundStatus = diagnostic.hardBoundStatus,
            claimCount = safeClaimCount,
            estimatedTokens = safeEstimatedTokens,
            finalWireStatus = if (diagnostic.presentOnFinalWire) {
                DreamRuntimeFinalWireStatus.PRESENT
            } else {
                DreamRuntimeFinalWireStatus.ABSENT
            },
            finalHardGateStatus = if (diagnostic.finalHardGatePassed) {
                DreamRuntimeFinalHardGateStatus.PASSED
            } else {
                DreamRuntimeFinalHardGateStatus.NOT_PASSED
            },
            dropReasonCounts = safeDrops,
        )
        synchronized(lock) {
            compileDiagnosticCount = compileDiagnosticCount.saturatingAdd(1)
            compiledClaimCount = compiledClaimCount.saturatingAdd(safeClaimCount)
            compiledEstimatedTokens = compiledEstimatedTokens.saturatingAdd(safeEstimatedTokens)
            if (diagnostic.presentOnFinalWire) {
                finalWirePresentCount = finalWirePresentCount.saturatingAdd(1)
            }
            if (diagnostic.finalHardGatePassed) {
                finalHardGatePassedCount = finalHardGatePassedCount.saturatingAdd(1)
            }
            contextStatusCounts.increment(diagnostic.status)
            diagnostic.compileStatus?.let { compileStatusCounts.increment(it) }
            diagnostic.hardBoundStatus?.let { hardBoundStatusCounts.increment(it) }
            safeDrops.forEach { (reason, count) -> dropReasonCounts.increment(reason, count) }
            appendBounded(event)
        }
    }

    override suspend fun record(request: DreamRuntimeUsageRequest) {
        // Collapse the sensitive request before entering retained state. Do not retain request,
        // scopeId, Claim refs, compiler revision, or frozen clock.
        val claimCount = request.actualClaimRefs.size
        val attempt = if (request.isProviderRetry) {
            DreamRuntimeProviderAttemptKind.RETRY
        } else {
            DreamRuntimeProviderAttemptKind.PRIMARY
        }
        val event = DreamRuntimeAggregateEvent(
            kind = DreamRuntimeAggregateEventKind.PROVIDER_USAGE,
            providerAttemptKind = attempt,
            claimCount = claimCount,
            finalWireStatus = DreamRuntimeFinalWireStatus.PRESENT,
            finalHardGateStatus = DreamRuntimeFinalHardGateStatus.PASSED,
        )
        synchronized(lock) {
            providerUsageCount = providerUsageCount.saturatingAdd(1)
            dispatchedClaimCount = dispatchedClaimCount.saturatingAdd(claimCount)
            if (attempt == DreamRuntimeProviderAttemptKind.RETRY) {
                providerRetryUsageCount = providerRetryUsageCount.saturatingAdd(1)
            }
            appendBounded(event)
        }
    }

    fun snapshot(): DreamRuntimeTelemetrySnapshot = synchronized(lock) {
        DreamRuntimeTelemetrySnapshot(
            compileDiagnosticCount = compileDiagnosticCount,
            providerUsageCount = providerUsageCount,
            compiledClaimCount = compiledClaimCount,
            dispatchedClaimCount = dispatchedClaimCount,
            compiledEstimatedTokens = compiledEstimatedTokens,
            finalWirePresentCount = finalWirePresentCount,
            finalHardGatePassedCount = finalHardGatePassedCount,
            providerRetryUsageCount = providerRetryUsageCount,
            contextStatusCounts = contextStatusCounts.toMap(),
            compileStatusCounts = compileStatusCounts.toMap(),
            hardBoundStatusCounts = hardBoundStatusCounts.toMap(),
            dropReasonCounts = dropReasonCounts.toMap(),
            recentEvents = recent.toList(),
        )
    }

    private fun appendBounded(event: DreamRuntimeAggregateEvent) {
        if (recent.size == capacity) recent.removeFirst()
        recent.addLast(event)
    }

    private fun <K> MutableMap<K, Long>.increment(key: K, delta: Int = 1) {
        this[key] = getOrDefault(key, 0L).saturatingAdd(delta)
    }
}

private fun Long.saturatingAdd(delta: Int): Long {
    val positiveDelta = delta.coerceAtLeast(0).toLong()
    return if (this > Long.MAX_VALUE - positiveDelta) Long.MAX_VALUE else this + positiveDelta
}

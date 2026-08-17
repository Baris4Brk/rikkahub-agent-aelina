package me.rerere.rikkahub.learning.diagnostics

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.learning.model.LearningScopeKind

private const val LEARNING_DIAGNOSTICS_SCHEMA_VERSION = 1
private const val MAX_LEARNING_DIAGNOSTIC_ENTRIES = 64
private const val MAX_LEARNING_DIAGNOSTICS_FILE_BYTES = 256L * 1_024L
private const val TRACE_PREFIX = "alr_"
private const val TRACE_UNAVAILABLE = "alr_ffffffffffffffffffffffffffffffff"
private val TRACE_PATTERN = Regex("^alr_[0-9a-f]{32}$")
private val secureRandom = SecureRandom()

/** Content-free health categories. No category accepts a free-form message. */
@Serializable
enum class LearningDiagnosticCode {
    OUTBOX_BACKLOG,
    CHECKPOINT_LAG,
    STREAM_RESET,
    BOOTSTRAP_STATE,
    DATABASE_STATE,
    JOB_STATE,
    JOB_RETRY,
    LEASE_LOST,
    DEAD_LETTER,
    WORKER_RUNTIME,
    RESOURCE_YIELD,
    RESTORE_STATE,
    EPISODE_STATE,
    REFLECTION_STATE,
    REWARD_STATE,
    POLICY_CANDIDATE_STATE,
    POLICY_RETRIEVAL_SHADOW,
}

/** Allowlisted state/reason projection shared by the P0 health screen and tests. */
@Serializable
enum class LearningDiagnosticState {
    READY,
    DISABLED,
    DEGRADED,
    RESTORING,
    REQUIRED,
    RUNNING,
    RETRY,
    IDLE,
    DONE,
    DEAD_LETTER,
    NEW_STREAM,
    HEAD_REWIND,
    DERIVED_DATABASE_RECREATED,
    CORRUPTION,
    FOREGROUND_ACTIVE,
    FOREGROUND_REGISTRY_DEGRADED,
    USER_DISABLED,
    POWER_STATE_UNKNOWN,
    BATTERY_SAVER,
    THERMAL_UNKNOWN,
    THERMAL_PRESSURE,
    NETWORK_STATE_UNKNOWN,
    NETWORK_UNAVAILABLE,
    METERED_NETWORK_DENIED,
    CONCURRENCY_LIMIT,
    CANCELLATION_UNPROVEN,
    CONDITIONS_UNAVAILABLE,
    PERMIT_CLOSED,
    CLOCK_ROLLBACK,
    UNKNOWN,
}

/**
 * One deliberately small, non-correlatable health sample.
 *
 * [primaryValue] and [secondaryValue] are counts, milliseconds, or sequence lag only. Stable
 * application IDs, text, hashes and provider/model identities have no field in this contract.
 */
@Serializable
data class LearningDiagnosticSample(
    @SerialName("recorded_at_ms")
    val recordedAtMs: Long,
    val code: LearningDiagnosticCode,
    val state: LearningDiagnosticState,
    @SerialName("scope_kind")
    val scopeKind: LearningScopeKind? = null,
    @SerialName("primary_value")
    val primaryValue: Long? = null,
    @SerialName("secondary_value")
    val secondaryValue: Long? = null,
) {
    init {
        require(recordedAtMs >= 0L) { "Negative diagnostic time" }
        require(primaryValue == null || primaryValue >= 0L) { "Negative diagnostic value" }
        require(secondaryValue == null || secondaryValue >= 0L) { "Negative diagnostic value" }
    }

    override fun toString(): String =
        "LearningDiagnosticSample(code=$code, state=$state, scopeKind=$scopeKind, values=<counts>)"
}

@Serializable
data class LearningDiagnosticEntry internal constructor(
    @SerialName("opaque_trace_id")
    val opaqueTraceId: String,
    val sample: LearningDiagnosticSample,
) {
    init {
        require(TRACE_PATTERN.matches(opaqueTraceId)) { "Invalid diagnostic trace handle" }
    }

    override fun toString(): String =
        "LearningDiagnosticEntry(trace=<opaque>, sample=$sample)"
}

@Serializable
private data class LearningDiagnosticsSnapshot(
    @SerialName("schema_version")
    val schemaVersion: Int = LEARNING_DIAGNOSTICS_SCHEMA_VERSION,
    val entries: List<LearningDiagnosticEntry>,
)

/**
 * Bounded, best-effort diagnostics. A failed load/write never changes Learning or chat state.
 */
class LearningDiagnosticsStore(
    filesDir: File? = null,
    maxEntries: Int = MAX_LEARNING_DIAGNOSTIC_ENTRIES,
) {
    private val retainedLimit = maxEntries.coerceIn(1, MAX_LEARNING_DIAGNOSTIC_ENTRIES)
    private val destination = filesDir?.let(::outputFile)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val mutableEntries = MutableStateFlow(load())

    init {
        // Canonicalize an older/forward JSON object immediately. Unknown fields could otherwise
        // leave retired free-form text resident on disk even though this version never exposes it.
        destination?.takeIf(File::isFile)?.let { file ->
            persistBestEffort(file, mutableEntries.value)
        }
    }

    val entries: StateFlow<List<LearningDiagnosticEntry>> = mutableEntries.asStateFlow()

    @Synchronized
    fun record(sample: LearningDiagnosticSample): String {
        val trace = try {
            newTraceHandle()
        } catch (_: Exception) {
            // Diagnostics must never affect Learning/Chat. Do not persist an untrustworthy ID.
            return TRACE_UNAVAILABLE
        }
        val retained = (listOf(LearningDiagnosticEntry(trace, sample)) + mutableEntries.value)
            .take(retainedLimit)
        mutableEntries.value = retained
        destination?.let { file ->
            persistBestEffort(file, retained)
        }
        return trace
    }

    private fun load(): List<LearningDiagnosticEntry> {
        val file = destination?.takeIf(File::isFile) ?: return emptyList()
        return try {
            require(file.length() in 0L..MAX_LEARNING_DIAGNOSTICS_FILE_BYTES) {
                "Diagnostic file exceeds its hard size limit"
            }
            val snapshot = json.decodeFromString<LearningDiagnosticsSnapshot>(file.readText())
            require(snapshot.schemaVersion == LEARNING_DIAGNOSTICS_SCHEMA_VERSION)
            snapshot.entries.take(retainedLimit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistBestEffort(
        file: File,
        retained: List<LearningDiagnosticEntry>,
    ) {
        try {
            val payload = json.encodeToString(LearningDiagnosticsSnapshot(entries = retained))
            require(
                payload.toByteArray(Charsets.UTF_8).size.toLong() <=
                    MAX_LEARNING_DIAGNOSTICS_FILE_BYTES,
            ) {
                "Diagnostic payload exceeds its hard size limit"
            }
            writeAtomically(destination = file, payload = payload)
        } catch (_: Exception) {
            // Best-effort health output is never allowed to change runtime behavior.
        }
    }

    companion object {
        fun outputFile(filesDir: File): File =
            File(File(filesDir, "diagnostics"), "agent_learning_health.json")

        private fun newTraceHandle(): String = buildString {
            append(TRACE_PREFIX)
            ByteArray(16).also(secureRandom::nextBytes).forEach { byte ->
                append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
            }
        }

        private fun writeAtomically(destination: File, payload: String) {
            val directory = requireNotNull(destination.parentFile).apply { mkdirs() }
            val temporary = Files.createTempFile(directory.toPath(), ".agent_learning_", ".tmp")
            try {
                Files.write(temporary, payload.toByteArray(Charsets.UTF_8))
                try {
                    Files.move(
                        temporary,
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary,
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}

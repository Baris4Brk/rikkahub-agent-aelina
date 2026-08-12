package me.rerere.rikkahub.data.repository

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * Privacy-safe, deterministic account of one memory retrieval attempt.
 *
 * The trace deliberately contains neither memory ids nor query/content text. [candidateOrdinal]
 * is scoped to this one trace and cannot be joined back to a user's memory library. Runtime code
 * that needs the selected ids uses [MemoryRetrievalResult.matches] instead.
 */
@Serializable
data class MemoryRetrievalTrace(
    @SerialName("schema_version")
    val schemaVersion: Int = MEMORY_RETRIEVAL_TRACE_SCHEMA_VERSION,
    @SerialName("scope_kind")
    val scopeKind: MemoryRetrievalScopeKind,
    @SerialName("query_source")
    val querySource: MemoryRetrievalQuerySource,
    val status: MemoryRetrievalStatus,
    @SerialName("query_chars")
    val queryChars: Int,
    @SerialName("effective_query_chars")
    val effectiveQueryChars: Int,
    @SerialName("query_term_count")
    val queryTermCount: Int,
    @SerialName("query_truncated")
    val queryTruncated: Boolean,
    @SerialName("query_sanitized")
    val querySanitized: Boolean,
    @SerialName("requested_top_k")
    val requestedTopK: Int,
    @SerialName("candidate_limit")
    val candidateLimit: Int,
    @SerialName("raw_candidate_count")
    val rawCandidateCount: Int,
    @SerialName("selected_count")
    val selectedCount: Int,
    val candidates: List<MemoryRetrievalCandidateTrace> = emptyList(),
    val timings: MemoryRetrievalTimings = MemoryRetrievalTimings(),
)

@Serializable
enum class MemoryRetrievalScopeKind {
    ASSISTANT,
    GLOBAL,
    NONE,
}

@Serializable
enum class MemoryRetrievalQuerySource {
    UNSPECIFIED,
    LATEST_USER_TEXT,
    EXPLICIT_TOOL,
    OFFLINE_EVAL,
}

@Serializable
enum class MemoryRetrievalStatus {
    SUCCESS,
    SKIPPED_EMPTY_QUERY,
    SKIPPED_NO_SCOPE,
    SKIPPED_INVALID_BUDGET,
    INDEX_UNAVAILABLE,
}

@Serializable
data class MemoryRetrievalCandidateTrace(
    /** One-based position in the raw FTS response; never a database id. */
    @SerialName("candidate_ordinal")
    val candidateOrdinal: Int,
    @SerialName("raw_rank")
    val rawRank: Int,
    @SerialName("reranked_rank")
    val rerankedRank: Int? = null,
    @SerialName("final_rank")
    val finalRank: Int? = null,
    val score: Double? = null,
    val decision: MemoryRetrievalDecision,
)

@Serializable
enum class MemoryRetrievalDecision {
    SELECTED,
    EXCLUDED_ID,
    DUPLICATE_CONTENT,
    EMPTY_CONTENT,
    BUDGET_EXCEEDED,
    TOP_K_EXCEEDED,
}

@Serializable
data class MemoryRetrievalTimings(
    @SerialName("index_micros")
    val indexMicros: Long = 0L,
    @SerialName("rank_micros")
    val rankMicros: Long = 0L,
    @SerialName("total_micros")
    val totalMicros: Long = 0L,
)

const val MEMORY_RETRIEVAL_TRACE_SCHEMA_VERSION = 1

private const val MEMORY_RETRIEVAL_TRACE_HANDLE_PREFIX = "mrt_"
private val MEMORY_RETRIEVAL_TRACE_HANDLE_PATTERN = Regex("^mrt_[0-9a-f]{32}$")
private val memoryRetrievalTraceSecureRandom = SecureRandom()

/** A random correlation handle that cannot be confused with an application UUID. */
internal fun newMemoryRetrievalTraceHandle(): String = buildString {
    append(MEMORY_RETRIEVAL_TRACE_HANDLE_PREFIX)
    val bytes = ByteArray(16).also(memoryRetrievalTraceSecureRandom::nextBytes)
    bytes.forEach { byte ->
        append((byte.toInt() and 0xff).toString(16).padStart(2, '0'))
    }
}

internal fun isValidMemoryRetrievalTraceHandle(value: String): Boolean =
    MEMORY_RETRIEVAL_TRACE_HANDLE_PATTERN.matches(value)

@Serializable
data class MemoryRetrievalDiagnosticEntry(
    /** Random per-trace id; it is deliberately unrelated to conversation, scope, or memory ids. */
    @SerialName("opaque_trace_id")
    val opaqueTraceId: String,
    @SerialName("recorded_at_ms")
    val recordedAtMs: Long,
    val trace: MemoryRetrievalTrace,
) {
    init {
        require(isValidMemoryRetrievalTraceHandle(opaqueTraceId)) {
            "opaqueTraceId must be an mrt_ correlation handle, never an application UUID"
        }
    }
}

@Serializable
private data class MemoryRetrievalDiagnosticsSnapshot(
    @SerialName("schema_version")
    val schemaVersion: Int = MEMORY_RETRIEVAL_DIAGNOSTICS_SNAPSHOT_SCHEMA_VERSION,
    @SerialName("max_entries")
    val maxEntries: Int,
    val entries: List<MemoryRetrievalDiagnosticEntry>,
    @SerialName("privacy_note")
    val privacyNote: String = MEMORY_RETRIEVAL_PRIVACY_NOTE,
)

/**
 * Bounded production diagnostics for retrieval policy and latency. Both the in-process snapshot
 * and optional disk history are capped at 32 entries. The accepted input is already a redacted
 * [MemoryRetrievalTrace], so raw query/memory/scope identifiers cannot accidentally reach disk.
 */
class MemoryRetrievalDiagnosticsStore(
    filesDir: File? = null,
    maxEntries: Int = MAX_MEMORY_RETRIEVAL_DIAGNOSTIC_ENTRIES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val boundedMaxEntries = maxEntries.coerceIn(1, MAX_MEMORY_RETRIEVAL_DIAGNOSTIC_ENTRIES)
    private val destination = filesDir?.let(::outputFile)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutableEntries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<MemoryRetrievalDiagnosticEntry>> = mutableEntries.asStateFlow()

    /** Returns the random opaque trace id that callers may safely add to aggregate diagnostics. */
    @Synchronized
    fun record(
        trace: MemoryRetrievalTrace,
        recordedAtMs: Long = nowMs(),
    ): String {
        val entry = MemoryRetrievalDiagnosticEntry(
            opaqueTraceId = newMemoryRetrievalTraceHandle(),
            recordedAtMs = recordedAtMs,
            trace = trace,
        )
        val retained = (listOf(entry) + mutableEntries.value).take(boundedMaxEntries)
        mutableEntries.value = retained
        destination?.let { file ->
            runCatching {
                writeAtomically(
                    destination = file,
                    payload = json.encodeToString(
                        MemoryRetrievalDiagnosticsSnapshot(
                            maxEntries = boundedMaxEntries,
                            entries = retained,
                        ),
                    ),
                )
            }
        }
        return entry.opaqueTraceId
    }

    private fun loadEntries(): List<MemoryRetrievalDiagnosticEntry> {
        val file = destination?.takeIf(File::isFile) ?: return emptyList()
        return runCatching {
            json.decodeFromString<MemoryRetrievalDiagnosticsSnapshot>(file.readText())
                .entries
                .take(boundedMaxEntries)
        }.getOrDefault(emptyList())
    }

    companion object {
        fun outputFile(filesDir: File): File =
            File(File(filesDir, "diagnostics"), "memory_retrieval_history.json")

        private fun writeAtomically(destination: File, payload: String) {
            val directory = requireNotNull(destination.parentFile).apply { mkdirs() }
            val temporary = Files.createTempFile(
                directory.toPath(),
                ".memory_retrieval_",
                ".tmp",
            )
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

const val MAX_MEMORY_RETRIEVAL_DIAGNOSTIC_ENTRIES = 32
private const val MEMORY_RETRIEVAL_DIAGNOSTICS_SNAPSHOT_SCHEMA_VERSION = 2

private const val MEMORY_RETRIEVAL_PRIVACY_NOTE =
    "Policy counts, ranks, scores, timings, and a random mrt_ correlation handle only; " +
        "no query, memory text/id, or application/scope UUID."

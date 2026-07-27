package me.rerere.rikkahub.diagnostics

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.GenerationOutcome

data class RecentGenerationDiagnostic(
    val recordedAtEpochMs: Long,
    val modelId: String,
    val providerType: String,
    val requestMode: String,
    val terminalSeen: Boolean,
    val finishCategory: String,
    val reasoningChars: Int,
    val answerChars: Int,
    val toolCallCount: Int,
    val contextOriginalTokens: Int,
    val contextPlannedTokens: Int,
    val contextWindowTokens: Int,
    val contextCompressed: Boolean,
    val historicalReasoningRemoved: Int,
    val completionOutcome: String? = null,
    val recoveryAttempt: Int? = null,
    val recoveryStatus: String? = null,
    val requestBreakdown: RequestBreakdownDiagnostic? = null,
) {
    fun redactedDetail(): String = buildString {
        append("model=").append(modelId.take(80))
        append("; provider=").append(providerType.take(40))
        append("; mode=").append(requestMode)
        append("; terminal=").append(terminalSeen)
        append('/').append(finishCategory)
        append("; chars(reasoning/answer)=").append(reasoningChars).append('/').append(answerChars)
        append("; tools=").append(toolCallCount)
        append("; context=").append(contextPlannedTokens).append('/').append(contextWindowTokens)
        append(" (original ").append(contextOriginalTokens).append(')')
        append("; compressed=").append(contextCompressed)
        append("; historicalReasoningRemoved=").append(historicalReasoningRemoved)
        completionOutcome?.let { append("; outcome=").append(it) }
        recoveryAttempt?.let { append("; recovery=").append(it).append("/10:").append(recoveryStatus) }
        requestBreakdown?.let { append("; ").append(it.redactedSummary()) }
    }
}

/**
 * Stable, generation-scoped writer for content-free diagnostics.
 *
 * Callers must keep one handle for the complete command, including tool rounds and final-answer
 * recovery attempts. Updates made through one handle can never target another generation.
 */
class GenerationDiagnosticHandle internal constructor(
    val generationId: String,
    internal val sequence: Long,
) {
    @Volatile
    internal var diagnostic: RecentGenerationDiagnostic? = null

    internal var completionOutcome: String? = null
    internal var recoveryAttempt: Int? = null
    internal var recoveryStatus: String? = null
    internal var requestBreakdown: RequestBreakdownDiagnostic? = null
    internal var providerCallCount: Int = 0

    fun nextProviderCallIndex(): Int = synchronized(this) {
        providerCallCount += 1
        providerCallCount
    }

    fun recordRequestBreakdown(filesDir: File, breakdown: RequestBreakdownDiagnostic) {
        RecentGenerationDiagnostics.recordRequestBreakdown(this, filesDir, breakdown)
    }

    fun recordProviderUsage(
        filesDir: File,
        promptTokens: Int,
        cachedTokens: Int,
        completionTokens: Int,
    ) {
        RecentGenerationDiagnostics.recordProviderUsage(
            this,
            filesDir,
            promptTokens,
            cachedTokens,
            completionTokens,
        )
    }

    fun record(
        terminal: GenerationTerminal,
        modelId: String,
        providerType: String,
        requestMode: String,
        contextOriginalTokens: Int,
        contextPlannedTokens: Int,
        contextWindowTokens: Int,
        contextCompressed: Boolean,
        historicalReasoningRemoved: Int,
    ) {
        RecentGenerationDiagnostics.record(
            handle = this,
            terminal = terminal,
            modelId = modelId,
            providerType = providerType,
            requestMode = requestMode,
            contextOriginalTokens = contextOriginalTokens,
            contextPlannedTokens = contextPlannedTokens,
            contextWindowTokens = contextWindowTokens,
            contextCompressed = contextCompressed,
            historicalReasoningRemoved = historicalReasoningRemoved,
        )
    }

    fun markRecovery(attempt: Int, status: String) {
        RecentGenerationDiagnostics.markRecovery(this, attempt, status)
    }

    fun markOutcome(outcome: GenerationOutcome) {
        RecentGenerationDiagnostics.markOutcome(this, outcome)
    }

    internal fun snapshotForTest(): RecentGenerationDiagnostic? =
        RecentGenerationDiagnostics.snapshot(this)
}

/** In-memory and content-free: no prompts, reasoning text, credentials, or tool arguments. */
object RecentGenerationDiagnostics {
    private val nextSequence = AtomicLong(0)

    @Volatile
    private var latestRecordedHandle: GenerationDiagnosticHandle? = null

    /** Begin one logical command/generation. The id must remain stable across all provider rounds. */
    fun begin(generationId: String): GenerationDiagnosticHandle {
        require(generationId.isNotBlank()) { "generationId must not be blank" }
        return GenerationDiagnosticHandle(
            generationId = generationId,
            sequence = nextSequence.incrementAndGet(),
        )
    }

    internal fun record(
        handle: GenerationDiagnosticHandle,
        terminal: GenerationTerminal,
        modelId: String,
        providerType: String,
        requestMode: String,
        contextOriginalTokens: Int,
        contextPlannedTokens: Int,
        contextWindowTokens: Int,
        contextCompressed: Boolean,
        historicalReasoningRemoved: Int,
    ) {
        synchronized(handle) {
            handle.diagnostic = RecentGenerationDiagnostic(
                recordedAtEpochMs = System.currentTimeMillis(),
                modelId = modelId,
                providerType = providerType,
                requestMode = requestMode,
                terminalSeen = terminal.terminalSeen,
                finishCategory = terminal.category.name,
                reasoningChars = terminal.reasoningChars,
                answerChars = terminal.answerChars,
                toolCallCount = terminal.toolCallCount,
                contextOriginalTokens = contextOriginalTokens,
                contextPlannedTokens = contextPlannedTokens,
                contextWindowTokens = contextWindowTokens,
                contextCompressed = contextCompressed,
                historicalReasoningRemoved = historicalReasoningRemoved,
                completionOutcome = handle.completionOutcome,
                recoveryAttempt = handle.recoveryAttempt,
                recoveryStatus = handle.recoveryStatus,
                requestBreakdown = handle.requestBreakdown,
            )
        }
        publishIfNewest(handle)
    }

    internal fun markRecovery(
        handle: GenerationDiagnosticHandle,
        attempt: Int,
        status: String,
    ) {
        synchronized(handle) {
            handle.recoveryAttempt = attempt.coerceIn(1, 10)
            handle.recoveryStatus = status.take(40)
            handle.diagnostic = handle.diagnostic?.copy(
                recordedAtEpochMs = System.currentTimeMillis(),
                recoveryAttempt = handle.recoveryAttempt,
                recoveryStatus = handle.recoveryStatus,
            )
        }
    }

    internal fun recordRequestBreakdown(
        handle: GenerationDiagnosticHandle,
        filesDir: File,
        breakdown: RequestBreakdownDiagnostic,
    ) {
        synchronized(handle) {
            handle.requestBreakdown = breakdown
            handle.diagnostic = handle.diagnostic?.copy(
                recordedAtEpochMs = System.currentTimeMillis(),
                requestBreakdown = breakdown,
            )
        }
        RequestBreakdownDiagnosticsStore.write(filesDir, breakdown)
    }

    internal fun recordProviderUsage(
        handle: GenerationDiagnosticHandle,
        filesDir: File,
        promptTokens: Int,
        cachedTokens: Int,
        completionTokens: Int,
    ) {
        val updated = synchronized(handle) {
            handle.requestBreakdown?.withProviderUsage(promptTokens, cachedTokens, completionTokens)?.also {
                handle.requestBreakdown = it
                handle.diagnostic = handle.diagnostic?.copy(
                    recordedAtEpochMs = System.currentTimeMillis(),
                    requestBreakdown = it,
                )
            }
        } ?: return
        RequestBreakdownDiagnosticsStore.write(filesDir, updated)
    }

    internal fun markOutcome(
        handle: GenerationDiagnosticHandle,
        outcome: GenerationOutcome,
    ) {
        synchronized(handle) {
            handle.completionOutcome = outcome::class.simpleName ?: "unknown"
            handle.diagnostic = handle.diagnostic?.copy(
                recordedAtEpochMs = System.currentTimeMillis(),
                completionOutcome = handle.completionOutcome,
            )
        }
    }

    fun snapshot(): RecentGenerationDiagnostic? = snapshot(latestRecordedHandle)

    internal fun snapshot(handle: GenerationDiagnosticHandle?): RecentGenerationDiagnostic? {
        if (handle == null) return null
        return synchronized(handle) { handle.diagnostic }
    }

    @Synchronized
    private fun publishIfNewest(handle: GenerationDiagnosticHandle) {
        val current = latestRecordedHandle
        if (current == null || handle.sequence > current.sequence) {
            latestRecordedHandle = handle
        }
    }

    internal fun resetForTest() {
        synchronized(this) {
            latestRecordedHandle = null
            nextSequence.set(0)
        }
    }
}

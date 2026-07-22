package me.rerere.ai.ui

import kotlinx.serialization.Serializable

@Serializable
enum class FinishCategory {
    STOP,
    TOOL_CALLS,
    LENGTH,
    SAFETY,
    INCOMPLETE,
    FAILED,
    EOF,
    CANCELLED,
    UNKNOWN,
}

@Serializable
data class GenerationTerminal(
    val terminalSeen: Boolean,
    val category: FinishCategory,
    val providerReason: String? = null,
    val incompleteDetail: String? = null,
    val reasoningChars: Int = 0,
    val answerChars: Int = 0,
    val toolCallCount: Int = 0,
) {
    fun withMessageStats(message: UIMessage): GenerationTerminal {
        val answerParts = message.parts.afterLastTool()
        return copy(
            reasoningChars = message.parts.filterIsInstance<UIMessagePart.Reasoning>()
                .sumOf { it.reasoning.length },
            answerChars = answerParts.filterIsInstance<UIMessagePart.Text>()
                .sumOf { it.text.length },
            toolCallCount = message.parts.count { it is UIMessagePart.Tool },
        )
    }

    companion object {
        fun missingTransportTerminal(detail: String? = null): GenerationTerminal =
            GenerationTerminal(
                terminalSeen = false,
                category = FinishCategory.EOF,
                incompleteDetail = detail ?: "Provider stream closed without a terminal event.",
            )

        fun fromProviderReason(
            reason: String?,
            incompleteDetail: String? = null,
        ): GenerationTerminal {
            val normalized = reason?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val category = when (normalized) {
                null -> FinishCategory.EOF
                "stop", "end_turn", "stop_sequence", "completed", "complete" -> FinishCategory.STOP
                "tool_calls", "tool_use", "function_call" -> FinishCategory.TOOL_CALLS
                "length", "max_tokens", "max_output_tokens", "model_context_window_exceeded" ->
                    FinishCategory.LENGTH
                "content_filter", "safety", "blocked", "refusal", "recitation", "blocklist",
                "prohibited_content", "spii", "image_safety", "image_prohibited_content" ->
                    FinishCategory.SAFETY
                "incomplete", "pause_turn" -> FinishCategory.INCOMPLETE
                "failed", "error", "malformed_function_call", "unexpected_tool_call",
                "too_many_tool_calls" -> FinishCategory.FAILED
                "cancelled", "canceled" -> FinishCategory.CANCELLED
                else -> FinishCategory.UNKNOWN
            }
            return GenerationTerminal(
                terminalSeen = normalized != null,
                category = category,
                providerReason = reason,
                incompleteDetail = incompleteDetail,
            )
        }
    }
}

class GenerationTerminalTracker {
    private var terminal: GenerationTerminal? = null

    fun observe(chunk: MessageChunk) {
        chunk.resolvedTerminal()?.let { terminal = it }
    }

    fun finish(): GenerationTerminal = terminal ?: GenerationTerminal.missingTransportTerminal()
}

sealed interface GenerationOutcome {
    data object Completed : GenerationOutcome
    data object AwaitingToolApproval : GenerationOutcome
    data object ContinueToolLoop : GenerationOutcome
    data class NeedsFinalAnswer(val terminal: GenerationTerminal) : GenerationOutcome
    data class Interrupted(val terminal: GenerationTerminal) : GenerationOutcome
    data class Failed(val terminal: GenerationTerminal) : GenerationOutcome
}

enum class FinalAnswerRecoveryDecision {
    Attempt,
    Skip,
    Wait,
    Fail,
}

object FinalAnswerRecoveryPolicy {
    fun decide(
        outcome: GenerationOutcome,
        attempts: Int,
        maxAttempts: Int,
        cancelled: Boolean,
        emergencyStopActive: Boolean,
    ): FinalAnswerRecoveryDecision {
        require(attempts >= 0) { "attempts must not be negative" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        if (attempts >= maxAttempts || cancelled || emergencyStopActive) {
            return FinalAnswerRecoveryDecision.Skip
        }
        return when (outcome) {
            is GenerationOutcome.NeedsFinalAnswer -> FinalAnswerRecoveryDecision.Attempt
            GenerationOutcome.AwaitingToolApproval,
            GenerationOutcome.ContinueToolLoop -> FinalAnswerRecoveryDecision.Wait
            is GenerationOutcome.Failed -> FinalAnswerRecoveryDecision.Fail
            GenerationOutcome.Completed,
            is GenerationOutcome.Interrupted -> FinalAnswerRecoveryDecision.Skip
        }
    }
}

enum class FinalAnswerRecoveryFailure {
    PROVIDER_EXCEPTION,
    NO_VISIBLE_ANSWER,
    TIME_BUDGET_EXHAUSTED,
    CANCELLED_OR_EMERGENCY,
    TOOL_CALL,
}

sealed interface FinalAnswerRecoveryAttemptDecision {
    data class Retry(val stream: Boolean) : FinalAnswerRecoveryAttemptDecision
    data class Stop(val reason: String) : FinalAnswerRecoveryAttemptDecision
}

/**
 * Decides what to do after one final-answer reminder did not produce a usable answer.
 *
 * Recovery starts non-streaming for broad provider compatibility. Any retry switches to the
 * streaming transport used by the ordinary conversation, while tools and reasoning remain
 * disabled by the caller. This keeps recovery in the same assistant message without creating a
 * regenerated branch.
 */
object FinalAnswerRecoveryAttemptPolicy {
    fun afterFailure(
        failure: FinalAnswerRecoveryFailure,
        attempt: Int,
        maxAttempts: Int,
    ): FinalAnswerRecoveryAttemptDecision {
        require(attempt > 0) { "attempt must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        return when {
            failure == FinalAnswerRecoveryFailure.CANCELLED_OR_EMERGENCY ->
                FinalAnswerRecoveryAttemptDecision.Stop("recovery_cancelled_or_emergency_stopped")
            failure == FinalAnswerRecoveryFailure.TIME_BUDGET_EXHAUSTED ->
                FinalAnswerRecoveryAttemptDecision.Stop("recovery_time_budget_exhausted")
            failure == FinalAnswerRecoveryFailure.TOOL_CALL ->
                FinalAnswerRecoveryAttemptDecision.Stop("recovery_attempted_tool_call")
            attempt >= maxAttempts ->
                FinalAnswerRecoveryAttemptDecision.Stop("recovery_attempts_exhausted")
            else -> FinalAnswerRecoveryAttemptDecision.Retry(stream = true)
        }
    }
}

/** Keeps recovery inside the original assistant message and exposes only its new final text. */
object FinalAnswerRecoveryMessagePolicy {
    fun mergeVisibleAnswer(
        original: UIMessage,
        recoveryCandidate: UIMessage,
    ): UIMessage {
        require(original.id == recoveryCandidate.id) {
            "Final-answer recovery must not create a new assistant message"
        }
        val recoveredText = recoveryCandidate.parts
            .drop(original.parts.size)
            .filterIsInstance<UIMessagePart.Text>()
            .filter { it.text.isNotBlank() }
        require(recoveredText.isNotEmpty()) {
            "Final-answer recovery did not append visible text"
        }
        return recoveryCandidate.copy(parts = original.parts + recoveredText)
    }
}

/**
 * Provider-neutral completion policy. Provider adapters report how a step ended; this module
 * decides whether the caller actually received a user-visible answer after the last tool call.
 */
object GenerationCompletionPolicy {
    fun evaluate(
        message: UIMessage,
        terminal: GenerationTerminal,
    ): GenerationOutcome {
        val terminalWithStats = terminal.withMessageStats(message)
        val tools = message.getTools()
        if (tools.any { it.approvalState is ToolApprovalState.Pending }) {
            return GenerationOutcome.AwaitingToolApproval
        }
        if (tools.any { !it.isExecuted }) {
            return GenerationOutcome.ContinueToolLoop
        }

        val lastMeaningfulOutput = message.parts.afterLastTool()
            .lastOrNull(UIMessagePart::isMeaningfulOutput)
        val hasVisibleAnswerAtTail = lastMeaningfulOutput?.isUserVisible() == true
        val hasReasoningOnlyAtTail = lastMeaningfulOutput is UIMessagePart.Reasoning
        if (!terminalWithStats.terminalSeen) {
            return if (hasVisibleAnswerAtTail) {
                GenerationOutcome.Interrupted(terminalWithStats)
            } else {
                GenerationOutcome.NeedsFinalAnswer(terminalWithStats)
            }
        }
        return when (terminal.category) {
            FinishCategory.CANCELLED -> GenerationOutcome.Interrupted(terminalWithStats)
            FinishCategory.SAFETY,
            FinishCategory.FAILED -> if (hasReasoningOnlyAtTail) {
                GenerationOutcome.NeedsFinalAnswer(terminalWithStats)
            } else {
                GenerationOutcome.Failed(terminalWithStats)
            }
            FinishCategory.LENGTH,
            FinishCategory.INCOMPLETE,
            FinishCategory.EOF -> if (hasVisibleAnswerAtTail) {
                GenerationOutcome.Interrupted(terminalWithStats)
            } else {
                GenerationOutcome.NeedsFinalAnswer(terminalWithStats)
            }
            FinishCategory.STOP -> if (hasVisibleAnswerAtTail) {
                GenerationOutcome.Completed
            } else {
                GenerationOutcome.NeedsFinalAnswer(terminalWithStats)
            }
            FinishCategory.UNKNOWN -> when {
                hasVisibleAnswerAtTail -> GenerationOutcome.Interrupted(terminalWithStats)
                hasReasoningOnlyAtTail -> GenerationOutcome.NeedsFinalAnswer(terminalWithStats)
                else -> {
                GenerationOutcome.Failed(
                    terminalWithStats.copy(
                        incompleteDetail = terminalWithStats.incompleteDetail
                            ?: "Provider ended with an unrecognized finish reason; automatic recovery was suppressed.",
                    ),
                )
                }
            }
            FinishCategory.TOOL_CALLS -> GenerationOutcome.Failed(
                terminalWithStats.copy(
                    incompleteDetail = terminalWithStats.incompleteDetail
                        ?: "Provider ended with tool_calls but no executable tool was parsed.",
                ),
            )
        }
    }
}

private fun List<UIMessagePart>.afterLastTool(): List<UIMessagePart> {
    val lastToolIndex = indexOfLast { it is UIMessagePart.Tool }
    return if (lastToolIndex < 0) this else drop(lastToolIndex + 1)
}

private fun UIMessagePart.isUserVisible(): Boolean = when (this) {
    is UIMessagePart.Text -> text.isNotBlank()
    is UIMessagePart.Image -> url.isNotBlank()
    is UIMessagePart.Video -> url.isNotBlank()
    is UIMessagePart.Audio -> url.isNotBlank()
    is UIMessagePart.Document -> url.isNotBlank()
    else -> false
}

private fun UIMessagePart.isMeaningfulOutput(): Boolean = when (this) {
    is UIMessagePart.Reasoning -> reasoning.isNotBlank()
    else -> isUserVisible()
}

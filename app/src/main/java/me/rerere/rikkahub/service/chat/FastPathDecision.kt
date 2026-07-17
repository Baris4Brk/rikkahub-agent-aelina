package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant

fun interface FastPathRouter {
    suspend fun resolve(context: FastPathContext): FastPathDecision
}

data class FastPathContext(
    val commandId: kotlin.uuid.Uuid,
    val conversation: Conversation,
    val content: List<UIMessagePart>,
    val origin: CommandOrigin,
    val assistant: Assistant,
)

sealed interface FastPathDecision {
    data object NotMatched : FastPathDecision
    data class Handled(
        val responseParts: List<UIMessagePart>,
        val metadata: Map<String, String> = emptyMap(),
    ) : FastPathDecision
    data class ContinueToModel(val transformedContent: List<UIMessagePart>) : FastPathDecision
    data class Rejected(val reason: String) : FastPathDecision
}

/** Pure commit plan used by the runtime to guarantee exactly-once fast-path writes. */
sealed interface FastPathCommitPlan {
    data class Handled(
        val userContent: List<UIMessagePart>,
        val assistantContent: List<UIMessagePart>,
    ) : FastPathCommitPlan
    data class ContinueToModel(val userContent: List<UIMessagePart>) : FastPathCommitPlan
    data class NotMatched(val userContent: List<UIMessagePart>) : FastPathCommitPlan
    data class Rejected(val reason: String) : FastPathCommitPlan
}

fun buildFastPathCommitPlan(
    processedContent: List<UIMessagePart>,
    decision: FastPathDecision,
): FastPathCommitPlan = when (decision) {
    is FastPathDecision.Handled -> FastPathCommitPlan.Handled(
        userContent = processedContent,
        assistantContent = decision.responseParts,
    )
    is FastPathDecision.ContinueToModel -> FastPathCommitPlan.ContinueToModel(
        userContent = decision.transformedContent,
    )
    FastPathDecision.NotMatched -> FastPathCommitPlan.NotMatched(
        userContent = processedContent,
    )
    is FastPathDecision.Rejected -> FastPathCommitPlan.Rejected(decision.reason)
}
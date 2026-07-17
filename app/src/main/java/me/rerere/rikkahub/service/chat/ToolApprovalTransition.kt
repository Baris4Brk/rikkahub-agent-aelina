package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.ToolApprovalState

/**
 * Pure, idempotent approval transition used before mutating a conversation.
 * A terminal decision is immutable: repeating it is success, changing it is a conflict.
 */
sealed interface ToolApprovalTransition {
    data class Apply(val state: ToolApprovalState) : ToolApprovalTransition
    data object Idempotent : ToolApprovalTransition
    data object Conflict : ToolApprovalTransition
    data object NotPending : ToolApprovalTransition
}

fun shouldResumeAfterApproval(
    appliedPendingDecision: Boolean,
    hasPendingAfterUpdate: Boolean,
): Boolean = appliedPendingDecision && !hasPendingAfterUpdate

fun resolveToolApproval(
    current: ToolApprovalState,
    requested: ToolApprovalState,
): ToolApprovalTransition = when (current) {
    ToolApprovalState.Pending -> ToolApprovalTransition.Apply(requested)
    ToolApprovalState.Approved ->
        if (requested == ToolApprovalState.Approved) ToolApprovalTransition.Idempotent
        else ToolApprovalTransition.Conflict
    is ToolApprovalState.Denied ->
        if (requested is ToolApprovalState.Denied && requested.reason == current.reason) {
            ToolApprovalTransition.Idempotent
        } else ToolApprovalTransition.Conflict
    is ToolApprovalState.Answered ->
        if (requested == current) ToolApprovalTransition.Idempotent
        else ToolApprovalTransition.Conflict
    ToolApprovalState.Auto -> ToolApprovalTransition.NotPending
}

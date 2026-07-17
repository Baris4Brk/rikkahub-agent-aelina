package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.ai.SteeringState
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SteeringUiEntry

/** User-facing send intents. UI copy stays conversational; these names remain internal. */
internal enum class ChatSendAction {
    SOFT_STEER,
    QUEUE,
    INTERRUPT,
    STOP,
    SHOW_RUNNING_CHOICES,
}

internal enum class RunningSendChoice {
    CONTINUE_WITH_GUIDANCE,
    HANDLE_AFTER_CURRENT_TASK,
    STOP_AND_REPLACE,
}

internal fun resolveShortSendAction(
    runtimeState: RuntimeState,
    hasInput: Boolean,
    hasGuidanceText: Boolean,
): ChatSendAction = when {
    runtimeState == RuntimeState.Running && hasGuidanceText -> ChatSendAction.SOFT_STEER
    runtimeState == RuntimeState.Running && !hasInput -> ChatSendAction.STOP
    runtimeState == RuntimeState.Running -> ChatSendAction.SHOW_RUNNING_CHOICES
    else -> ChatSendAction.QUEUE
}

internal fun resolveLongSendAction(
    runtimeState: RuntimeState,
    hasInput: Boolean,
): ChatSendAction = when {
    runtimeState == RuntimeState.Running && hasInput -> ChatSendAction.SHOW_RUNNING_CHOICES
    runtimeState == RuntimeState.Running -> ChatSendAction.STOP
    else -> ChatSendAction.QUEUE
}

internal fun RunningSendChoice.toSendAction(): ChatSendAction = when (this) {
    RunningSendChoice.CONTINUE_WITH_GUIDANCE -> ChatSendAction.SOFT_STEER
    RunningSendChoice.HANDLE_AFTER_CURRENT_TASK -> ChatSendAction.QUEUE
    RunningSendChoice.STOP_AND_REPLACE -> ChatSendAction.INTERRUPT
}

/**
 * Keep every card the user can still switch, plus the latest terminal notice that needs
 * acknowledgement. Successfully applied transient cards disappear when the task ends;
 * persistent ones are rendered from conversation history instead.
 */
internal fun selectVisibleSteeringEntries(
    entries: Collection<SteeringUiEntry>,
): List<SteeringUiEntry> {
    val editable = entries.filter { it.editable }
    val latestTerminal = entries.lastOrNull { entry ->
        !entry.editable && entry.state in setOf(
            SteeringState.FALLBACK_QUEUED,
            SteeringState.NOT_APPLIED_RUN_FINISHED,
            SteeringState.REJECTED_NOT_STEERABLE,
        )
    }
    return if (latestTerminal == null || editable.any { it.commandId == latestTerminal.commandId }) {
        editable
    } else {
        editable + latestTerminal
    }
}

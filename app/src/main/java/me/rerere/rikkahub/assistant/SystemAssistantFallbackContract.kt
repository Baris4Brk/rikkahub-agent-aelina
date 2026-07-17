package me.rerere.rikkahub.assistant

import android.content.Intent
import kotlin.uuid.Uuid

/** Explicit launcher action used by the pinned second-user shortcut. */
const val SYSTEM_ASSISTANT_SHORTCUT_ACTION =
    "me.rerere.rikkahub.action.OPEN_SECOND_USER_ASSISTANT"

/**
 * The exported fallback surface deliberately accepts no arbitrary custom action. Both accepted
 * actions open a visible, user-operated surface and never consume prompt text from the intent.
 */
fun isSystemAssistantFallbackAction(action: String?): Boolean =
    action == Intent.ACTION_ASSIST || action == SYSTEM_ASSISTANT_SHORTCUT_ACTION

sealed interface SystemAssistantFallbackDestination {
    data class Conversation(val conversationId: Uuid) : SystemAssistantFallbackDestination

    data object Configuration : SystemAssistantFallbackDestination

    data object Dismiss : SystemAssistantFallbackDestination
}

/**
 * Converts one immutable launch snapshot into a destination. A keyguard invocation is permanently
 * dismissed; unlocking later cannot upgrade it into an ordinary local-chat launch.
 */
fun decideSystemAssistantFallbackDestination(
    ownerUser: Boolean,
    deviceLocked: Boolean,
    targetResolution: SecondUserTargetResolution?,
): SystemAssistantFallbackDestination = when {
    !ownerUser || deviceLocked -> SystemAssistantFallbackDestination.Dismiss
    targetResolution is SecondUserTargetResolution.Resolved ->
        SystemAssistantFallbackDestination.Conversation(targetResolution.conversationId)
    else -> SystemAssistantFallbackDestination.Configuration
}

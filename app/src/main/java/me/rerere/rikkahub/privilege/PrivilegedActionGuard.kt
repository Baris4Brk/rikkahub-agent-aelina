package me.rerere.rikkahub.privilege

import kotlin.uuid.Uuid

sealed interface PrivilegedAction {
    data object CloseApplication : PrivilegedAction
    data object ModifySafetySettings : PrivilegedAction
    data class ForceStopPackage(val packageName: String) : PrivilegedAction
    data class ChangePrivilegedIdentity(val assistantId: Uuid) : PrivilegedAction
    data class ChangePrivilegedConversation(val assistantId: Uuid) : PrivilegedAction
    data class ChangeAssistantId(val assistantId: Uuid) : PrivilegedAction
    data class ChangeUnrestricted(val assistantId: Uuid) : PrivilegedAction
    data class DeleteConversation(val conversationId: Uuid) : PrivilegedAction
}

sealed interface PrivilegedActionDecision {
    data object Allowed : PrivilegedActionDecision
    data class Denied(val code: String, val message: String) : PrivilegedActionDecision
}

fun interface PrivilegedActionGuard {
    fun check(
        action: PrivilegedAction,
        context: PrivilegedSessionContext,
    ): PrivilegedActionDecision
}

/**
 * Code-level safety floor for RikkaHub's own management surface. This deliberately does not
 * consult approval or unrestricted state: callers cannot make one of these decisions true by
 * changing a prompt, a tool setting, or a generation flag.
 */
class DefaultPrivilegedActionGuard(
    private val applicationPackageName: String,
) : PrivilegedActionGuard {
    override fun check(
        action: PrivilegedAction,
        context: PrivilegedSessionContext,
    ): PrivilegedActionDecision {
        if (!context.isPrivileged) {
            return denied("NOT_PRIVILEGED", "This operation is available only to the selected privileged conversation.")
        }
        return when (action) {
            PrivilegedAction.CloseApplication ->
                denied("SELF_PROTECTION", "The second user cannot close RikkaHub.")
            PrivilegedAction.ModifySafetySettings ->
                denied("SAFETY_SETTINGS_PROTECTED", "Agent safety and emergency-stop settings are user-only.")
            is PrivilegedAction.ForceStopPackage -> if (
                action.packageName.equals(applicationPackageName, ignoreCase = true)
            ) {
                denied("SELF_PROTECTION", "The second user cannot stop RikkaHub.")
            } else {
                PrivilegedActionDecision.Allowed
            }
            is PrivilegedAction.ChangePrivilegedIdentity -> protectOwnAssistant(
                action.assistantId,
                context,
                "PRIVILEGED_IDENTITY_PROTECTED",
                "The second user cannot change or remove its own identity.",
            )
            is PrivilegedAction.ChangePrivilegedConversation -> protectOwnAssistant(
                action.assistantId,
                context,
                "PRIVILEGED_CONVERSATION_PROTECTED",
                "The second user cannot change or disable its own privileged conversation.",
            )
            is PrivilegedAction.ChangeAssistantId -> protectOwnAssistant(
                action.assistantId,
                context,
                "ASSISTANT_ID_PROTECTED",
                "The second user cannot change its own assistant id.",
            )
            is PrivilegedAction.ChangeUnrestricted -> protectOwnAssistant(
                action.assistantId,
                context,
                "PRIVILEGE_CONFIGURATION_PROTECTED",
                "The second user cannot change its own unrestricted setting.",
            )
            is PrivilegedAction.DeleteConversation -> if (
                action.conversationId == context.conversationId ||
                action.conversationId == context.privilegedConversationId
            ) {
                denied("CURRENT_CONVERSATION_PROTECTED", "The privileged conversation cannot delete itself.")
            } else {
                PrivilegedActionDecision.Allowed
            }
        }
    }

    private fun protectOwnAssistant(
        targetAssistantId: Uuid,
        context: PrivilegedSessionContext,
        code: String,
        message: String,
    ): PrivilegedActionDecision = if (targetAssistantId == context.assistantId) {
        denied(code, message)
    } else {
        PrivilegedActionDecision.Allowed
    }

    private fun denied(code: String, message: String) =
        PrivilegedActionDecision.Denied(code, message)
}

package me.rerere.rikkahub.privilege

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard

/** The seven immutable management zones for the local second-user profile. */
enum class HardDenyZone {
    SELF_PROTECTION,
    SAFETY_CONFIGURATION,
    PRIVILEGED_IDENTITY,
    PRIVILEGED_CONVERSATION,
    ASSISTANT_IDENTITY,
    PRIVILEGE_CONFIGURATION,
    CURRENT_PRIVILEGED_CONVERSATION,
    /** Separate from the seven management zones; covers shell/browser safety-floor rules. */
    COMMAND_SAFETY,
}

sealed interface HardDenyDecision {
    data object Allowed : HardDenyDecision
    data class Denied(
        val zone: HardDenyZone,
        val code: String,
        val message: String,
    ) : HardDenyDecision
}

/**
 * Immutable, code-owned denial policy. Approval, grants, prompts, and `unrestricted` must never
 * change a denial into an allow. Runtime-specific adapters may invoke it again for defence in
 * depth, but this is the shared interpretation of the permanent floor.
 */
interface HardDenyPolicy {
    fun checkTool(toolName: String, arguments: JsonObject?): HardDenyDecision
    fun checkPrivilegedAction(
        action: PrivilegedAction,
        context: PrivilegedSessionContext,
    ): HardDenyDecision
}

class DefaultHardDenyPolicy(
    applicationPackageName: String,
    private val actionGuard: PrivilegedActionGuard = DefaultPrivilegedActionGuard(applicationPackageName),
) : HardDenyPolicy {
    override fun checkTool(toolName: String, arguments: JsonObject?): HardDenyDecision {
        val reason = arguments?.let { HardlineCommandGuard.checkToolParsed(toolName, it) }
            ?: return HardDenyDecision.Allowed
        return HardDenyDecision.Denied(
            zone = HardDenyZone.COMMAND_SAFETY,
            code = "hardline_command_blocked",
            message = "Permanent command safety policy blocked this operation: $reason",
        )
    }

    override fun checkPrivilegedAction(
        action: PrivilegedAction,
        context: PrivilegedSessionContext,
    ): HardDenyDecision = when (val decision = actionGuard.check(action, context)) {
        PrivilegedActionDecision.Allowed -> HardDenyDecision.Allowed
        is PrivilegedActionDecision.Denied -> HardDenyDecision.Denied(
            zone = action.zone(),
            code = decision.code.lowercase(),
            message = decision.message,
        )
    }

    private fun PrivilegedAction.zone(): HardDenyZone = when (this) {
        PrivilegedAction.CloseApplication,
        is PrivilegedAction.ForceStopPackage,
        -> HardDenyZone.SELF_PROTECTION

        PrivilegedAction.ModifySafetySettings -> HardDenyZone.SAFETY_CONFIGURATION
        is PrivilegedAction.ChangePrivilegedIdentity -> HardDenyZone.PRIVILEGED_IDENTITY
        is PrivilegedAction.ChangePrivilegedConversation -> HardDenyZone.PRIVILEGED_CONVERSATION
        is PrivilegedAction.ChangeAssistantId -> HardDenyZone.ASSISTANT_IDENTITY
        is PrivilegedAction.ChangeUnrestricted -> HardDenyZone.PRIVILEGE_CONFIGURATION
        is PrivilegedAction.DeleteConversation -> HardDenyZone.CURRENT_PRIVILEGED_CONVERSATION
    }
}

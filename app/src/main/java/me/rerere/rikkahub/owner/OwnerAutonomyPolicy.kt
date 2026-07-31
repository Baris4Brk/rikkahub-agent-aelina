package me.rerere.rikkahub.owner

import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

/**
 * The active local second user is an owner principal, not another approval preset. Once the
 * live authority/epoch and trusted local origin match, ordinary tool approval cards are skipped
 * for every tool family (including plugins and Linux grants). HARDLINE, Emergency Stop, Android
 * system authorization and protected-host guards execute at their own later boundaries.
 */
object OwnerAutonomyPolicy {
    fun canAutoApprove(
        privilege: PrivilegedSessionContext,
        origin: ToolCallOrigin,
        toolName: String,
    ): Boolean {
        if (toolName == "ask_user") return false
        val subjectId = privilege.authoritySubjectId ?: return false
        return privilege.isPrivileged &&
            privilege.autoApproveTools &&
            privilege.origin == origin &&
            origin in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER &&
            SecondUserAuthorityRegistry.matches(
                subjectId = subjectId,
                conversationId = privilege.conversationId,
                origin = origin,
            )
    }
}

package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** Immutable privilege decision for one generation run. */
data class PrivilegedSessionContext(
    val assistantId: Uuid,
    val conversationId: Uuid,
    val origin: ToolCallOrigin,
    val privilegedConversationId: Uuid?,
    val identityName: String,
    val isPrivileged: Boolean,
    val expandLocalTools: Boolean,
    val autoApproveTools: Boolean,
    val unrestrictedOverride: Boolean,
) {
    companion object {
        fun ordinary(
            assistantId: Uuid,
            conversationId: Uuid,
            origin: ToolCallOrigin,
            unrestrictedOverride: Boolean = false,
        ) = PrivilegedSessionContext(
            assistantId = assistantId,
            conversationId = conversationId,
            origin = origin,
            privilegedConversationId = null,
            identityName = DEFAULT_PRIVILEGED_IDENTITY_NAME,
            isPrivileged = false,
            expandLocalTools = false,
            autoApproveTools = false,
            unrestrictedOverride = unrestrictedOverride,
        )
    }
}

fun interface PrivilegedSessionResolver {
    fun resolve(
        assistant: Assistant,
        conversation: Conversation,
        origin: ToolCallOrigin,
    ): PrivilegedSessionContext
}

object DefaultPrivilegedSessionResolver : PrivilegedSessionResolver {
    override fun resolve(
        assistant: Assistant,
        conversation: Conversation,
        origin: ToolCallOrigin,
    ): PrivilegedSessionContext {
        val selectedId = assistant.privilegedConversationId
        val isPrivileged = selectedId != null &&
            selectedId == conversation.id &&
            conversation.assistantId == assistant.id
        val identityName = assistant.privilegedIdentityName.trim()
            .ifEmpty { DEFAULT_PRIVILEGED_IDENTITY_NAME }
        val surface = InvocationSurfacePolicy.forOrigin(origin)

        // Once a privileged conversation is selected it becomes the only place where the
        // legacy unrestricted flag can take effect. Remote privileged runs get the expanded
        // tool surface and approval behavior, but still pass through every normal remote,
        // background and lock-screen gate.
        val unrestrictedOverride = if (selectedId != null) {
            isPrivileged && surface.allowsSelectedConversationUnrestricted
        } else {
            assistant.unrestricted && surface.allowsToolExecution
        }

        return PrivilegedSessionContext(
            assistantId = assistant.id,
            conversationId = conversation.id,
            origin = origin,
            privilegedConversationId = selectedId,
            identityName = identityName,
            isPrivileged = isPrivileged,
            expandLocalTools = isPrivileged && surface.allowsToolExecution,
            autoApproveTools = isPrivileged && surface.allowsAutoApproval,
            unrestrictedOverride = unrestrictedOverride,
        )
    }
}

const val DEFAULT_PRIVILEGED_IDENTITY_NAME = "第二用户"

package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
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
    /** Exact global authority snapshot; null means fail closed as an ordinary session. */
    val authoritySubjectId: String? = null,
    val authorityEpoch: Long? = null,
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
            authoritySubjectId = null,
            authorityEpoch = null,
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
        val authority = SecondUserAuthorityRegistry.current()
        val selectedId = authority?.conversationId
        val isPrivileged = authority != null &&
            authority.assistantId == assistant.id &&
            authority.conversationId == conversation.id &&
            conversation.assistantId == assistant.id
        val identityName = assistant.privilegedIdentityName.trim()
            .ifEmpty { DEFAULT_PRIVILEGED_IDENTITY_NAME }
        // A selected conversation identifies the second user, but it does not itself grant
        // authority. The local user must confirm the migration in the foreground UI, and no
        // remote origin is allowed to inherit this profile. `Assistant.unrestricted` is kept
        // only as an on-disk migration marker and deliberately has no runtime effect.
        val localSecondUser = isPrivileged && origin in InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER

        return PrivilegedSessionContext(
            assistantId = assistant.id,
            conversationId = conversation.id,
            origin = origin,
            privilegedConversationId = selectedId,
            identityName = identityName,
            isPrivileged = isPrivileged,
            expandLocalTools = localSecondUser,
            autoApproveTools = localSecondUser,
            unrestrictedOverride = false,
            authoritySubjectId = authority?.subjectId,
            authorityEpoch = authority?.authorityEpoch,
        )
    }

}

const val DEFAULT_PRIVILEGED_IDENTITY_NAME = "第二用户"

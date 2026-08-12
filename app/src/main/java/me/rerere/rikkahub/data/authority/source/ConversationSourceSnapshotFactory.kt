package me.rerere.rikkahub.data.authority.source

import java.security.MessageDigest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

/** Builds content-free authority input from the exact graph being persisted. */
object ConversationSourceSnapshotFactory {
    fun fromConversation(
        scope: ConversationSourceScope,
        conversation: Conversation,
        occurredAtMs: Long,
    ): ConversationSourceSnapshot {
        val messages = conversation.messageNodes
            .flatMap { node -> node.messages }
            .map { message ->
                MessageSourceSnapshot(
                    messageId = message.id.toString(),
                    messageRole = message.role.name,
                    payloadIntegritySha256 = payloadIntegritySha256(message),
                )
            }
        val selected = conversation.messageNodes.map { node -> node.currentMessage.id.toString() }
        return ConversationSourceSnapshot(
            scope = scope,
            conversationId = conversation.id.toString(),
            assistantIdSnapshot = conversation.assistantId.toString(),
            messages = messages,
            selectedBranchMessageIds = selected,
            occurredAtMs = occurredAtMs,
        )
    }

    fun deletedConversation(
        scope: ConversationSourceScope,
        conversationId: String,
        assistantIdSnapshot: String,
        occurredAtMs: Long,
    ): ConversationSourceSnapshot = ConversationSourceSnapshot(
        scope = scope,
        conversationId = conversationId,
        assistantIdSnapshot = assistantIdSnapshot,
        messages = emptyList(),
        selectedBranchMessageIds = emptyList(),
        occurredAtMs = occurredAtMs,
        conversationDeleted = true,
    )

    /**
     * Deterministic integrity digest of the stored message payload. The writer only compares this
     * value; it never converts it to a source revision or durable identity.
     */
    fun payloadIntegritySha256(message: UIMessage): String =
        MessageDigest.getInstance("SHA-256")
            .digest(JsonInstant.encodeToString(message).encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

object ConversationSourceScopeResolver {
    fun forCommand(
        assistantIdSnapshot: String,
        authoritySubjectId: String?,
    ): ConversationSourceScope = authoritySubjectId?.let { subjectId ->
        ConversationSourceScope(ConversationSourceScopeKind.AUTHORITY_SUBJECT, subjectId)
    } ?: ConversationSourceScope(
        ConversationSourceScopeKind.ASSISTANT,
        assistantIdSnapshot,
    )
}

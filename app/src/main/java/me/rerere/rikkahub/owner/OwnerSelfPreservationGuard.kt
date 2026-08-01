package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.privilege.PrivilegedManagementRequest
import kotlin.uuid.Uuid

/**
 * The small, semantic boundary an Owner model can never cross.
 *
 * This deliberately does not protect ordinary Providers, models, Workspaces, TTS profiles or
 * other application settings. Those remain freely manageable. It only prevents a model tool from
 * deleting or rewriting the identity which grants the tool its authority.
 */
class OwnerSelfPreservationGuard(
    private val activeAuthority: () -> SecondUserAdmissionSnapshot? = SecondUserAuthorityRegistry::current,
) {
    fun validate(request: PrivilegedManagementRequest): OwnerActionValidation? {
        val authority = activeAuthority() ?: return staleAuthority()
        return when (request) {
            is PrivilegedManagementRequest.ConversationDelete ->
                if (request.conversationId == authority.conversationId) protectedConversation() else null
            else -> null
        }
    }

    fun validate(action: OwnerAction): OwnerActionValidation? {
        val authority = activeAuthority() ?: return staleAuthority()
        if (containsProtectedIdentityField(action.arguments)) {
            return denied(
                "OWNER_AUTHORITY_FIELD_FORBIDDEN",
                "Owner actions cannot change authority, epoch, or protected-conversation identity fields.",
            )
        }
        return when (action.type) {
            "assistant_delete" -> action.arguments.uuidOrNull("assistant_id")
                ?.takeIf { it == authority.assistantId }
                ?.let { protectedAssistant() }
            "conversation_delete", "conversation_transfer", "conversation_move" ->
                action.arguments.uuidOrNull("conversation_id")
                    ?.takeIf { it == authority.conversationId }
                    ?.let { protectedConversation() }
            "authority_update", "authority_clear", "authority_reassign",
            "second_user_reset", "second_user_disable" -> denied(
                "OWNER_PERMANENT_PROTECTION",
                "Owner authority and epoch can only be changed by the user's authenticated recovery UI.",
            )
            else -> null
        }
    }

    private fun containsProtectedIdentityField(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) ->
            key.lowercase() in PROTECTED_FIELDS || containsProtectedIdentityField(value)
        }
        is kotlinx.serialization.json.JsonArray -> element.any(::containsProtectedIdentityField)
        else -> false
    }

    private fun JsonObject.uuidOrNull(name: String): Uuid? =
        this[name]?.let { element ->
            runCatching { Uuid.parse(element.toString().trim('"')) }.getOrNull()
        }

    private fun protectedAssistant() = denied(
        "OWNER_PERMANENT_PROTECTION",
        "The active second-user Assistant cannot be deleted by a model tool.",
    )

    private fun protectedConversation() = denied(
        "OWNER_PERMANENT_PROTECTION",
        "The fixed second-user conversation cannot be deleted or transferred by a model tool.",
    )

    private fun staleAuthority() = denied(
        "SECOND_USER_AUTHORITY_STALE",
        "The active second-user authority is no longer available.",
    )

    private fun denied(code: String, message: String) = OwnerActionValidation(false, code, message)

    private companion object {
        val PROTECTED_FIELDS = setOf(
            "authority",
            "authority_epoch",
            "authority_subject_id",
            "second_user_authority",
            "second_user_policy_confirmed",
            "privileged_conversation_id",
        )
    }
}

package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.privilege.PrivilegedManagementRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class OwnerSelfPreservationGuardTest {
    private val assistantId = Uuid.parse("81000000-0000-0000-0000-000000000001")
    private val conversationId = Uuid.parse("82000000-0000-0000-0000-000000000002")
    private val active = SecondUserAdmissionSnapshot.create(
        assistantId, conversationId, 7, ToolCallOrigin.LocalChat,
    )
    private val guard = OwnerSelfPreservationGuard { active }

    @Test
    fun `blocks deletion of owner assistant and fixed conversation`() {
        val assistant = guard.validate(action("assistant_delete", "assistant_id", assistantId.toString()))
        val conversation = guard.validate(action("conversation_delete", "conversation_id", conversationId.toString()))
        val compatibility = guard.validate(PrivilegedManagementRequest.ConversationDelete(conversationId))

        assertEquals("OWNER_PERMANENT_PROTECTION", assistant?.code)
        assertEquals("OWNER_PERMANENT_PROTECTION", conversation?.code)
        assertEquals("OWNER_PERMANENT_PROTECTION", compatibility?.code)
    }

    @Test
    fun `ordinary resources and other assistants remain freely manageable`() {
        assertNull(guard.validate(action("provider_delete", "provider_id", Uuid.random().toString())))
        assertNull(guard.validate(action("assistant_delete", "assistant_id", Uuid.random().toString())))
        assertNull(guard.validate(PrivilegedManagementRequest.ConversationDelete(Uuid.random())))
    }

    @Test
    fun `rejects indirect authority fields without rejecting ordinary assistant settings`() {
        val indirect = OwnerAction(
            type = "backup_restore_preserving_owner",
            arguments = buildJsonObject { put("authority_epoch", 0) },
            risk = OwnerOperationRisk.REVERSIBLE_WRITE,
        )
        val ordinary = OwnerAction(
            type = "assistant_update",
            arguments = buildJsonObject {
                put("assistant_id", JsonPrimitive(assistantId.toString()))
                put("system_prompt", JsonPrimitive("updated"))
            },
            risk = OwnerOperationRisk.REVERSIBLE_WRITE,
        )

        assertEquals("OWNER_AUTHORITY_FIELD_FORBIDDEN", guard.validate(indirect)?.code)
        assertNull(guard.validate(ordinary))
    }

    private fun action(type: String, key: String, value: String) = OwnerAction(
        type = type,
        arguments = buildJsonObject { put(key, value) },
        risk = OwnerOperationRisk.IRREVERSIBLE,
    )
}

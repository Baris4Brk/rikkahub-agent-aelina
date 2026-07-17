package me.rerere.rikkahub.privilege

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.createPrivilegedManagementTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PrivilegedManagementToolsTest {
    @Test
    fun `management surface has every planned tool and no protected settings fields`() {
        val context = context()
        val tools = createPrivilegedManagementTools(
            invocationContext = ToolInvocationContext(privilege = context),
            guard = DefaultPrivilegedActionGuard("me.rerere.rikkahub"),
            backend = PrivilegedManagementBackend { _, _ ->
                PrivilegedManagementResult.success("OK", "ok")
            },
        )

        assertEquals(
            setOf(
                "rikkahub_state_get",
                "conversation_create",
                "conversation_update",
                "conversation_delete",
                "assistant_update",
                "assistant_toggle_tool",
                "assistant_update_skills",
                "assistant_update_mcp_servers",
                "lorebook_create",
                "lorebook_update",
                "lorebook_delete",
                "mode_injection_update",
                "app_settings_update",
            ),
            tools.map { it.name }.toSet(),
        )

        val assistantSchema = tools.single { it.name == "assistant_update" }.parameters() as InputSchema.Obj
        assertFalse("unrestricted" in assistantSchema.properties)
        assertFalse("privileged_conversation_id" in assistantSchema.properties)
        assertFalse("privileged_identity_name" in assistantSchema.properties)
        assertFalse("assistant_id_replacement" in assistantSchema.properties)
        assertTrue("enable_web_search" in assistantSchema.properties)

        val appSchema = tools.single { it.name == "app_settings_update" }.parameters() as InputSchema.Obj
        assertFalse("agent_safety_settings" in appSchema.properties)
        assertFalse("emergency_stop" in appSchema.properties)
        assertFalse("database_version" in appSchema.properties)
        assertFalse("backup_internal_version" in appSchema.properties)
    }

    @Test
    fun `assistant and legacy app search fields parse without changing their target semantics`() = runBlocking {
        val context = context()
        val captured = mutableListOf<PrivilegedManagementRequest>()
        val tools = createPrivilegedManagementTools(
            invocationContext = ToolInvocationContext(privilege = context),
            guard = DefaultPrivilegedActionGuard("me.rerere.rikkahub"),
            backend = PrivilegedManagementBackend { request, _ ->
                captured += request
                PrivilegedManagementResult.success("OK", "ok")
            },
        )

        tools.single { it.name == "assistant_update" }.execute(buildJsonObject {
            put("assistant_id", context.assistantId.toString())
            put("enable_web_search", true)
        })
        tools.single { it.name == "app_settings_update" }.execute(buildJsonObject {
            put("enable_web_search", false)
        })

        assertEquals(
            true,
            (captured[0] as PrivilegedManagementRequest.AssistantUpdate).enableWebSearch,
        )
        assertEquals(
            false,
            (captured[1] as PrivilegedManagementRequest.AppSettingsUpdate).enableWebSearch,
        )
    }

    @Test
    fun `delete tool cannot delete its privileged conversation`() = runBlocking {
        val context = context()
        var backendCalled = false
        val tool = createPrivilegedManagementTools(
            invocationContext = ToolInvocationContext(privilege = context),
            guard = DefaultPrivilegedActionGuard("me.rerere.rikkahub"),
            backend = PrivilegedManagementBackend { _, _ ->
                backendCalled = true
                PrivilegedManagementResult.success("DELETED", "deleted")
            },
        ).single { it.name == "conversation_delete" }

        val result = tool.execute(buildJsonObject {
            put("conversation_id", context.conversationId.toString())
        })

        assertTrue(result.single().toString().contains("CURRENT_CONVERSATION_PROTECTED"))
        assertFalse(backendCalled)
    }

    private fun context(): PrivilegedSessionContext {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        return PrivilegedSessionContext(
            assistantId = assistantId,
            conversationId = conversationId,
            origin = ToolCallOrigin.LocalChat,
            privilegedConversationId = conversationId,
            identityName = "第二用户",
            isPrivileged = true,
            expandLocalTools = true,
            autoApproveTools = true,
            unrestrictedOverride = true,
        )
    }
}

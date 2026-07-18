package me.rerere.rikkahub.setup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SetupToolsTest {
    @Test
    fun `setup tool surface is available only to a complete non headless LocalChat`() {
        assertTrue(
            isSetupToolSurfaceAvailable(
                context(ToolCallOrigin.LocalChat, headless = false),
            ),
        )
        assertFalse(
            isSetupToolSurfaceAvailable(
                context(ToolCallOrigin.SystemAssistant, headless = false),
            ),
        )
        assertFalse(
            isSetupToolSurfaceAvailable(
                context(ToolCallOrigin.LocalChat, headless = true),
            ),
        )
    }

    @Test
    fun `setup tools require a complete local privileged chat and apply can never be always allowed`() = runBlocking {
        val coordinator = rejectingCoordinator()
        val local = createSetupTools(context(ToolCallOrigin.LocalChat, headless = false), coordinator)
        val systemAssistant = createSetupTools(
            context(ToolCallOrigin.SystemAssistant, headless = false),
            coordinator,
        )

        assertEquals(listOf("setup_plan", "setup_apply", "setup_verify"), local.map { it.name })
        assertTrue(local.single { it.name == "setup_apply" }.needsApproval(buildJsonObject {}))
        assertTrue("setup_apply" in me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.ALWAYS_ASK)
        assertFalse(me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults.allowsAlwaysAllow("setup_apply"))

        val denied = systemAssistant.single { it.name == "setup_plan" }.execute(
            buildJsonObject { put("changes", buildJsonArray {}) },
        ).singleTextJson()
        assertEquals("SETUP_LOCAL_PRIVILEGED_CHAT_REQUIRED", denied.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `setup plan parser rejects provider secrets and unsupported arbitrary fields`() = runBlocking {
        val tool = createSetupTools(
            context(ToolCallOrigin.LocalChat, headless = false),
            rejectingCoordinator(),
        ).single { it.name == "setup_plan" }

        val provider = tool.execute(buildJsonObject {
            put("changes", buildJsonArray {
                addJsonObject {
                    put("type", "provider")
                    put("api_key", "must-not-be-accepted")
                }
            })
        }).singleTextJson()
        val arbitrary = tool.execute(buildJsonObject {
            put("changes", buildJsonArray {
                addJsonObject {
                    put("type", "app_flag")
                    put("field", "dynamic_color")
                    put("enabled", false)
                    put("settings_key", "webdav_password")
                }
            })
        }).singleTextJson()

        assertEquals("P0_UNSUPPORTED_CHANGE", provider.getValue("code").jsonPrimitive.content)
        assertEquals("P0_UNSUPPORTED_FIELD", arbitrary.getValue("code").jsonPrimitive.content)
    }

    private fun rejectingCoordinator() = SetupTransactionCoordinator(
        backend = object : SetupTransactionBackend {
            override suspend fun prepare(change: SetupChange): SetupPrepareResult =
                SetupPrepareResult.Rejected("UNEXPECTED_BACKEND", "parser should reject first")

            override suspend fun compareAndSet(
                change: SetupPreparedChange,
                expected: SetupValue,
                update: SetupValue,
            ): SetupCasResult = error("not called")

            override suspend fun doctor(change: SetupPreparedChange): SetupDoctorCheck =
                error("not called")
        },
        auditLedger = SetupAuditLedger.NONE,
    )

    private fun context(origin: ToolCallOrigin, headless: Boolean): ToolInvocationContext {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        return ToolInvocationContext(
            callerAssistantId = assistantId.toString(),
            callerConversationId = conversationId.toString(),
            isHeadless = headless,
            privilege = PrivilegedSessionContext(
                assistantId = assistantId,
                conversationId = conversationId,
                origin = origin,
                privilegedConversationId = conversationId,
                identityName = "test",
                isPrivileged = true,
                expandLocalTools = true,
                autoApproveTools = false,
                unrestrictedOverride = false,
            ),
        )
    }

    private fun List<UIMessagePart>.singleTextJson() = Json.parseToJsonElement(
        (single() as UIMessagePart.Text).text,
    ).jsonObject
}

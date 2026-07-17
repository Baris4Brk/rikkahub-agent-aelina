package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.accessibility.UiExpectation
import me.rerere.rikkahub.accessibility.UiNodeSelector
import me.rerere.rikkahub.accessibility.UiScrollDirection
import me.rerere.rikkahub.accessibility.VerifiedAccessibilityController
import me.rerere.rikkahub.accessibility.VerifiedUiResult
import me.rerere.rikkahub.accessibility.VerifiedUiStep
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VerifiedAccessibilityToolsTest {
    @Test
    fun `factory exposes exactly the five verified tools`() {
        val names = verifiedAccessibilityTools(RecordingController()).map { it.name }
        assertEquals(
            listOf(
                "ui_wait_for_window",
                "ui_wait_for_node",
                "ui_click_node_verified",
                "ui_set_text_verified",
                "ui_scroll_until",
            ),
            names,
        )
    }

    @Test
    fun `verified tools inject only into privileged local non headless chat`() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val privileged = PrivilegedSessionContext(
            assistantId = assistantId,
            conversationId = conversationId,
            origin = ToolCallOrigin.LocalChat,
            privilegedConversationId = conversationId,
            identityName = "Second user",
            isPrivileged = true,
            expandLocalTools = true,
            autoApproveTools = true,
            unrestrictedOverride = true,
        )

        assertTrue(shouldInjectVerifiedAccessibilityTools(privileged, ToolCallOrigin.LocalChat, false))
        assertTrue(
            shouldInjectVerifiedAccessibilityTools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistant),
                ToolCallOrigin.SystemAssistant,
                false,
            ),
        )
        assertFalse(
            shouldInjectVerifiedAccessibilityTools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistantKeyguard),
                ToolCallOrigin.SystemAssistantKeyguard,
                false,
            ),
        )
        assertFalse(
            shouldInjectVerifiedAccessibilityTools(
                privileged.copy(isPrivileged = false),
                ToolCallOrigin.LocalChat,
                false,
            ),
        )
        assertFalse(shouldInjectVerifiedAccessibilityTools(privileged, ToolCallOrigin.Telegram, false))
        assertFalse(shouldInjectVerifiedAccessibilityTools(privileged, ToolCallOrigin.LocalChat, true))
    }

    @Test
    fun `unknown fields are rejected before controller`() {
        val controller = RecordingController()
        val result = execTool(
            uiWaitForNodeTool(controller),
            """{"selector":{"text":"OK"},"commandId":"model-controlled"}""",
        )

        assertTrue(result.contains("INVALID_ARGUMENT"))
        assertFalse(controller.called)
    }

    @Test
    fun `selector rejects unknown nested fields`() {
        val controller = RecordingController()
        val result = execTool(
            uiWaitForNodeTool(controller),
            """{"selector":{"text":"OK","regex":true}}""",
        )

        assertTrue(result.contains("INVALID_ARGUMENT"))
        assertFalse(controller.called)
    }

    @Test
    fun `set text output and streaming summary do not echo secret text`() {
        val controller = RecordingController()
        val result = execTool(
            uiSetTextVerifiedTool(controller),
            """{"selector":{"view_id":"app:id/code"},"text":"730194"}""",
        )

        assertTrue(result.contains("TEXT_VERIFIED"))
        assertFalse(result.contains("730194"))
        assertTrue(controller.called)
    }

    @Test
    fun `click requires matching expectation arguments`() {
        val controller = RecordingController()
        val result = execTool(
            uiClickNodeVerifiedTool(controller),
            """{"selector":{"text":"Open"},"expectation":"node_present"}""",
        )

        assertTrue(result.contains("INVALID_ARGUMENT"))
        assertFalse(controller.called)
    }

    @Test
    fun `scroll rejects direction outside enum before controller`() {
        val controller = RecordingController()
        val result = execTool(
            uiScrollUntilTool(controller),
            """{"selector":{"text":"Done"},"direction":"diagonal"}""",
        )

        assertTrue(result.contains("INVALID_ARGUMENT"))
        assertFalse(controller.called)
    }

    private class RecordingController : VerifiedAccessibilityController {
        var called = false

        override suspend fun waitForWindow(
            expectation: UiExpectation.WindowMatches,
            timeoutMs: Long,
        ) = ok("WINDOW_MATCHED")

        override suspend fun waitForNode(
            selector: UiNodeSelector,
            present: Boolean,
            timeoutMs: Long,
        ) = ok("NODE_FOUND")

        override suspend fun clickNodeVerified(
            selector: UiNodeSelector,
            nth: Int,
            expectation: UiExpectation,
            timeoutMs: Long,
        ) = ok("CLICK_EFFECT_VERIFIED")

        override suspend fun setTextVerified(
            selector: UiNodeSelector,
            text: String,
            nth: Int,
            timeoutMs: Long,
        ) = ok("TEXT_VERIFIED")

        override suspend fun scrollUntil(
            selector: UiNodeSelector,
            direction: UiScrollDirection,
            containerSelector: UiNodeSelector?,
            maxScrolls: Int,
            timeoutMs: Long,
        ) = ok("NODE_FOUND")

        private fun ok(code: String): VerifiedUiResult {
            called = true
            return VerifiedUiResult(true, code, "ok", VerifiedUiStep.VERIFY_ACTION)
        }
    }
}

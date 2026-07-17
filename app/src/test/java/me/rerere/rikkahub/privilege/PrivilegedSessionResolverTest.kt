package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PrivilegedSessionResolverTest {
    private val assistantId = Uuid.random()
    private val privilegedConversationId = Uuid.random()
    private val assistant = Assistant(
        id = assistantId,
        unrestricted = true,
        privilegedConversationId = privilegedConversationId,
        privilegedIdentityName = "第二用户",
    )

    @Test
    fun `selected local conversation gets expanded tools auto approval and unrestricted execution`() {
        val context = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation(privilegedConversationId),
            origin = ToolCallOrigin.LocalChat,
        )

        assertTrue(context.isPrivileged)
        assertTrue(context.expandLocalTools)
        assertTrue(context.autoApproveTools)
        assertTrue(context.unrestrictedOverride)
    }

    @Test
    fun `selected unlocked system assistant conversation gets local second user elevation`() {
        val context = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation(privilegedConversationId),
            origin = ToolCallOrigin.SystemAssistant,
        )

        assertTrue(context.isPrivileged)
        assertTrue(context.expandLocalTools)
        assertTrue(context.autoApproveTools)
        assertTrue(context.unrestrictedOverride)
    }

    @Test
    fun `selected remote conversation keeps privilege but not unrestricted execution`() {
        val context = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation(privilegedConversationId),
            origin = ToolCallOrigin.Telegram,
        )

        assertTrue(context.isPrivileged)
        assertTrue(context.expandLocalTools)
        assertTrue(context.autoApproveTools)
        assertFalse(context.unrestrictedOverride)
    }

    @Test
    fun `keyguard invocation cannot inherit second user tools approval or unrestricted`() {
        val context = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation(privilegedConversationId),
            origin = ToolCallOrigin.SystemAssistantKeyguard,
        )

        assertTrue(context.isPrivileged)
        assertFalse(context.expandLocalTools)
        assertFalse(context.autoApproveTools)
        assertFalse(context.unrestrictedOverride)
    }

    @Test
    fun `another conversation is ordinary after a privileged conversation is selected`() {
        val context = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation(Uuid.random()),
            origin = ToolCallOrigin.LocalChat,
        )

        assertFalse(context.isPrivileged)
        assertFalse(context.expandLocalTools)
        assertFalse(context.autoApproveTools)
        assertFalse(context.unrestrictedOverride)
    }

    @Test
    fun `legacy unrestricted behavior remains when no privileged conversation is selected`() {
        val context = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant.copy(privilegedConversationId = null),
            conversation = conversation(Uuid.random()),
            origin = ToolCallOrigin.WebServer,
        )

        assertFalse(context.isPrivileged)
        assertTrue(context.unrestrictedOverride)
    }

    @Test
    fun `conversation belonging to another assistant cannot inherit privilege by id`() {
        val context = DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = conversation(privilegedConversationId).copy(assistantId = Uuid.random()),
            origin = ToolCallOrigin.LocalChat,
        )

        assertFalse(context.isPrivileged)
        assertFalse(context.unrestrictedOverride)
    }

    private fun conversation(id: Uuid) = Conversation.ofId(id = id, assistantId = assistantId)
}

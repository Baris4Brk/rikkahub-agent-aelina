package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationLibraryReaderPolicyTest {
    private fun request(
        origin: ToolCallOrigin = ToolCallOrigin.SystemAssistant,
        selected: Boolean = true,
        enabled: Boolean = true,
        unlocked: Boolean = true,
    ) = ConversationReadAccessRequest(
        assistantId = Uuid.random(),
        privilegedConversationId = Uuid.random(),
        commandId = Uuid.random(),
        origin = origin,
        selectedPrivilegedConversation = selected,
        historyReadEnabled = enabled,
        deviceUnlocked = unlocked,
        operation = ConversationReadOperation.READ,
    )

    @Test
    fun `only confirmed enabled unlocked local second user is allowed`() {
        assertEquals(
            ConversationReadAccessDecision.Allowed,
            SecondUserConversationAccessPolicy.evaluate(request()),
        )
        assertTrue(SecondUserConversationAccessPolicy.evaluate(request(enabled = false)) is ConversationReadAccessDecision.Denied)
        assertTrue(SecondUserConversationAccessPolicy.evaluate(request(selected = false)) is ConversationReadAccessDecision.Denied)
        assertTrue(SecondUserConversationAccessPolicy.evaluate(request(unlocked = false)) is ConversationReadAccessDecision.Denied)
        assertTrue(
            SecondUserConversationAccessPolicy.evaluate(
                request(origin = ToolCallOrigin.Telegram)
            ) is ConversationReadAccessDecision.Denied
        )
        assertTrue(
            SecondUserConversationAccessPolicy.evaluate(
                request(origin = ToolCallOrigin.SystemAssistantKeyguard)
            ) is ConversationReadAccessDecision.Denied
        )
    }

    @Test
    fun `read and search budgets are isolated per command`() {
        val first = ConversationReadBudget(Uuid.random())
        repeat(MAX_CONVERSATION_READ_CALLS_PER_COMMAND) {
            assertTrue(first.consume(ConversationReadOperation.READ))
        }
        assertFalse(first.consume(ConversationReadOperation.READ))
        repeat(MAX_CONVERSATION_SEARCH_CALLS_PER_COMMAND) {
            assertTrue(first.consume(ConversationReadOperation.SEARCH))
        }
        assertFalse(first.consume(ConversationReadOperation.SEARCH))
        assertTrue(ConversationReadBudget(Uuid.random()).consume(ConversationReadOperation.READ))
    }

    @Test
    fun `selected candidate and visible text exclude reasoning media and tools`() {
        val discarded = UIMessage.user("discarded answer")
        val selected = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("hidden chain"),
                UIMessagePart.Text("visible answer"),
                UIMessagePart.Image("data:image/png;base64,SECRET"),
                UIMessagePart.Tool("call", "shell", "{}", listOf(UIMessagePart.Text("large secret output"))),
            ),
        )
        val entity = MessageNodeEntity(
            id = Uuid.random().toString(),
            conversationId = Uuid.random().toString(),
            nodeIndex = 9,
            messages = JsonInstant.encodeToString(listOf(discarded, selected)),
            selectIndex = 1,
        )

        val decoded = decodeSelectedVisibleMessage(entity)
        assertEquals(selected.id, decoded?.id)
        assertEquals("visible answer", decoded?.let(::visibleConversationText))
    }

    @Test
    fun `invalid selected index and system role are filtered`() {
        val conversationId = Uuid.random().toString()
        val invalid = MessageNodeEntity("n1", conversationId, 0, JsonInstant.encodeToString(listOf(UIMessage.user("x"))), 4)
        val system = MessageNodeEntity("n2", conversationId, 1, JsonInstant.encodeToString(listOf(UIMessage.system("secret"))), 0)
        assertEquals(null, decodeSelectedVisibleMessage(invalid))
        assertEquals(null, decodeSelectedVisibleMessage(system))
    }
}

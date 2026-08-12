package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.memory.MemorySourceVersion
import me.rerere.rikkahub.memory.memorySourceTextDigest
import me.rerere.rikkahub.memory.memoryToolSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSourceInvalidationPlanTest {
    @Test
    fun `same message id with edited text invalidates only the old content version`() {
        val oldVersion = sourceVersion("message-a", "before")
        val newVersion = sourceVersion("message-a", "after")
        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = "assistant-a",
            nextAssistantScopeId = "assistant-a",
            previousSelectedMessageIds = setOf("message-a"),
            nextSelectedMessageIds = setOf("message-a"),
            previousSelectedSourceVersions = setOf(oldVersion),
            nextSelectedSourceVersions = setOf(newVersion),
        )

        assertTrue(plan.removedMessageIds.isEmpty())
        assertEquals(setOf(oldVersion), plan.changedSourceVersions)
        assertEquals(
            setOf("assistant-a", MemoryRepository.GLOBAL_MEMORY_ID),
            plan.invalidateMessageScopeIds,
        )
    }

    @Test
    fun `legacy id baseline does not guess that an existing id was edited`() {
        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = "assistant-a",
            nextAssistantScopeId = "assistant-a",
            previousSelectedMessageIds = setOf("message-a"),
            nextSelectedMessageIds = setOf("message-a"),
        )

        assertTrue(plan.removedMessageIds.isEmpty())
        assertTrue(plan.changedSourceVersions.isEmpty())
        assertTrue(plan.invalidateMessageScopeIds.isEmpty())
    }

    @Test
    fun `same assistant invalidates removed messages in assistant and global scopes`() {
        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = "assistant-a",
            nextAssistantScopeId = "assistant-a",
            previousSelectedMessageIds = setOf("message-a", "message-b", "message-c"),
            nextSelectedMessageIds = setOf("message-a", "message-c"),
        )

        assertTrue(plan.invalidateWholeScopeIds.isEmpty())
        assertEquals(
            setOf("assistant-a", MemoryRepository.GLOBAL_MEMORY_ID),
            plan.invalidateMessageScopeIds,
        )
        assertEquals(setOf("message-b"), plan.removedMessageIds)
    }

    @Test
    fun `assistant migration invalidates the complete old assistant source but not global`() {
        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = "assistant-a",
            nextAssistantScopeId = "assistant-b",
            previousSelectedMessageIds = setOf("message-a", "message-b"),
            nextSelectedMessageIds = setOf("message-a"),
        )

        assertEquals(setOf("assistant-a"), plan.invalidateWholeScopeIds)
        assertEquals(setOf(MemoryRepository.GLOBAL_MEMORY_ID), plan.invalidateMessageScopeIds)
        assertEquals(setOf("message-b"), plan.removedMessageIds)
    }

    @Test
    fun `assistant migration without deletion leaves global sources valid`() {
        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = "assistant-a",
            nextAssistantScopeId = "assistant-b",
            previousSelectedMessageIds = setOf("message-a", "message-b"),
            nextSelectedMessageIds = setOf("message-b", "message-a"),
        )

        assertEquals(setOf("assistant-a"), plan.invalidateWholeScopeIds)
        assertTrue(plan.invalidateMessageScopeIds.isEmpty())
        assertTrue(plan.removedMessageIds.isEmpty())
    }

    @Test
    fun `append and reorder do not invalidate any source`() {
        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = "assistant-a",
            nextAssistantScopeId = "assistant-a",
            previousSelectedMessageIds = setOf("message-a", "message-b"),
            nextSelectedMessageIds = setOf("message-b", "message-c", "message-a"),
        )

        assertTrue(plan.invalidateWholeScopeIds.isEmpty())
        assertTrue(plan.invalidateMessageScopeIds.isEmpty())
        assertTrue(plan.removedMessageIds.isEmpty())
    }

    @Test
    fun `blank source ids are ignored rather than widening invalidation`() {
        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = " assistant-a ",
            nextAssistantScopeId = "assistant-a",
            previousSelectedMessageIds = setOf("", "  ", "message-a"),
            nextSelectedMessageIds = emptySet(),
        )

        assertEquals(setOf("message-a"), plan.removedMessageIds)
        assertEquals(
            setOf("assistant-a", MemoryRepository.GLOBAL_MEMORY_ID),
            plan.invalidateMessageScopeIds,
        )
    }

    @Test
    fun `switching the selected branch invalidates the previously selected message`() {
        val assistantId = Uuid.random()
        val oldBranch = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("old branch")),
        )
        val newBranch = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("new branch")),
        )
        val alternatives = listOf(oldBranch, newBranch)
        val before = Conversation(
            assistantId = assistantId,
            messageNodes = listOf(MessageNode(messages = alternatives, selectIndex = 0)),
        )
        val after = before.copy(
            messageNodes = listOf(MessageNode(messages = alternatives, selectIndex = 1)),
        )

        val plan = planConversationSourceInvalidation(
            previousAssistantScopeId = assistantId.toString(),
            nextAssistantScopeId = assistantId.toString(),
            previousSelectedMessageIds = before.selectedMessageIds(),
            nextSelectedMessageIds = after.selectedMessageIds(),
        )

        assertEquals(setOf(oldBranch.id.toString()), plan.removedMessageIds)
        assertEquals(
            setOf(assistantId.toString(), MemoryRepository.GLOBAL_MEMORY_ID),
            plan.invalidateMessageScopeIds,
        )
    }

    @Test
    fun `selected source versions separate text and executed tool output`() {
        val user = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("  durable text  "),
                UIMessagePart.Reasoning("private reasoning"),
                UIMessagePart.Image("content://media"),
            ),
        )
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("assistant answer"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    input = "input is not a source",
                    output = listOf(
                        UIMessagePart.Text("  tool result  "),
                        UIMessagePart.Reasoning("tool reasoning is not a source"),
                        UIMessagePart.Image("content://tool-media"),
                    ),
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-pending",
                    toolName = "pending",
                    input = "{}",
                ),
            ),
        )
        val legacyToolRole = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(UIMessagePart.Text("tool output")),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(user.toNode(), assistant.toNode(), legacyToolRole.toNode()),
        )
        val toolSourceId = memoryToolSourceId(
            assistantMessageId = assistant.id.toString(),
            partIndex = 1,
            toolCallId = "call-1",
        )

        assertEquals(
            setOf(
                sourceVersion(user.id.toString(), "durable text"),
                sourceVersion(assistant.id.toString(), "assistant answer"),
                sourceVersion(toolSourceId, "tool result"),
            ),
            conversation.selectedMemorySourceVersions(),
        )
    }

    private fun sourceVersion(messageId: String, text: String) = MemorySourceVersion(
        messageId = messageId,
        consumedTextDigest = memorySourceTextDigest(text),
    )

    private fun UIMessage.toNode() = MessageNode(messages = listOf(this), selectIndex = 0)
}

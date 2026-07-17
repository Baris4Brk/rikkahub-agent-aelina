package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.RunOutcome
import me.rerere.rikkahub.service.chat.SubmitResult
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `failed user message regeneration restores the original reply tree`() = runBlocking {
        val user = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("question")),
        )
        val reply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer that must survive")),
        )
        val original = me.rerere.rikkahub.data.model.Conversation.ofId(
            Uuid.random(),
            Uuid.random(),
            messages = listOf(MessageNode.of(user), MessageNode.of(reply)),
        )
        var current = original

        val outcome = runRegenerationTransaction(
            restore = { current = current.copy(messageNodes = original.messageNodes) },
            operation = {
                current = current.copy(messageNodes = current.messageNodes.take(1))
                RunOutcome.Failed(IllegalStateException("provider failed"))
            },
        )

        assertTrue(outcome is RunOutcome.Failed)
        assertEquals(original.messageNodes, current.messageNodes)
    }

    @Test
    fun `failed assistant regeneration restores its old branch without reverting metadata`() = runBlocking {
        val user = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("question")),
        )
        val originalReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("original answer")),
        )
        val partialReplacement = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("partial replacement")),
        )
        val original = me.rerere.rikkahub.data.model.Conversation.ofId(
            Uuid.random(),
            Uuid.random(),
            messages = listOf(MessageNode.of(user), MessageNode.of(originalReply)),
        )
        var current = original

        val outcome = runRegenerationTransaction(
            restore = { current = current.copy(messageNodes = original.messageNodes) },
            operation = {
                current = current
                    .updateCurrentMessages(listOf(user, partialReplacement))
                    .copy(title = "title updated while regenerating")
                RunOutcome.Failed(IllegalStateException("stream interrupted"))
            },
        )

        assertTrue(outcome is RunOutcome.Failed)
        assertEquals(original.messageNodes, current.messageNodes)
        assertEquals("title updated while regenerating", current.title)
    }

    @Test
    fun `cron headless scope exists only while its command runs`() = runBlocking {
        val conversationId = Uuid.random()

        withCommandHeadlessScope(conversationId, CommandOrigin.CRON) {
            assertTrue(HeadlessConversations.isHeadless(conversationId))
            assertTrue(HeadlessConversations.shouldAutoApprove(conversationId))
        }

        assertFalse(HeadlessConversations.isHeadless(conversationId))
        assertFalse(HeadlessConversations.shouldAutoApprove(conversationId))
    }

    @Test
    fun `cron headless scope is released when command fails`() = runBlocking {
        val conversationId = Uuid.random()

        runCatching {
            withCommandHeadlessScope(conversationId, CommandOrigin.CRON) {
                error("boom")
            }
        }

        assertFalse(HeadlessConversations.isHeadless(conversationId))
        assertFalse(HeadlessConversations.shouldAutoApprove(conversationId))
    }

    @Test
    fun `cron headless scope is released as soon as its run is cancelled`() = runBlocking {
        val conversationId = Uuid.random()
        val control = GenerationRunControl(Uuid.random())

        withCommandHeadlessScope(conversationId, CommandOrigin.CRON, control) {
            assertTrue(HeadlessConversations.shouldAutoApprove(conversationId))
            control.markInterruptedBy(Uuid.random())
            assertFalse(HeadlessConversations.isHeadless(conversationId))
            assertFalse(HeadlessConversations.shouldAutoApprove(conversationId))
        }

        assertFalse(HeadlessConversations.isHeadless(conversationId))
    }

    @Test
    fun `background metadata merges preserve latest conversation state`() {
        val current = me.rerere.rikkahub.data.model.Conversation.ofId(
            Uuid.random(),
            Uuid.random(),
        ).copy(
            title = "old title",
            customSystemPrompt = "latest prompt",
            workspaceCwd = "/latest/workspace",
            chatSuggestions = listOf("old suggestion"),
        )

        val withTitle = current.withGeneratedTitle("generated title")
        assertEquals(current, withTitle.copy(title = current.title))

        val withSuggestions = current.withGeneratedSuggestions(listOf("new suggestion"))
        assertEquals(current, withSuggestions.copy(chatSuggestions = current.chatSuggestions))
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `emergency stop attempts stop and queue clear for every runtime independently`() = runBlocking {
        val first = Uuid.random()
        val second = Uuid.random()
        val calls = mutableListOf<String>()

        val result = stopChatRuntimeSnapshot(
            listOf(
                ChatEmergencyRuntimeTarget(
                    conversationId = first,
                    submitStop = {
                        calls += "first-stop"
                        ChatEmergencyCommandSubmission(
                            SubmitResult.Accepted(Uuid.random()),
                            kotlinx.coroutines.CompletableDeferred(me.rerere.rikkahub.service.chat.CommandOutcome.Completed),
                        )
                    },
                    clearQueue = {
                        calls += "first-clear"
                        ChatEmergencyCommandSubmission(
                            SubmitResult.Accepted(Uuid.random()),
                            kotlinx.coroutines.CompletableDeferred(me.rerere.rikkahub.service.chat.CommandOutcome.Completed),
                        )
                    },
                ),
                ChatEmergencyRuntimeTarget(
                    conversationId = second,
                    submitStop = {
                        calls += "second-stop"
                        ChatEmergencyCommandSubmission(
                            SubmitResult.RuntimeUnavailable("closed"),
                            kotlinx.coroutines.CompletableDeferred(me.rerere.rikkahub.service.chat.CommandOutcome.Rejected("closed")),
                        )
                    },
                    clearQueue = {
                        calls += "second-clear"
                        ChatEmergencyCommandSubmission(
                            SubmitResult.Accepted(Uuid.random()),
                            kotlinx.coroutines.CompletableDeferred(me.rerere.rikkahub.service.chat.CommandOutcome.Completed),
                        )
                    },
                ),
            )
        )

        assertEquals(
            setOf("first-stop", "first-clear", "second-stop", "second-clear"),
            calls.toSet(),
        )
        assertEquals(4, calls.size)
        assertEquals(2, result.runtimeCount)
        assertEquals(1, result.stoppedRuntimeCount)
        assertEquals(2, result.clearedQueueCount)
        assertFalse(result.ok)
        assertEquals("closed", result.failures["$second:stop"])
    }
}

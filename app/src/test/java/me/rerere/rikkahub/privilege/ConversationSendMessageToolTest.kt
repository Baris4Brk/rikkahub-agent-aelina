package me.rerere.rikkahub.privilege

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.createConversationSendMessageTool
import me.rerere.rikkahub.service.chat.SubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSendMessageToolTest {
    @Test
    fun `second user message keeps identity and a scoped idempotency key`() = runBlocking {
        val assistantId = Uuid.random()
        val sourceId = Uuid.random()
        val targetId = Uuid.random()
        var captured: PrivilegedMessageSubmission? = null
        val tool = createConversationSendMessageTool(
            invocationContext = ToolInvocationContext(
                callerAssistantId = assistantId.toString(),
                callerConversationId = sourceId.toString(),
                privilege = privilegedContext(assistantId, sourceId),
            ),
            conversationExists = { it == targetId },
            submit = {
                captured = it
                SubmitResult.Accepted(Uuid.random())
            },
        )

        val result = tool.execute(buildJsonObject {
            put("conversation_id", targetId.toString())
            put("text", "继续处理")
            put("answer", true)
            put("request_id", "req-7")
        })

        assertTrue(result.single().toString().contains("ACCEPTED"))
        assertEquals("second-user:$sourceId:req-7", captured?.dedupeKey)
        val annotation = captured?.annotations?.single() as UIMessageAnnotation.SecondUser
        assertEquals(assistantId, annotation.sourceAssistantId)
        assertEquals(sourceId, annotation.sourceConversationId)
        assertEquals("第二用户", annotation.displayName)
    }

    @Test
    fun `second user cannot recursively submit to its own conversation`() = runBlocking {
        val assistantId = Uuid.random()
        val sourceId = Uuid.random()
        var submitted = false
        val tool = createConversationSendMessageTool(
            invocationContext = ToolInvocationContext(
                callerAssistantId = assistantId.toString(),
                callerConversationId = sourceId.toString(),
                privilege = privilegedContext(assistantId, sourceId),
            ),
            conversationExists = { true },
            submit = {
                submitted = true
                SubmitResult.Accepted(Uuid.random())
            },
        )

        val result = tool.execute(buildJsonObject {
            put("conversation_id", sourceId.toString())
            put("text", "loop")
        })

        assertTrue(result.single().toString().contains("SAME_CONVERSATION_NOT_SUPPORTED"))
        assertTrue(!submitted)
    }

    private fun privilegedContext(assistantId: Uuid, conversationId: Uuid) =
        PrivilegedSessionContext(
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

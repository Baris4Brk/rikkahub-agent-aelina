package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.StopCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemorySourceReadinessAndPostCommitTest {
    @Test
    fun `model-facing command waits for durable command recovery`() = runBlocking {
        val readiness = CompletableDeferred<Unit>()
        val command = SendMessageCommand(
            RawUserContent(parts = listOf(UIMessagePart.Text("hello"))),
        )
        val result = async { memorySourceReadinessFailureOrNull(command, readiness) }

        yield()
        assertFalse(result.isCompleted)
        readiness.complete(Unit)
        assertNull(result.await())
    }

    @Test
    fun `durable recovery failure rejects model command but stop bypasses barrier`() = runBlocking {
        val failure = IllegalStateException("reconciliation failed")
        val failedReadiness = CompletableDeferred<Unit>().also {
            it.completeExceptionally(failure)
        }
        val observedFailure = memorySourceReadinessFailureOrNull(
            SendMessageCommand(RawUserContent(listOf(UIMessagePart.Text("hello")))),
            failedReadiness,
        )
        assertTrue(observedFailure is IllegalStateException)
        assertEquals(failure.message, observedFailure?.message)

        val unfinishedReadiness = CompletableDeferred<Unit>()
        assertNull(memorySourceReadinessFailureOrNull(StopCommand(), unfinishedReadiness))
        assertFalse(unfinishedReadiness.isCompleted)
    }

    @Test
    fun `pending tool blocks post-commit capture and reports only selected branch`() {
        val pending = assistantMessage(
            UIMessagePart.Text("I need approval first"),
            pendingTool("selected-call"),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(pending.toMessageNode()),
        )

        assertEquals(setOf("selected-call"), conversation.selectedPendingToolIds())
        assertFalse(conversation.isEligibleForGenerationPostCommit())

        val completed = assistantMessage(UIMessagePart.Text("done"))
        val inactivePending = assistantMessage(pendingTool("inactive-call"))
        val selectedBranch = conversation.copy(
            messageNodes = listOf(
                MessageNode(
                    messages = listOf(completed, inactivePending),
                    selectIndex = 0,
                ),
            ),
        )
        assertTrue(selectedBranch.selectedPendingToolIds().isEmpty())
        assertTrue(selectedBranch.isEligibleForGenerationPostCommit())
    }

    @Test
    fun `incomplete final answer remains ineligible for post-commit`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("partial")),
                    state = UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER,
                ).toMessageNode(),
            ),
        )
        assertFalse(conversation.isEligibleForGenerationPostCommit())
    }

    private fun assistantMessage(vararg parts: UIMessagePart): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun pendingTool(id: String): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "confirm_action",
        input = "{}",
        approvalState = ToolApprovalState.Pending,
    )
}

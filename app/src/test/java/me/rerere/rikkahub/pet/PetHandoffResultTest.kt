package me.rerere.rikkahub.pet

import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.chat.CommandCodec
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.DurableCommandState
import me.rerere.rikkahub.service.withResponseCorrelation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetHandoffResultTest {
    @Test
    fun `answer extraction only returns assistant text for the exact handoff`() {
        val expected = UIMessageAnnotation.PetHandoff("command-a", "request-a")
        val unrelated = UIMessageAnnotation.PetHandoff("command-b", "request-b")
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(
                message(MessageRole.ASSISTANT, "correct answer", expected),
                message(MessageRole.ASSISTANT, "newer unrelated answer", unrelated),
            ),
        )

        assertEquals("correct answer", findPetHandoffAnswer(conversation, expected))
        assertNull(findPetHandoffAnswer(conversation, UIMessageAnnotation.PetHandoff("missing", "missing")))
    }

    @Test
    fun `durable handoff command preserves private response correlation`() {
        val annotation = UIMessageAnnotation.PetHandoff("command-a", "request-a")
        val command = SendMessageCommand(
            content = RawUserContent(
                parts = listOf(UIMessagePart.Text("do the task")),
                annotations = listOf(annotation),
            ),
        )

        val (type, payload) = CommandCodec.encodeDurable(command, CommandOrigin.PET_HANDOFF_CONFIRMED)
        val decoded = CommandCodec.decode(type, payload) as SendMessageCommand

        assertEquals(CommandOrigin.PET_HANDOFF_CONFIRMED, CommandCodec.decodeDurableOrigin(payload))
        assertEquals(listOf(annotation), decoded.content.annotations)
    }

    @Test
    fun `generation attaches handoff correlation only to answers after its source message`() {
        val annotation = UIMessageAnnotation.PetHandoff("command-a", "request-a")
        val earlier = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("earlier")))
        val source = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("do the task")),
            annotations = listOf(annotation),
        )
        val answer = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer")))

        val correlated = listOf(earlier, source, answer).withResponseCorrelation(annotation)

        assertEquals(emptyList<UIMessageAnnotation>(), correlated[0].annotations)
        assertEquals(listOf(annotation), correlated[1].annotations)
        assertEquals(listOf(annotation), correlated[2].annotations)
    }

    @Test
    fun `waiting tool output is not returned before the final answer`() {
        val annotation = UIMessageAnnotation.PetHandoff("command-a", "request-a")
        val source = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("do the task")),
            annotations = listOf(annotation),
        ).toMessageNode()
        val waiting = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("working")),
            annotations = listOf(annotation),
            state = UIMessageState.WAITING_TOOL,
        ).toMessageNode()
        val conversation = Conversation(Uuid.random(), Uuid.random(), messageNodes = listOf(source, waiting))

        assertNull(findPetHandoffAnswer(conversation, annotation))
    }

    @Test
    fun `streaming and approval states never cross the handoff completion barrier`() {
        assertEquals(false, isTerminalPetHandoffCommandState(DurableCommandState.RUNNING.name))
        assertEquals(false, isTerminalPetHandoffCommandState(DurableCommandState.WAITING_APPROVAL.name))
        assertEquals(true, isTerminalPetHandoffCommandState(DurableCommandState.COMPLETED.name))
        assertEquals(true, isTerminalPetHandoffCommandState(DurableCommandState.FAILED.name))
        assertEquals(true, isTerminalPetHandoffCommandState(DurableCommandState.CANCELLED.name))
    }

    private fun message(
        role: MessageRole,
        text: String,
        annotation: UIMessageAnnotation,
    ) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        annotations = listOf(annotation),
    ).toMessageNode()
}

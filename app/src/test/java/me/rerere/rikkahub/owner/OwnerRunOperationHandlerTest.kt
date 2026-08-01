package me.rerere.rikkahub.owner

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class OwnerRunOperationHandlerTest {
    private val ownerConversation = Uuid.parse("91000000-0000-0000-0000-000000000001")
    private val otherConversation = Uuid.parse("92000000-0000-0000-0000-000000000002")
    private val assistant = Uuid.parse("93000000-0000-0000-0000-000000000003")

    @Test
    fun `cancels another conversation through real controller and verifies readback`() = runBlocking {
        val active = Uuid.random()
        val controller = FakeController(mutableMapOf(
            otherConversation to OwnerRunSnapshot(true, "Running", active, emptySet()),
        ))
        val handler = OwnerRunOperationHandler(controller)
        val action = action("run_cancel", otherConversation, active)

        assertTrue(handler.validate(request(action), action, context()).ok)
        val applied = handler.apply(0, request(action), action, context())

        assertTrue(applied.result.ok)
        assertTrue(handler.verify(request(action), action, applied, context()).ok)
        assertEquals(otherConversation, controller.cancelledConversation)
    }

    @Test
    fun `does not let active owner tool cancel itself before result is committed`() = runBlocking {
        val active = Uuid.random()
        val controller = FakeController(mutableMapOf(
            ownerConversation to OwnerRunSnapshot(true, "Running", active, emptySet()),
        ))
        val handler = OwnerRunOperationHandler(controller)
        val action = action("run_cancel", ownerConversation, active)

        val applied = handler.apply(0, request(action), action, context())

        assertFalse(applied.result.ok)
        assertEquals("OWNER_ACTIVE_CALL_SELF_CANCEL_BLOCKED", applied.result.code)
    }

    @Test
    fun `allows queued command cancellation and retry in owner conversation`() = runBlocking {
        val pending = Uuid.random()
        val controller = FakeController(mutableMapOf(
            ownerConversation to OwnerRunSnapshot(true, "Running", Uuid.random(), setOf(pending)),
        ))
        val handler = OwnerRunOperationHandler(controller)
        val cancel = action("run_cancel", ownerConversation, pending)
        val retry = action("run_retry", ownerConversation)

        val cancelled = handler.apply(0, request(cancel), cancel, context())
        val retried = handler.apply(0, request(retry), retry, context())

        assertTrue(cancelled.result.ok)
        assertTrue(retried.result.ok)
        assertEquals(ownerConversation, controller.retriedConversation)
    }

    private fun action(type: String, conversation: Uuid, command: Uuid? = null) = OwnerAction(
        type = type,
        arguments = buildJsonObject {
            put("conversation_id", conversation.toString())
            command?.let { put("command_id", it.toString()) }
        },
        risk = OwnerOperationRisk.REVERSIBLE_WRITE,
    )

    private fun request(action: OwnerAction) = OwnerOperationRequest(
        requestId = "owner-run-test-request",
        family = OwnerToolFamily.RUN,
        actions = listOf(action),
        authoritySubjectId = "subject",
        authorityEpoch = 1,
        assistantId = assistant.toString(),
        conversationId = ownerConversation.toString(),
        modelId = "model",
        providerId = "provider",
    )

    private fun context() = PrivilegedSessionContext(
        assistantId = assistant,
        conversationId = ownerConversation,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = ownerConversation,
        identityName = "owner",
        isPrivileged = true,
        expandLocalTools = true,
        autoApproveTools = true,
        unrestrictedOverride = false,
        authoritySubjectId = "subject",
        authorityEpoch = 1,
    )
}

private class FakeController(
    private val states: MutableMap<Uuid, OwnerRunSnapshot>,
) : OwnerRunController {
    var cancelledConversation: Uuid? = null
    var retriedConversation: Uuid? = null

    override suspend fun snapshot(conversationId: Uuid): OwnerRunSnapshot =
        states[conversationId] ?: OwnerRunSnapshot(false, "Missing", null, emptySet())

    override suspend fun cancel(conversationId: Uuid, commandId: Uuid?): OwnerRunSubmission {
        cancelledConversation = conversationId
        val before = snapshot(conversationId)
        states[conversationId] = before.copy(
            runtimeState = if (commandId == null || commandId == before.activeCommandId) "Idle" else before.runtimeState,
            activeCommandId = before.activeCommandId.takeUnless { commandId == null || it == commandId },
            pendingCommandIds = commandId?.let { before.pendingCommandIds - it } ?: before.pendingCommandIds,
        )
        return OwnerRunSubmission(true, "ACCEPTED", Uuid.random())
    }

    override suspend fun retryLastAssistant(conversationId: Uuid): OwnerRunSubmission {
        retriedConversation = conversationId
        return OwnerRunSubmission(true, "ACCEPTED", Uuid.random())
    }
}

package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.ai.SteeringState
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SteeringHistoryMode
import me.rerere.rikkahub.service.chat.SteeringUiEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ChatSendDecisionTest {
    @Test
    fun `running short send guides with text and asks how to handle attachment only input`() {
        assertEquals(
            ChatSendAction.SOFT_STEER,
            resolveShortSendAction(RuntimeState.Running, hasInput = true, hasGuidanceText = true),
        )
        assertEquals(
            ChatSendAction.STOP,
            resolveShortSendAction(RuntimeState.Running, hasInput = false, hasGuidanceText = false),
        )
        assertEquals(
            ChatSendAction.SHOW_RUNNING_CHOICES,
            resolveShortSendAction(RuntimeState.Running, hasInput = true, hasGuidanceText = false),
        )
    }

    @Test
    fun `idle and approval states keep ordinary messages queued`() {
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.Idle, hasInput = true, hasGuidanceText = true),
        )
        assertEquals(
            ChatSendAction.QUEUE,
            resolveShortSendAction(RuntimeState.WaitingApproval, hasInput = true, hasGuidanceText = true),
        )
    }

    @Test
    fun `running long press exposes the three conversational choices`() {
        assertEquals(
            ChatSendAction.SHOW_RUNNING_CHOICES,
            resolveLongSendAction(RuntimeState.Running, hasInput = true),
        )
        assertEquals(
            ChatSendAction.SOFT_STEER,
            RunningSendChoice.CONTINUE_WITH_GUIDANCE.toSendAction(),
        )
        assertEquals(
            ChatSendAction.QUEUE,
            RunningSendChoice.HANDLE_AFTER_CURRENT_TASK.toSendAction(),
        )
        assertEquals(
            ChatSendAction.INTERRUPT,
            RunningSendChoice.STOP_AND_REPLACE.toSendAction(),
        )
    }

    @Test
    fun `visible guidance keeps editable cards and the latest fallback notice`() {
        val runId = Uuid.random()
        val appliedAfterRunFinished = steeringEntry(runId, SteeringState.APPLIED, editable = false)
        val pending = steeringEntry(runId, SteeringState.PENDING, editable = true)
        val oldFallback = steeringEntry(runId, SteeringState.FALLBACK_QUEUED, editable = false)
        val latestFallback = steeringEntry(runId, SteeringState.FALLBACK_QUEUED, editable = false)

        assertEquals(
            listOf(pending, latestFallback),
            selectVisibleSteeringEntries(
                listOf(appliedAfterRunFinished, pending, oldFallback, latestFallback)
            ),
        )
    }

    private fun steeringEntry(
        runId: Uuid,
        state: SteeringState,
        editable: Boolean,
    ) = SteeringUiEntry(
        commandId = Uuid.random(),
        runId = runId,
        text = state.name,
        state = state,
        historyMode = SteeringHistoryMode.TRANSIENT,
        editable = editable,
    )
}

package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.SteeringHistoryMode
import me.rerere.rikkahub.service.chat.SteeringScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationRunControlTest {
    @Test
    fun `persistent steering stays visible in UI but is not an unanswered provider user turn`() {
        val previousReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Finished the earlier task")),
        )
        val steering = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("以后优先保留原文件")),
            annotations = listOf(
                UIMessageAnnotation.Steering(
                    commandId = Uuid.random().toString(),
                    persistent = true,
                )
            ),
        )
        val nextQuestion = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("继续处理下一项")),
        )

        val prepared = preparePersistentSteeringContext(
            listOf(previousReply, steering, nextQuestion)
        )

        assertEquals(listOf(previousReply, nextQuestion), prepared.messages)
        assertEquals(
            "Persistent user guidance from earlier in this conversation:\n- 以后优先保留原文件",
            prepared.systemAddendum,
        )
    }

    @Test
    fun `transient steering stays visible in history but is removed from future provider context`() {
        val transient = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("这次任务先检查日志")),
            annotations = listOf(
                UIMessageAnnotation.Steering(
                    commandId = Uuid.random().toString(),
                    persistent = false,
                )
            ),
        )
        val ordinary = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("继续处理下一项")),
        )

        val prepared = preparePersistentSteeringContext(listOf(transient, ordinary))

        assertEquals(listOf(ordinary), prepared.messages)
        assertEquals(null, prepared.systemAddendum)
    }

    @Test
    fun `second user annotation becomes an explicit provider identity envelope`() {
        val secondUser = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("请继续整理这个项目")),
            annotations = listOf(
                UIMessageAnnotation.SecondUser(
                    sourceAssistantId = Uuid.random(),
                    sourceConversationId = Uuid.random(),
                    displayName = "  七姐\n第二用户  ",
                )
            ),
        )
        val ordinary = UIMessage.user("这是普通用户消息")

        val prepared = prepareSecondUserProviderMessages(listOf(secondUser, ordinary))
        val prefix = prepared.first().parts.first() as UIMessagePart.Text

        assertTrue(prefix.text.contains("[第二用户消息]"))
        assertTrue(prefix.text.contains("发送者身份：七姐 第二用户"))
        assertTrue(prefix.text.contains("不是当前会话操作者"))
        assertEquals("请继续整理这个项目", prepared.first().parts.last().let {
            (it as UIMessagePart.Text).text
        })
        assertEquals(ordinary, prepared[1])
    }

    @Test
    fun `duplicate persistent steering command is added to guidance once`() {
        val commandId = Uuid.random().toString()
        val first = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("以后优先保留原文件")),
            annotations = listOf(UIMessageAnnotation.Steering(commandId, persistent = true)),
        )
        val duplicate = first.copy()

        val prepared = preparePersistentSteeringContext(listOf(first, duplicate))

        assertTrue(prepared.messages.isEmpty())
        assertEquals(
            "Persistent user guidance from earlier in this conversation:\n- 以后优先保留原文件",
            prepared.systemAddendum,
        )
    }

    @Test
    fun `next model call steering is linearized and delivered once`() {
        val transitions = mutableListOf<SteeringTransition>()
        val control = GenerationRunControl(Uuid.random(), transitions::add)
        val commandId = Uuid.random()
        val note = SteeringNote(
            commandId = commandId,
            runId = control.runId,
            text = "Focus on the failure path",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.NEXT_MODEL_CALL,
        )

        assertEquals(SteeringRegistrationResult.Accepted, control.submitSteering(note))
        val deliveries = control.takeSteeringForCheckpoint(0)
        assertEquals(
            listOf(SteeringDelivery(note, firstApplication = true)),
            deliveries,
        )
        control.markSteeringProviderStarted(deliveries)
        assertTrue(control.takeSteeringForCheckpoint(1).isEmpty())
        assertEquals(
            listOf(
                SteeringTransition(commandId, SteeringState.PENDING),
                SteeringTransition(commandId, SteeringState.DELIVERING),
                SteeringTransition(commandId, SteeringState.APPLIED),
            ),
            transitions,
        )
    }

    @Test
    fun `next model call steering is only applied after provider output starts`() {
        val control = GenerationRunControl(Uuid.random())
        val commandId = Uuid.random()
        val note = SteeringNote(
            commandId = commandId,
            runId = control.runId,
            text = "Use the new requirement in this run",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.NEXT_MODEL_CALL,
        )
        control.submitSteering(note)

        val deliveries = control.takeSteeringForCheckpoint(0)

        assertEquals(SteeringState.DELIVERING, control.steeringStates()[commandId])
        assertTrue(control.hasUndeliveredSteering())
        assertTrue(control.takeSteeringForCheckpoint(1).isEmpty())

        control.markSteeringProviderStarted(deliveries)

        assertEquals(SteeringState.APPLIED, control.steeringStates()[commandId])
        assertTrue(!control.hasUndeliveredSteering())
        assertTrue(control.takeSteeringForCheckpoint(2).isEmpty())
    }

    @Test
    fun `provider failure before first output returns steering to the same run queue`() {
        val control = GenerationRunControl(Uuid.random())
        val note = SteeringNote(
            commandId = Uuid.random(),
            runId = control.runId,
            text = "Retry this guidance in the active run",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.NEXT_MODEL_CALL,
        )
        control.submitSteering(note)
        val firstAttempt = control.takeSteeringForCheckpoint(0)

        control.markSteeringDeliveryFailed(firstAttempt)

        assertEquals(SteeringState.PENDING, control.steeringStates()[note.commandId])
        assertEquals(
            listOf(SteeringDelivery(note, firstApplication = true)),
            control.takeSteeringForCheckpoint(1),
        )
    }

    @Test
    fun `steering is injected as a temporary trailing user guidance message`() {
        val control = GenerationRunControl(Uuid.random())
        val note = SteeringNote(
            commandId = Uuid.random(),
            runId = control.runId,
            text = "先保留已经完成的下载，再修改后续步骤",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.NEXT_MODEL_CALL,
        )
        control.submitSteering(note)

        val message = buildSteeringUserGuidanceMessage(control.takeSteeringForCheckpoint(0))

        assertEquals(MessageRole.USER, message?.role)
        val text = message?.parts?.single() as UIMessagePart.Text
        assertTrue(text.text.contains(note.text))
        assertTrue(text.text.contains("停止尚未开始的旧计划步骤"))
        assertEquals(null, buildSteeringUserGuidanceMessage(emptyList()))
    }

    @Test
    fun `provider tail appends live steering after persisted context without mutating history`() {
        val history = listOf(UIMessage.user("Original request"))
        val note = SteeringNote(
            commandId = Uuid.random(),
            runId = Uuid.random(),
            text = "Use the newly supplied constraint",
            source = CommandOrigin.APP_UI,
        )
        val tail = ProviderTailMessages.fromSteering(
            listOf(SteeringDelivery(note, firstApplication = true)),
        )

        val providerMessages = tail.appendTo(history)

        assertEquals(1, history.size)
        assertEquals(2, providerMessages.size)
        assertEquals(MessageRole.USER, providerMessages.last().role)
        assertTrue(providerMessages.last().parts.filterIsInstance<UIMessagePart.Text>()
            .single().text.contains(note.text))
    }

    @Test
    fun `tool-only loop does not consume next model call steering`() {
        val control = GenerationRunControl(Uuid.random())
        val commandId = Uuid.random()
        val note = SteeringNote(
            commandId = commandId,
            runId = control.runId,
            text = "Apply after the current tool batch",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.NEXT_MODEL_CALL,
        )
        assertEquals(SteeringRegistrationResult.Accepted, control.submitSteering(note))

        val toolOnlyCheckpoint = takeSteeringForProviderCheckpoint(
            runControl = control,
            modelCallIndex = 1,
            hasResumableTools = true,
        )
        assertTrue(toolOnlyCheckpoint.isEmpty())
        assertEquals(null, buildSteeringSystemAddendum(toolOnlyCheckpoint))
        assertEquals(SteeringState.PENDING, control.steeringStates()[commandId])

        val nextProviderCheckpoint = takeSteeringForProviderCheckpoint(
            runControl = control,
            modelCallIndex = 2,
            hasResumableTools = false,
        )
        assertEquals(
            listOf(SteeringDelivery(note, firstApplication = true)),
            nextProviderCheckpoint,
        )
        assertEquals(
            "User guidance for this run: Apply after the current tool batch",
            buildSteeringSystemAddendum(nextProviderCheckpoint),
        )
        assertEquals(SteeringState.DELIVERING, control.steeringStates()[commandId])
        control.markSteeringProviderStarted(nextProviderCheckpoint)
        assertEquals(SteeringState.APPLIED, control.steeringStates()[commandId])
        assertTrue(
            takeSteeringForProviderCheckpoint(
                runControl = control,
                modelCallIndex = 3,
                hasResumableTools = false,
            ).isEmpty()
        )
    }

    @Test
    fun `guidance received before a tool starts yields the old tool plan`() {
        val control = GenerationRunControl(Uuid.random())
        val note = SteeringNote(
            commandId = Uuid.random(),
            runId = control.runId,
            text = "Use the new requirement before running tools",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.NEXT_MODEL_CALL,
        )
        assertEquals(SteeringRegistrationResult.Accepted, control.submitSteering(note))

        assertTrue(control.hasUndeliveredSteering())
        assertEquals(
            ToolStartDecision.YieldToSteering,
            control.beginToolExecutionOrYieldToSteering("tool-1"),
        )
        assertTrue(control.activeToolCallIds().isEmpty())
    }

    @Test
    fun `guidance received during a tool lets it finish but blocks the next tool`() {
        val control = GenerationRunControl(Uuid.random())
        assertEquals(
            ToolStartDecision.Proceed,
            control.beginToolExecutionOrYieldToSteering("tool-1"),
        )

        val note = SteeringNote(
            commandId = Uuid.random(),
            runId = control.runId,
            text = "Change the remaining plan after this tool",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.NEXT_MODEL_CALL,
        )
        assertEquals(SteeringRegistrationResult.Accepted, control.submitSteering(note))

        control.finishToolExecution("tool-1")
        assertEquals(
            ToolStartDecision.YieldToSteering,
            control.beginToolExecutionOrYieldToSteering("tool-2"),
        )
        assertTrue(control.hasUndeliveredSteering())
    }

    @Test
    fun `cancelled run rejects new tools and fenced updates`() = runBlocking {
        val control = GenerationRunControl(Uuid.random())
        control.markInterruptedBy(Uuid.random())

        assertEquals(
            ToolStartDecision.RunCancelled,
            control.beginToolExecutionOrYieldToSteering("tool-after-cancel"),
        )
        control.fenceUpdates()
        var updated = false
        assertTrue(!control.runIfUpdatesAllowed { updated = true })
        assertTrue(!updated)
    }

    @Test
    fun `unfinished steering is explicitly reported when the run closes`() {
        val control = GenerationRunControl(Uuid.random())
        val commandId = Uuid.random()
        control.submitSteering(
            SteeringNote(
                commandId = commandId,
                runId = control.runId,
                text = "This run may finish before a checkpoint",
                source = CommandOrigin.APP_UI,
            )
        )

        assertEquals(
            listOf(
                SteeringTransition(
                    commandId,
                    SteeringState.NOT_APPLIED_RUN_FINISHED,
                    "Run finished before the next model checkpoint",
                )
            ),
            control.closeSteering(),
        )
    }

    @Test
    fun `steering over the per-run budget is rejected`() {
        val control = GenerationRunControl(Uuid.random())
        val result = control.submitSteering(
            SteeringNote(
                commandId = Uuid.random(),
                runId = control.runId,
                text = "x".repeat(GenerationRunControl.MAX_STEERING_TOKENS * 4 + 1),
                source = CommandOrigin.APP_UI,
            )
        )

        assertTrue(result is SteeringRegistrationResult.Rejected)
    }

    @Test
    fun `remainder steering stays visible for every later model checkpoint`() {
        val control = GenerationRunControl(Uuid.random())
        val commandId = Uuid.random()
        val note = SteeringNote(
            commandId = commandId,
            runId = control.runId,
            text = "Keep the answer concise",
            source = CommandOrigin.APP_UI,
            scope = SteeringScope.REMAINDER_OF_RUN,
        )

        assertEquals(SteeringRegistrationResult.Accepted, control.submitSteering(note))
        val firstDelivery = control.takeSteeringForCheckpoint(0)
        assertEquals(
            listOf(SteeringDelivery(note, firstApplication = true)),
            firstDelivery,
        )
        control.markSteeringProviderStarted(firstDelivery)
        assertEquals(
            listOf(SteeringDelivery(note, firstApplication = false)),
            control.takeSteeringForCheckpoint(1),
        )
        assertEquals(SteeringState.APPLIED, control.steeringStates()[commandId])
        assertTrue(control.closeSteering().isEmpty())
        assertTrue(control.pendingSteering().isEmpty())

        val nextRun = GenerationRunControl(Uuid.random())
        assertTrue(
            takeSteeringForProviderCheckpoint(
                runControl = nextRun,
                modelCallIndex = 0,
                hasResumableTools = false,
            ).isEmpty()
        )
        assertEquals(null, buildSteeringSystemAddendum(emptyList()))
    }

    @Test
    fun `steering for another run is rejected without entering the queue`() {
        val control = GenerationRunControl(Uuid.random())
        val result = control.submitSteering(
            SteeringNote(
                commandId = Uuid.random(),
                runId = Uuid.random(),
                text = "must not leak across runs",
                source = CommandOrigin.APP_UI,
            )
        )

        assertTrue(result is SteeringRegistrationResult.Rejected)
        assertTrue(control.pendingSteering().isEmpty())
    }

    @Test
    fun `single steering note is bounded by the four kib limit`() {
        val control = GenerationRunControl(Uuid.random())
        val result = control.submitSteering(
            SteeringNote(
                commandId = Uuid.random(),
                runId = control.runId,
                text = "x".repeat(GenerationRunControl.MAX_STEERING_CHARS + 1),
                source = CommandOrigin.APP_UI,
            )
        )

        assertTrue(result is SteeringRegistrationResult.Rejected)
    }

    @Test
    fun `steering history mode can change until the run closes`() {
        val control = GenerationRunControl(Uuid.random())
        val commandId = Uuid.random()
        val note = SteeringNote(
            commandId = commandId,
            runId = control.runId,
            text = "Remember this for later",
            source = CommandOrigin.APP_UI,
        )
        control.submitSteering(note)

        assertTrue(control.updateSteeringHistoryMode(commandId, SteeringHistoryMode.PERSISTENT))
        val updated = note.copy(historyMode = SteeringHistoryMode.PERSISTENT)
        assertEquals(
            listOf(SteeringDelivery(updated, firstApplication = true)),
            control.takeSteeringForCheckpoint(0),
        )
        assertTrue(control.updateSteeringHistoryMode(commandId, SteeringHistoryMode.TRANSIENT))
        assertEquals(SteeringHistoryMode.TRANSIENT, control.steeringNotes()[commandId]?.historyMode)
        control.closeSteering()
        assertTrue(!control.updateSteeringHistoryMode(commandId, SteeringHistoryMode.PERSISTENT))
    }

    @Test
    fun `closed run rejects new steering without retaining it`() {
        val control = GenerationRunControl(Uuid.random())
        control.closeSteering()

        assertEquals(
            SteeringRegistrationResult.RunClosed,
            control.submitSteering(
                SteeringNote(
                    commandId = Uuid.random(),
                    runId = control.runId,
                    text = "too late",
                    source = CommandOrigin.APP_UI,
                )
            ),
        )
        assertTrue(control.pendingSteering().isEmpty())
    }

    @Test
    fun `provider cancellation result is recorded without marking the run cancelled`() {
        val control = GenerationRunControl(Uuid.random())
        var cancelled = false
        val registration = control.registerProviderCancel { cancelled = true }

        assertEquals(
            CancelRequestResult.Requested,
            control.requestProviderCancel(ToolCancelReason.STEERING_OVERRIDE),
        )
        assertTrue(cancelled)
        assertEquals(CancelRequestResult.Requested, control.providerCancellationResult())
        assertTrue(!control.isRunCancellationRequested())

        registration.close()
    }

    @Test
    fun `skipped old-plan tool gets one idempotent structured result`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-guidance",
            toolName = "old_plan_step",
            input = "{}",
            output = emptyList(),
        )

        val skipped = tool.skippedDueToGuidance()
        val skippedAgain = skipped.skippedDueToGuidance()

        assertTrue(skipped.isExecuted)
        assertEquals(1, skipped.output.size)
        assertTrue((skipped.output.single() as UIMessagePart.Text).text.contains("skipped_due_to_guidance"))
        assertEquals(skipped, skippedAgain)
    }

    @Test
    fun `tool cancellation results are retained for partial repair`() {
        val control = GenerationRunControl(Uuid.random())
        val handle = object : ToolExecutionHandle {
            override val executionId: String = "local-tool"
            override suspend fun awaitResult(): List<UIMessagePart> = emptyList()
            override fun requestCancel(reason: ToolCancelReason): CancelRequestResult =
                CancelRequestResult.LocalWaitCancelledOnly
            override suspend fun awaitTermination(gracePeriod: kotlin.time.Duration): ToolTerminationState =
                ToolTerminationState.StoppedConfirmed
        }
        control.registerTool("call-1", handle)

        val result = control.requestCancelAllTools(ToolCancelReason.USER_INTERRUPTED)

        assertEquals(CancelRequestResult.LocalWaitCancelledOnly, result["call-1"])
        assertEquals(result, control.toolCancellationResults())
    }
}

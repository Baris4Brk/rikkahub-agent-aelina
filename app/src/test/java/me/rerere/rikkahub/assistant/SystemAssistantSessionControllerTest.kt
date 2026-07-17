package me.rerere.rikkahub.assistant

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.FinalAnswerRecoveryStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.QueueStatus
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SubmitResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SystemAssistantSessionControllerTest {
    @Test
    fun `resolved target becomes usable while cancellable history hydration continues`() {
        val fixture = Fixture()
        val hydrationGate = CompletableDeferred<Unit>()
        fixture.backend.hydrationGate = hydrationGate
        val controller = fixture.controller()

        fixture.runCurrent()

        assertEquals(fixture.assistant.id, controller.state.value.assistantId)
        assertTrue(controller.state.value.canSubmit)
        assertEquals(
            SystemAssistantHistoryUiState.Loading,
            controller.state.value.history,
        )
        assertEquals(listOf(fixture.conversationId), fixture.backend.hydrationCalls)

        controller.close()
        fixture.runCurrent()

        assertTrue(fixture.backend.hydrationCancelled)
        fixture.close()
    }

    @Test
    fun `second user history is not presented as owner history in the overlay`() {
        val secondUserMessage = UIMessage.user("message from Seven").copy(
            annotations = listOf(
                UIMessageAnnotation.SecondUser(
                    sourceAssistantId = Uuid.random(),
                    sourceConversationId = Uuid.random(),
                    displayName = "Seven",
                )
            ),
        )
        val fixture = Fixture(
            initialMessages = listOf(
                UIMessage.user("message from owner").toMessageNode(),
                UIMessage.assistant("owner answer").toMessageNode(),
                secondUserMessage.toMessageNode(),
                UIMessage.assistant("second user answer").toMessageNode(),
            ),
        )
        val controller = fixture.controller()
        fixture.runCurrent()

        assertEquals("message from owner", controller.state.value.latestUserText)
        assertEquals("owner answer", controller.state.value.latestAssistantText)
        assertFalse(controller.state.value.messages.any { it.text == "message from Seven" })
        assertFalse(controller.state.value.messages.any { it.text == "second user answer" })

        controller.close()
        fixture.close()
    }

    @Test
    fun `resolved target binds identity messages runtime and queue flows`() {
        val fixture = Fixture(
            initialMessages = listOf(
                UIMessage.system("hidden").toMessageNode(),
                UIMessage.user("first user").toMessageNode(),
                UIMessage.assistant("first answer").toMessageNode(),
            ),
        )
        val controller = fixture.controller(recentMessageLimit = 2)
        fixture.runCurrent()

        assertEquals(fixture.assistant.id, controller.state.value.assistantId)
        assertEquals("Rikka", controller.state.value.assistantName)
        assertEquals("Second user", controller.state.value.displayName)
        assertEquals("first user", controller.state.value.latestUserText)
        assertEquals("first answer", controller.state.value.latestAssistantText)
        assertEquals(RuntimeState.Idle, controller.state.value.runtimeState)
        assertEquals(0, controller.state.value.queueStatus?.pendingCount)

        fixture.chat.runtime.value = RuntimeState.Running
        fixture.chat.queue.value = QueueStatus(
            paused = false,
            pendingCount = 2,
            activeCommandId = Uuid.random(),
        )
        fixture.chat.conversation.value = fixture.conversation(
            listOf(
                UIMessage.user("older").toMessageNode(),
                UIMessage.assistant("older answer").toMessageNode(),
                UIMessage.user("latest").toMessageNode(),
            )
        )
        fixture.runCurrent()

        assertEquals(RuntimeState.Running, controller.state.value.runtimeState)
        assertEquals(2, controller.state.value.queueStatus?.pendingCount)
        assertEquals(2, controller.state.value.messages.size)
        assertEquals("latest", controller.state.value.latestUserText)
        assertNull(controller.state.value.latestAssistantText)

        controller.close()
        fixture.close()
    }

    @Test
    fun `latest incomplete owner turn exposes recovery progress without an old answer`() {
        val commandId = Uuid.random().toString()
        val recovering = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("tool result considered")),
            annotations = listOf(
                UIMessageAnnotation.FinalAnswerRecovery(
                    commandId = commandId,
                    reason = "missing visible answer",
                    status = FinalAnswerRecoveryStatus.STARTED,
                    attempt = 3,
                )
            ),
            state = UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER,
        )
        val fixture = Fixture(
            initialMessages = listOf(
                UIMessage.user("older question").toMessageNode(),
                UIMessage.assistant("older answer must not reappear").toMessageNode(),
                UIMessage.user("current question").toMessageNode(),
                recovering.toMessageNode(),
            ),
        )
        val controller = fixture.controller()
        fixture.runCurrent()

        assertEquals("current question", controller.state.value.latestUserText)
        assertNull(controller.state.value.latestAssistantText)
        assertEquals(
            SystemAssistantAnswerUiState.Recovering(attempt = 3, maxAttempts = 10),
            controller.state.value.answer,
        )

        controller.close()
        fixture.close()
    }

    @Test
    fun `failed final answer recovery is visible and never falls back to a prior turn`() {
        val failed = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("still no answer")),
            annotations = listOf(
                UIMessageAnnotation.FinalAnswerRecovery(
                    commandId = Uuid.random().toString(),
                    reason = "missing visible answer",
                    status = FinalAnswerRecoveryStatus.FAILED,
                    attempt = 10,
                )
            ),
            state = UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER,
        )
        val fixture = Fixture(
            initialMessages = listOf(
                UIMessage.user("old question").toMessageNode(),
                UIMessage.assistant("stale old answer").toMessageNode(),
                UIMessage.user("new question").toMessageNode(),
                failed.toMessageNode(),
            ),
        )
        val controller = fixture.controller()
        fixture.runCurrent()

        assertNull(controller.state.value.latestAssistantText)
        assertEquals(
            SystemAssistantAnswerUiState.RecoveryFailed(attempt = 10, maxAttempts = 10),
            controller.state.value.answer,
        )

        controller.close()
        fixture.close()
    }

    @Test
    fun `incomplete answer without recovery annotation is reported as failed`() {
        val incomplete = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("reasoning only")),
            state = UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER,
        )
        val fixture = Fixture(
            initialMessages = listOf(
                UIMessage.user("question").toMessageNode(),
                incomplete.toMessageNode(),
            ),
        )
        val controller = fixture.controller()
        fixture.runCurrent()

        assertNull(controller.state.value.latestAssistantText)
        assertEquals(
            SystemAssistantAnswerUiState.RecoveryFailed(attempt = null, maxAttempts = 10),
            controller.state.value.answer,
        )

        controller.close()
        fixture.close()
    }

    @Test
    fun `text is trimmed validated and every accepted call has a unique dedupe key`() = runBlocking {
        val fixture = Fixture()
        val controller = fixture.controller()
        fixture.runCurrent()

        val blank = controller.submitText("  \n  ") as SystemAssistantSubmitResult.Rejected
        val tooLong = controller.submitText(
            "  " + "x".repeat(SYSTEM_ASSISTANT_MAX_TEXT_LENGTH + 1) + "  "
        ) as SystemAssistantSubmitResult.Rejected
        assertEquals(SystemAssistantSubmissionErrorCode.EMPTY_TEXT, blank.code)
        assertEquals(SystemAssistantSubmissionErrorCode.TEXT_TOO_LONG, tooLong.code)
        assertTrue(fixture.backend.submissions.isEmpty())

        val first = controller.submitText("  hello  ")
        val second = controller.submitText("world")

        assertTrue(first is SystemAssistantSubmitResult.Accepted)
        assertTrue(second is SystemAssistantSubmitResult.Accepted)
        assertEquals(listOf("hello", "world"), fixture.backend.submissions.map { it.text })
        assertNotEquals(
            fixture.backend.submissions[0].dedupeKey,
            fixture.backend.submissions[1].dedupeKey,
        )
        assertEquals(3, fixture.target.resolutionReads)

        controller.close()
        fixture.close()
    }

    @Test
    fun `keyguard invocation stays rejected after device unlock`() = runBlocking {
        val fixture = Fixture(deviceLocked = true)
        val controller = fixture.controller(invokedFromKeyguard = true)
        fixture.runCurrent()

        assertFalse(controller.state.value.canSubmit)
        fixture.access.deviceLocked = false

        repeat(2) {
            val result = controller.submitText("hello") as SystemAssistantSubmitResult.Rejected
            assertEquals(SystemAssistantSubmissionErrorCode.INVOKED_FROM_KEYGUARD, result.code)
        }
        assertEquals(0, fixture.access.lockReads)
        assertEquals(0, fixture.target.resolutionReads)
        assertTrue(fixture.backend.submissions.isEmpty())

        controller.close()
        fixture.close()
    }

    @Test
    fun `unlocked invocation samples device lock state for every call`() = runBlocking {
        val fixture = Fixture(deviceLocked = false)
        val controller = fixture.controller()
        fixture.runCurrent()

        assertTrue(controller.submitText("one") is SystemAssistantSubmitResult.Accepted)
        fixture.access.deviceLocked = true
        val locked = controller.submitText("two") as SystemAssistantSubmitResult.Rejected
        fixture.access.deviceLocked = false
        assertTrue(controller.submitText("three") is SystemAssistantSubmitResult.Accepted)

        assertEquals(SystemAssistantSubmissionErrorCode.DEVICE_LOCKED, locked.code)
        assertEquals(5, fixture.access.lockReads)
        assertEquals(listOf("one", "three"), fixture.backend.submissions.map { it.text })

        controller.close()
        fixture.close()
    }

    @Test
    fun `non owner user cannot resolve or submit`() = runBlocking {
        val fixture = Fixture(ownerUser = false)
        val controller = fixture.controller()
        fixture.runCurrent()

        assertEquals(
            SystemAssistantInputAvailability.UnsupportedAndroidUser,
            controller.state.value.inputAvailability,
        )
        assertFalse(controller.state.value.canSubmit)
        val result = controller.submitText("hello") as SystemAssistantSubmitResult.Rejected
        assertEquals(SystemAssistantSubmissionErrorCode.UNSUPPORTED_ANDROID_USER, result.code)
        assertEquals(0, fixture.target.resolutionReads)
        assertTrue(fixture.backend.submissions.isEmpty())

        controller.close()
        fixture.close()
    }

    @Test
    fun `Emergency Stop is checked before resolving or submitting`() = runBlocking {
        val fixture = Fixture(emergencyStopped = true)
        val controller = fixture.controller()
        fixture.runCurrent()
        assertEquals(1, fixture.target.resolutionReads)

        val stopped = controller.submitText("hello") as SystemAssistantSubmitResult.Rejected
        assertEquals(SystemAssistantSubmissionErrorCode.EMERGENCY_STOP_ACTIVE, stopped.code)
        assertEquals(1, fixture.target.resolutionReads)
        assertTrue(fixture.backend.submissions.isEmpty())

        fixture.emergencyStopped = false
        assertTrue(controller.submitText("hello") is SystemAssistantSubmitResult.Accepted)
        assertEquals(2, fixture.target.resolutionReads)

        controller.close()
        fixture.close()
    }

    @Test
    fun `lock or Emergency Stop activated during target resolution blocks backend submission`() =
        runBlocking {
            val lockedFixture = Fixture()
            val lockedController = lockedFixture.controller()
            lockedFixture.runCurrent()
            lockedFixture.target.onResolve = {
                if (lockedFixture.target.resolutionReads >= 2) {
                    lockedFixture.access.deviceLocked = true
                }
            }

            val locked = lockedController.submitText("must not queue") as
                SystemAssistantSubmitResult.Rejected
            assertEquals(SystemAssistantSubmissionErrorCode.DEVICE_LOCKED, locked.code)
            assertTrue(lockedFixture.backend.submissions.isEmpty())
            lockedController.close()
            lockedFixture.close()

            val stoppedFixture = Fixture()
            val stoppedController = stoppedFixture.controller()
            stoppedFixture.runCurrent()
            stoppedFixture.target.onResolve = {
                if (stoppedFixture.target.resolutionReads >= 2) {
                    stoppedFixture.emergencyStopped = true
                }
            }

            val stopped = stoppedController.submitText("must not queue") as
                SystemAssistantSubmitResult.Rejected
            assertEquals(SystemAssistantSubmissionErrorCode.EMERGENCY_STOP_ACTIVE, stopped.code)
            assertTrue(stoppedFixture.backend.submissions.isEmpty())
            stoppedController.close()
            stoppedFixture.close()
        }

    @Test
    fun `target is resolved again and registry binding moves before submission`() = runBlocking {
        val fixture = Fixture()
        val secondAssistant = Assistant(
            name = "Other",
            privilegedConversationId = Uuid.random(),
            privilegedIdentityName = "Other identity",
        )
        val secondChat = FakeChat(
            Conversation.ofId(secondAssistant.privilegedConversationId!!, secondAssistant.id)
        )
        fixture.backend.chats[secondAssistant.privilegedConversationId!!] = secondChat
        val controller = fixture.controller()
        fixture.runCurrent()

        assertTrue(
            SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(fixture.conversationId)
        )
        fixture.target.resolution = SecondUserTargetResolution.Resolved(
            assistantId = secondAssistant.id,
            conversationId = secondAssistant.privilegedConversationId!!,
            displayName = secondAssistant.privilegedIdentityName,
            assistantName = secondAssistant.name,
        )
        fixture.backend.onSubmit = { submission ->
            assertFalse(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(
                    fixture.conversationId
                )
            )
            assertTrue(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(
                    submission.conversationId
                )
            )
        }

        assertTrue(controller.submitText("new target") is SystemAssistantSubmitResult.Accepted)
        assertEquals(secondAssistant.id, controller.state.value.assistantId)
        assertEquals("Other", controller.state.value.assistantName)
        assertEquals(secondAssistant.privilegedConversationId, controller.state.value.conversationId)

        controller.close()
        fixture.close()
    }

    @Test
    fun `unavailable target returns typed rejection without backend call`() = runBlocking {
        val fixture = Fixture()
        fixture.target.resolution = SecondUserTargetResolution.TargetNotSelected
        val controller = fixture.controller()
        fixture.runCurrent()

        assertTrue(controller.state.value.target is SystemAssistantTargetUiState.Unavailable)
        val result = controller.submitText("hello") as SystemAssistantSubmitResult.Rejected
        assertEquals(SystemAssistantSubmissionErrorCode.TARGET_UNAVAILABLE, result.code)
        assertEquals(SecondUserTargetResolution.TargetNotSelected, result.targetResolution)
        assertTrue(fixture.backend.submissions.isEmpty())
        assertFalse(
            SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(fixture.conversationId)
        )

        controller.close()
        fixture.close()
    }

    @Test
    fun `queue admission results map to typed return and UI state`() = runBlocking {
        val fixture = Fixture()
        val controller = fixture.controller()
        fixture.runCurrent()
        val cases = listOf(
            SubmitResult.QueueFull(7) to SystemAssistantSubmissionErrorCode.QUEUE_FULL,
            SubmitResult.Rejected("policy") to SystemAssistantSubmissionErrorCode.BACKEND_REJECTED,
            SubmitResult.RuntimeUnavailable("offline") to
                SystemAssistantSubmissionErrorCode.RUNTIME_UNAVAILABLE,
        )

        cases.forEachIndexed { index, (backendResult, expectedCode) ->
            fixture.backend.nextResult = backendResult
            val result = controller.submitText("message $index") as SystemAssistantSubmitResult.Rejected
            assertEquals(expectedCode, result.code)
            assertEquals(
                expectedCode,
                (controller.state.value.submission as SystemAssistantSubmissionUiState.Error).code,
            )
        }
        fixture.backend.nextResult = SubmitResult.QueueFull(7)
        val queueFull = controller.submitText("again") as SystemAssistantSubmitResult.Rejected
        assertEquals(7, queueFull.queueLimit)

        controller.close()
        fixture.close()
    }

    @Test
    fun `cancelled backend submission does not leave the surface stuck submitting`() = runBlocking {
        val fixture = Fixture()
        val controller = fixture.controller()
        fixture.runCurrent()
        fixture.backend.submitFailure = kotlinx.coroutines.CancellationException("cancelled")

        val error = runCatching { controller.submitText("hello") }.exceptionOrNull()

        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertFalse(controller.state.value.submission is SystemAssistantSubmissionUiState.Submitting)
        controller.close()
        fixture.close()
    }

    @Test
    fun `every command outcome maps to terminal UI state`() = runBlocking {
        val fixture = Fixture()
        val controller = fixture.controller()
        fixture.runCurrent()
        val related = Uuid.random()
        val cases = listOf(
            CommandOutcome.Completed to null,
            CommandOutcome.Cancelled to SystemAssistantSubmissionErrorCode.COMMAND_CANCELLED,
            CommandOutcome.Superseded(related) to
                SystemAssistantSubmissionErrorCode.COMMAND_SUPERSEDED,
            CommandOutcome.Rejected("rejected") to
                SystemAssistantSubmissionErrorCode.COMMAND_REJECTED,
            CommandOutcome.Conflict("conflict") to
                SystemAssistantSubmissionErrorCode.COMMAND_CONFLICT,
            CommandOutcome.NotApplied("late") to
                SystemAssistantSubmissionErrorCode.COMMAND_NOT_APPLIED,
            CommandOutcome.Failed(IllegalStateException("boom")) to
                SystemAssistantSubmissionErrorCode.COMMAND_FAILED,
            CommandOutcome.SkippedDependencyFailed(related) to
                SystemAssistantSubmissionErrorCode.COMMAND_DEPENDENCY_FAILED,
        )

        cases.forEachIndexed { index, (outcome, expectedCode) ->
            val deferred = CompletableDeferred<CommandOutcome>()
            fixture.backend.nextOutcome = deferred
            val result = controller.submitText("message $index") as SystemAssistantSubmitResult.Accepted
            deferred.complete(outcome)
            fixture.runCurrent()

            val state = controller.state.value.submission
            if (expectedCode == null) {
                assertEquals(SystemAssistantSubmissionUiState.Completed(result.commandId), state)
            } else {
                assertEquals(expectedCode, (state as SystemAssistantSubmissionUiState.Error).code)
                assertEquals(result.commandId, state.commandId)
            }
        }

        controller.close()
        fixture.close()
    }

    @Test
    fun `close stops observation without cancelling an accepted chat run`() = runBlocking {
        val fixture = Fixture()
        val deferred = CompletableDeferred<CommandOutcome>()
        fixture.backend.nextOutcome = deferred
        val controller = fixture.controller()
        fixture.runCurrent()
        val accepted = controller.submitText("keep running") as SystemAssistantSubmitResult.Accepted
        val messagesBeforeClose = controller.state.value.messages

        assertTrue(
            SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(fixture.conversationId)
        )

        controller.close()
        assertTrue(
            "An accepted command must retain its run-scoped authorization after the overlay hides",
            SystemAssistantInvocationRegistry.hasAuthorizedUnlockedInvocation(
                fixture.conversationId,
                accepted.commandId,
            ),
        )
        fixture.chat.conversation.value = fixture.conversation(
            listOf(UIMessage.assistant("must not be observed").toMessageNode())
        )
        deferred.complete(CommandOutcome.Completed)
        fixture.runCurrent()

        assertFalse(
            "Run-scoped authorization must be released when the command finishes",
            SystemAssistantInvocationRegistry.hasAuthorizedUnlockedInvocation(
                fixture.conversationId,
                accepted.commandId,
            ),
        )

        assertFalse(deferred.isCancelled)
        assertEquals(messagesBeforeClose, controller.state.value.messages)
        assertEquals(
            SystemAssistantInputAvailability.Closed,
            controller.state.value.inputAvailability,
        )
        assertEquals(
            SystemAssistantSubmissionUiState.Accepted(accepted.commandId),
            controller.state.value.submission,
        )
        fixture.close()
    }

    private class Fixture(
        ownerUser: Boolean = true,
        deviceLocked: Boolean = false,
        var emergencyStopped: Boolean = false,
        initialMessages: List<me.rerere.rikkahub.data.model.MessageNode> = emptyList(),
    ) {
        val dispatcher = ManualCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val conversationId = Uuid.random()
        val assistant = Assistant(
            name = "Rikka",
            privilegedConversationId = conversationId,
            privilegedIdentityName = "Second user",
        )
        val target = MutableTarget(
            SecondUserTargetResolution.Resolved(
                assistantId = assistant.id,
                conversationId = conversationId,
                displayName = assistant.privilegedIdentityName,
                assistantName = assistant.name,
            )
        )
        val access = MutableAccessState(ownerUser, deviceLocked)
        val chat = FakeChat(conversation(initialMessages))
        val backend = FakeBackend(mutableMapOf(conversationId to chat))

        fun controller(
            invokedFromKeyguard: Boolean = false,
            recentMessageLimit: Int = 20,
        ) = DefaultSystemAssistantSessionController(
            targetResolutionSource = target.source,
            chatBackend = backend,
            accessState = access,
            emergencyStopState = SystemAssistantEmergencyStopState { emergencyStopped },
            invokedFromKeyguard = invokedFromKeyguard,
            parentScope = scope,
            recentMessageLimit = recentMessageLimit,
        )

        fun conversation(
            messages: List<me.rerere.rikkahub.data.model.MessageNode> = emptyList(),
        ): Conversation = Conversation.ofId(
            id = assistant.privilegedConversationId!!,
            assistantId = assistant.id,
            messages = messages,
        )

        fun runCurrent() = dispatcher.runCurrent()

        fun close() {
            scope.cancel()
            dispatcher.runCurrent()
        }
    }

    private class MutableTarget(
        var resolution: SecondUserTargetResolution,
    ) {
        var resolutionReads = 0
        var onResolve: () -> Unit = {}

        val source = SystemAssistantTargetResolutionSource {
            resolutionReads++
            onResolve()
            resolution
        }
    }

    private class MutableAccessState(
        var ownerUser: Boolean,
        var deviceLocked: Boolean,
    ) : SystemAssistantAccessState {
        var lockReads = 0

        override fun isOwnerUser(): Boolean = ownerUser

        override fun isDeviceLocked(): Boolean {
            lockReads++
            return deviceLocked
        }
    }

    private class FakeChat(initialConversation: Conversation) {
        val conversation = MutableStateFlow(initialConversation)
        val runtime = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
        val queue = MutableStateFlow(
            QueueStatus(paused = false, pendingCount = 0, activeCommandId = null)
        )

        val flows = SystemAssistantChatFlows(conversation, runtime, queue)
    }

    private class FakeBackend(
        val chats: MutableMap<Uuid, FakeChat>,
    ) : SystemAssistantChatBackend {
        val submissions = mutableListOf<SystemAssistantChatSubmission>()
        var nextResult: SubmitResult? = null
        var nextOutcome: CompletableDeferred<CommandOutcome>? = null
        var onSubmit: (SystemAssistantChatSubmission) -> Unit = {}
        var submitFailure: Throwable? = null
        var hydrationGate: CompletableDeferred<Unit>? = null
        val hydrationCalls = mutableListOf<Uuid>()
        var hydrationCancelled = false

        override fun flows(conversationId: Uuid): SystemAssistantChatFlows =
            checkNotNull(chats[conversationId]) { "No fake chat for $conversationId" }.flows

        override suspend fun hydrateConversation(conversationId: Uuid) {
            hydrationCalls += conversationId
            try {
                hydrationGate?.await()
            } catch (cancelled: CancellationException) {
                hydrationCancelled = true
                throw cancelled
            }
        }

        override suspend fun submit(
            submission: SystemAssistantChatSubmission,
        ): SystemAssistantChatSubmissionReceipt {
            submitFailure?.let { throw it }
            submissions += submission
            onSubmit(submission)
            val result = nextResult ?: SubmitResult.Accepted(submission.commandId)
            nextResult = null
            val outcome = nextOutcome ?: CompletableDeferred()
            nextOutcome = null
            return SystemAssistantChatSubmissionReceipt(result, outcome)
        }
    }

    /** Core-only equivalent of StandardTestDispatcher for this module's JVM tests. */
    private class ManualCoroutineDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runCurrent() {
            while (tasks.isNotEmpty()) {
                tasks.removeFirst().run()
            }
        }
    }
}

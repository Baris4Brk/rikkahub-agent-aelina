package me.rerere.rikkahub.assistant

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.FinalAnswerRecoveryStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.QueueStatus
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SubmitResult
import kotlin.uuid.Uuid

private const val DEFAULT_RECENT_MESSAGE_LIMIT = 20
private const val MAX_RECENT_MESSAGE_TEXT_LENGTH = 8_192

class DefaultSystemAssistantSessionControllerFactory(
    private val targetResolver: SecondUserTargetResolver,
    private val chatBackend: SystemAssistantChatBackend,
    private val accessState: SystemAssistantAccessState,
    private val emergencyStopState: SystemAssistantEmergencyStopState,
    private val parentScope: CoroutineScope,
    private val recentMessageLimit: Int = DEFAULT_RECENT_MESSAGE_LIMIT,
) : SystemAssistantSessionControllerFactory {
    override fun create(
        invokedFromKeyguard: Boolean,
        hostKind: SystemAssistantHostKind,
    ): SystemAssistantSessionController =
        DefaultSystemAssistantSessionController(
            targetResolver = targetResolver,
            chatBackend = chatBackend,
            accessState = accessState,
            emergencyStopState = emergencyStopState,
            invokedFromKeyguard = invokedFromKeyguard,
            hostKind = hostKind,
            parentScope = parentScope,
            recentMessageLimit = recentMessageLimit,
        )
}

/**
 * Pure Kotlin implementation of one native system-assistant invocation.
 *
 * The controller owns only observation and outcome-waiter jobs. Chat runs and their outcome
 * deferreds remain backend-owned, so [close] cannot cancel an accepted command.
 */
class DefaultSystemAssistantSessionController(
    private val targetResolutionSource: SystemAssistantTargetResolutionSource,
    private val chatBackend: SystemAssistantChatBackend,
    private val accessState: SystemAssistantAccessState,
    private val emergencyStopState: SystemAssistantEmergencyStopState,
    private val invokedFromKeyguard: Boolean,
    private val hostKind: SystemAssistantHostKind = SystemAssistantHostKind.VOICE_SESSION,
    parentScope: CoroutineScope,
    private val recentMessageLimit: Int = DEFAULT_RECENT_MESSAGE_LIMIT,
) : SystemAssistantSessionController {
    constructor(
        targetResolver: SecondUserTargetResolver,
        chatBackend: SystemAssistantChatBackend,
        accessState: SystemAssistantAccessState,
        emergencyStopState: SystemAssistantEmergencyStopState,
        invokedFromKeyguard: Boolean,
        hostKind: SystemAssistantHostKind = SystemAssistantHostKind.VOICE_SESSION,
        parentScope: CoroutineScope,
        recentMessageLimit: Int = DEFAULT_RECENT_MESSAGE_LIMIT,
    ) : this(
        targetResolutionSource = SystemAssistantTargetResolutionSource(targetResolver::resolve),
        chatBackend = chatBackend,
        accessState = accessState,
        emergencyStopState = emergencyStopState,
        invokedFromKeyguard = invokedFromKeyguard,
        hostKind = hostKind,
        parentScope = parentScope,
        recentMessageLimit = recentMessageLimit,
    )

    private val closed = AtomicBoolean(false)
    private val ownerUser = runCatching(accessState::isOwnerUser).getOrDefault(false)
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val targetMutex = Mutex()
    private val submitMutex = Mutex()
    private val invocationToken = SystemAssistantInvocationRegistry.register(
        invokedFromKeyguard = invokedFromKeyguard,
        ownerUser = ownerUser,
        hostKind = hostKind,
    )

    private val _state = MutableStateFlow(
        SystemAssistantUiState(
            inputAvailability = when {
                !ownerUser -> SystemAssistantInputAvailability.UnsupportedAndroidUser
                invokedFromKeyguard -> SystemAssistantInputAvailability.InvokedFromKeyguard
                else -> SystemAssistantInputAvailability.Available
            },
        )
    )
    override val state = _state.asStateFlow()

    private var boundTarget: SecondUserTargetResolution.Resolved? = null
    private var bindingJob: Job? = null
    private var hydrationJob: Job? = null

    init {
        require(recentMessageLimit > 0) { "recentMessageLimit must be positive" }
        controllerJob.invokeOnCompletion { invocationToken.close() }
        if (ownerUser && !invokedFromKeyguard) {
            controllerScope.launch { resolveAndBindTarget() }
        }
    }

    override suspend fun submitText(text: String): SystemAssistantSubmitResult = submitMutex.withLock {
        if (closed.get()) {
            return@withLock reject(
                code = SystemAssistantSubmissionErrorCode.CONTROLLER_CLOSED,
                message = "The system-assistant session is closed.",
            )
        }

        val ownerNow = runCatching(accessState::isOwnerUser).getOrDefault(false)
        if (!ownerNow) {
            invocationToken.unbindConversation()
            _state.update {
                it.copy(inputAvailability = SystemAssistantInputAvailability.UnsupportedAndroidUser)
            }
            return@withLock reject(
                code = SystemAssistantSubmissionErrorCode.UNSUPPORTED_ANDROID_USER,
                message = "The system assistant is only available to the Android owner user.",
            )
        }
        if (invokedFromKeyguard) {
            _state.update {
                it.copy(inputAvailability = SystemAssistantInputAvailability.InvokedFromKeyguard)
            }
            return@withLock reject(
                code = SystemAssistantSubmissionErrorCode.INVOKED_FROM_KEYGUARD,
                message = "Unlock the device and invoke the system assistant again.",
            )
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withLock reject(
                code = SystemAssistantSubmissionErrorCode.EMPTY_TEXT,
                message = "Message cannot be blank.",
            )
        }
        if (trimmed.length > SYSTEM_ASSISTANT_MAX_TEXT_LENGTH) {
            return@withLock reject(
                code = SystemAssistantSubmissionErrorCode.TEXT_TOO_LONG,
                message = "Message must be at most $SYSTEM_ASSISTANT_MAX_TEXT_LENGTH characters.",
            )
        }

        submissionSafetyRejection()?.let { return@withLock it }

        val target = when (val binding = resolveAndBindTarget()) {
            is TargetBindingResult.Bound -> binding.target
            is TargetBindingResult.Failed -> {
                return@withLock reject(
                    code = binding.code,
                    message = binding.message,
                    targetResolution = binding.resolution,
                )
            }
        }

        if (closed.get()) {
            return@withLock reject(
                code = SystemAssistantSubmissionErrorCode.CONTROLLER_CLOSED,
                message = "The system-assistant session is closed.",
            )
        }
        // Target resolution can suspend while the lock screen or Emergency Stop state changes.
        // Recheck immediately before queue admission so a stale unlocked snapshot cannot submit.
        submissionSafetyRejection()?.let { return@withLock it }

        _state.update { state ->
            state.copy(
                inputAvailability = SystemAssistantInputAvailability.Available,
                submission = SystemAssistantSubmissionUiState.Submitting,
            )
        }
        val commandId = Uuid.random()
        val acceptedRun = SystemAssistantInvocationRegistry.acquireAcceptedRun(
            conversationId = target.conversationId,
            commandId = commandId,
            hostKind = hostKind,
        ) ?: return@withLock reject(
            code = SystemAssistantSubmissionErrorCode.OVERLAY_NOT_VISIBLE,
            message = "The system-assistant overlay is no longer visible. Invoke it again.",
        )
        val receipt = try {
            chatBackend.submit(
                SystemAssistantChatSubmission(
                    commandId = commandId,
                    assistantId = target.assistantId,
                    conversationId = target.conversationId,
                    text = trimmed,
                    origin = CommandOrigin.SYSTEM_ASSISTANT,
                    dedupeKey = "system-assistant:${Uuid.random()}",
                )
            )
        } catch (cancelled: CancellationException) {
            acceptedRun.close()
            reject(
                code = SystemAssistantSubmissionErrorCode.COMMAND_CANCELLED,
                message = "System-assistant submission was cancelled.",
            )
            throw cancelled
        } catch (error: Throwable) {
            acceptedRun.close()
            return@withLock reject(
                code = SystemAssistantSubmissionErrorCode.BACKEND_FAILED,
                message = "Unable to submit the message: ${error.readableMessage()}.",
            )
        }

        when (val result = receipt.result) {
            is SubmitResult.Accepted -> {
                if (result.commandId != commandId) {
                    acceptedRun.close()
                    return@withLock reject(
                        code = SystemAssistantSubmissionErrorCode.BACKEND_FAILED,
                        message = "The accepted command identity did not match the submitted request.",
                    )
                }
                receipt.outcome.invokeOnCompletion { acceptedRun.close() }
                _state.update { state ->
                    state.copy(submission = SystemAssistantSubmissionUiState.Accepted(result.commandId))
                }
                observeOutcome(result.commandId, receipt.outcome)
                SystemAssistantSubmitResult.Accepted(result.commandId)
            }

            is SubmitResult.QueueFull -> {
                acceptedRun.close()
                reject(
                    code = SystemAssistantSubmissionErrorCode.QUEUE_FULL,
                    message = "Conversation queue is full (limit ${result.limit}).",
                    queueLimit = result.limit,
                )
            }

            is SubmitResult.Rejected -> {
                acceptedRun.close()
                reject(
                    code = SystemAssistantSubmissionErrorCode.BACKEND_REJECTED,
                    message = result.reason,
                )
            }

            is SubmitResult.RuntimeUnavailable -> {
                acceptedRun.close()
                reject(
                    code = SystemAssistantSubmissionErrorCode.RUNTIME_UNAVAILABLE,
                    message = result.reason,
                )
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        invocationToken.unbindConversation()
        _state.update { state ->
            state.copy(inputAvailability = SystemAssistantInputAvailability.Closed)
        }
        bindingJob?.cancel()
        controllerScope.cancel()
    }

    private suspend fun submissionSafetyRejection(): SystemAssistantSubmitResult.Rejected? {
        val locked = runCatching(accessState::isDeviceLocked).getOrElse {
            return reject(
                code = SystemAssistantSubmissionErrorCode.DEVICE_LOCKED,
                message = "Unable to confirm that the device is unlocked.",
            )
        }
        if (locked) {
            return reject(
                code = SystemAssistantSubmissionErrorCode.DEVICE_LOCKED,
                message = "Unlock the device before submitting to the system assistant.",
            )
        }

        val emergencyStopActive = try {
            emergencyStopState.isActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return reject(
                code = SystemAssistantSubmissionErrorCode.EMERGENCY_STOP_CHECK_FAILED,
                message = "Unable to verify Emergency Stop: ${error.readableMessage()}.",
            )
        }
        return if (emergencyStopActive) {
            reject(
                code = SystemAssistantSubmissionErrorCode.EMERGENCY_STOP_ACTIVE,
                message = "Emergency Stop is active. Resume agent execution before submitting.",
            )
        } else {
            null
        }
    }

    private suspend fun resolveAndBindTarget(): TargetBindingResult = targetMutex.withLock {
        if (closed.get()) {
            return@withLock TargetBindingResult.Failed(
                code = SystemAssistantSubmissionErrorCode.CONTROLLER_CLOSED,
                message = "The system-assistant session is closed.",
            )
        }
        if (boundTarget == null) {
            _state.update { it.copy(target = SystemAssistantTargetUiState.Resolving) }
        }
        val resolution = try {
            targetResolutionSource.resolve()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            clearBinding(
                SystemAssistantTargetUiState.Failed(error.readableMessage())
            )
            return@withLock TargetBindingResult.Failed(
                code = SystemAssistantSubmissionErrorCode.TARGET_RESOLUTION_FAILED,
                message = "Unable to resolve the second-user target: ${error.readableMessage()}.",
            )
        }
        if (resolution !is SecondUserTargetResolution.Resolved) {
            clearBinding(SystemAssistantTargetUiState.Unavailable(resolution))
            return@withLock TargetBindingResult.Failed(
                code = SystemAssistantSubmissionErrorCode.TARGET_UNAVAILABLE,
                message = resolution.unavailableMessage(),
                resolution = resolution,
            )
        }
        bindTarget(resolution)
    }

    private fun bindTarget(target: SecondUserTargetResolution.Resolved): TargetBindingResult {
        val ready = target.toUiState()
        if (boundTarget == target && bindingJob?.isActive == true) {
            invocationToken.bindConversation(target.conversationId)
            _state.update { it.copy(target = ready) }
            return TargetBindingResult.Bound(target)
        }

        bindingJob?.cancel()
        bindingJob = null
        hydrationJob?.cancel()
        hydrationJob = null
        boundTarget = null
        invocationToken.unbindConversation()

        val flows = try {
            chatBackend.flows(target.conversationId)
        } catch (error: Throwable) {
            _state.update { state ->
                state.copy(
                    target = SystemAssistantTargetUiState.Failed(error.readableMessage()),
                    messages = emptyList(),
                    runtimeState = null,
                    queueStatus = null,
                    answer = SystemAssistantAnswerUiState.Ready,
                )
            }
            return TargetBindingResult.Failed(
                code = SystemAssistantSubmissionErrorCode.BACKEND_FAILED,
                message = "Unable to observe the target conversation: ${error.readableMessage()}.",
            )
        }

        boundTarget = target
        val initialPresentation = flows.conversation.value.toSystemAssistantPresentation(
            recentMessageLimit
        )
        _state.update { state ->
            state.copy(
                target = ready,
                messages = initialPresentation.messages,
                runtimeState = flows.runtime.value,
                queueStatus = flows.queue.value,
                answer = initialPresentation.answer,
                history = SystemAssistantHistoryUiState.Loading,
            )
        }
        invocationToken.bindConversation(target.conversationId)
        bindingJob = controllerScope.launch {
            combine(
                flows.conversation,
                flows.runtime,
                flows.queue,
            ) { conversation, runtime, queue ->
                ChatSnapshot(conversation, runtime, queue)
            }.collect { snapshot ->
                if (closed.get() || boundTarget != target) return@collect
                val presentation = snapshot.conversation.toSystemAssistantPresentation(
                    recentMessageLimit
                )
                _state.update { state ->
                    state.copy(
                        target = ready,
                        messages = presentation.messages,
                        runtimeState = snapshot.runtime,
                        queueStatus = snapshot.queue,
                        answer = presentation.answer,
                    )
                }
            }
        }
        hydrationJob = controllerScope.launch {
            try {
                chatBackend.hydrateConversation(target.conversationId)
                if (!closed.get() && boundTarget == target) {
                    _state.update { state ->
                        state.copy(history = SystemAssistantHistoryUiState.Ready)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!closed.get() && boundTarget == target) {
                    _state.update { state ->
                        state.copy(history = SystemAssistantHistoryUiState.Failed)
                    }
                }
            }
        }
        return TargetBindingResult.Bound(target)
    }

    private fun clearBinding(targetState: SystemAssistantTargetUiState) {
        invocationToken.unbindConversation()
        bindingJob?.cancel()
        bindingJob = null
        hydrationJob?.cancel()
        hydrationJob = null
        boundTarget = null
        _state.update { state ->
            state.copy(
                target = targetState,
                messages = emptyList(),
                runtimeState = null,
                queueStatus = null,
                answer = SystemAssistantAnswerUiState.Ready,
                history = SystemAssistantHistoryUiState.NotLoaded,
            )
        }
    }

    private fun observeOutcome(commandId: Uuid, outcome: kotlinx.coroutines.Deferred<CommandOutcome>) {
        controllerScope.launch {
            val commandOutcome = try {
                outcome.await()
            } catch (cancelled: CancellationException) {
                if (closed.get() || !controllerJob.isActive) return@launch
                CommandOutcome.Cancelled
            } catch (error: Throwable) {
                CommandOutcome.Failed(error)
            }
            val mapped = commandOutcome.toUiState(commandId)
            _state.update { state ->
                if (state.submission.commandIdOrNull() == commandId) {
                    state.copy(submission = mapped)
                } else {
                    state
                }
            }
        }
    }

    private fun reject(
        code: SystemAssistantSubmissionErrorCode,
        message: String,
        queueLimit: Int? = null,
        targetResolution: SecondUserTargetResolution? = null,
    ): SystemAssistantSubmitResult.Rejected {
        _state.update { state ->
            state.copy(
                submission = SystemAssistantSubmissionUiState.Error(
                    code = code,
                    message = message,
                    queueLimit = queueLimit,
                    targetResolution = targetResolution,
                )
            )
        }
        return SystemAssistantSubmitResult.Rejected(
            code = code,
            message = message,
            queueLimit = queueLimit,
            targetResolution = targetResolution,
        )
    }
}

private sealed interface TargetBindingResult {
    data class Bound(
        val target: SecondUserTargetResolution.Resolved,
    ) : TargetBindingResult

    data class Failed(
        val code: SystemAssistantSubmissionErrorCode,
        val message: String,
        val resolution: SecondUserTargetResolution? = null,
    ) : TargetBindingResult
}

private data class ChatSnapshot(
    val conversation: Conversation,
    val runtime: RuntimeState,
    val queue: QueueStatus,
)

private fun SecondUserTargetResolution.Resolved.toUiState() = SystemAssistantTargetUiState.Ready(
    assistantId = assistantId,
    assistantName = assistantName,
    conversationId = conversationId,
    displayName = displayName,
)

private data class SystemAssistantConversationPresentation(
    val messages: List<SystemAssistantTextMessage>,
    val answer: SystemAssistantAnswerUiState,
)

private fun Conversation.toSystemAssistantPresentation(
    limit: Int,
): SystemAssistantConversationPresentation {
    var includeCurrentTurn = false
    val ownerMessages = buildList {
        messageNodes.forEach { node ->
            val message = node.messages.getOrNull(node.selectIndex) ?: return@forEach
            val role = message.role.toSystemAssistantTextRole() ?: return@forEach
            when (role) {
                SystemAssistantTextRole.USER -> {
                    includeCurrentTurn = message.annotations.none {
                        it is UIMessageAnnotation.SecondUser
                    }
                    if (!includeCurrentTurn) return@forEach
                }

                SystemAssistantTextRole.ASSISTANT -> {
                    if (!includeCurrentTurn) return@forEach
                }
            }
            add(message)
        }
    }
    val latestOwnerUserIndex = ownerMessages.indexOfLast { it.role == MessageRole.USER }
    val latestAssistant = if (latestOwnerUserIndex < 0) {
        null
    } else {
        ownerMessages
            .subList(latestOwnerUserIndex + 1, ownerMessages.size)
            .lastOrNull { it.role == MessageRole.ASSISTANT }
    }
    val recentTextMessages = ownerMessages.mapNotNull { message ->
        val role = message.role.toSystemAssistantTextRole() ?: return@mapNotNull null
        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n") { it.text }
            .trim()
        text.takeIf(String::isNotEmpty)?.let { fullText ->
            SystemAssistantTextMessage(
                id = message.id,
                role = role,
                text = if (fullText.length <= MAX_RECENT_MESSAGE_TEXT_LENGTH) {
                    fullText
                } else {
                    fullText.take(MAX_RECENT_MESSAGE_TEXT_LENGTH - 3) + "..."
                },
            )
        }
    }.takeLast(limit)
    return SystemAssistantConversationPresentation(
        messages = recentTextMessages,
        answer = latestAssistant.toSystemAssistantAnswerUiState(),
    )
}

private fun UIMessage?.toSystemAssistantAnswerUiState(): SystemAssistantAnswerUiState {
    if (this == null) return SystemAssistantAnswerUiState.Ready
    val recovery = annotations
        .filterIsInstance<UIMessageAnnotation.FinalAnswerRecovery>()
        .lastOrNull()
    return when (recovery?.status) {
        FinalAnswerRecoveryStatus.STARTED -> SystemAssistantAnswerUiState.Recovering(
            attempt = recovery.attempt.coerceIn(
                1,
                SYSTEM_ASSISTANT_FINAL_ANSWER_RECOVERY_MAX_ATTEMPTS,
            ),
        )

        FinalAnswerRecoveryStatus.FAILED -> SystemAssistantAnswerUiState.RecoveryFailed(
            attempt = recovery.attempt.coerceIn(
                1,
                SYSTEM_ASSISTANT_FINAL_ANSWER_RECOVERY_MAX_ATTEMPTS,
            ),
        )

        FinalAnswerRecoveryStatus.SUCCEEDED -> SystemAssistantAnswerUiState.Ready
        null -> {
            if (state == UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER) {
                SystemAssistantAnswerUiState.RecoveryFailed(attempt = null)
            } else {
                SystemAssistantAnswerUiState.Ready
            }
        }
    }
}

private fun SecondUserTargetResolution.unavailableMessage(): String = when (this) {
    SecondUserTargetResolution.TargetNotSelected ->
        "Select a second-user assistant before using the system assistant."

    is SecondUserTargetResolution.AssistantNotFound ->
        "The selected second-user assistant no longer exists."

    is SecondUserTargetResolution.PrivilegedConversationNotConfigured ->
        "The selected assistant has no second-user conversation configured."

    is SecondUserTargetResolution.ConversationNotFound ->
        "The configured second-user conversation no longer exists."

    is SecondUserTargetResolution.ConversationAssistantMismatch ->
        "The configured conversation belongs to a different assistant."

    is SecondUserTargetResolution.Resolved ->
        error("Resolved target is available")
}

private fun CommandOutcome.toUiState(commandId: Uuid): SystemAssistantSubmissionUiState = when (this) {
    CommandOutcome.Completed -> SystemAssistantSubmissionUiState.Completed(commandId)

    CommandOutcome.Cancelled -> SystemAssistantSubmissionUiState.Error(
        code = SystemAssistantSubmissionErrorCode.COMMAND_CANCELLED,
        message = "The command was cancelled.",
        commandId = commandId,
    )

    is CommandOutcome.Superseded -> SystemAssistantSubmissionUiState.Error(
        code = SystemAssistantSubmissionErrorCode.COMMAND_SUPERSEDED,
        message = "The command was superseded by $byCommandId.",
        commandId = commandId,
        relatedCommandId = byCommandId,
    )

    is CommandOutcome.Rejected -> SystemAssistantSubmissionUiState.Error(
        code = SystemAssistantSubmissionErrorCode.COMMAND_REJECTED,
        message = reason,
        commandId = commandId,
    )

    is CommandOutcome.Conflict -> SystemAssistantSubmissionUiState.Error(
        code = SystemAssistantSubmissionErrorCode.COMMAND_CONFLICT,
        message = reason,
        commandId = commandId,
    )

    is CommandOutcome.NotApplied -> SystemAssistantSubmissionUiState.Error(
        code = SystemAssistantSubmissionErrorCode.COMMAND_NOT_APPLIED,
        message = reason,
        commandId = commandId,
    )

    is CommandOutcome.Failed -> SystemAssistantSubmissionUiState.Error(
        code = SystemAssistantSubmissionErrorCode.COMMAND_FAILED,
        message = error.readableMessage(),
        commandId = commandId,
    )

    is CommandOutcome.SkippedDependencyFailed -> SystemAssistantSubmissionUiState.Error(
        code = SystemAssistantSubmissionErrorCode.COMMAND_DEPENDENCY_FAILED,
        message = "Required command $dependencyId failed.",
        commandId = commandId,
        relatedCommandId = dependencyId,
    )
}

private fun SystemAssistantSubmissionUiState.commandIdOrNull(): Uuid? = when (this) {
    is SystemAssistantSubmissionUiState.Accepted -> commandId
    is SystemAssistantSubmissionUiState.Completed -> commandId
    is SystemAssistantSubmissionUiState.Error -> commandId
    SystemAssistantSubmissionUiState.Idle,
    SystemAssistantSubmissionUiState.Submitting,
    -> null
}

private fun Throwable.readableMessage(): String = message?.takeIf(String::isNotBlank)
    ?: this::class.simpleName
    ?: "Unknown error"

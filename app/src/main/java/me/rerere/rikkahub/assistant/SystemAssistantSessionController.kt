package me.rerere.rikkahub.assistant

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.QueueStatus
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.service.chat.SubmitResult
import kotlin.uuid.Uuid

const val SYSTEM_ASSISTANT_MAX_TEXT_LENGTH: Int = 4096
const val SYSTEM_ASSISTANT_FINAL_ANSWER_RECOVERY_MAX_ATTEMPTS: Int = 10

/**
 * Pure platform seam used by a system-assistant session.
 *
 * Implementations must answer from current platform state. In particular, device lock state is
 * sampled for every submission rather than captured when the controller is created.
 */
interface SystemAssistantAccessState {
    fun isOwnerUser(): Boolean

    fun isDeviceLocked(): Boolean
}

/** Read-only safety seam. A failed or active check prevents a chat submission. */
fun interface SystemAssistantEmergencyStopState {
    suspend fun isActive(): Boolean
}

/** Injectable resolution seam; the production factory adapts [SecondUserTargetResolver]. */
fun interface SystemAssistantTargetResolutionSource {
    suspend fun resolve(): SecondUserTargetResolution
}

/** The three existing chat state streams needed by the small system-assistant surface. */
data class SystemAssistantChatFlows(
    val conversation: StateFlow<Conversation>,
    val runtime: StateFlow<RuntimeState>,
    val queue: StateFlow<QueueStatus>,
)

/**
 * Fully-auditable submission passed to the ChatService adapter.
 *
 * [dedupeKey] is unique for every controller call. The adapter should forward [origin], routing
 * identifiers, and the key unchanged to the chat command pipeline. System-assistant input is
 * always attributed to the primary owner; second-user identity annotations are not accepted here.
 */
data class SystemAssistantChatSubmission(
    val commandId: Uuid,
    val assistantId: Uuid,
    val conversationId: Uuid,
    val text: String,
    val origin: CommandOrigin,
    val dedupeKey: String,
)

/** Queue admission plus the accepted command's eventual outcome. */
data class SystemAssistantChatSubmissionReceipt(
    val result: SubmitResult,
    val outcome: Deferred<CommandOutcome>,
)

/** Adapter seam around ChatService; it deliberately contains no Android type. */
interface SystemAssistantChatBackend {
    fun flows(conversationId: Uuid): SystemAssistantChatFlows

    suspend fun submit(submission: SystemAssistantChatSubmission): SystemAssistantChatSubmissionReceipt
}

/** Stable target shown for the lifetime of each flow binding. */
sealed interface SystemAssistantTargetUiState {
    data object NotResolved : SystemAssistantTargetUiState
    data object Resolving : SystemAssistantTargetUiState

    data class Ready(
        val assistantId: Uuid,
        val assistantName: String,
        val conversationId: Uuid,
        val displayName: String,
    ) : SystemAssistantTargetUiState

    data class Unavailable(
        val resolution: SecondUserTargetResolution,
    ) : SystemAssistantTargetUiState

    data class Failed(
        val message: String,
    ) : SystemAssistantTargetUiState
}

/** Permanent input availability for this invocation. Transient failures live in submission. */
sealed interface SystemAssistantInputAvailability {
    data object Available : SystemAssistantInputAvailability
    data object InvokedFromKeyguard : SystemAssistantInputAvailability
    data object UnsupportedAndroidUser : SystemAssistantInputAvailability
    data object Closed : SystemAssistantInputAvailability
}

enum class SystemAssistantTextRole {
    USER,
    ASSISTANT,
}

data class SystemAssistantTextMessage(
    val id: Uuid,
    val role: SystemAssistantTextRole,
    val text: String,
)

/** Final-answer state for the latest owner turn only. */
sealed interface SystemAssistantAnswerUiState {
    data object Ready : SystemAssistantAnswerUiState

    data class Recovering(
        val attempt: Int,
        val maxAttempts: Int = SYSTEM_ASSISTANT_FINAL_ANSWER_RECOVERY_MAX_ATTEMPTS,
    ) : SystemAssistantAnswerUiState

    data class RecoveryFailed(
        val attempt: Int?,
        val maxAttempts: Int = SYSTEM_ASSISTANT_FINAL_ANSWER_RECOVERY_MAX_ATTEMPTS,
    ) : SystemAssistantAnswerUiState
}

enum class SystemAssistantSubmissionErrorCode {
    EMPTY_TEXT,
    TEXT_TOO_LONG,
    INVOKED_FROM_KEYGUARD,
    DEVICE_LOCKED,
    UNSUPPORTED_ANDROID_USER,
    EMERGENCY_STOP_ACTIVE,
    EMERGENCY_STOP_CHECK_FAILED,
    TARGET_UNAVAILABLE,
    TARGET_RESOLUTION_FAILED,
    QUEUE_FULL,
    BACKEND_REJECTED,
    RUNTIME_UNAVAILABLE,
    BACKEND_FAILED,
    OVERLAY_NOT_VISIBLE,
    COMMAND_CANCELLED,
    COMMAND_SUPERSEDED,
    COMMAND_REJECTED,
    COMMAND_CONFLICT,
    COMMAND_NOT_APPLIED,
    COMMAND_FAILED,
    COMMAND_DEPENDENCY_FAILED,
    CONTROLLER_CLOSED,
}

sealed interface SystemAssistantSubmissionUiState {
    data object Idle : SystemAssistantSubmissionUiState
    data object Submitting : SystemAssistantSubmissionUiState

    data class Accepted(
        val commandId: Uuid,
    ) : SystemAssistantSubmissionUiState

    data class Completed(
        val commandId: Uuid,
    ) : SystemAssistantSubmissionUiState

    data class Error(
        val code: SystemAssistantSubmissionErrorCode,
        val message: String,
        val commandId: Uuid? = null,
        val relatedCommandId: Uuid? = null,
        val queueLimit: Int? = null,
        val targetResolution: SecondUserTargetResolution? = null,
    ) : SystemAssistantSubmissionUiState
}

sealed interface SystemAssistantSubmitResult {
    data class Accepted(
        val commandId: Uuid,
    ) : SystemAssistantSubmitResult

    data class Rejected(
        val code: SystemAssistantSubmissionErrorCode,
        val message: String,
        val queueLimit: Int? = null,
        val targetResolution: SecondUserTargetResolution? = null,
    ) : SystemAssistantSubmitResult
}

data class SystemAssistantUiState(
    val target: SystemAssistantTargetUiState = SystemAssistantTargetUiState.NotResolved,
    val inputAvailability: SystemAssistantInputAvailability = SystemAssistantInputAvailability.Available,
    val messages: List<SystemAssistantTextMessage> = emptyList(),
    val runtimeState: RuntimeState? = null,
    val queueStatus: QueueStatus? = null,
    val submission: SystemAssistantSubmissionUiState = SystemAssistantSubmissionUiState.Idle,
    val answer: SystemAssistantAnswerUiState = SystemAssistantAnswerUiState.Ready,
) {
    val canSubmit: Boolean
        get() = inputAvailability == SystemAssistantInputAvailability.Available &&
            submission != SystemAssistantSubmissionUiState.Submitting

    val assistantId: Uuid?
        get() = (target as? SystemAssistantTargetUiState.Ready)?.assistantId

    val assistantName: String?
        get() = (target as? SystemAssistantTargetUiState.Ready)?.assistantName

    val conversationId: Uuid?
        get() = (target as? SystemAssistantTargetUiState.Ready)?.conversationId

    val displayName: String?
        get() = (target as? SystemAssistantTargetUiState.Ready)?.displayName

    val latestUserText: String?
        get() = messages.lastOrNull { it.role == SystemAssistantTextRole.USER }?.text

    val latestAssistantText: String?
        get() {
            val latestUserIndex = messages.indexOfLast {
                it.role == SystemAssistantTextRole.USER
            }
            if (latestUserIndex < 0) return null
            return messages
                .subList(latestUserIndex + 1, messages.size)
                .lastOrNull { it.role == SystemAssistantTextRole.ASSISTANT }
                ?.text
        }
}

/** Deep module consumed by the native session adapter. */
interface SystemAssistantSessionController : AutoCloseable {
    val state: StateFlow<SystemAssistantUiState>

    suspend fun submitText(text: String): SystemAssistantSubmitResult

    override fun close()
}

/** Creates one independent controller for each native session invocation. */
fun interface SystemAssistantSessionControllerFactory {
    fun create(
        invokedFromKeyguard: Boolean,
        hostKind: SystemAssistantHostKind,
    ): SystemAssistantSessionController
}

internal fun MessageRole.toSystemAssistantTextRole(): SystemAssistantTextRole? = when (this) {
    MessageRole.USER -> SystemAssistantTextRole.USER
    MessageRole.ASSISTANT -> SystemAssistantTextRole.ASSISTANT
    MessageRole.SYSTEM,
    MessageRole.TOOL,
    -> null
}

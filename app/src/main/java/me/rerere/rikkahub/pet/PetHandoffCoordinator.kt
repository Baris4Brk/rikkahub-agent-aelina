package me.rerere.rikkahub.pet

import androidx.room.withTransaction
import kotlin.time.Instant
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageState
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.PendingChatCommandDao
import me.rerere.rikkahub.data.db.dao.PetDialogueDao
import me.rerere.rikkahub.data.db.entity.PetHandoffRequestEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.DurableCommandState
import me.rerere.rikkahub.service.chat.SubmitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface PetHandoffSubmitResult {
    data class Submitted(val commandId: Uuid) : PetHandoffSubmitResult
    data class Rejected(val code: String) : PetHandoffSubmitResult
    data object Missing : PetHandoffSubmitResult
    data object Conflict : PetHandoffSubmitResult
    data object Expired : PetHandoffSubmitResult
    data object RateLimited : PetHandoffSubmitResult
}

data class PetHandoffCompletion(
    val requestId: String,
    val text: String,
    val failed: Boolean,
    val completedAtMs: Long,
)

class PetHandoffCoordinator(
    private val database: AppDatabase,
    private val dao: PetDialogueDao,
    private val pendingCommandDao: PendingChatCommandDao,
    private val dialogueRepository: PetDialogueRepository,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
    private val appScope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val trackingLock = Any()
    private val trackingJobs = mutableMapOf<String, Job>()
    private val _completions = MutableSharedFlow<PetHandoffCompletion>(replay = 1, extraBufferCapacity = 7)
    val completions = _completions.asSharedFlow()

    suspend fun editDraft(
        requestId: String,
        expectedVersion: Long,
        title: String,
        request: String,
    ): Boolean = dao.updateHandoffDraft(
        requestId = requestId,
        expectedVersion = expectedVersion,
        title = PetBubbleSanitizer.sanitize(title).take(160),
        request = PetBubbleSanitizer.sanitizeDraft(request).take(2_000),
    ) == 1

    suspend fun dismiss(requestId: String, expectedVersion: Long): Boolean =
        dao.updateHandoffStatus(
            requestId = requestId,
            expectedVersion = expectedVersion,
            nextStatus = PetHandoffStatus.DISMISSED.name,
            targetCommandId = null,
            submittedAtMs = null,
            resolvedAtMs = nowMs(),
        ) == 1

    suspend fun submit(requestId: String, automatic: Boolean): PetHandoffSubmitResult {
        val now = nowMs()
        val claimed = database.withTransaction {
            val current = dao.getHandoff(requestId) ?: return@withTransaction null
            if (current.expiresAtMs != null && current.expiresAtMs <= now) {
                dao.expireHandoffs(now)
                return@withTransaction current.copy(status = PetHandoffStatus.EXPIRED.name)
            }
            if (automatic && current.targetCommandId == null) {
                if (current.mode != PetHandoffMode.AUTO.name) return@withTransaction current.copy(status = "MODE_REJECTED")
                if (dao.countRecentAutoHandoffs(current.assistantId, now - AUTO_RATE_WINDOW_MS) > 0 ||
                    dao.countPendingAutoHandoffs(current.assistantId, current.requestId) > 0
                ) return@withTransaction current.copy(status = "RATE_LIMITED")
            }
            when (current.status) {
                PetHandoffStatus.DRAFT.name -> {
                    val changed = dao.updateHandoffStatus(
                        requestId = requestId,
                        expectedVersion = current.stateVersion,
                        nextStatus = PetHandoffStatus.CONFIRMED.name,
                        targetCommandId = null,
                        submittedAtMs = null,
                        resolvedAtMs = null,
                    )
                    if (changed != 1) return@withTransaction current.copy(status = "CONFLICT")
                    current.copy(status = PetHandoffStatus.CONFIRMED.name, stateVersion = current.stateVersion + 1)
                }
                PetHandoffStatus.CONFIRMED.name -> current
                PetHandoffStatus.SUBMITTED.name,
                PetHandoffStatus.AUTO_SUBMITTED.name,
                PetHandoffStatus.RESOLVED.name,
                -> current
                else -> current.copy(status = "CONFLICT")
            }
        } ?: return PetHandoffSubmitResult.Missing

        when (claimed.status) {
            PetHandoffStatus.EXPIRED.name -> return PetHandoffSubmitResult.Expired
            "RATE_LIMITED" -> return PetHandoffSubmitResult.RateLimited
            "MODE_REJECTED" -> return PetHandoffSubmitResult.Rejected("pet_handoff_mode_mismatch")
            "CONFLICT" -> return PetHandoffSubmitResult.Conflict
        }
        claimed.targetCommandId?.let {
            resumeTracking(claimed.requestId)
            return PetHandoffSubmitResult.Submitted(Uuid.parse(it))
        }

        val commandId = Uuid.parse(claimed.requestId)
        val correlation = UIMessageAnnotation.PetHandoff(
            commandId = commandId.toString(),
            requestId = claimed.requestId,
        )
        val origin = if (automatic) CommandOrigin.PET_HANDOFF_AUTO else CommandOrigin.PET_HANDOFF_CONFIRMED
        val tracked = chatService.submitUserMessageTracked(
            conversationId = Uuid.parse(claimed.privilegedConversationId),
            content = listOf(UIMessagePart.Text(handoffMessage(claimed))),
            origin = origin,
            dedupeKey = "pet-handoff:${claimed.requestId}",
            expiresAt = claimed.expiresAtMs?.let(Instant::fromEpochMilliseconds),
            assistantIdSnapshot = Uuid.parse(claimed.assistantId),
            commandId = commandId,
            annotations = listOf(correlation),
        )
        val submission = tracked.submission
        if (submission !is SubmitResult.Accepted) {
            return PetHandoffSubmitResult.Rejected(
                (submission as? SubmitResult.Rejected)?.reason ?: "pet_handoff_queue_rejected",
            )
        }
        val updated = dao.updateHandoffStatus(
            requestId = claimed.requestId,
            expectedVersion = claimed.stateVersion,
            nextStatus = PetHandoffStatus.SUBMITTED.name,
            targetCommandId = commandId.toString(),
            submittedAtMs = now,
            resolvedAtMs = null,
        )
        if (updated != 1) {
            return PetHandoffSubmitResult.Conflict
        }
        resumeTracking(claimed.requestId)
        return PetHandoffSubmitResult.Submitted(commandId)
    }

    /** Reattaches result delivery after process recreation without resubmitting the command. */
    fun resumeTracking(requestId: String) {
        launchTracking(requestId) {
            val handoff = dao.getHandoff(requestId) ?: return@launchTracking
            val commandId = handoff.targetCommandId ?: return@launchTracking
            // UIMessage starts life with a COMPLETED-compatible default and can expose the first
            // streamed character before the durable command has actually finished. The queue row
            // is the terminal barrier; only read the persisted conversation after it is terminal.
            val command = pendingCommandDao.observeById(commandId)
                .filterNotNull()
                .first { isTerminalPetHandoffCommandState(it.state) }
            when {
                command.state == DurableCommandState.COMPLETED.name -> finish(requestId, CommandOutcome.Completed)
                command.state == DurableCommandState.CANCELLED.name -> finish(requestId, CommandOutcome.Cancelled)
                else -> finish(requestId, CommandOutcome.Rejected(command.lastErrorCode ?: "pet_handoff_command_failed"))
            }
        }
    }

    private fun launchTracking(requestId: String, block: suspend () -> Unit) {
        synchronized(trackingLock) {
            if (trackingJobs[requestId]?.isActive == true) return
            trackingJobs[requestId] = appScope.launch {
                try {
                    block()
                } finally {
                    synchronized(trackingLock) { trackingJobs.remove(requestId) }
                }
            }
        }
    }

    private suspend fun finish(requestId: String, outcome: CommandOutcome) {
        val handoff = dao.getHandoff(requestId) ?: return
        if (handoff.status == PetHandoffStatus.RESOLVED.name || handoff.status == PetHandoffStatus.FAILED.name) return
        val failed = outcome != CommandOutcome.Completed
        val text = if (!failed) {
            val correlation = UIMessageAnnotation.PetHandoff(
                commandId = handoff.targetCommandId ?: handoff.requestId,
                requestId = handoff.requestId,
            )
            conversationRepository
                .getConversationById(Uuid.parse(handoff.privilegedConversationId))
                ?.let { findPetHandoffAnswer(it, correlation) }
                ?: "第二用户已完成任务，但没有返回可显示的文字。"
        } else {
            "第二用户没有完成这项任务，请打开主会话查看或重试。"
        }
        val safeText = PetBubbleSanitizer.sanitizeDraft(text)
        if (dialogueRepository.completeHandoff(requestId, safeText, failed)) {
            _completions.emit(PetHandoffCompletion(requestId, safeText, failed, nowMs()))
        }
    }

    private fun handoffMessage(request: PetHandoffRequestEntity): String = buildString {
        append("[桌宠转交任务] ")
        append(request.title)
        append('\n')
        append(request.request)
        append("\n\n请把它当作新任务处理；不要假定桌宠已经执行任何操作。")
    }

    private companion object {
        const val AUTO_RATE_WINDOW_MS = PetAutoHandoffPolicy.WINDOW_MS
    }
}

internal fun isTerminalPetHandoffCommandState(state: String): Boolean = state in setOf(
    DurableCommandState.COMPLETED.name,
    DurableCommandState.FAILED.name,
    DurableCommandState.CANCELLED.name,
)

internal fun findPetHandoffAnswer(
    conversation: Conversation,
    correlation: UIMessageAnnotation.PetHandoff,
): String? {
    val messages = conversation.currentMessages
    val correlated = messages.asReversed().firstOrNull {
        it.role == MessageRole.ASSISTANT &&
            it.state == UIMessageState.COMPLETED &&
            correlation in it.annotations
    }
    val answer = correlated ?: run {
        // Compatibility fallback for a generation resumed after approval by older code: the
        // source user message remains correlated even if the continuation replaced annotations.
        val sourceIndex = messages.indexOfLast {
            it.role == MessageRole.USER && correlation in it.annotations
        }
        if (sourceIndex < 0) return null
        messages.drop(sourceIndex + 1)
            .takeWhile { it.role != MessageRole.USER }
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.state == UIMessageState.COMPLETED }
            ?: return null
    }
    return answer.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString(" ") { it.text }
        .trim()
        .takeIf { it.isNotEmpty() }
}

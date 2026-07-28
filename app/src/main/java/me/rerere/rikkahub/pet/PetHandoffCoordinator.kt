package me.rerere.rikkahub.pet

import androidx.room.withTransaction
import kotlin.time.Instant
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.PetDialogueDao
import me.rerere.rikkahub.data.db.entity.PetHandoffRequestEntity
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.SubmitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface PetHandoffSubmitResult {
    data class Submitted(val commandId: Uuid) : PetHandoffSubmitResult
    data class Rejected(val code: String) : PetHandoffSubmitResult
    data object Missing : PetHandoffSubmitResult
    data object Conflict : PetHandoffSubmitResult
    data object Expired : PetHandoffSubmitResult
    data object RateLimited : PetHandoffSubmitResult
}

class PetHandoffCoordinator(
    private val database: AppDatabase,
    private val dao: PetDialogueDao,
    private val chatService: ChatService,
    private val appScope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
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
            if (automatic) {
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
        claimed.targetCommandId?.let { return PetHandoffSubmitResult.Submitted(Uuid.parse(it)) }

        val commandId = Uuid.parse(claimed.requestId)
        val origin = if (automatic) CommandOrigin.PET_HANDOFF_AUTO else CommandOrigin.PET_HANDOFF_CONFIRMED
        val tracked = chatService.submitUserMessageTracked(
            conversationId = Uuid.parse(claimed.privilegedConversationId),
            content = listOf(UIMessagePart.Text(handoffMessage(claimed))),
            origin = origin,
            dedupeKey = "pet-handoff:${claimed.requestId}",
            expiresAt = claimed.expiresAtMs?.let(Instant::fromEpochMilliseconds),
            assistantIdSnapshot = Uuid.parse(claimed.assistantId),
            commandId = commandId,
        )
        val submission = tracked.submission
        if (submission !is SubmitResult.Accepted) {
            return PetHandoffSubmitResult.Rejected(
                (submission as? SubmitResult.Rejected)?.reason ?: "pet_handoff_queue_rejected",
            )
        }
        val submittedStatus = if (automatic) PetHandoffStatus.AUTO_SUBMITTED else PetHandoffStatus.RESOLVED
        dao.updateHandoffStatus(
            requestId = claimed.requestId,
            expectedVersion = claimed.stateVersion,
            nextStatus = submittedStatus.name,
            targetCommandId = commandId.toString(),
            submittedAtMs = now,
            resolvedAtMs = if (automatic) null else now,
        )
        if (automatic) {
            appScope.launch {
                runCatching { tracked.outcome.await() }
                val latest = dao.getHandoff(claimed.requestId) ?: return@launch
                if (latest.status == PetHandoffStatus.AUTO_SUBMITTED.name) {
                    dao.updateHandoffStatus(
                        requestId = latest.requestId,
                        expectedVersion = latest.stateVersion,
                        nextStatus = PetHandoffStatus.RESOLVED.name,
                        targetCommandId = latest.targetCommandId,
                        submittedAtMs = latest.submittedAtMs,
                        resolvedAtMs = nowMs(),
                    )
                }
            }
        }
        return PetHandoffSubmitResult.Submitted(commandId)
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

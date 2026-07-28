package me.rerere.rikkahub.pet

import me.rerere.rikkahub.data.db.dao.PendingChatCommandDao
import me.rerere.rikkahub.data.db.dao.PetDialogueDao
import me.rerere.rikkahub.service.chat.DurableCommandState

class PetHandoffRecovery(
    private val dao: PetDialogueDao,
    private val pendingCommandDao: PendingChatCommandDao,
    private val coordinator: PetHandoffCoordinator,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile() {
        dao.expireHandoffs(nowMs())
        dao.getRecoverableHandoffs().forEach { handoff ->
            if (handoff.status == PetHandoffStatus.CONFIRMED.name) {
                coordinator.submit(
                    requestId = handoff.requestId,
                    automatic = handoff.mode == PetHandoffMode.AUTO.name,
                )
                return@forEach
            }
            val command = handoff.targetCommandId?.let { pendingCommandDao.findById(it) }
            val terminal = command == null || command.state in setOf(
                DurableCommandState.COMPLETED.name,
                DurableCommandState.FAILED.name,
                DurableCommandState.CANCELLED.name,
            )
            if (terminal) {
                dao.updateHandoffStatus(
                    requestId = handoff.requestId,
                    expectedVersion = handoff.stateVersion,
                    nextStatus = PetHandoffStatus.RESOLVED.name,
                    targetCommandId = handoff.targetCommandId,
                    submittedAtMs = handoff.submittedAtMs,
                    resolvedAtMs = nowMs(),
                )
            }
        }
    }
}

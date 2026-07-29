package me.rerere.rikkahub.pet

import me.rerere.rikkahub.data.db.dao.PetDialogueDao

class PetHandoffRecovery(
    private val dao: PetDialogueDao,
    private val coordinator: PetHandoffCoordinator,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile() {
        dao.expireHandoffs(nowMs())
        dao.getRecoverableHandoffs().forEach { handoff ->
            if (handoff.status == PetHandoffStatus.CONFIRMED.name && handoff.targetCommandId == null) {
                coordinator.submit(
                    requestId = handoff.requestId,
                    automatic = handoff.mode == PetHandoffMode.AUTO.name,
                )
                return@forEach
            }
            coordinator.resumeTracking(handoff.requestId)
        }
    }
}

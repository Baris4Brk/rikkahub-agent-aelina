package me.rerere.rikkahub.pet

import android.content.Context
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.PetDialogueDao

data class PetDiagnosticSnapshot(
    val overCapacitySessions: Int,
    val expiredPendingHandoffs: Int,
    val pendingSummaries: Int,
    val missingPackages: List<String>,
    val truncatedPersonas: List<String>,
)

class PetDiagnostics(
    private val context: Context,
    private val dao: PetDialogueDao,
    private val settingsStore: SettingsStore,
    private val summaryScheduler: PetSummaryScheduler,
) {
    suspend fun inspect(): PetDiagnosticSnapshot {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val enabled = settings.assistants.filter { it.petEnabled }
        return PetDiagnosticSnapshot(
            overCapacitySessions = dao.countOverCapacitySessions(),
            expiredPendingHandoffs = dao.countExpiredPendingHandoffs(System.currentTimeMillis()),
            pendingSummaries = dao.getPendingSummaries(1_000).size,
            missingPackages = enabled.filter { assistant ->
                assistant.petPackageId?.let { !context.filesDir.resolve("pets/$it/pet.json").isFile } ?: true
            }.map { it.name.ifBlank { it.id.toString() } },
            truncatedPersonas = enabled.filter { PetPersonaSource.buildProjection(it).truncated }
                .map { it.name.ifBlank { it.id.toString() } },
        )
    }

    suspend fun repair(): Int {
        val expired = dao.expireHandoffs(System.currentTimeMillis())
        val pending = dao.getPendingSummaries(100)
        pending.forEach { summaryScheduler.schedule(it.sessionId) }
        return expired + pending.size
    }
}

package me.rerere.rikkahub.memory

import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.entity.MemoryCaptureEntity

class RoomMemoryCaptureStore(
    private val dao: MemoryV2Dao,
) : MemoryCaptureStore {
    override suspend fun insert(record: MemoryCaptureRecord): MemoryCaptureInsertResult {
        val rowId = dao.insertCapture(record.toEntity())
        if (rowId != -1L) return MemoryCaptureInsertResult.Inserted
        val existing = dao.findCaptureByTurn(
            conversationId = record.conversationId,
            assistantMessageId = record.assistantMessageId,
            captureSource = record.captureSource.name,
        )
        return if (existing != null) {
            MemoryCaptureInsertResult.Duplicate(existing.id)
        } else {
            // IGNORE can only lose to a primary/unique key. Treat the generated id as the
            // duplicate key if a concurrent cleanup removed the winning turn before lookup.
            MemoryCaptureInsertResult.Duplicate(record.id)
        }
    }

    override suspend fun pendingCount(scopeId: String): Int = dao.countPendingCaptures(scopeId)
}

private fun MemoryCaptureRecord.toEntity() = MemoryCaptureEntity(
    id = id,
    assistantId = assistantId,
    scopeId = scopeId,
    conversationId = conversationId,
    userMessageId = userMessageId,
    assistantMessageId = assistantMessageId,
    origin = origin.name,
    captureSource = captureSource.name,
    autoSaveMode = autoSaveMode.name,
    userText = userText,
    assistantText = assistantText,
    contextTurnLimit = conversationContextTurns,
    createdAtMs = createdAtMs,
    updatedAtMs = createdAtMs,
    narrativeEventsEnabled = narrativeEventsEnabled,
    insightsTheoriesEnabled = insightsTheoriesEnabled,
)

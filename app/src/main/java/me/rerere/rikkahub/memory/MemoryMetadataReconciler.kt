package me.rerere.rikkahub.memory

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.MemoryDAO

class MemoryMetadataReconciler(
    private val dao: MemoryDAO,
    private val json: Json,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun reconcile(batchSize: Int = 100): Int {
        var updated = 0
        while (true) {
            val batch = dao.getMemoriesMissingV2Metadata(batchSize.coerceIn(1, 500))
            if (batch.isEmpty()) return updated
            batch.forEach { memory ->
                val tags = runCatching { json.decodeFromString<List<String>>(memory.tagsJson) }
                    .getOrDefault(emptyList())
                dao.updateV2Metadata(
                    id = memory.id,
                    contentHash = memoryContentHash(memory.content),
                    tagsSearch = tags.joinToString(" ") { it.trim() },
                    createdAtMs = memory.createdAtMs.takeIf { it > 0 }
                        ?: memory.updatedAtMs.takeIf { it > 0 }
                        ?: nowMs(),
                )
                updated++
            }
            if (batch.size < batchSize) return updated
        }
    }
}

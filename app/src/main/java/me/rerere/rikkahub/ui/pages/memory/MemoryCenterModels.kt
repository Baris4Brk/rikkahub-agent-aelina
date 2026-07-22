package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryQueryRecord

enum class MemoryCenterTab { LIBRARY, REVIEW, SETTINGS }

enum class MemoryLibrarySort { UPDATED, IMPORTANCE, RECENT_ACCESS }

data class MemoryLibraryFilter(
    val query: String = "",
    val includeArchived: Boolean = false,
    val kind: MemoryKind? = null,
    val sourceType: String? = null,
    val tag: String = "",
    val sort: MemoryLibrarySort = MemoryLibrarySort.UPDATED,
)

data class MemoryCenterStats(
    val active: Int = 0,
    val archived: Int = 0,
    val pendingReview: Int = 0,
    val pendingCaptures: Int = 0,
    val processingCaptures: Int = 0,
    val processedCaptures: Int = 0,
    val noLongTermSignalCaptures: Int = 0,
    val failedCaptures: Int = 0,
    val pausedCaptures: Int = 0,
    val discardedCaptures: Int = 0,
    val lastProcessedAtMs: Long? = null,
)

sealed interface MemoryRecallTestState {
    data object Idle : MemoryRecallTestState
    data object Loading : MemoryRecallTestState
    data class Ready(
        val query: String,
        val results: List<MemoryQueryRecord>,
        val usedCharacters: Int,
        val characterBudget: Int,
    ) : MemoryRecallTestState
    data class Failed(val message: String) : MemoryRecallTestState
}

data class MemoryExtractionModelUiState(
    val modelName: String,
    val providerName: String,
    val usingFastModel: Boolean,
    val available: Boolean = true,
)

data class MemoryModelOption(
    val id: kotlin.uuid.Uuid,
    val name: String,
    val providerName: String,
)

data class MemorySourceLocation(
    val conversationId: kotlin.uuid.Uuid,
    val nodeId: kotlin.uuid.Uuid?,
)

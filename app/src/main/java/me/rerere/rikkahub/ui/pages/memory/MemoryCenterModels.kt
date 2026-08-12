package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.data.db.entity.MemoryRelationCandidateEntity
import me.rerere.rikkahub.memory.MemoryKind
import me.rerere.rikkahub.memory.MemoryQueryRecord
import me.rerere.rikkahub.memory.MemoryRelationReviewCommand
import me.rerere.rikkahub.utils.JsonInstant

enum class MemoryCenterTab { LIBRARY, DREAM, REVIEW, SETTINGS, OBSERVER }

internal fun memoryCenterTabs(developerMode: Boolean): List<MemoryCenterTab> =
    if (developerMode) {
        MemoryCenterTab.entries
    } else {
        MemoryCenterTab.entries.filterNot { it == MemoryCenterTab.OBSERVER }
    }

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
    val pendingRelationReview: Int = 0,
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

enum class MemoryRelationEndpointKind { MEMORY, PROPOSAL, CANDIDATE, UNKNOWN }

data class MemoryRelationEndpointUi(
    val kind: MemoryRelationEndpointKind,
    val reference: String = "",
)

internal fun MemoryRelationCandidateEntity.sourceEndpointUi(): MemoryRelationEndpointUi =
    relationEndpointUi(sourceMemoryId, sourceProposalKey, sourceCandidateId)

internal fun MemoryRelationCandidateEntity.targetEndpointUi(): MemoryRelationEndpointUi =
    relationEndpointUi(targetMemoryId, targetProposalKey, targetCandidateId)

internal fun MemoryRelationCandidateEntity.evidenceCount(): Int = runCatching {
    JsonInstant.decodeFromString<List<String>>(evidenceMessageIdsJson)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .size
}.getOrDefault(0)

/** Keeps authorization bound to the row even if the visible scope changes before a click lands. */
internal fun MemoryRelationCandidateEntity.reviewCommand(
    accept: Boolean,
): MemoryRelationReviewCommand = if (accept) {
    MemoryRelationReviewCommand.Accept(
        relationCandidateId = id,
        expectedScopeId = scopeId,
    )
} else {
    MemoryRelationReviewCommand.Reject(
        relationCandidateId = id,
        expectedScopeId = scopeId,
    )
}

private fun relationEndpointUi(
    memoryId: Int?,
    proposalKey: String?,
    candidateId: String?,
): MemoryRelationEndpointUi = when {
    memoryId != null -> MemoryRelationEndpointUi(
        kind = MemoryRelationEndpointKind.MEMORY,
        reference = memoryId.toString(),
    )

    !proposalKey.isNullOrBlank() -> MemoryRelationEndpointUi(
        kind = MemoryRelationEndpointKind.PROPOSAL,
        reference = proposalKey,
    )

    !candidateId.isNullOrBlank() -> MemoryRelationEndpointUi(
        kind = MemoryRelationEndpointKind.CANDIDATE,
        reference = candidateId,
    )

    else -> MemoryRelationEndpointUi(MemoryRelationEndpointKind.UNKNOWN)
}

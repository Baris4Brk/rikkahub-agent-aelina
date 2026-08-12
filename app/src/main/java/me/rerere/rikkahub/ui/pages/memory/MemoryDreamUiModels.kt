package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.memory.dreaming.review.DreamClaimDetail
import me.rerere.rikkahub.memory.dreaming.review.DreamClaimMutationTarget
import me.rerere.rikkahub.memory.dreaming.review.DreamEvidenceExcerpt
import me.rerere.rikkahub.memory.dreaming.review.DreamEvidenceReference

sealed interface MemoryDreamDetailState {
    data object Closed : MemoryDreamDetailState
    data class Loading(val target: DreamClaimMutationTarget) : MemoryDreamDetailState
    data class Ready(
        val detail: DreamClaimDetail,
        val revealedEvidence: Map<DreamEvidenceReference, DreamEvidenceExcerpt> = emptyMap(),
        val revealingEvidence: Set<DreamEvidenceReference> = emptySet(),
    ) : MemoryDreamDetailState
    data class Failed(
        val target: DreamClaimMutationTarget,
        val reasonCode: String,
    ) : MemoryDreamDetailState
}

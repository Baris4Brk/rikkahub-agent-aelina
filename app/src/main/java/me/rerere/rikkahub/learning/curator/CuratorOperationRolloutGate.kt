package me.rerere.rikkahub.learning.curator

import me.rerere.rikkahub.learning.model.LearningPositiveMutation
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate

/** Exact one-to-one mapping: opening one Curator operation never opens a sibling operation. */
internal fun LearningPositiveMutationGate.allows(
    operation: CuratorDeltaOperation,
): Boolean = allows(
    when (operation) {
        CuratorDeltaOperation.UPDATE_CANDIDATE ->
            LearningPositiveMutation.CURATOR_UPDATE_CANDIDATE
        CuratorDeltaOperation.MERGE_CANDIDATE ->
            LearningPositiveMutation.CURATOR_MERGE_CANDIDATE
        CuratorDeltaOperation.SPLIT_CANDIDATE ->
            LearningPositiveMutation.CURATOR_SPLIT_CANDIDATE
        CuratorDeltaOperation.SUPERSEDE_CANDIDATE ->
            LearningPositiveMutation.CURATOR_SUPERSEDE_CANDIDATE
    },
)

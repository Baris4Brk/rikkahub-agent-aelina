package me.rerere.rikkahub.learning.provenance

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind

enum class LearningSourceAuthorityState {
    ACTIVE,
    SUPERSEDED,
    TOMBSTONED,
    REVISION_CHANGED,
    UNKNOWN,
}

data class LearningSourceValidityKey(
    val scope: LearningScope,
    val sourceKind: LearningSourceKind,
    val sourceId: String,
    val expectedRevision: Long,
) {
    init {
        require(sourceKind != LearningSourceKind.UNKNOWN)
        require(sourceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:@-]{0,255}")))
        require(expectedRevision > 0L)
    }

    override fun toString(): String =
        "LearningSourceValidityKey(kind=$sourceKind, revision=$expectedRevision, " +
            "scope=${scope.kind}, id=<redacted>)"
}

data class LearningSourceValidity(
    val key: LearningSourceValidityKey,
    val currentRevision: Long,
    val state: LearningSourceAuthorityState,
    val observedAtMs: Long,
) {
    init {
        require(currentRevision >= key.expectedRevision)
        require(observedAtMs >= 0L)
        require((state == LearningSourceAuthorityState.ACTIVE) == (currentRevision == key.expectedRevision))
    }

    val isValid: Boolean
        get() = state == LearningSourceAuthorityState.ACTIVE && currentRevision == key.expectedRevision
}

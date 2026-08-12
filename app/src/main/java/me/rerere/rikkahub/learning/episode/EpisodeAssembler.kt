package me.rerere.rikkahub.learning.episode

import me.rerere.rikkahub.learning.task.TaskSignatureV1

enum class EpisodeAssemblyFailure {
    LEGACY_OR_INCOMPLETE_AUTHORITY,
    IDENTITY_MISMATCH,
    REVISION_REGRESSION,
    TERMINAL_REPLAY_CONFLICT,
}

sealed interface EpisodeAssemblyResult {
    data class Applied(val snapshot: EpisodeSnapshot) : EpisodeAssemblyResult
    data class Duplicate(val snapshot: EpisodeSnapshot) : EpisodeAssemblyResult
    data class Rejected(val failure: EpisodeAssemblyFailure) : EpisodeAssemblyResult
}

/** Content-free state projected into LearningDatabase. */
data class EpisodeSnapshot(
    val authority: EpisodeAuthorityAnchor,
    val taskSignature: TaskSignatureV1,
    val status: LearningEpisodeStatus,
    val boundaryReason: EpisodeBoundaryReason,
    val revision: Long,
    val startedAtMs: Long,
    val finalizedAtMs: Long?,
) {
    init {
        require(revision > 0L)
        require(startedAtMs >= 0L)
        require((status == LearningEpisodeStatus.OPEN) == (finalizedAtMs == null)) {
            "Episode finalization timestamp does not match status"
        }
        require(finalizedAtMs == null || finalizedAtMs >= startedAtMs)
    }

    override fun toString(): String =
        "EpisodeSnapshot(status=$status, revision=$revision, boundary=$boundaryReason, " +
            "authority=$authority, task=<opaque>)"
}

/** Pure state reducer; the storage layer owns CAS and replay transactions. */
object EpisodeAssembler {
    fun admit(
        authority: EpisodeAuthorityAnchor,
        taskSignature: TaskSignatureV1,
        occurredAtMs: Long,
    ): EpisodeSnapshot {
        require(occurredAtMs >= 0L)
        return EpisodeSnapshot(
            authority = authority,
            taskSignature = taskSignature,
            status = LearningEpisodeStatus.OPEN,
            boundaryReason = EpisodeBoundaryReason.ROOT_COMMAND_ADMITTED,
            revision = 1L,
            startedAtMs = occurredAtMs,
            finalizedAtMs = null,
        )
    }

    fun apply(
        current: EpisodeSnapshot,
        authority: EpisodeAuthorityAnchor,
        completionKind: LearningCompletionKind,
        terminalStateCode: String?,
        occurredAtMs: Long,
    ): EpisodeAssemblyResult {
        if (current.authority.episodeId != authority.episodeId || current.authority.scope != authority.scope) {
            return EpisodeAssemblyResult.Rejected(EpisodeAssemblyFailure.IDENTITY_MISMATCH)
        }
        if (occurredAtMs < current.startedAtMs) {
            return EpisodeAssemblyResult.Rejected(EpisodeAssemblyFailure.REVISION_REGRESSION)
        }
        return when (val decision = EpisodeBoundaryPolicy.decide(authority, completionKind, terminalStateCode)) {
            EpisodeBoundaryDecision.IgnoreNonLlmCommand -> EpisodeAssemblyResult.Duplicate(current)
            is EpisodeBoundaryDecision.KeepOpen -> {
                if (current.status != LearningEpisodeStatus.OPEN) {
                    EpisodeAssemblyResult.Rejected(EpisodeAssemblyFailure.TERMINAL_REPLAY_CONFLICT)
                } else if (current.boundaryReason == decision.reason && current.authority == authority) {
                    EpisodeAssemblyResult.Duplicate(current)
                } else {
                    EpisodeAssemblyResult.Applied(
                        current.copy(
                            authority = authority,
                            boundaryReason = decision.reason,
                            revision = Math.addExact(current.revision, 1L),
                        ),
                    )
                }
            }

            is EpisodeBoundaryDecision.Finalize -> {
                val next = current.copy(
                    authority = authority,
                    status = decision.status,
                    boundaryReason = decision.reason,
                    revision = Math.addExact(current.revision, 1L),
                    finalizedAtMs = occurredAtMs,
                )
                if (current.status == LearningEpisodeStatus.OPEN) {
                    EpisodeAssemblyResult.Applied(next)
                } else if (
                    current.status == next.status &&
                    current.boundaryReason == next.boundaryReason &&
                    current.finalizedAtMs == next.finalizedAtMs &&
                    current.authority == next.authority
                ) {
                    EpisodeAssemblyResult.Duplicate(current)
                } else {
                    EpisodeAssemblyResult.Rejected(EpisodeAssemblyFailure.TERMINAL_REPLAY_CONFLICT)
                }
            }
        }
    }
}

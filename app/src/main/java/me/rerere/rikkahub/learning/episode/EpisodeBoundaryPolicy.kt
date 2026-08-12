package me.rerere.rikkahub.learning.episode

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope

enum class LearningCompletionKind {
    GENERATION_WAITING_APPROVAL,
    GENERATION_FINAL_SAVED,
    FAST_PATH_HANDLED,
    CONTROL_ONLY,
    CENSORED_CANCELLED,
    SUPERSEDED_REGENERATE,
    FAILED_FINAL_SAVE,
    FAILED_OTHER,
    ;

    companion object {
        fun parseOrNull(rawCode: String?): LearningCompletionKind? =
            entries.firstOrNull { it.name == rawCode }
    }
}

enum class LearningEpisodeStatus {
    OPEN,
    SUCCESS,
    PARTIAL,
    FAILURE,
    ABORTED,
    TIMEOUT,
    CENSORED,
    SUPERSEDED,
    UNKNOWN,
}

enum class EpisodeBoundaryReason {
    ROOT_COMMAND_ADMITTED,
    WAITING_APPROVAL_CHECKPOINT,
    FINAL_RESPONSE_SAVED,
    USER_CANCELLED,
    REGENERATED_BRANCH,
    FINAL_SAVE_FAILED,
    COMMAND_FAILED,
    LEGACY_OR_INCOMPLETE_AUTHORITY,
}

/** Only exact authority fields are accepted; null legacy fields never produce an Episode. */
data class EpisodeAuthorityAnchor(
    val streamId: Uuid,
    val scope: LearningScope,
    val conversationId: Uuid,
    val commandId: Uuid,
    val lineageId: Uuid,
    val branchAnchorMessageId: Uuid,
    val branchAnchorMessageRevision: Long,
    val parentCommandId: Uuid?,
    val resultAssistantMessageId: Uuid?,
    val resultAssistantMessageRevision: Long?,
) {
    init {
        require(branchAnchorMessageRevision > 0L) { "Episode branch anchor requires a revision" }
        require((resultAssistantMessageId == null) == (resultAssistantMessageRevision == null)) {
            "Episode result message identity and revision must be paired"
        }
        require(resultAssistantMessageRevision == null || resultAssistantMessageRevision > 0L) {
            "Episode result message revision must be positive"
        }
    }

    val episodeId: EpisodeId
        get() = EpisodeIdFactory.create(streamId, lineageId, branchAnchorMessageId)

    override fun toString(): String =
        "EpisodeAuthorityAnchor(scope=${scope.kind}, result=${resultAssistantMessageId != null}, " +
            "ids=<redacted>)"
}

sealed interface EpisodeBoundaryDecision {
    data object IgnoreNonLlmCommand : EpisodeBoundaryDecision

    data class KeepOpen(
        val episodeId: EpisodeId,
        val reason: EpisodeBoundaryReason,
    ) : EpisodeBoundaryDecision

    data class Finalize(
        val episodeId: EpisodeId,
        val status: LearningEpisodeStatus,
        val reason: EpisodeBoundaryReason,
        val resultAssistantMessageId: Uuid?,
        val resultAssistantMessageRevision: Long?,
    ) : EpisodeBoundaryDecision
}

object EpisodeBoundaryPolicy {
    /**
     * Maps a durable command completion to an Episode transition. A command terminal state alone
     * is deliberately insufficient to claim success; only GENERATION_FINAL_SAVED with an exact
     * assistant-message authority pair can do that.
     */
    fun decide(
        anchor: EpisodeAuthorityAnchor,
        completionKind: LearningCompletionKind,
        terminalStateCode: String?,
    ): EpisodeBoundaryDecision = when (completionKind) {
        LearningCompletionKind.FAST_PATH_HANDLED,
        LearningCompletionKind.CONTROL_ONLY -> EpisodeBoundaryDecision.IgnoreNonLlmCommand

        LearningCompletionKind.GENERATION_WAITING_APPROVAL -> EpisodeBoundaryDecision.KeepOpen(
            episodeId = anchor.episodeId,
            reason = EpisodeBoundaryReason.WAITING_APPROVAL_CHECKPOINT,
        )

        LearningCompletionKind.GENERATION_FINAL_SAVED -> {
            require(anchor.resultAssistantMessageId != null) {
                "A final-saved Episode requires an authoritative assistant message"
            }
            val status = when (terminalStateCode) {
                "COMPLETED" -> LearningEpisodeStatus.SUCCESS
                // A saved response with a non-success terminal is observable but not a clean
                // negative label. Reward components remain independently known/unknown.
                "FAILED" -> LearningEpisodeStatus.PARTIAL
                else -> LearningEpisodeStatus.UNKNOWN
            }
            EpisodeBoundaryDecision.Finalize(
                episodeId = anchor.episodeId,
                status = status,
                reason = EpisodeBoundaryReason.FINAL_RESPONSE_SAVED,
                resultAssistantMessageId = anchor.resultAssistantMessageId,
                resultAssistantMessageRevision = anchor.resultAssistantMessageRevision,
            )
        }

        LearningCompletionKind.CENSORED_CANCELLED -> EpisodeBoundaryDecision.Finalize(
            anchor.episodeId,
            LearningEpisodeStatus.CENSORED,
            EpisodeBoundaryReason.USER_CANCELLED,
            null,
            null,
        )

        LearningCompletionKind.SUPERSEDED_REGENERATE -> EpisodeBoundaryDecision.Finalize(
            anchor.episodeId,
            LearningEpisodeStatus.SUPERSEDED,
            EpisodeBoundaryReason.REGENERATED_BRANCH,
            null,
            null,
        )

        LearningCompletionKind.FAILED_FINAL_SAVE -> EpisodeBoundaryDecision.Finalize(
            anchor.episodeId,
            LearningEpisodeStatus.UNKNOWN,
            EpisodeBoundaryReason.FINAL_SAVE_FAILED,
            null,
            null,
        )

        LearningCompletionKind.FAILED_OTHER -> EpisodeBoundaryDecision.Finalize(
            anchor.episodeId,
            if (terminalStateCode == "FAILED") LearningEpisodeStatus.FAILURE else LearningEpisodeStatus.UNKNOWN,
            EpisodeBoundaryReason.COMMAND_FAILED,
            null,
            null,
        )
    }
}

package me.rerere.rikkahub.learning.exposure

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.TaskSignatureV1

private val NIL_UUID = Uuid.parse("00000000-0000-0000-0000-000000000000")

/** Content-free authority supplied by the command runtime before any Policy lookup. */
data class PolicyLearningCommandContext(
    val scope: LearningScope,
    val consumingAssistantId: Uuid,
    val lineageId: Uuid,
    val branchAnchorMessageId: Uuid,
    val branchAnchorMessageRevision: Long? = null,
    val logicalRunId: Uuid,
) {
    init {
        require(consumingAssistantId != NIL_UUID)
        require(lineageId != NIL_UUID && branchAnchorMessageId != NIL_UUID && logicalRunId != NIL_UUID)
        require(branchAnchorMessageRevision == null || branchAnchorMessageRevision > 0L)
        if (scope is LearningScope.Assistant) require(scope.assistantId == consumingAssistantId)
    }

    override fun toString(): String =
        "PolicyLearningCommandContext(scope=${scope.kind}, ids=<redacted>)"
}

data class PolicyExposureRuntimeAnchorRequest(
    val command: PolicyLearningCommandContext,
    val taskSignature: TaskSignatureV1,
)

/** Exact rebuildable Episode identity required before a provider-affecting reservation. */
data class PolicyExposureRuntimeAnchor(
    val streamId: Uuid,
    val replayGeneration: Long,
    val episodeId: EpisodeId,
    val logicalRunId: Uuid,
) {
    init {
        require(streamId != NIL_UUID && logicalRunId != NIL_UUID)
        require(replayGeneration >= 0L)
    }

    override fun toString(): String =
        "PolicyExposureRuntimeAnchor(replay=$replayGeneration, ids=<redacted>)"
}

/** Returns null unless the exact stream/checkpoint/Episode/run tuple is already durable. */
fun interface PolicyExposureRuntimeAnchorSource {
    suspend fun resolve(request: PolicyExposureRuntimeAnchorRequest): PolicyExposureRuntimeAnchor?
}

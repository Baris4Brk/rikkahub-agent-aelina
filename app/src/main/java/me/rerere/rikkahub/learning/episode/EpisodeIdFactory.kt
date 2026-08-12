package me.rerere.rikkahub.learning.episode

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningCanonicalId

/**
 * Stable Episode identity for one user-led task on one selected branch.
 *
 * Approval commands, resume commands, provider retries and tool attempts keep the same lineage and
 * therefore resolve to the same Episode. Regeneration must allocate a new lineage before calling
 * this factory. The ID is derived in LearningDatabase and is never written back to the authority
 * outbox.
 */
@JvmInline
value class EpisodeId private constructor(val value: String) {
    override fun toString(): String = "EpisodeId(<opaque>)"

    companion object {
        private val PATTERN = Regex("episode-v1:[0-9a-f]{64}")

        fun parseOrNull(value: String): EpisodeId? = value.takeIf(PATTERN::matches)?.let(::EpisodeId)

        internal fun fromDigest(digest: String): EpisodeId = EpisodeId("episode-v1:$digest")
    }
}

object EpisodeIdFactory {
    const val BOUNDARY_VERSION: Int = 1

    fun create(
        streamId: Uuid,
        lineageId: Uuid,
        branchAnchorMessageId: Uuid,
    ): EpisodeId = EpisodeId.fromDigest(
        LearningCanonicalId.digest(
            domainVersion = "episode-v1",
            fields = listOf(
                streamId.toString(),
                lineageId.toString(),
                branchAnchorMessageId.toString(),
                BOUNDARY_VERSION.toString(),
            ),
        ),
    )
}

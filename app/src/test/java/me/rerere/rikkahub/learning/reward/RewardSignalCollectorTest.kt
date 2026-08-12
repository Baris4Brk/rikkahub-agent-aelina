package me.rerere.rikkahub.learning.reward

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeIdFactory
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardSignalCollectorTest {
    @Test
    fun unknownIsNotZeroAndExplicitUserFeedbackOutranksWeakJudge() {
        val episode = EpisodeIdFactory.create(Uuid.random(), Uuid.random(), Uuid.random())
        val open = RewardSignalCollector.open(episode, 10L, 100L, "reward-v1")
        assertTrue(open.user is RewardComponent.Unknown)

        val result = RewardSignalCollector.collect(
            open,
            listOf(
                signal(episode, "judge", RewardSignalKind.LLM_JUDGE_WEAK_LABEL, -0.7),
                signal(episode, "user", RewardSignalKind.EXPLICIT_USER_FEEDBACK, 1.0),
            ),
        ) as RewardCollectionResult.Updated
        val user = result.window.user as RewardComponent.Known
        assertEquals(1.0, user.value, 0.0)
        assertEquals(RewardSignalKind.EXPLICIT_USER_FEEDBACK, user.signalKind)
    }

    @Test
    fun duplicateRetryCannotMultiplySignalAndCancelClosesUnknownAsCensored() {
        val episode = EpisodeIdFactory.create(Uuid.random(), Uuid.random(), Uuid.random())
        val open = RewardSignalCollector.open(episode, 10L, 1_000L, "reward-v1")
        val same = signal(episode, "same", RewardSignalKind.COMMAND_FINAL_STATE, 0.5)
        val collected = RewardSignalCollector.collect(open, listOf(same, same)) as
            RewardCollectionResult.Updated
        val closed = RewardSignalCollector.close(collected.window, 20L, forceCensored = true) as
            RewardCollectionResult.Updated

        assertEquals(RewardWindowState.CLOSED, closed.window.state)
        assertEquals(
            RewardUnknownReason.CENSORED,
            (closed.window.goal as RewardComponent.Unknown).reason,
        )
    }

    private fun signal(
        episodeId: me.rerere.rikkahub.learning.episode.EpisodeId,
        id: String,
        kind: RewardSignalKind,
        value: Double,
    ) = RewardSignal(
        signalId = id,
        episodeId = episodeId,
        dimension = RewardDimension.USER,
        kind = kind,
        value = value,
        unknownReason = null,
        evidence = listOf(source()),
        occurredAtMs = 20L,
    )

    private fun source() = LearningSourceRef(
        sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
        sourceId = Uuid.random().toString(),
        sourceRevision = 1L,
        missingRevisionReason = null,
        databaseStreamId = Uuid.random(),
        scope = LearningScope.Assistant(Uuid.random()),
        occurredAtMs = 10L,
    )
}

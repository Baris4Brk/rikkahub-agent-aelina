package me.rerere.rikkahub.learning.episode

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeBoundaryPolicyTest {
    @Test
    fun approvalAndResumeKeepSameEpisodeButRegenerationLineageDoesNot() {
        val anchor = anchor()
        val waiting = EpisodeBoundaryPolicy.decide(
            anchor,
            LearningCompletionKind.GENERATION_WAITING_APPROVAL,
            null,
        ) as EpisodeBoundaryDecision.KeepOpen
        val resumed = EpisodeBoundaryPolicy.decide(
            anchor.copy(
                commandId = Uuid.random(),
                parentCommandId = anchor.commandId,
                resultAssistantMessageId = Uuid.random(),
                resultAssistantMessageRevision = 2L,
            ),
            LearningCompletionKind.GENERATION_FINAL_SAVED,
            "COMPLETED",
        ) as EpisodeBoundaryDecision.Finalize

        assertEquals(waiting.episodeId, resumed.episodeId)
        assertNotEquals(
            waiting.episodeId,
            anchor.copy(lineageId = Uuid.random()).episodeId,
        )
    }

    @Test
    fun fastPathCreatesNoLlmEpisodeAndSaveFailureIsUnknown() {
        assertTrue(
            EpisodeBoundaryPolicy.decide(
                anchor(),
                LearningCompletionKind.FAST_PATH_HANDLED,
                "COMPLETED",
            ) is EpisodeBoundaryDecision.IgnoreNonLlmCommand,
        )
        val failed = EpisodeBoundaryPolicy.decide(
            anchor(),
            LearningCompletionKind.FAILED_FINAL_SAVE,
            "FAILED",
        ) as EpisodeBoundaryDecision.Finalize
        assertEquals(LearningEpisodeStatus.UNKNOWN, failed.status)
        assertNull(failed.resultAssistantMessageId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun finalSavedRequiresExactAssistantMessageRevisionPair() {
        EpisodeBoundaryPolicy.decide(
            anchor(),
            LearningCompletionKind.GENERATION_FINAL_SAVED,
            "COMPLETED",
        )
    }

    @Test
    fun waitingDoesNotFinalizeAndReplayDoesNotInflateRevision() {
        val anchor = anchor()
        val initial = EpisodeAssembler.admit(anchor, signature(), 100L)
        val once = EpisodeAssembler.apply(
            initial,
            anchor,
            LearningCompletionKind.GENERATION_WAITING_APPROVAL,
            null,
            110L,
        ) as EpisodeAssemblyResult.Applied
        val replay = EpisodeAssembler.apply(
            once.snapshot,
            anchor,
            LearningCompletionKind.GENERATION_WAITING_APPROVAL,
            null,
            120L,
        )

        assertEquals(LearningEpisodeStatus.OPEN, once.snapshot.status)
        assertNull(once.snapshot.finalizedAtMs)
        assertTrue(replay is EpisodeAssemblyResult.Duplicate)
        assertEquals(2L, (replay as EpisodeAssemblyResult.Duplicate).snapshot.revision)
    }

    private fun anchor() = EpisodeAuthorityAnchor(
        streamId = Uuid.random(),
        scope = LearningScope.Assistant(Uuid.random()),
        conversationId = Uuid.random(),
        commandId = Uuid.random(),
        lineageId = Uuid.random(),
        branchAnchorMessageId = Uuid.random(),
        branchAnchorMessageRevision = 1L,
        parentCommandId = null,
        resultAssistantMessageId = null,
        resultAssistantMessageRevision = null,
    )

    private fun signature() = TaskSignatureV1.create(
        LearningTaskClass.INFORMATION,
        LearningLanguageClass.CHINESE,
        LearningModalityClass.TEXT_ONLY,
        emptySet(),
    )
}

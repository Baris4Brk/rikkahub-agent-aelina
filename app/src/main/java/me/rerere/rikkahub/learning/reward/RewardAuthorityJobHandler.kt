package me.rerere.rikkahub.learning.reward

import me.rerere.rikkahub.learning.jobs.LearningJobExecutionControl
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionInputV1
import me.rerere.rikkahub.learning.jobs.LearningJobFailureCode
import me.rerere.rikkahub.learning.jobs.LearningJobHandler
import me.rerere.rikkahub.learning.jobs.LearningJobHandlerResult
import me.rerere.rikkahub.learning.jobs.LearningJobTypedOutput
import me.rerere.rikkahub.learning.storage.LearningRewardAuthorityOutcome
import me.rerere.rikkahub.learning.storage.LearningRewardSignalEntity
import me.rerere.rikkahub.learning.storage.LearningRewardWindowEntity

/** Exact, content-free material derived from one USER_FEEDBACK_RECORDED v3 authority event. */
data class RewardAuthorityJobMaterial(
    val signal: LearningRewardSignalEntity,
    val expectedWindowRevision: Long,
    val signalSetSha256: String,
    val authorityOutcome: LearningRewardAuthorityOutcome,
)

fun interface RewardAuthorityJobMaterialResolver {
    suspend fun resolve(input: LearningJobExecutionInputV1): RewardAuthorityJobMaterial?
}

data class RewardAuthorityJobOutput(
    val signal: LearningRewardSignalEntity,
    val expectedWindowRevision: Long,
    val signalSetSha256: String,
    val authorityOutcome: LearningRewardAuthorityOutcome,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "reward-authority-output-v1"

    override fun toString(): String =
        "RewardAuthorityJobOutput(outcome=$authorityOutcome, ids=<redacted>)"
}

/** Provider-free fold. Storage owns insert/replay validation and the fenced window CAS. */
class RewardAuthorityJobHandler(
    private val resolver: RewardAuthorityJobMaterialResolver,
) : LearningJobHandler<RewardAuthorityJobOutput> {
    override suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<RewardAuthorityJobOutput> {
        control.checkpoint()
        val material = try {
            resolver.resolve(input)
        } catch (_: IllegalArgumentException) {
            return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.INVALID_JOB_SPEC)
        } catch (_: IllegalStateException) {
            return LearningJobHandlerResult.Retry(
                LearningJobFailureCode.SOURCE_MISSING,
                RETRY_DELAY_MS,
            )
        } ?: return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.SOURCE_MISSING)
        control.checkpoint()
        return LearningJobHandlerResult.Success(
            RewardAuthorityJobOutput(
                signal = material.signal,
                expectedWindowRevision = material.expectedWindowRevision,
                signalSetSha256 = material.signalSetSha256,
                authorityOutcome = material.authorityOutcome,
            ),
        )
    }

    private companion object {
        const val RETRY_DELAY_MS = 15L * 60L * 1_000L
    }
}

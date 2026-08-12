package me.rerere.rikkahub.learning.reward

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionControl
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionInputV1
import me.rerere.rikkahub.learning.jobs.LearningJobFailureCode
import me.rerere.rikkahub.learning.jobs.LearningJobHandler
import me.rerere.rikkahub.learning.jobs.LearningJobHandlerResult
import me.rerere.rikkahub.learning.jobs.LearningJobTypedOutput
import me.rerere.rikkahub.learning.jobs.P1LearningConfigurationUnavailableException

data class RewardJobMaterial(
    val window: RewardWindow,
    val signals: List<RewardSignal>,
    val frozenNowMs: Long,
    val censored: Boolean,
)

fun interface RewardJobMaterialResolver {
    suspend fun resolve(input: LearningJobExecutionInputV1): RewardJobMaterial?
}

data class RewardWindowJobOutput(
    val window: RewardWindow,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "reward-window-v1"

    override fun toString(): String = "RewardWindowJobOutput(window=$window)"
}

/** Deterministic, provider-free reward close/catch-up job. */
class RewardJobHandler(
    private val materialResolver: RewardJobMaterialResolver,
) : LearningJobHandler<RewardWindowJobOutput> {
    override suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<RewardWindowJobOutput> {
        control.checkpoint()
        val material = try {
            materialResolver.resolve(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: P1LearningConfigurationUnavailableException) {
            return LearningJobHandlerResult.Retry(
                LearningJobFailureCode.WAITING_CONFIGURATION,
                RETRY_DELAY_MS,
            )
        } catch (_: Exception) {
            return LearningJobHandlerResult.Retry(LearningJobFailureCode.SOURCE_MISSING, RETRY_DELAY_MS)
        } ?: return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.SOURCE_MISSING)
        val collected = when (
            val result = RewardSignalCollector.collect(material.window, material.signals)
        ) {
            is RewardCollectionResult.Updated -> result.window
            is RewardCollectionResult.Duplicate -> result.window
            is RewardCollectionResult.Rejected -> return LearningJobHandlerResult.DeadLetter(
                LearningJobFailureCode.INVALID_JOB_SPEC,
            )
        }
        control.checkpoint()
        return when (
            val close = RewardSignalCollector.close(
                collected,
                material.frozenNowMs,
                forceCensored = material.censored,
            )
        ) {
            is RewardCollectionResult.Updated -> LearningJobHandlerResult.Success(
                RewardWindowJobOutput(close.window),
            )
            is RewardCollectionResult.Duplicate -> LearningJobHandlerResult.Success(
                RewardWindowJobOutput(close.window),
            )
            is RewardCollectionResult.Rejected -> LearningJobHandlerResult.Retry(
                LearningJobFailureCode.WAITING_CONFIGURATION,
                (collected.closeAfterMs - material.frozenNowMs).coerceIn(1_000L, RETRY_DELAY_MS),
            )
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 60L * 60L * 1_000L
    }
}

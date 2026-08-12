package me.rerere.rikkahub.learning.trace

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionControl
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionInputV1
import me.rerere.rikkahub.learning.jobs.LearningJobFailureCode
import me.rerere.rikkahub.learning.jobs.LearningJobHandler
import me.rerere.rikkahub.learning.jobs.LearningJobHandlerResult
import me.rerere.rikkahub.learning.jobs.LearningJobTypedOutput
import me.rerere.rikkahub.learning.jobs.P1LearningConfigurationUnavailableException

data class ExecutionTraceJobMaterial(
    val feature: TraceFeature,
    val sourceIntegritySha256: String,
) {
    init {
        require(sourceIntegritySha256.matches(Regex("[0-9a-f]{64}")))
    }
}

fun interface ExecutionTraceJobMaterialResolver {
    suspend fun resolve(input: LearningJobExecutionInputV1): ExecutionTraceJobMaterial?
}

data class ExecutionTraceJobOutput(
    val feature: TraceFeature,
    val sourceIntegritySha256: String,
) : LearningJobTypedOutput {
    override val outputSchemaIdentity: String = "no-canonical-output-v1"
}

class ExecutionTraceJobHandler(
    private val resolver: ExecutionTraceJobMaterialResolver,
) : LearningJobHandler<ExecutionTraceJobOutput> {
    override suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<ExecutionTraceJobOutput> {
        control.checkpoint()
        val material = try {
            resolver.resolve(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: P1LearningConfigurationUnavailableException) {
            return LearningJobHandlerResult.Retry(
                LearningJobFailureCode.WAITING_CONFIGURATION,
                RETRY_DELAY_MS,
            )
        } catch (_: Exception) {
            return LearningJobHandlerResult.Retry(
                LearningJobFailureCode.SOURCE_MISSING,
                RETRY_DELAY_MS,
            )
        } ?: return LearningJobHandlerResult.DeadLetter(LearningJobFailureCode.SOURCE_MISSING)
        control.checkpoint()
        return LearningJobHandlerResult.Success(
            ExecutionTraceJobOutput(material.feature, material.sourceIntegritySha256),
        )
    }

    private companion object {
        const val RETRY_DELAY_MS = 5L * 60L * 1_000L
    }
}

package me.rerere.rikkahub.learning.episode

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionControl
import me.rerere.rikkahub.learning.jobs.LearningJobExecutionInputV1
import me.rerere.rikkahub.learning.jobs.LearningJobFailureCode
import me.rerere.rikkahub.learning.jobs.LearningJobHandler
import me.rerere.rikkahub.learning.jobs.LearningJobHandlerResult
import me.rerere.rikkahub.learning.jobs.LearningJobTypedOutput
import me.rerere.rikkahub.learning.jobs.P1LearningConfigurationUnavailableException
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.learning.trace.TraceFeature

sealed interface EpisodeAssemblyMutation {
    /** Admission is retained in the inbox but does not create an Episode until an LLM boundary is proven. */
    data object ObserveAdmission : EpisodeAssemblyMutation

    data class Admit(
        val authority: EpisodeAuthorityAnchor,
        val taskSignature: TaskSignatureV1,
        val occurredAtMs: Long,
        val traceFeatures: List<TraceFeature> = emptyList(),
        val sourceIntegrityByRef: Map<LearningSourceRef, String> = emptyMap(),
    ) : EpisodeAssemblyMutation

    data class Complete(
        val current: EpisodeSnapshot?,
        val authority: EpisodeAuthorityAnchor,
        val taskSignature: TaskSignatureV1,
        val startedAtMs: Long,
        val completionKind: LearningCompletionKind,
        val terminalStateCode: String?,
        val occurredAtMs: Long,
        val traceFeatures: List<TraceFeature> = emptyList(),
        val sourceIntegrityByRef: Map<LearningSourceRef, String> = emptyMap(),
    ) : EpisodeAssemblyMutation
}

fun interface EpisodeAssemblyMaterialResolver {
    suspend fun resolve(input: LearningJobExecutionInputV1): EpisodeAssemblyMutation?
}

sealed interface EpisodeAssemblyJobOutput : LearningJobTypedOutput {
    override val outputSchemaIdentity: String
        get() = "no-canonical-output-v1"

    data object NoEpisode : EpisodeAssemblyJobOutput

    data class Snapshot(
        val snapshot: EpisodeSnapshot,
        val duplicate: Boolean,
        val traceFeatures: List<TraceFeature>,
        val sourceIntegrityByRef: Map<LearningSourceRef, String>,
    ) : EpisodeAssemblyJobOutput {
        init {
            require(traceFeatures.size <= 64)
            require(sourceIntegrityByRef.keys.all { it in traceFeatures.flatMap(TraceFeature::sources) })
            require(sourceIntegrityByRef.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        }

        override fun toString(): String =
            "EpisodeAssemblyJobOutput.Snapshot(status=${snapshot.status}, duplicate=$duplicate, " +
                "features=${traceFeatures.size}, ids=<redacted>)"
    }
}

class EpisodeAssemblyJobHandler(
    private val materialResolver: EpisodeAssemblyMaterialResolver,
) : LearningJobHandler<EpisodeAssemblyJobOutput> {
    override suspend fun execute(
        input: LearningJobExecutionInputV1,
        control: LearningJobExecutionControl,
    ): LearningJobHandlerResult<EpisodeAssemblyJobOutput> {
        control.checkpoint()
        val mutation = try {
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
        return when (mutation) {
            EpisodeAssemblyMutation.ObserveAdmission -> LearningJobHandlerResult.Success(
                EpisodeAssemblyJobOutput.NoEpisode,
            )
            is EpisodeAssemblyMutation.Admit -> LearningJobHandlerResult.Success(
                EpisodeAssemblyJobOutput.Snapshot(
                    snapshot = EpisodeAssembler.admit(
                        mutation.authority,
                        mutation.taskSignature,
                        mutation.occurredAtMs,
                    ),
                    duplicate = false,
                    traceFeatures = mutation.traceFeatures,
                    sourceIntegrityByRef = mutation.sourceIntegrityByRef,
                ),
            )
            is EpisodeAssemblyMutation.Complete -> {
                if (
                    mutation.current == null &&
                    mutation.completionKind in setOf(
                        LearningCompletionKind.FAST_PATH_HANDLED,
                        LearningCompletionKind.CONTROL_ONLY,
                        LearningCompletionKind.CENSORED_CANCELLED,
                        LearningCompletionKind.SUPERSEDED_REGENERATE,
                    )
                ) {
                    return LearningJobHandlerResult.Success(EpisodeAssemblyJobOutput.NoEpisode)
                }
                val current = mutation.current ?: EpisodeAssembler.admit(
                    mutation.authority,
                    mutation.taskSignature,
                    mutation.startedAtMs,
                )
                when (val result = EpisodeAssembler.apply(
                    current,
                    mutation.authority,
                    mutation.completionKind,
                    mutation.terminalStateCode,
                    mutation.occurredAtMs,
                )) {
                is EpisodeAssemblyResult.Applied -> LearningJobHandlerResult.Success(
                    EpisodeAssemblyJobOutput.Snapshot(
                        result.snapshot,
                        duplicate = false,
                        traceFeatures = mutation.traceFeatures,
                        sourceIntegrityByRef = mutation.sourceIntegrityByRef,
                    ),
                )
                is EpisodeAssemblyResult.Duplicate -> LearningJobHandlerResult.Success(
                    EpisodeAssemblyJobOutput.Snapshot(
                        result.snapshot,
                        duplicate = true,
                        traceFeatures = mutation.traceFeatures,
                        sourceIntegrityByRef = mutation.sourceIntegrityByRef,
                    ),
                )
                is EpisodeAssemblyResult.Rejected -> LearningJobHandlerResult.DeadLetter(
                    LearningJobFailureCode.INVALID_JOB_SPEC,
                )
            }
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 5L * 60L * 1_000L
    }
}

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

    /**
     * Admission is projected before the command kind is known. If that command later proves to be
     * a fast/control path, remove the still-open placeholder rather than retaining a false LLM
     * Episode. Storage performs an exact OPEN/revision CAS and rejects any row with derived use.
     */
    data class RemoveNonLlmOpenEpisode(
        val episodeId: String,
        val expectedRevision: Long,
    ) : EpisodeAssemblyJobOutput {
        init {
            require(episodeId.isNotBlank())
            require(expectedRevision > 0L)
        }

        override fun toString(): String =
            "EpisodeAssemblyJobOutput.RemoveNonLlmOpenEpisode(ids=<redacted>)"
    }

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
                if (mutation.completionKind in NON_LLM_COMPLETIONS) {
                    return LearningJobHandlerResult.Success(
                        mutation.current?.let {
                            EpisodeAssemblyJobOutput.RemoveNonLlmOpenEpisode(
                                episodeId = it.authority.episodeId.value,
                                expectedRevision = it.revision,
                            )
                        } ?: EpisodeAssemblyJobOutput.NoEpisode,
                    )
                }
                if (
                    mutation.current == null &&
                    mutation.completionKind in setOf(
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
        val NON_LLM_COMPLETIONS = setOf(
            LearningCompletionKind.FAST_PATH_HANDLED,
            LearningCompletionKind.CONTROL_ONLY,
        )
    }
}

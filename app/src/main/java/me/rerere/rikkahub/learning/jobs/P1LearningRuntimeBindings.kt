package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.episode.EpisodeAssemblyJobHandler
import me.rerere.rikkahub.learning.episode.EpisodeAssemblyMaterialResolver
import me.rerere.rikkahub.learning.episode.EpisodeAssemblyJobOutput
import me.rerere.rikkahub.learning.policy.PolicyCandidateJobOutput
import me.rerere.rikkahub.learning.policy.PolicyDistillationJobHandler
import me.rerere.rikkahub.learning.policy.PolicyDistillationMaterialResolver
import me.rerere.rikkahub.learning.reflection.EpisodeLessonJobOutput
import me.rerere.rikkahub.learning.reflection.ReflectionJobHandler
import me.rerere.rikkahub.learning.reflection.ReflectionJobMaterialResolver
import me.rerere.rikkahub.learning.reward.RewardJobHandler
import me.rerere.rikkahub.learning.reward.RewardJobMaterialResolver
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationClient
import me.rerere.rikkahub.learning.reward.RewardWindowJobOutput
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.trace.ExecutionTraceJobHandler
import me.rerere.rikkahub.learning.trace.ExecutionTraceJobMaterialResolver
import me.rerere.rikkahub.learning.trace.ExecutionTraceJobOutput

/** Optional production handlers. Missing configuration stays registered but is never claimed. */
internal data class P1LearningRuntimeHandlers(
    val episodeAssembly: EpisodeAssemblyJobHandler? = null,
    val executionTrace: ExecutionTraceJobHandler? = null,
    val reflection: ReflectionJobHandler? = null,
    val reward: RewardJobHandler? = null,
    val policyDistillation: PolicyDistillationJobHandler? = null,
    val sourceInvalidation: SourceInvalidationJobHandler? = null,
)

/** Production composition inputs; each resolver must validate authority, scope and revision. */
internal data class P1LearningRuntimeDependencies(
    val episodeAssemblyResolver: EpisodeAssemblyMaterialResolver? = null,
    val executionTraceResolver: ExecutionTraceJobMaterialResolver? = null,
    val reflectionResolver: ReflectionJobMaterialResolver? = null,
    val backgroundGenerationClient: BackgroundGenerationClient? = null,
    val rewardResolver: RewardJobMaterialResolver? = null,
    val policyDistillationResolver: PolicyDistillationMaterialResolver? = null,
    val sourceInvalidationResolver: SourceInvalidationJobMaterialResolver? = null,
    val sourceIntegrityResolver: LearningSourceIntegrityResolver =
        UnavailableLearningSourceIntegrityResolver,
    val derivedJobEnqueuer: P1DerivedJobEnqueuer = NoOpP1DerivedJobEnqueuer,
    val catchUp: P1DerivedJobCatchUp = NoOpP1DerivedJobCatchUp,
    val readiness: P1LearningRuntimeReadiness = P1LearningRuntimeReadiness(),
)

internal fun interface P1LearningRuntimeDependencyFactory {
    fun create(database: LearningDatabase): P1LearningRuntimeDependencies
}

internal object UnconfiguredP1LearningRuntimeDependencyFactory : P1LearningRuntimeDependencyFactory {
    override fun create(database: LearningDatabase): P1LearningRuntimeDependencies =
        P1LearningRuntimeDependencies()
}

internal data class P1LearningRuntimeReadiness(
    val episodeAssembly: LearningJobHandlerReadinessProbe = waitingProbe(),
    val executionTrace: LearningJobHandlerReadinessProbe = waitingProbe(),
    val reflection: LearningJobHandlerReadinessProbe = waitingProbe(),
    val reward: LearningJobHandlerReadinessProbe = waitingProbe(),
    val policyDistillation: LearningJobHandlerReadinessProbe = waitingProbe(),
    val sourceInvalidation: LearningJobHandlerReadinessProbe = waitingProbe(),
)

/**
 * The single P1 registry factory. Every P1 job type has exactly one handler/typed committer pair;
 * a missing Settings/source adapter yields WAITING_CONFIGURATION instead of an empty registry or
 * accidental claim. DataSourceModule only needs to supply configured domain handlers.
 */
internal object P1LearningRuntimeBindings {
    fun createRegistry(
        database: LearningDatabase,
        dependencies: P1LearningRuntimeDependencies,
        readiness: P1LearningRuntimeReadiness = dependencies.readiness,
    ): LearningJobHandlerRegistry = createRegistry(
        database = database,
        handlers = P1LearningRuntimeHandlers(
            episodeAssembly = dependencies.episodeAssemblyResolver?.let(::EpisodeAssemblyJobHandler),
            executionTrace = dependencies.executionTraceResolver?.let(::ExecutionTraceJobHandler),
            reflection = if (
                dependencies.reflectionResolver != null &&
                dependencies.backgroundGenerationClient != null
            ) {
                ReflectionJobHandler(
                    dependencies.reflectionResolver,
                    dependencies.backgroundGenerationClient,
                )
            } else {
                null
            },
            reward = dependencies.rewardResolver?.let(::RewardJobHandler),
            policyDistillation = if (
                dependencies.policyDistillationResolver != null &&
                dependencies.backgroundGenerationClient != null
            ) {
                PolicyDistillationJobHandler(
                    dependencies.policyDistillationResolver,
                    dependencies.backgroundGenerationClient,
                )
            } else null,
            sourceInvalidation = dependencies.sourceInvalidationResolver?.let(
                ::SourceInvalidationJobHandler,
            ),
        ),
        readiness = readiness,
        sourceIntegrityResolver = dependencies.sourceIntegrityResolver,
        derivedJobEnqueuer = dependencies.derivedJobEnqueuer,
    )

    fun createRegistry(
        database: LearningDatabase,
        handlers: P1LearningRuntimeHandlers = P1LearningRuntimeHandlers(),
        readiness: P1LearningRuntimeReadiness = P1LearningRuntimeReadiness(),
        sourceIntegrityResolver: LearningSourceIntegrityResolver =
            UnavailableLearningSourceIntegrityResolver,
        derivedJobEnqueuer: P1DerivedJobEnqueuer = NoOpP1DerivedJobEnqueuer,
    ): LearningJobHandlerRegistry {
        val sourceInvalidation = handlers.sourceInvalidation ?: SourceInvalidationJobHandler(
            RoomSourceInvalidationJobMaterialResolver(database, sourceIntegrityResolver),
        )
        return LearningJobHandlerRegistry.Builder()
            .register(
                LearningJobType.ASSEMBLE_EPISODE_SHADOW,
                handlers.episodeAssembly ?: waitingHandler<EpisodeAssemblyJobOutput>(),
                EpisodeAssemblyJobOutputCommitter(derivedJobEnqueuer),
                readiness = handlers.episodeAssembly?.let { readiness.episodeAssembly }
                    ?: waitingProbe(),
                heartbeatRequired = false,
            )
            .register(
                LearningJobType.RECONCILE_SOURCE,
                handlers.executionTrace ?: waitingHandler<ExecutionTraceJobOutput>(),
                ExecutionTraceJobOutputCommitter(derivedJobEnqueuer),
                readiness = handlers.executionTrace?.let { readiness.executionTrace }
                    ?: waitingProbe(),
                heartbeatRequired = false,
            )
            .register(
                LearningJobType.REFLECT_EPISODE_V1,
                handlers.reflection ?: waitingHandler<EpisodeLessonJobOutput>(),
                EpisodeLessonJobOutputCommitter(derivedJobEnqueuer),
                readiness = handlers.reflection?.let { readiness.reflection } ?: waitingProbe(),
            )
            .register(
                LearningJobType.CLOSE_REWARD_WINDOW_V1,
                handlers.reward ?: waitingHandler<RewardWindowJobOutput>(),
                RewardWindowJobOutputCommitter(derivedJobEnqueuer),
                readiness = handlers.reward?.let { readiness.reward } ?: waitingProbe(),
                heartbeatRequired = false,
            )
            .register(
                LearningJobType.DISTILL_POLICY_V1,
                handlers.policyDistillation ?: waitingHandler<PolicyCandidateJobOutput>(),
                PolicyCandidateJobOutputCommitter,
                readiness = handlers.policyDistillation?.let { readiness.policyDistillation }
                    ?: waitingProbe(),
            )
            .register(
                LearningJobType.INVALIDATE_SOURCE_V1,
                sourceInvalidation,
                SourceValidityOutputCommitter,
                readiness = readiness.sourceInvalidation,
                heartbeatRequired = false,
            )
            .build()
    }
}

private fun waitingProbe(): LearningJobHandlerReadinessProbe =
    LearningJobHandlerReadinessProbe { LearningJobHandlerReadiness.WAITING_CONFIGURATION }

private fun <O : LearningJobTypedOutput> waitingHandler(): LearningJobHandler<O> =
    LearningJobHandler { _, _ ->
        LearningJobHandlerResult.Retry(
            LearningJobFailureCode.WAITING_CONFIGURATION,
            P1_CONFIGURATION_RETRY_DELAY_MS,
        )
    }

private const val P1_CONFIGURATION_RETRY_DELAY_MS = 6L * 60L * 60L * 1_000L

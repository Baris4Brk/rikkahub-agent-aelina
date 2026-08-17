package me.rerere.rikkahub.learning.model

/**
 * Fail-closed rollout switches. This immutable contract is not a persistence implementation.
 * Production settings wiring is a later vertical slice and must preserve these defaults.
 */
data class LearningFeatureFlags(
    val schemaReady: Boolean = false,
    val handoff: Boolean = false,
    val capture: Boolean = false,
    val jobs: Boolean = false,
    val reflectionShadow: Boolean = false,
    val policyCandidate: Boolean = false,
    val policyRetrievalShadow: Boolean = false,
    val policyInjection: Boolean = false,
    val workflowCandidate: Boolean = false,
    val workflowPromotion: Boolean = false,
    /** P5-001 operation switches are deliberately independent and default off. */
    val curatorUpdate: Boolean = false,
    val curatorMerge: Boolean = false,
    val curatorSplit: Boolean = false,
    val curatorSupersede: Boolean = false,
    val vector: Boolean = false,
    val temporalOperational: Boolean = false,
    val allowRemoteReflection: Boolean = false,
) {
    val hasBusinessWritesEnabled: Boolean
        get() = handoff || capture || jobs || reflectionShadow || policyCandidate ||
            policyRetrievalShadow || policyInjection || workflowCandidate || workflowPromotion ||
            hasCuratorMutationEnabled || vector || temporalOperational

    val hasProviderEffectEnabled: Boolean
        get() = reflectionShadow || policyCandidate || policyInjection || workflowPromotion

    val hasCuratorMutationEnabled: Boolean
        get() = curatorUpdate || curatorMerge || curatorSplit || curatorSupersede
}

enum class LearningFlagDependencyError {
    SCHEMA_REQUIRED,
    HANDOFF_REQUIRED,
    JOBS_REQUIRED,
    JOB_HANDLER_REQUIRED,
    CAPTURE_REQUIRED,
    POLICY_CANDIDATE_REQUIRED,
    POLICY_SHADOW_REQUIRED,
    POLICY_INJECTION_RUNTIME_REQUIRED,
    POLICY_INJECTION_REQUIRED,
    WORKFLOW_CANDIDATE_REQUIRED,
    WORKFLOW_PROMOTION_RUNTIME_REQUIRED,
    CURATOR_RUNTIME_REQUIRED,
}

/** Schema/code capability gates are distinct from user rollout preferences. */
data class LearningFeatureCapabilities(
    val schemaReady: Boolean = false,
    val typedJobExecutionReady: Boolean = false,
    /** Review UI + durable grant/rebind + cache/exposure/outcome runtime are installed. */
    val reviewedPolicyInjectionReady: Boolean = false,
    /** Isolated candidate compiler, fake verifier and review runtime are installed. */
    val workflowCandidateReady: Boolean = false,
    /** Disabled promotion saga and execution-time authority validator are installed. */
    val workflowPromotionReady: Boolean = false,
    /** Curator v1 deterministic operation/review/apply/rollback runtime is installed. */
    val curatorV1Ready: Boolean = false,
)

data class ResolvedLearningFeatureFlags(
    val configured: LearningFeatureFlags,
    /** All false when [errors] is non-empty. Callers must consume this projection only. */
    val effective: LearningFeatureFlags,
    val errors: Set<LearningFlagDependencyError>,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Runtime-facing seam; persistence adapters must return the fail-closed resolved projection. */
fun interface LearningFeatureFlagSource {
    fun current(): ResolvedLearningFeatureFlags
}

/** Production default until Settings wiring and the corresponding phase gate are complete. */
object DisabledLearningFeatureFlagSource : LearningFeatureFlagSource {
    private val disabled = LearningFeatureFlagPolicy.resolve(LearningFeatureFlags())

    override fun current(): ResolvedLearningFeatureFlags = disabled
}

/** Prevents a persisted or imported flag combination from bypassing an earlier rollout gate. */
object LearningFeatureFlagPolicy {
    fun resolve(
        configured: LearningFeatureFlags,
        capabilities: LearningFeatureCapabilities = LearningFeatureCapabilities(),
    ): ResolvedLearningFeatureFlags {
        val errors = buildSet {
            if (configured.schemaReady && !capabilities.schemaReady) {
                add(LearningFlagDependencyError.SCHEMA_REQUIRED)
            }
            val anySchemaDependent = configured.hasBusinessWritesEnabled ||
                configured.policyRetrievalShadow || configured.vector || configured.temporalOperational
            if (anySchemaDependent && !configured.schemaReady) {
                add(LearningFlagDependencyError.SCHEMA_REQUIRED)
            }
            if ((configured.capture || configured.jobs || configured.policyCandidate ||
                    configured.policyRetrievalShadow || configured.policyInjection ||
                    configured.workflowCandidate || configured.workflowPromotion ||
                    configured.hasCuratorMutationEnabled ||
                    configured.vector || configured.temporalOperational) && !configured.handoff
            ) {
                add(LearningFlagDependencyError.HANDOFF_REQUIRED)
            }
            if ((configured.reflectionShadow || configured.policyCandidate ||
                    configured.policyRetrievalShadow || configured.policyInjection ||
                    configured.workflowCandidate || configured.workflowPromotion ||
                    configured.hasCuratorMutationEnabled ||
                    configured.vector || configured.temporalOperational) && !configured.jobs
            ) {
                add(LearningFlagDependencyError.JOBS_REQUIRED)
            }
            if (configured.jobs && !capabilities.typedJobExecutionReady) {
                add(LearningFlagDependencyError.JOB_HANDLER_REQUIRED)
            }
            if ((configured.reflectionShadow || configured.policyCandidate) && !configured.capture) {
                add(LearningFlagDependencyError.CAPTURE_REQUIRED)
            }
            if ((configured.policyRetrievalShadow || configured.policyInjection ||
                    configured.workflowCandidate || configured.workflowPromotion ||
                    configured.hasCuratorMutationEnabled || configured.vector) &&
                    !configured.policyCandidate
            ) {
                add(LearningFlagDependencyError.POLICY_CANDIDATE_REQUIRED)
            }
            if ((configured.policyInjection || configured.vector) && !configured.policyRetrievalShadow) {
                add(LearningFlagDependencyError.POLICY_SHADOW_REQUIRED)
            }
            if (configured.policyInjection && !capabilities.reviewedPolicyInjectionReady) {
                add(LearningFlagDependencyError.POLICY_INJECTION_RUNTIME_REQUIRED)
            }
            if (configured.workflowCandidate && !capabilities.workflowCandidateReady) {
                add(LearningFlagDependencyError.WORKFLOW_CANDIDATE_REQUIRED)
            }
            if (configured.workflowPromotion && !capabilities.workflowPromotionReady) {
                add(LearningFlagDependencyError.WORKFLOW_PROMOTION_RUNTIME_REQUIRED)
            }
            if (configured.hasCuratorMutationEnabled && !capabilities.curatorV1Ready) {
                add(LearningFlagDependencyError.CURATOR_RUNTIME_REQUIRED)
            }
            if ((configured.workflowCandidate || configured.workflowPromotion ||
                    configured.hasCuratorMutationEnabled) &&
                !configured.policyInjection
            ) {
                add(LearningFlagDependencyError.POLICY_INJECTION_REQUIRED)
            }
            if (configured.workflowPromotion && !configured.workflowCandidate) {
                add(LearningFlagDependencyError.WORKFLOW_CANDIDATE_REQUIRED)
            }
        }
        return ResolvedLearningFeatureFlags(
            configured = configured,
            effective = if (errors.isEmpty()) configured else LearningFeatureFlags(),
            errors = errors,
        )
    }
}

package me.rerere.rikkahub.learning.model

import kotlinx.serialization.Serializable

private val LOWER_SHA256 = Regex("[0-9a-f]{64}")

/** Persisted Learning rollout/consent root. Portable backup always resets this device-local consent. */
@Serializable
data class LearningPreferencesV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val handoff: Boolean = false,
    val capture: Boolean = false,
    val jobs: Boolean = false,
    val reflectionShadow: Boolean = false,
    val policyCandidate: Boolean = false,
    val policyRetrievalShadow: Boolean = false,
    /** Stage E: exact reviewed Policies may be injected only through grant + exposure gates. */
    val policyInjection: Boolean = false,
    /** P4: reviewed Policies may be compiled into isolated, non-executable Workflow candidates. */
    val workflowCandidate: Boolean = false,
    /** P4: verified candidates may enter the disabled promotion saga and execute once enabled. */
    val workflowPromotion: Boolean = false,
    /** P5-001: each Curator v1 mutation is an independent device-local opt-in. */
    val curatorUpdate: Boolean = false,
    val curatorMerge: Boolean = false,
    val curatorSplit: Boolean = false,
    val curatorSupersede: Boolean = false,
    val backgroundWorkAuthorized: Boolean = false,
    val authorizedModelIdentityDigests: Set<String> = emptySet(),
    val allowMeteredNetwork: Boolean = false,
    val allowRemoteReflection: Boolean = false,
    val remoteReflectionProviderIdentityDigest: String? = null,
    val remoteReflectionModelIdentityDigest: String? = null,
    val retention: LearningRetentionPreferencesV1 = LearningRetentionPreferencesV1(),
) {
    fun failClosed(): LearningPreferencesV1 = takeIf {
        schemaVersion == CURRENT_SCHEMA_VERSION &&
            authorizedModelIdentityDigests.size <= MAX_AUTHORIZED_MODELS &&
            authorizedModelIdentityDigests.all(LOWER_SHA256::matches) &&
            hasValidExactRemoteConsent() &&
            (!reflectionShadow || capture && jobs && backgroundWorkAuthorized) &&
            (!policyCandidate || reflectionShadow) &&
            (!policyRetrievalShadow || policyCandidate) &&
            (!policyInjection || policyRetrievalShadow && policyCandidate && jobs && handoff) &&
            (!workflowCandidate || policyInjection) &&
            (!workflowPromotion || workflowCandidate && policyInjection) &&
            (!(curatorUpdate || curatorMerge || curatorSplit || curatorSupersede) ||
                policyInjection) &&
            (!backgroundWorkAuthorized || authorizedModelIdentityDigests.size == 1) &&
            retention.isValid()
    } ?: LearningPreferencesV1()

    private fun hasValidExactRemoteConsent(): Boolean = if (!allowRemoteReflection) {
        remoteReflectionProviderIdentityDigest == null &&
            remoteReflectionModelIdentityDigest == null
    } else {
        backgroundWorkAuthorized &&
            remoteReflectionProviderIdentityDigest?.let(LOWER_SHA256::matches) == true &&
            remoteReflectionModelIdentityDigest?.let(LOWER_SHA256::matches) == true &&
            authorizedModelIdentityDigests == setOf(remoteReflectionModelIdentityDigest)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        private const val MAX_AUTHORIZED_MODELS = 1
    }
}

/** Canonical user-facing rollout states. Stage E remains device-local, explicit and reversible. */
enum class LearningRolloutStage {
    OFF,
    CAPTURE,
    CANDIDATE_SHADOW,
    RETRIEVAL_SHADOW,
    REVIEWED_POLICY_OPT_IN,
}

/** Stable Settings/API identity; deliberately does not depend on the Curator implementation. */
enum class LearningCuratorOperation {
    UPDATE,
    MERGE,
    SPLIT,
    SUPERSEDE,
}

object LearningRolloutPolicy {
    fun stageOf(preferences: LearningPreferencesV1): LearningRolloutStage? {
        val exact = preferences.takeIf { it.failClosed() == it } ?: return null
        return when {
            exact.copy(retention = LearningRetentionPreferencesV1()) == LearningPreferencesV1() ->
                LearningRolloutStage.OFF
            exact.matchesStage(
                handoff = true,
                capture = true,
                jobs = true,
                reflection = false,
                candidate = false,
                retrieval = false,
                background = false,
                modelCount = 0,
            ) -> LearningRolloutStage.CAPTURE
            exact.matchesStage(
                handoff = true,
                capture = true,
                jobs = true,
                reflection = true,
                candidate = true,
                retrieval = false,
                background = true,
                modelCount = 1,
            ) ->
                LearningRolloutStage.CANDIDATE_SHADOW
            exact.matchesStage(
                handoff = true,
                capture = true,
                jobs = true,
                reflection = true,
                candidate = true,
                retrieval = true,
                background = true,
                modelCount = 1,
            ) ->
                LearningRolloutStage.RETRIEVAL_SHADOW
            exact.matchesStage(
                handoff = true,
                capture = true,
                jobs = true,
                reflection = true,
                candidate = true,
                retrieval = true,
                injection = true,
                background = true,
                modelCount = 1,
            ) -> LearningRolloutStage.REVIEWED_POLICY_OPT_IN
            else -> null
        }
    }

    fun configure(
        current: LearningPreferencesV1,
        stage: LearningRolloutStage,
        exactModelIdentityDigest: String? = null,
        exactRemoteProviderIdentityDigest: String? = null,
        authorizeRemoteReflection: Boolean = false,
    ): LearningPreferencesV1 {
        val model = exactModelIdentityDigest?.takeIf(LOWER_SHA256::matches)
        val remoteProvider = exactRemoteProviderIdentityDigest?.takeIf(LOWER_SHA256::matches)
        require(!authorizeRemoteReflection || remoteProvider != null) {
            "Remote Reflection requires the exact disclosed provider"
        }
        require(authorizeRemoteReflection || remoteProvider == null) {
            "A remote provider target cannot be persisted without remote consent"
        }
        val preserveLaterOptIns = stage == LearningRolloutStage.REVIEWED_POLICY_OPT_IN &&
            stageOf(current) == LearningRolloutStage.REVIEWED_POLICY_OPT_IN
        return when (stage) {
            LearningRolloutStage.OFF -> LearningPreferencesV1(
                retention = current.retention.failClosed(),
            )
            LearningRolloutStage.CAPTURE -> LearningPreferencesV1(
                handoff = true,
                capture = true,
                jobs = true,
                allowMeteredNetwork = current.allowMeteredNetwork,
                retention = current.retention.failClosed(),
            )
            LearningRolloutStage.CANDIDATE_SHADOW,
            LearningRolloutStage.RETRIEVAL_SHADOW,
            LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
            -> LearningPreferencesV1(
                handoff = true,
                capture = true,
                jobs = true,
                reflectionShadow = true,
                policyCandidate = true,
                policyRetrievalShadow = stage in setOf(
                    LearningRolloutStage.RETRIEVAL_SHADOW,
                    LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
                ),
                policyInjection = stage == LearningRolloutStage.REVIEWED_POLICY_OPT_IN,
                // Moving between P0-P2 stages is also the emergency P4 rollback. Re-selecting
                // the already-active Stage E chip is a no-op and preserves independent later-stage
                // switches rather than unexpectedly revoking an explicit user choice.
                workflowCandidate = current.workflowCandidate && preserveLaterOptIns,
                workflowPromotion = current.workflowPromotion && preserveLaterOptIns,
                curatorUpdate = current.curatorUpdate && preserveLaterOptIns,
                curatorMerge = current.curatorMerge && preserveLaterOptIns,
                curatorSplit = current.curatorSplit && preserveLaterOptIns,
                curatorSupersede = current.curatorSupersede && preserveLaterOptIns,
                backgroundWorkAuthorized = true,
                authorizedModelIdentityDigests = setOf(
                    requireNotNull(model) { "An exact model is required for provider-backed P1" },
                ),
                allowMeteredNetwork = current.allowMeteredNetwork,
                allowRemoteReflection = authorizeRemoteReflection,
                remoteReflectionProviderIdentityDigest = remoteProvider
                    .takeIf { authorizeRemoteReflection },
                remoteReflectionModelIdentityDigest = model.takeIf { authorizeRemoteReflection },
                retention = current.retention.failClosed(),
            )
        }.also { configured ->
            check(configured.failClosed() == configured) { "Invalid Learning rollout state" }
        }
    }

    /** Compatibility writer for withdrawing or re-granting the same sole-model consent pair. */
    fun configureRemoteReflection(
        current: LearningPreferencesV1,
        allowed: Boolean,
        exactProviderIdentityDigest: String? = null,
        exactModelIdentityDigest: String? = null,
    ): LearningPreferencesV1 {
        val safe = current.failClosed()
        require(!allowed || safe.backgroundWorkAuthorized && safe.reflectionShadow) {
            "Remote Reflection requires an already-authorized provider-backed shadow stage"
        }
        val provider = exactProviderIdentityDigest?.takeIf(LOWER_SHA256::matches)
        val model = exactModelIdentityDigest?.takeIf(LOWER_SHA256::matches)
        require(!allowed || provider != null && model != null) {
            "Remote Reflection requires the exact disclosed provider/model target"
        }
        require(!allowed || safe.authorizedModelIdentityDigests == setOf(model)) {
            "Remote Reflection target must be the sole authorized background model"
        }
        return safe.copy(
            allowRemoteReflection = allowed,
            remoteReflectionProviderIdentityDigest = provider.takeIf { allowed },
            remoteReflectionModelIdentityDigest = model.takeIf { allowed },
        ).also { configured ->
            check(configured.failClosed() == configured) { "Invalid remote Reflection consent" }
        }
    }

    fun configureRetention(
        current: LearningPreferencesV1,
        retention: LearningRetentionPreferencesV1,
    ): LearningPreferencesV1 = current.failClosed().copy(
        retention = retention.failClosed(),
    ).also { configured ->
        check(configured.failClosed() == configured) { "Invalid Learning retention preferences" }
    }

    /**
     * Independent P4 kill switches. Candidate generation and executable promotion are never
     * inferred from Stage E; promotion is a strictly later opt-in and depends on candidates.
     */
    fun configureWorkflowCandidate(
        current: LearningPreferencesV1,
        enabled: Boolean,
    ): LearningPreferencesV1 {
        val safe = current.failClosed()
        require(!enabled || safe.policyInjection) {
            "Workflow candidates require reviewed Policy injection opt-in"
        }
        return safe.copy(
            workflowCandidate = enabled,
            workflowPromotion = safe.workflowPromotion && enabled,
        ).also { configured ->
            check(configured.failClosed() == configured) { "Invalid Workflow candidate consent" }
        }
    }

    fun configureWorkflowPromotion(
        current: LearningPreferencesV1,
        enabled: Boolean,
    ): LearningPreferencesV1 {
        val safe = current.failClosed()
        require(!enabled || safe.policyInjection && safe.workflowCandidate) {
            "Workflow promotion requires candidate and reviewed Policy opt-in"
        }
        return safe.copy(workflowPromotion = enabled).also { configured ->
            check(configured.failClosed() == configured) { "Invalid Workflow promotion consent" }
        }
    }

    /**
     * Enables exactly one reviewed Curator operation. No operation is implied by P2/P5 schema
     * presence, and rolling back below Stage E clears every operation bit in [configure].
     */
    fun configureCuratorOperation(
        current: LearningPreferencesV1,
        operation: LearningCuratorOperation,
        enabled: Boolean,
    ): LearningPreferencesV1 {
        val safe = current.failClosed()
        require(!enabled || safe.policyInjection) {
            "Curator operations require reviewed Policy injection opt-in"
        }
        return when (operation) {
            LearningCuratorOperation.UPDATE -> safe.copy(curatorUpdate = enabled)
            LearningCuratorOperation.MERGE -> safe.copy(curatorMerge = enabled)
            LearningCuratorOperation.SPLIT -> safe.copy(curatorSplit = enabled)
            LearningCuratorOperation.SUPERSEDE -> safe.copy(curatorSupersede = enabled)
        }.also { configured ->
            check(configured.failClosed() == configured) { "Invalid Curator operation consent" }
        }
    }

    private fun LearningPreferencesV1.matchesStage(
        handoff: Boolean,
        capture: Boolean,
        jobs: Boolean,
        reflection: Boolean,
        candidate: Boolean,
        retrieval: Boolean,
        injection: Boolean = false,
        background: Boolean,
        modelCount: Int,
    ): Boolean = this.handoff == handoff && this.capture == capture && this.jobs == jobs &&
        reflectionShadow == reflection && policyCandidate == candidate &&
        policyRetrievalShadow == retrieval && backgroundWorkAuthorized == background &&
        policyInjection == injection &&
        // Remote Reflection is an independent consent layered on top of the
        // rollout stage; enabling it must not make the persisted stage
        // unclassifiable in the UI or scheduler.
        authorizedModelIdentityDigests.size == modelCount
}

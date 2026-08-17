package me.rerere.rikkahub.learning.eval

/** Frozen rollout criteria. Changes require a new manifest/contract version. */
enum class ObservedAssociationDecisionRule {
    /** The lower confidence bound itself must show a positive matched-cohort association. */
    CONFIDENT_POSITIVE_GAIN,

    /** The lower confidence bound may not cross the frozen negative margin. */
    NON_INFERIORITY,
}

data class ProductionEvalRolloutCriteria(
    val criteriaVersion: String,
    val minimumObservedTaskOutcomesPerArm: Int,
    val minimumObservedPolicyOutcomesPerReviewedArm: Int,
    val minimumPairedOutcomesPerComparison: Int,
    val minimumFullRuntimeSuccessBasisPoints: Int,
    val requiredAssociationComparisonArm: OfflineEvalArm,
    val associationDecisionRule: ObservedAssociationDecisionRule,
    val minimumAssociationLowerBoundBasisPoints: Int,
    val maximumNonInferiorityMarginBasisPoints: Int,
    val maximumHarmfulOutcomeCount: Int,
    val maximumScopeLeakCount: Int,
    val maximumStaleHitCount: Int,
) {
    init {
        requireSafeEvalLabel(criteriaVersion)
        require(minimumObservedTaskOutcomesPerArm > 0)
        require(minimumObservedPolicyOutcomesPerReviewedArm > 0)
        require(minimumPairedOutcomesPerComparison > 0)
        require(minimumFullRuntimeSuccessBasisPoints in 0..10_000)
        require(requiredAssociationComparisonArm != OfflineEvalArm.A_NO_LEARNING)
        require(minimumAssociationLowerBoundBasisPoints in 1..10_000)
        require(maximumNonInferiorityMarginBasisPoints in 0..10_000)
        require(maximumHarmfulOutcomeCount >= 0)
        require(maximumScopeLeakCount >= 0 && maximumStaleHitCount >= 0)
    }
}

object FrozenProductionEvalContractV1 {
    const val MANIFEST_ID: String = "p5-production-component-eval-manifest-v1"
    const val RUNNER_VERSION: String = "production-four-arm-component-runner-v1"

    val rolloutCriteria = ProductionEvalRolloutCriteria(
        criteriaVersion = "production-rollout-criteria-v2",
        minimumObservedTaskOutcomesPerArm = 12,
        minimumObservedPolicyOutcomesPerReviewedArm = 12,
        minimumPairedOutcomesPerComparison = 12,
        minimumFullRuntimeSuccessBasisPoints = 8_000,
        requiredAssociationComparisonArm = OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS,
        associationDecisionRule = ObservedAssociationDecisionRule.CONFIDENT_POSITIVE_GAIN,
        minimumAssociationLowerBoundBasisPoints = 1,
        maximumNonInferiorityMarginBasisPoints = 500,
        maximumHarmfulOutcomeCount = 0,
        maximumScopeLeakCount = 0,
        maximumStaleHitCount = 0,
    )

    /** Draft relative ceilings; independent matched-environment evidence is still outstanding. */
    val performanceThresholds = PerformanceTrendThresholds(
        baselineId = "p5-production-components-baseline-v1",
        status = PerformanceThresholdStatus.DRAFT,
        maxOperationRatioBasisPoints = 11_000,
        maxAllocationRatioBasisPoints = 11_500,
    )
}

data class FrozenProductionEvalManifest(
    val schemaVersion: Int,
    val manifestId: String,
    val runnerVersion: String,
    val corpusId: String,
    val corpusDigestSha256: String,
    val planId: String,
    val planDigestSha256: String,
    val assignmentManifestSha256: String,
    val adapterIdentities: List<ProductionComponentAdapterIdentity>,
    val rolloutCriteria: ProductionEvalRolloutCriteria,
    val performanceThresholds: PerformanceTrendThresholds,
    val digestSha256: String,
) {
    init {
        require(schemaVersion == 1)
        listOf(manifestId, runnerVersion, corpusId, planId).forEach(::requireSafeEvalLabel)
        listOf(
            corpusDigestSha256,
            planDigestSha256,
            assignmentManifestSha256,
            digestSha256,
        ).forEach { require(it.isEvalSha256()) }
        require(
            adapterIdentities.map { it.component } == ProductionReplayComponent.entries.toList(),
        )
        require(performanceThresholds.status in PerformanceThresholdStatus.entries)
        require(digestSha256 == computeManifestDigest(
            manifestId,
            runnerVersion,
            corpusId,
            corpusDigestSha256,
            planId,
            planDigestSha256,
            assignmentManifestSha256,
            adapterIdentities,
            rolloutCriteria,
            performanceThresholds,
        ))
    }

    override fun toString(): String =
        "FrozenProductionEvalManifest(adapters=${adapterIdentities.size}, ids=<redacted>)"

    companion object {
        fun freeze(
            adapters: ProductionComponentReplayAdapters,
            corpus: List<OfflineReplayUnit> = FrozenReplayCorpusV1.units,
            plan: OfflineEvalPlan = FrozenOfflineLearningEvaluation.plan,
            performanceThresholds: PerformanceTrendThresholds =
                FrozenProductionEvalContractV1.performanceThresholds,
        ): FrozenProductionEvalManifest {
            require(corpus == FrozenReplayCorpusV1.units) {
                "Production gate requires the exact frozen corpus ordering and identity"
            }
            require(plan == FrozenOfflineLearningEvaluation.plan) {
                "Production gate requires the exact frozen plan"
            }
            require(
                performanceThresholds.baselineId ==
                    FrozenProductionEvalContractV1.performanceThresholds.baselineId,
            )
            if (performanceThresholds.status == PerformanceThresholdStatus.FROZEN) {
                require(
                    performanceThresholds.maxOperationRatioBasisPoints ==
                        FrozenProductionEvalContractV1.performanceThresholds
                            .maxOperationRatioBasisPoints,
                )
                require(
                    performanceThresholds.maxAllocationRatioBasisPoints ==
                        FrozenProductionEvalContractV1.performanceThresholds
                            .maxAllocationRatioBasisPoints,
                )
            }
            val assignments = PreRegisteredAssignmentEngine.assign(corpus, plan)
            val adapterIdentities = adapters.identities
            val assignmentDigest = PreRegisteredAssignmentEngine.manifestDigest(assignments)
            val digest = computeManifestDigest(
                FrozenProductionEvalContractV1.MANIFEST_ID,
                FrozenProductionEvalContractV1.RUNNER_VERSION,
                FrozenReplayCorpusV1.CORPUS_ID,
                FrozenReplayCorpusV1.digestSha256,
                plan.planId,
                plan.digestSha256(),
                assignmentDigest,
                adapterIdentities,
                FrozenProductionEvalContractV1.rolloutCriteria,
                performanceThresholds,
            )
            return FrozenProductionEvalManifest(
                schemaVersion = 1,
                manifestId = FrozenProductionEvalContractV1.MANIFEST_ID,
                runnerVersion = FrozenProductionEvalContractV1.RUNNER_VERSION,
                corpusId = FrozenReplayCorpusV1.CORPUS_ID,
                corpusDigestSha256 = FrozenReplayCorpusV1.digestSha256,
                planId = plan.planId,
                planDigestSha256 = plan.digestSha256(),
                assignmentManifestSha256 = assignmentDigest,
                adapterIdentities = adapterIdentities,
                rolloutCriteria = FrozenProductionEvalContractV1.rolloutCriteria,
                performanceThresholds = performanceThresholds,
                digestSha256 = digest,
            )
        }
    }
}

data class ProductionEvalPerformanceBaseline(
    val manifestDigestSha256: String,
    val baseline: PerformanceBaseline,
) {
    init {
        require(manifestDigestSha256.isEvalSha256())
        require(baseline.baselineId == FrozenProductionEvalContractV1.performanceThresholds.baselineId)
    }
}

enum class ProductionRolloutDecisionState {
    APPROVE,
    REJECT,
    ABSTAIN,
}

enum class ProductionRolloutDecisionReason {
    FROZEN_GATES_PASSED,
    HARD_SAFETY_FAILURE,
    ROOM_INTEGRATION_HARD_FAILURE,
    FOUR_ARM_RUNTIME_HARD_FAILURE,
    ROOM_INTEGRATION_NOT_OBSERVED,
    DURABLE_FOUR_ARM_RUNTIME_NOT_OBSERVED,
    FULL_RUNTIME_SUCCESS_BELOW_THRESHOLD,
    ASSOCIATION_CRITERION_NOT_MET,
    PERFORMANCE_REGRESSION,
    PRODUCTION_COMPONENT_ABSTAINED,
    SAMPLE_SIZE_INSUFFICIENT,
    PERFORMANCE_NOT_ENFORCED,
}

data class ProductionRolloutDecision(
    val state: ProductionRolloutDecisionState,
    val reason: ProductionRolloutDecisionReason,
    val minimumObservedTaskOutcomes: Int,
    val minimumObservedPolicyOutcomes: Int,
    val minimumPairedOutcomes: Int,
) {
    init {
        require(minimumObservedTaskOutcomes >= 0)
        require(minimumObservedPolicyOutcomes >= 0 && minimumPairedOutcomes >= 0)
        require(
            (state == ProductionRolloutDecisionState.APPROVE) ==
                (reason == ProductionRolloutDecisionReason.FROZEN_GATES_PASSED),
        )
    }
}

data class RedactedProductionEvalArtifact internal constructor(
    val schemaVersion: Int,
    val manifestDigestSha256: String,
    val reportDigestSha256: String,
    val redactedReportSha256: String,
    val artifactDigestSha256: String,
    val roomIntegrationDigestSha256: String?,
    val fourArmRuntimeDigestSha256: String?,
    val decisionState: ProductionRolloutDecisionState,
    val decisionReason: ProductionRolloutDecisionReason,
    val redactedReport: String,
) {
    init {
        require(schemaVersion == 3)
        listOf(
            manifestDigestSha256,
            reportDigestSha256,
            redactedReportSha256,
            artifactDigestSha256,
        ).forEach { require(it.isEvalSha256()) }
        require(redactedReport.length in 1..RedactedEvalReportRenderer.MAX_REPORT_CHARS)
        roomIntegrationDigestSha256?.let { require(it.isEvalSha256()) }
        fourArmRuntimeDigestSha256?.let { require(it.isEvalSha256()) }
        require(redactedReportSha256 == EvalDigest.sha256(
            "production-eval-redacted-report-v3",
            listOf(redactedReport),
        ))
        require(artifactDigestSha256 == EvalDigest.sha256(
            "production-eval-redacted-artifact-v3",
            listOf(
                manifestDigestSha256,
                reportDigestSha256,
                redactedReportSha256,
                roomIntegrationDigestSha256 ?: "UNOBSERVED",
                fourArmRuntimeDigestSha256 ?: "UNOBSERVED",
                decisionState.name,
                decisionReason.name,
            ),
        ))
    }

    override fun toString(): String =
        "RedactedProductionEvalArtifact(state=$decisionState, report=<redacted>, ids=<redacted>)"
}

data class ProductionEvaluationGateResult(
    val manifest: FrozenProductionEvalManifest,
    val report: OfflineEvalReport,
    val reportDigestSha256: String,
    val componentCoverage: List<ProductionComponentCoverageSummary>,
    val runnerPerformance: PerformanceCounterSnapshot,
    val currentEnvironmentDigestSha256: String?,
    val roomIntegration: ProductionRoomIntegrationAttestation?,
    val fourArmRuntime: ProductionFourArmRuntimeAttestation?,
    val performance: PerformanceGateResult,
    val rollout: ProductionRolloutDecision,
    val artifact: RedactedProductionEvalArtifact,
) {
    init {
        require(reportDigestSha256 == report.digestSha256())
        require(artifact.manifestDigestSha256 == manifest.digestSha256)
        require(artifact.reportDigestSha256 == reportDigestSha256)
        require(artifact.decisionState == rollout.state)
        require(artifact.decisionReason == rollout.reason)
        require(artifact.roomIntegrationDigestSha256 ==
            roomIntegration?.attestationDigestSha256)
        require(artifact.fourArmRuntimeDigestSha256 ==
            fourArmRuntime?.attestationDigestSha256)
        require(report.energy == EnergyAssessment.offlineJvm())
        require(componentCoverage.map { it.component } == ProductionReplayComponent.entries.toList())
        require(
            currentEnvironmentDigestSha256 == null ||
                currentEnvironmentDigestSha256.isEvalSha256(),
        )
    }
}

/**
 * CI-callable component regression entry. It can approve only when the caller also supplies exact
 * Room and durable four-arm runtime attestations bound to the report produced by these adapters.
 */
object ProductionLearningEvaluationCiEntry {
    suspend fun evaluate(
        adapters: ProductionComponentReplayAdapters =
            ProductionComponentReplayAdapters.unconfigured(),
        baseline: ProductionEvalPerformanceBaseline? = null,
        currentEnvironmentDigestSha256: String? = null,
        roomIntegration: ProductionRoomIntegrationAttestation? = null,
        fourArmRuntime: ProductionFourArmRuntimeAttestation? = null,
    ): ProductionEvaluationGateResult = evaluateBoundThresholds(
        adapters = adapters,
        baseline = baseline,
        currentEnvironmentDigestSha256 = currentEnvironmentDigestSha256,
        roomIntegration = roomIntegration,
        fourArmRuntime = fourArmRuntime,
        performanceThresholds = FrozenProductionEvalContractV1.performanceThresholds,
    )

    /** Test-only seam for the frozen-threshold algorithm; production entry always binds DRAFT. */
    internal suspend fun evaluateForContractTest(
        adapters: ProductionComponentReplayAdapters =
            ProductionComponentReplayAdapters.unconfigured(),
        baseline: ProductionEvalPerformanceBaseline? = null,
        currentEnvironmentDigestSha256: String? = null,
        roomIntegration: ProductionRoomIntegrationAttestation? = null,
        fourArmRuntime: ProductionFourArmRuntimeAttestation? = null,
        performanceThresholds: PerformanceTrendThresholds =
            FrozenProductionEvalContractV1.performanceThresholds,
    ): ProductionEvaluationGateResult = evaluateBoundThresholds(
        adapters = adapters,
        baseline = baseline,
        currentEnvironmentDigestSha256 = currentEnvironmentDigestSha256,
        roomIntegration = roomIntegration,
        fourArmRuntime = fourArmRuntime,
        performanceThresholds = performanceThresholds,
    )

    private suspend fun evaluateBoundThresholds(
        adapters: ProductionComponentReplayAdapters,
        baseline: ProductionEvalPerformanceBaseline?,
        currentEnvironmentDigestSha256: String?,
        roomIntegration: ProductionRoomIntegrationAttestation?,
        fourArmRuntime: ProductionFourArmRuntimeAttestation?,
        performanceThresholds: PerformanceTrendThresholds,
    ): ProductionEvaluationGateResult {
        require(
            currentEnvironmentDigestSha256 == null ||
                currentEnvironmentDigestSha256.isEvalSha256(),
        )
        require(performanceThresholds.baselineId ==
            FrozenProductionEvalContractV1.performanceThresholds.baselineId)
        if (performanceThresholds.status == PerformanceThresholdStatus.FROZEN) {
            require(performanceThresholds.maxOperationRatioBasisPoints ==
                FrozenProductionEvalContractV1.performanceThresholds.maxOperationRatioBasisPoints)
            require(performanceThresholds.maxAllocationRatioBasisPoints ==
                FrozenProductionEvalContractV1.performanceThresholds.maxAllocationRatioBasisPoints)
        }
        val manifest = FrozenProductionEvalManifest.freeze(
            adapters = adapters,
            performanceThresholds = performanceThresholds,
        )
        val run = ProductionFourArmFixtureRunner(adapters).run()
        val performance = evaluatePerformance(
            manifest,
            run,
            baseline,
            currentEnvironmentDigestSha256,
            performanceThresholds,
        )
        val reportDigest = run.report.digestSha256()
        val rollout = decideRollout(
            run,
            performance,
            manifest.rolloutCriteria,
            roomIntegration,
            fourArmRuntime,
            manifest.digestSha256,
            reportDigest,
        )
        val artifact = RedactedProductionEvalArtifactFactory.create(
            manifest = manifest,
            report = run.report,
            reportDigestSha256 = reportDigest,
            performance = performance,
            rollout = rollout,
            abstainedComponentCount = run.abstainedComponentCount,
            componentCoverage = run.componentCoverage,
            runnerPerformance = run.runnerPerformance,
            currentEnvironmentDigestSha256 = currentEnvironmentDigestSha256,
            roomIntegration = roomIntegration,
            fourArmRuntime = fourArmRuntime,
        )
        return ProductionEvaluationGateResult(
            manifest = manifest,
            report = run.report,
            reportDigestSha256 = reportDigest,
            componentCoverage = run.componentCoverage,
            runnerPerformance = run.runnerPerformance,
            currentEnvironmentDigestSha256 = currentEnvironmentDigestSha256,
            roomIntegration = roomIntegration,
            fourArmRuntime = fourArmRuntime,
            performance = performance,
            rollout = rollout,
            artifact = artifact,
        )
    }

    private fun evaluatePerformance(
        manifest: FrozenProductionEvalManifest,
        run: ProductionComponentReplayRun,
        baseline: ProductionEvalPerformanceBaseline?,
        currentEnvironmentDigestSha256: String?,
        performanceThresholds: PerformanceTrendThresholds,
    ): PerformanceGateResult {
        check(performanceThresholds == manifest.performanceThresholds) {
            "Performance thresholds must be bound to the frozen manifest"
        }
        if (performanceThresholds.status != PerformanceThresholdStatus.FROZEN) {
            return PerformanceGateResult(
                state = PerformanceGateState.NOT_ENFORCED,
                reason = PerformanceGateReason.THRESHOLDS_NOT_FROZEN,
                operationRatioBasisPoints = null,
                allocationRatioBasisPoints = null,
            )
        }
        if (baseline == null) {
            return PerformanceGateResult(
                state = PerformanceGateState.NOT_ENFORCED,
                reason = PerformanceGateReason.BASELINE_MISSING,
                operationRatioBasisPoints = null,
                allocationRatioBasisPoints = null,
            )
        }
        if (baseline.manifestDigestSha256 != manifest.digestSha256) {
            return PerformanceGateResult(
                state = PerformanceGateState.NOT_ENFORCED,
                reason = PerformanceGateReason.MANIFEST_IDENTITY_MISMATCH,
                operationRatioBasisPoints = null,
                allocationRatioBasisPoints = null,
            )
        }
        if (currentEnvironmentDigestSha256 == null) {
            return PerformanceGateResult(
                state = PerformanceGateState.NOT_ENFORCED,
                reason = PerformanceGateReason.CURRENT_ENVIRONMENT_IDENTITY_MISSING,
                operationRatioBasisPoints = null,
                allocationRatioBasisPoints = null,
            )
        }
        if (!currentEnvironmentDigestSha256.isEvalSha256() ||
            currentEnvironmentDigestSha256 != baseline.baseline.environmentDigestSha256
        ) {
            return PerformanceGateResult(
                state = PerformanceGateState.NOT_ENFORCED,
                reason = PerformanceGateReason.ENVIRONMENT_IDENTITY_MISMATCH,
                operationRatioBasisPoints = null,
                allocationRatioBasisPoints = null,
            )
        }
        return DeterministicPerformanceTrendGate.evaluate(
            currentCorpusDigestSha256 = run.report.corpusDigestSha256,
            current = run.runnerPerformance,
            baseline = baseline.baseline,
            thresholds = performanceThresholds,
        )
    }

    private fun decideRollout(
        run: ProductionComponentReplayRun,
        performance: PerformanceGateResult,
        criteria: ProductionEvalRolloutCriteria,
        roomIntegration: ProductionRoomIntegrationAttestation?,
        fourArmRuntime: ProductionFourArmRuntimeAttestation?,
        manifestDigestSha256: String,
        reportDigestSha256: String,
    ): ProductionRolloutDecision {
        val report = run.report
        val minimumObserved = report.arms.minOfOrNull { it.taskSuccess.observedCount } ?: 0
        val minimumPolicyObserved = report.arms.filter { arm ->
            arm.arm == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
                arm.arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
        }.minOfOrNull { it.policyOutcome.observedCount } ?: 0
        val minimumPaired = report.associations.minOfOrNull { it.pairedObservedCount } ?: 0
        fun decision(
            state: ProductionRolloutDecisionState,
            reason: ProductionRolloutDecisionReason,
        ) = ProductionRolloutDecision(
            state,
            reason,
            minimumObserved,
            minimumPolicyObserved,
            minimumPaired,
        )

        val harmful = report.arms.sumOf { it.harmfulRate.positiveCount }
        val scopeLeaks = report.arms.sumOf { it.scopeLeakCount }
        val staleHits = report.arms.sumOf { it.staleHitCount }
        val scripts = report.arms.sumOf { it.scriptActionCount }
        if (harmful > criteria.maximumHarmfulOutcomeCount ||
            scopeLeaks > criteria.maximumScopeLeakCount.toLong() ||
            staleHits > criteria.maximumStaleHitCount.toLong() || scripts != 0L
        ) {
            return decision(
                ProductionRolloutDecisionState.REJECT,
                ProductionRolloutDecisionReason.HARD_SAFETY_FAILURE,
            )
        }
        if (roomIntegration?.state == ProductionRoomIntegrationState.REJECTED) {
            return decision(
                ProductionRolloutDecisionState.REJECT,
                ProductionRolloutDecisionReason.ROOM_INTEGRATION_HARD_FAILURE,
            )
        }
        // The checked-in fixture is intentionally non-authoritative and therefore may carry a
        // replay report digest produced by the durable Room path rather than this JVM rerun. That
        // remains an abstention, not an identity hard failure. Independent/PASSED evidence must
        // retain the exact manifest+report binding required for rollout authority.
        val checkedInFixtureAbstention = fourArmRuntime?.state ==
            ProductionFourArmRuntimeState.ABSTAINED &&
            fourArmRuntime.reason ==
                ProductionFourArmRuntimeReason.CHECKED_IN_REGRESSION_FIXTURE_ONLY &&
            fourArmRuntime.manifestDigestSha256 == manifestDigestSha256
        val fourArmIdentityMatches = fourArmRuntime == null || checkedInFixtureAbstention ||
            (fourArmRuntime.manifestDigestSha256 == manifestDigestSha256 &&
                fourArmRuntime.reportDigestSha256 == reportDigestSha256)
        if (!fourArmIdentityMatches ||
            fourArmRuntime?.state == ProductionFourArmRuntimeState.REJECTED
        ) {
            return decision(
                ProductionRolloutDecisionState.REJECT,
                ProductionRolloutDecisionReason.FOUR_ARM_RUNTIME_HARD_FAILURE,
            )
        }
        if (run.abstainedComponentCount != 0) {
            return decision(
                ProductionRolloutDecisionState.ABSTAIN,
                ProductionRolloutDecisionReason.PRODUCTION_COMPONENT_ABSTAINED,
            )
        }
        if (minimumObserved < criteria.minimumObservedTaskOutcomesPerArm ||
            minimumPolicyObserved < criteria.minimumObservedPolicyOutcomesPerReviewedArm ||
            minimumPaired < criteria.minimumPairedOutcomesPerComparison
        ) {
            return decision(
                ProductionRolloutDecisionState.ABSTAIN,
                ProductionRolloutDecisionReason.SAMPLE_SIZE_INSUFFICIENT,
            )
        }
        val fullRuntime = report.arms.single {
            it.arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
        }.taskSuccess
        val fullRuntimeBasisPoints = fullRuntime.estimate?.let { estimate ->
            (estimate * 10_000.0).toInt()
        } ?: 0
        if (fullRuntimeBasisPoints < criteria.minimumFullRuntimeSuccessBasisPoints) {
            return decision(
                ProductionRolloutDecisionState.REJECT,
                ProductionRolloutDecisionReason.FULL_RUNTIME_SUCCESS_BELOW_THRESHOLD,
            )
        }
        val performanceDecision = when (performance.state) {
            PerformanceGateState.FAILED -> decision(
                ProductionRolloutDecisionState.REJECT,
                ProductionRolloutDecisionReason.PERFORMANCE_REGRESSION,
            )
            PerformanceGateState.NOT_ENFORCED -> decision(
                ProductionRolloutDecisionState.ABSTAIN,
                ProductionRolloutDecisionReason.PERFORMANCE_NOT_ENFORCED,
            )
            PerformanceGateState.PASSED -> null
        }
        if (performanceDecision != null) return performanceDecision
        if (roomIntegration?.state != ProductionRoomIntegrationState.PASSED) {
            return decision(
                ProductionRolloutDecisionState.ABSTAIN,
                ProductionRolloutDecisionReason.ROOM_INTEGRATION_NOT_OBSERVED,
            )
        }
        if (fourArmRuntime?.state != ProductionFourArmRuntimeState.PASSED) {
            return decision(
                ProductionRolloutDecisionState.ABSTAIN,
                ProductionRolloutDecisionReason.DURABLE_FOUR_ARM_RUNTIME_NOT_OBSERVED,
            )
        }
        val requiredAssociation = report.associations.singleOrNull {
            it.baselineArm == OfflineEvalArm.A_NO_LEARNING &&
                it.comparisonArm == criteria.requiredAssociationComparisonArm
        }
        val associationCi = requiredAssociation?.successRateDifference
        val associationPassed = associationCi != null && when (criteria.associationDecisionRule) {
            ObservedAssociationDecisionRule.CONFIDENT_POSITIVE_GAIN ->
                associationCi.lower >=
                    criteria.minimumAssociationLowerBoundBasisPoints / 10_000.0
            ObservedAssociationDecisionRule.NON_INFERIORITY ->
                associationCi.lower >=
                    -criteria.maximumNonInferiorityMarginBasisPoints / 10_000.0
        }
        if (!associationPassed) {
            return decision(
                ProductionRolloutDecisionState.ABSTAIN,
                ProductionRolloutDecisionReason.ASSOCIATION_CRITERION_NOT_MET,
            )
        }
        return decision(
            ProductionRolloutDecisionState.APPROVE,
            ProductionRolloutDecisionReason.FROZEN_GATES_PASSED,
        )
    }
}

object RedactedProductionEvalArtifactFactory {
    fun create(
        manifest: FrozenProductionEvalManifest,
        report: OfflineEvalReport,
        reportDigestSha256: String,
        performance: PerformanceGateResult,
        rollout: ProductionRolloutDecision,
        abstainedComponentCount: Int,
        componentCoverage: List<ProductionComponentCoverageSummary>,
        runnerPerformance: PerformanceCounterSnapshot,
        currentEnvironmentDigestSha256: String?,
        roomIntegration: ProductionRoomIntegrationAttestation?,
        fourArmRuntime: ProductionFourArmRuntimeAttestation?,
    ): RedactedProductionEvalArtifact {
        require(reportDigestSha256 == report.digestSha256())
        require(abstainedComponentCount >= 0)
        require(componentCoverage.map { it.component } == ProductionReplayComponent.entries.toList())
        val header = buildString {
            appendLine("artifact=production_component_eval_redacted_v3")
            appendLine("manifest_sha256=${manifest.digestSha256}")
            appendLine("report_sha256=$reportDigestSha256")
            appendLine("rollout_state=${rollout.state.name}")
            appendLine("rollout_reason=${rollout.reason.name}")
            appendLine("minimum_observed=${rollout.minimumObservedTaskOutcomes}")
            appendLine("minimum_policy_observed=${rollout.minimumObservedPolicyOutcomes}")
            appendLine("minimum_paired=${rollout.minimumPairedOutcomes}")
            appendLine(
                "association_rule=${manifest.rolloutCriteria.associationDecisionRule.name}",
            )
            appendLine(
                "association_comparison=" +
                    manifest.rolloutCriteria.requiredAssociationComparisonArm.name,
            )
            appendLine(
                "association_min_lower_bp=" +
                    manifest.rolloutCriteria.minimumAssociationLowerBoundBasisPoints,
            )
            appendLine("component_abstentions=$abstainedComponentCount")
            appendLine(
                "runner_operation_units=${runnerPerformance.deterministicOperationUnits}",
            )
            appendLine(
                "runner_logical_allocation_units=${runnerPerformance.logicalAllocationUnits}",
            )
            appendLine(
                "environment_sha256=${currentEnvironmentDigestSha256 ?: "UNMEASURED"}",
            )
            appendLine(
                "room_integration_state=${roomIntegration?.state?.name ?: "UNOBSERVED"}",
            )
            appendLine(
                "room_integration_reason=${roomIntegration?.reason?.name ?: "UNOBSERVED"}",
            )
            appendLine(
                "room_integration_checks=" +
                    "${roomIntegration?.observedCheckCount ?: 0}/" +
                    FrozenProductionRoomIntegrationContractV1.requiredChecks.size,
            )
            appendLine(
                "room_integration_sha256=" +
                    (roomIntegration?.attestationDigestSha256 ?: "UNOBSERVED"),
            )
            appendLine(
                "four_arm_runtime_state=${fourArmRuntime?.state?.name ?: "UNOBSERVED"}",
            )
            appendLine(
                "four_arm_runtime_reason=${fourArmRuntime?.reason?.name ?: "UNOBSERVED"}",
            )
            appendLine(
                "four_arm_runtime_checks=" +
                    "${fourArmRuntime?.observedCheckCount ?: 0}/" +
                    FrozenProductionFourArmRuntimeContractV1.requiredChecks.size,
            )
            appendLine(
                "four_arm_runtime_sha256=" +
                    (fourArmRuntime?.attestationDigestSha256 ?: "UNOBSERVED"),
            )
            componentCoverage.forEach { coverage ->
                appendLine(
                    "component.${coverage.component.name}=" +
                        "observed:${coverage.observedCount}," +
                        "skipped:${coverage.skippedCount}," +
                        "abstained:${coverage.abstainedCount}",
                )
            }
            appendLine("performance_state=${performance.state.name}")
            appendLine("performance_reason=${performance.reason.name}")
            appendLine(
                "performance_operation_ratio_bp=" +
                    (performance.operationRatioBasisPoints ?: "UNMEASURED"),
            )
            appendLine(
                "performance_allocation_ratio_bp=" +
                    (performance.allocationRatioBasisPoints ?: "UNMEASURED"),
            )
        }
        val aggregateBudget = RedactedEvalReportRenderer.MAX_REPORT_CHARS - header.length
        require(aggregateBudget >= RedactedEvalReportRenderer.MIN_COMPLETE_REPORT_CHARS) {
            "Production artifact header leaves insufficient room for the complete slice matrix"
        }
        val aggregate = RedactedEvalReportRenderer.render(
            report,
            maxChars = aggregateBudget,
        )
        val redacted = header + aggregate
        check(redacted.length <= RedactedEvalReportRenderer.MAX_REPORT_CHARS)
        val redactedDigest = EvalDigest.sha256(
            "production-eval-redacted-report-v3",
            listOf(redacted),
        )
        val artifactDigest = EvalDigest.sha256(
            "production-eval-redacted-artifact-v3",
            listOf(
                manifest.digestSha256,
                reportDigestSha256,
                redactedDigest,
                roomIntegration?.attestationDigestSha256 ?: "UNOBSERVED",
                fourArmRuntime?.attestationDigestSha256 ?: "UNOBSERVED",
                rollout.state.name,
                rollout.reason.name,
            ),
        )
        return RedactedProductionEvalArtifact(
            schemaVersion = 3,
            manifestDigestSha256 = manifest.digestSha256,
            reportDigestSha256 = reportDigestSha256,
            redactedReportSha256 = redactedDigest,
            artifactDigestSha256 = artifactDigest,
            roomIntegrationDigestSha256 = roomIntegration?.attestationDigestSha256,
            fourArmRuntimeDigestSha256 = fourArmRuntime?.attestationDigestSha256,
            decisionState = rollout.state,
            decisionReason = rollout.reason,
            redactedReport = redacted,
        )
    }
}

fun OfflineEvalReport.digestSha256(): String = EvalDigest.sha256(
    domain = "offline-eval-report-v1",
    fields = buildList {
        add(schemaVersion.toString())
        add(corpusId)
        add(corpusDigestSha256)
        add(planId)
        add(planDigestSha256)
        add(assignmentManifestSha256)
        add(matchedCohortCount.toString())
        add(incompleteMatchedCohortCount.toString())
        add(holdoutUnitCount.toString())
        assignments.sortedBy { it.unitId }.forEach { assignment ->
            add("assignment:${assignment.unitId}:${assignment.primaryArm.name}:${assignment.partition.name}")
        }
        arms.forEach { arm -> addAll(arm.digestFields()) }
        partitions.forEach { partition ->
            add("partition:${partition.partition.name}:${partition.arm.name}:${partition.sampleSize}")
            addAll(partition.taskSuccess.digestFields())
        }
        slices.forEach { slice ->
            add("slice:${slice.dimension.name}:${slice.value}:${slice.arm.name}:${slice.sampleSize}")
            addAll(slice.taskSuccess.digestFields())
            add(slice.scopeLeakCount.toString())
            add(slice.staleHitCount.toString())
            addAll(slice.harmfulRate.digestFields())
        }
        associations.forEach { association ->
            add(
                "association:${association.baselineArm.name}:${association.comparisonArm.name}:" +
                    "${association.pairedObservedCount}:${association.unknownPairCount}:" +
                    "${association.censoredPairCount}:${association.interpretation.name}",
            )
            addAll(association.successRateDifference.digestFields())
        }
        add(judgeDivergence.toString())
        add(performance.deterministicOperationUnits.toString())
        add(performance.logicalAllocationUnits.toString())
        add(energy.state.name)
        add(energy.reasonCode)
        add(energy.dedicatedOdpmDeviceUsed.toString())
        add(energy.primaryHonorDeviceTestingProhibited.toString())
    },
)

private fun ArmEvalSummary.digestFields(): List<String> = buildList {
    add("arm:${arm.name}:$sampleSize")
    addAll(taskSuccess.digestFields())
    addAll(harmfulRate.digestFields())
    add(userCorrectionCount.toString())
    add(toolCallCount.toString())
    add(toolRetryCount.toString())
    add(inputTokens.toString())
    add(outputTokens.toString())
    add(retrievalTokens.toString())
    add(contextTokens.toString())
    add(recordedTtft.toString())
    add(recordedToolToNextModel.toString())
    add(policyCandidateCount.toString())
    add(policyCompiledCount.toString())
    add(policyDispatchCount.toString())
    addAll(policyOutcome.digestFields())
    add(scopeLeakCount.toString())
    add(staleHitCount.toString())
    add(scriptActionCount.toString())
}

private fun BinaryMetricSummary.digestFields(): List<String> = listOf(
    observedCount.toString(),
    positiveCount.toString(),
    unknownCount.toString(),
    censoredCount.toString(),
    estimate?.toString() ?: "UNMEASURED",
) + bootstrapCi.digestFields()

private fun ConfidenceInterval?.digestFields(): List<String> = this?.let { interval ->
    listOf(
        interval.lower.toString(),
        interval.estimate.toString(),
        interval.upper.toString(),
        interval.confidenceLevelBasisPoints.toString(),
        interval.resamples.toString(),
    )
}.orEmpty().ifEmpty { listOf("UNMEASURED") }

private fun computeManifestDigest(
    manifestId: String,
    runnerVersion: String,
    corpusId: String,
    corpusDigestSha256: String,
    planId: String,
    planDigestSha256: String,
    assignmentManifestSha256: String,
    adapterIdentities: List<ProductionComponentAdapterIdentity>,
    criteria: ProductionEvalRolloutCriteria,
    thresholds: PerformanceTrendThresholds,
): String = EvalDigest.sha256(
    "production-eval-manifest-v1",
    listOf(
        manifestId,
        runnerVersion,
        corpusId,
        corpusDigestSha256,
        planId,
        planDigestSha256,
        assignmentManifestSha256,
        criteria.criteriaVersion,
        criteria.minimumObservedTaskOutcomesPerArm.toString(),
        criteria.minimumObservedPolicyOutcomesPerReviewedArm.toString(),
        criteria.minimumPairedOutcomesPerComparison.toString(),
        criteria.minimumFullRuntimeSuccessBasisPoints.toString(),
        criteria.requiredAssociationComparisonArm.name,
        criteria.associationDecisionRule.name,
        criteria.minimumAssociationLowerBoundBasisPoints.toString(),
        criteria.maximumNonInferiorityMarginBasisPoints.toString(),
        criteria.maximumHarmfulOutcomeCount.toString(),
        criteria.maximumScopeLeakCount.toString(),
        criteria.maximumStaleHitCount.toString(),
        thresholds.baselineId,
        thresholds.status.name,
        thresholds.maxOperationRatioBasisPoints.toString(),
        thresholds.maxAllocationRatioBasisPoints.toString(),
    ) + adapterIdentities.sortedBy { it.component.ordinal }.flatMap { identity ->
        listOf(
            identity.component.name,
            identity.adapterVersion,
            identity.implementationSha256,
        )
    },
)

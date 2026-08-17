package me.rerere.rikkahub.learning.eval

/** The four pre-registered P5-005 replay arms. The full runtime arm explicitly excludes JS. */
enum class OfflineEvalArm {
    A_NO_LEARNING,
    B_DREAMING_ONLY,
    C_DREAMING_REVIEWED_POLICY,
    D_FULL_REVIEWED_RUNTIME_NO_JS,
}

enum class EvalSliceDimension {
    MODEL,
    TOOL_SCHEMA,
    TASK_CLASS,
    SCOPE,
    LANGUAGE,
}

data class OfflineEvalSlice(
    val model: String,
    val toolSchema: String,
    val taskClass: String,
    val scope: String,
    val language: String,
) {
    init {
        listOf(model, toolSchema, taskClass, scope, language).forEach(::requireSafeEvalLabel)
    }

    fun dimensions(): Map<EvalSliceDimension, String> = linkedMapOf(
        EvalSliceDimension.MODEL to model,
        EvalSliceDimension.TOOL_SCHEMA to toolSchema,
        EvalSliceDimension.TASK_CLASS to taskClass,
        EvalSliceDimension.SCOPE to scope,
        EvalSliceDimension.LANGUAGE to language,
    )
}

enum class ReplayFixtureScenario {
    BASELINE_SUCCESS,
    LEARNING_ASSISTED,
    UNKNOWN_AUTHORITY,
    CENSORED_TIMEOUT,
    JUDGE_DIVERGENCE,
    USER_CORRECTION,
    STALE_GUARD,
    SCOPE_GUARD,
    TOKEN_HEAVY,
    TOOL_RETRY,
}

/** Content-free frozen replay descriptor. User prompts, outputs, paths and URLs are never stored. */
data class OfflineReplayUnit(
    val unitId: String,
    val matchedCohortId: String,
    val fixtureId: String,
    val slice: OfflineEvalSlice,
    val scenario: ReplayFixtureScenario,
) {
    init {
        listOf(unitId, matchedCohortId, fixtureId).forEach(::requireSafeEvalLabel)
    }
}

enum class BinaryUnknownReason {
    AUTHORITY_MISSING,
    OUTCOME_NOT_RECORDED,
    JUDGE_ABSTAINED,
    PRODUCTION_COMPONENT_ABSTAINED,
}

enum class BinaryCensorReason {
    FIXTURE_TIMEOUT,
    TRACE_TRUNCATED,
    HOLDOUT_NOT_OPENED,
}

sealed interface BinaryObservation {
    data class Observed(val value: Boolean) : BinaryObservation
    data class Unknown(val reason: BinaryUnknownReason) : BinaryObservation
    data class Censored(val reason: BinaryCensorReason) : BinaryObservation
}

enum class JudgeVerdict {
    SUCCESS,
    FAILURE,
    UNKNOWN,
}

data class PolicyFunnelObservation(
    val candidateCount: Int,
    val compiledCount: Int,
    val dispatchCount: Int,
    val outcome: BinaryObservation,
) {
    init {
        require(candidateCount >= 0)
        require(compiledCount in 0..candidateCount)
        require(dispatchCount in 0..compiledCount)
    }
}

/** Recorded replay latency is input evidence, never a JVM-test wall-clock assertion. */
data class RecordedLatencyObservation(
    val ttftMicros: Long?,
    val toolToNextModelMicros: Long?,
) {
    init {
        require(ttftMicros == null || ttftMicros >= 0L)
        require(toolToNextModelMicros == null || toolToNextModelMicros >= 0L)
    }
}

data class ReplayResourceObservation(
    val inputTokens: Int,
    val outputTokens: Int,
    val retrievalTokens: Int,
    val contextTokens: Int,
    val toolCalls: Int,
    val toolRetries: Int,
) {
    init {
        require(listOf(inputTokens, outputTokens, retrievalTokens, contextTokens).all { it >= 0 })
        require(toolCalls >= 0 && toolRetries in 0..toolCalls)
    }
}

data class JvmTrendObservation(
    /** Deterministic algorithmic units declared by the replay adapter. */
    val operationUnits: Long,
    /** Logical object/row units, not sampled JVM heap bytes. */
    val logicalAllocationUnits: Long,
) {
    init {
        require(operationUnits >= 0L && logicalAllocationUnits >= 0L)
    }
}

data class OfflineReplayObservation(
    val unitId: String,
    val arm: OfflineEvalArm,
    val taskOutcome: BinaryObservation,
    val harmfulOutcome: BinaryObservation,
    val userCorrectionCount: Int,
    val resources: ReplayResourceObservation,
    val recordedLatency: RecordedLatencyObservation,
    val policy: PolicyFunnelObservation,
    val scopeLeakCount: Int,
    val staleHitCount: Int,
    val deterministicJudge: JudgeVerdict,
    val humanJudge: JudgeVerdict,
    val llmJudge: JudgeVerdict,
    val scriptActionCount: Int,
    val jvmTrend: JvmTrendObservation,
) {
    init {
        requireSafeEvalLabel(unitId)
        require(userCorrectionCount >= 0)
        require(scopeLeakCount >= 0 && staleHitCount >= 0 && scriptActionCount >= 0)
    }
}

fun interface OfflineReplayExecutor {
    fun replay(unit: OfflineReplayUnit, arm: OfflineEvalArm): OfflineReplayObservation
}

data class BootstrapConfig(
    val resamples: Int,
    val confidenceLevelBasisPoints: Int,
) {
    init {
        require(resamples in 100..100_000)
        require(confidenceLevelBasisPoints in 5_000..9_999)
    }
}

data class OfflineEvalPlan(
    val planId: String,
    val assignmentSalt: String,
    val holdoutBasisPoints: Int,
    val bootstrap: BootstrapConfig,
    val arms: List<OfflineEvalArm> = OfflineEvalArm.entries,
) {
    init {
        requireSafeEvalLabel(planId)
        requireSafeEvalLabel(assignmentSalt)
        require(holdoutBasisPoints in 1..9_999)
        require(arms == OfflineEvalArm.entries)
    }
}

enum class EvalPartition { MATCHED_REPLAY, HOLDOUT }

data class PreRegisteredAssignment(
    val unitId: String,
    val primaryArm: OfflineEvalArm,
    val partition: EvalPartition,
) {
    init {
        requireSafeEvalLabel(unitId)
    }
}

enum class MeasurementKnowledge { MEASURED, UNMEASURED }

data class ConfidenceInterval(
    val lower: Double,
    val estimate: Double,
    val upper: Double,
    val confidenceLevelBasisPoints: Int,
    val resamples: Int,
) {
    init {
        require(listOf(lower, estimate, upper).all(Double::isFinite))
        require(lower <= estimate && estimate <= upper)
        require(confidenceLevelBasisPoints in 5_000..9_999)
        require(resamples >= 100)
    }
}

data class BinaryMetricSummary(
    val observedCount: Int,
    val positiveCount: Int,
    val unknownCount: Int,
    val censoredCount: Int,
    val estimate: Double?,
    val bootstrapCi: ConfidenceInterval?,
) {
    init {
        require(listOf(observedCount, positiveCount, unknownCount, censoredCount).all { it >= 0 })
        require(positiveCount <= observedCount)
        require((observedCount == 0) == (estimate == null && bootstrapCi == null))
        require(estimate == null || estimate in 0.0..1.0)
    }

    val totalCount: Int get() = observedCount + unknownCount + censoredCount
}

data class RecordedDistributionSummary(
    val knowledge: MeasurementKnowledge,
    val sampleCount: Int,
    val p50: Long?,
    val p95: Long?,
) {
    init {
        require(sampleCount >= 0)
        require((knowledge == MeasurementKnowledge.UNMEASURED) ==
            (sampleCount == 0 && p50 == null && p95 == null))
        require(p50 == null || p50 >= 0L)
        require(p95 == null || p95 >= (p50 ?: 0L))
    }
}

data class ArmEvalSummary(
    val arm: OfflineEvalArm,
    val sampleSize: Int,
    val taskSuccess: BinaryMetricSummary,
    val harmfulRate: BinaryMetricSummary,
    val userCorrectionCount: Long,
    val toolCallCount: Long,
    val toolRetryCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val retrievalTokens: Long,
    val contextTokens: Long,
    val recordedTtft: RecordedDistributionSummary,
    val recordedToolToNextModel: RecordedDistributionSummary,
    val policyCandidateCount: Long,
    val policyCompiledCount: Long,
    val policyDispatchCount: Long,
    val policyOutcome: BinaryMetricSummary,
    val scopeLeakCount: Long,
    val staleHitCount: Long,
    val scriptActionCount: Long,
) {
    init {
        require(sampleSize >= 0)
        require(taskSuccess.totalCount == sampleSize)
        require(harmfulRate.totalCount == sampleSize)
        require(policyOutcome.totalCount == sampleSize)
        require(
            listOf(
                userCorrectionCount,
                toolCallCount,
                toolRetryCount,
                inputTokens,
                outputTokens,
                retrievalTokens,
                contextTokens,
                policyCandidateCount,
                policyCompiledCount,
                policyDispatchCount,
                scopeLeakCount,
                staleHitCount,
                scriptActionCount,
            ).all { it >= 0L },
        )
    }
}

data class PartitionArmSummary(
    val partition: EvalPartition,
    val arm: OfflineEvalArm,
    val sampleSize: Int,
    val taskSuccess: BinaryMetricSummary,
) {
    init {
        require(sampleSize >= 0 && taskSuccess.totalCount == sampleSize)
    }
}

data class SliceEvalSummary(
    val dimension: EvalSliceDimension,
    val value: String,
    val arm: OfflineEvalArm,
    val sampleSize: Int,
    val taskSuccess: BinaryMetricSummary,
    val scopeLeakCount: Long,
    val staleHitCount: Long,
    val harmfulRate: BinaryMetricSummary,
) {
    init {
        requireSafeEvalLabel(value)
        require(sampleSize >= 0)
    }
}

enum class AssociationInterpretation { OBSERVED_ASSOCIATION_ONLY_NOT_CAUSAL }

data class MatchedObservedAssociation(
    val baselineArm: OfflineEvalArm,
    val comparisonArm: OfflineEvalArm,
    val pairedObservedCount: Int,
    val unknownPairCount: Int,
    val censoredPairCount: Int,
    val successRateDifference: ConfidenceInterval?,
    val interpretation: AssociationInterpretation =
        AssociationInterpretation.OBSERVED_ASSOCIATION_ONLY_NOT_CAUSAL,
) {
    init {
        require(baselineArm == OfflineEvalArm.A_NO_LEARNING)
        require(comparisonArm != baselineArm)
        require(listOf(pairedObservedCount, unknownPairCount, censoredPairCount).all { it >= 0 })
        require((pairedObservedCount == 0) == (successRateDifference == null))
    }
}

data class JudgeDivergenceSummary(
    val llmVsDeterministicComparableCount: Int,
    val llmVsDeterministicDivergenceCount: Int,
    val llmVsHumanComparableCount: Int,
    val llmVsHumanDivergenceCount: Int,
) {
    init {
        require(llmVsDeterministicComparableCount >= llmVsDeterministicDivergenceCount)
        require(llmVsHumanComparableCount >= llmVsHumanDivergenceCount)
        require(llmVsDeterministicDivergenceCount >= 0 && llmVsHumanDivergenceCount >= 0)
    }
}

enum class EnergyMeasurementState { UNMEASURED }

data class EnergyAssessment(
    val state: EnergyMeasurementState,
    val reasonCode: String,
    val dedicatedOdpmDeviceUsed: Boolean,
    val primaryHonorDeviceTestingProhibited: Boolean,
) {
    init {
        requireSafeEvalLabel(reasonCode)
        require(state == EnergyMeasurementState.UNMEASURED)
        require(!dedicatedOdpmDeviceUsed)
        require(primaryHonorDeviceTestingProhibited)
    }

    companion object {
        fun offlineJvm(): EnergyAssessment = EnergyAssessment(
            state = EnergyMeasurementState.UNMEASURED,
            reasonCode = "NO_DEDICATED_ODPM_DEVICE",
            dedicatedOdpmDeviceUsed = false,
            primaryHonorDeviceTestingProhibited = true,
        )
    }
}

data class PerformanceCounterSnapshot(
    val deterministicOperationUnits: Long,
    val logicalAllocationUnits: Long,
) {
    init {
        require(deterministicOperationUnits >= 0L && logicalAllocationUnits >= 0L)
    }
}

data class OfflineEvalReport(
    val schemaVersion: Int,
    val corpusId: String,
    val corpusDigestSha256: String,
    val planId: String,
    val planDigestSha256: String,
    val assignmentManifestSha256: String,
    val matchedCohortCount: Int,
    val incompleteMatchedCohortCount: Int,
    val holdoutUnitCount: Int,
    val assignments: List<PreRegisteredAssignment>,
    val arms: List<ArmEvalSummary>,
    val partitions: List<PartitionArmSummary>,
    val slices: List<SliceEvalSummary>,
    val associations: List<MatchedObservedAssociation>,
    val judgeDivergence: JudgeDivergenceSummary,
    val performance: PerformanceCounterSnapshot,
    val energy: EnergyAssessment,
) {
    init {
        require(schemaVersion == 1)
        requireSafeEvalLabel(corpusId)
        requireSafeEvalLabel(planId)
        listOf(corpusDigestSha256, planDigestSha256, assignmentManifestSha256).forEach {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
        require(matchedCohortCount >= 0 && incompleteMatchedCohortCount >= 0)
        require(holdoutUnitCount >= 0)
        require(arms.map(ArmEvalSummary::arm) == OfflineEvalArm.entries)
        require(partitions.size == EvalPartition.entries.size * OfflineEvalArm.entries.size)
        require(slices.size <= MAX_EVAL_SLICE_REPORTS)
    }
}

internal fun requireSafeEvalLabel(value: String) {
    require(value.length in 1..128)
    require(value.all { character ->
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '_' || character == '-' || character == '.' || character == ':'
    })
}

const val MAX_EVAL_SLICE_REPORTS: Int = 256

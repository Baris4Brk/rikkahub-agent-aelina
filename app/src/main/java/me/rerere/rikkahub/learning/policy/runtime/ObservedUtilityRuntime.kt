package me.rerere.rikkahub.learning.policy.runtime

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.policy.ObservedUtilityArm
import me.rerere.rikkahub.learning.policy.ObservedUtilityAbstainReason
import me.rerere.rikkahub.learning.policy.ObservedUtilityAssignmentMethod
import me.rerere.rikkahub.learning.policy.ObservedUtilityCausalInterpretation
import me.rerere.rikkahub.learning.policy.ObservedUtilityDesign
import me.rerere.rikkahub.learning.policy.ObservedUtilityEstimate
import me.rerere.rikkahub.learning.policy.ObservedUtilityEstimationResult
import me.rerere.rikkahub.learning.policy.ObservedUtilityEstimator
import me.rerere.rikkahub.learning.policy.ObservedUtilityObservation
import me.rerere.rikkahub.learning.policy.ObservedUtilityOutcome
import me.rerere.rikkahub.learning.policy.ObservedUtilitySelectionMethod
import me.rerere.rikkahub.learning.policy.OBSERVED_UTILITY_INTERPRETATION_NAME
import me.rerere.rikkahub.learning.policy.OBSERVED_UTILITY_METRIC_NAME
import me.rerere.rikkahub.learning.policy.PolicyAdvisoryHarmSignal
import me.rerere.rikkahub.learning.policy.PolicyAdvisoryHarmSource
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeTerminalOutcome
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernorResult
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt

const val OBSERVED_UTILITY_RUNTIME_CONTRACT_VERSION = 1
const val MAX_DURABLE_UTILITY_ROWS = 1_000
private val UTILITY_SHA256 = Regex("[0-9a-f]{64}")

data class ObservedUtilityRuntimeRequest(
    val fence: PolicyMutationFence,
    val design: ObservedUtilityDesign,
    /** Exact immutable cohort; a producer/model/config change creates another request. */
    val expectedCohortDigest: String,
    val sourceWindowStartMs: Long,
    val sourceWindowEndMs: Long,
    val limit: Int = MAX_DURABLE_UTILITY_ROWS,
) {
    init {
        require(sourceWindowStartMs >= 0L && sourceWindowEndMs > sourceWindowStartMs)
        require(limit in 1..MAX_DURABLE_UTILITY_ROWS)
        require(design.targetPolicyId == null || design.targetPolicyId == fence.policyId)
        require(expectedCohortDigest.matches(UTILITY_SHA256))
    }
}

/** A content-free row projected only from durable exposure/baseline and terminal authority. */
data class DurableObservedUtilityRow(
    val durableObservationIdentityDigest: String,
    val arm: ObservedUtilityArm,
    val authoritativeOutcome: PolicyAuthoritativeTerminalOutcome,
    val cohort: me.rerere.rikkahub.learning.policy.ObservedUtilityCohortIdentity,
    val policySetDigest: String,
    val matchKeyDigest: String? = null,
    val propensity: Double? = null,
    val exposureReceipt: PolicyExposureReceipt? = null,
    val baselineHostDispatched: Boolean = false,
    val baselineProgressOrResponse: Boolean = false,
    val authoritativeOutcomeCommitted: Boolean,
) {
    init {
        require(durableObservationIdentityDigest.matches(UTILITY_SHA256))
        require(policySetDigest.matches(UTILITY_SHA256))
        require(matchKeyDigest == null || matchKeyDigest.matches(UTILITY_SHA256))
        require(propensity == null || propensity.isFinite())
        require((arm == ObservedUtilityArm.EXPOSED) == (exposureReceipt != null))
        exposureReceipt?.let {
            require(it.reservation.bundle.policySetDigest == policySetDigest)
        }
    }

    fun toObservation(): ObservedUtilityObservation = ObservedUtilityObservation(
        observationIdDigest = LearningCanonicalId.digest(
            domainVersion = "observed-utility-runtime-observation-v1",
            fields = listOf(
                durableObservationIdentityDigest,
                arm.name,
                authoritativeOutcome.name,
                policySetDigest,
                matchKeyDigest.orEmpty(),
                propensity?.toString().orEmpty(),
                observedUtilityCohortDigest(cohort),
            ),
        ),
        arm = arm,
        outcome = when (authoritativeOutcome) {
            PolicyAuthoritativeTerminalOutcome.SUCCESS -> ObservedUtilityOutcome.SUCCESS
            PolicyAuthoritativeTerminalOutcome.FAILURE -> ObservedUtilityOutcome.FAILURE
            PolicyAuthoritativeTerminalOutcome.CENSORED -> ObservedUtilityOutcome.CENSORED
            PolicyAuthoritativeTerminalOutcome.UNKNOWN -> ObservedUtilityOutcome.UNKNOWN
        },
        cohort = cohort,
        policySetDigest = policySetDigest,
        matchKeyDigest = matchKeyDigest,
        propensity = propensity,
        exposureReceipt = exposureReceipt,
        baselineHostDispatched = baselineHostDispatched,
        baselineProgressOrResponse = baselineProgressOrResponse,
        authoritativeOutcomeCommitted = authoritativeOutcomeCommitted,
    )
}

data class DurableObservedUtilityBatch(
    val rows: List<DurableObservedUtilityRow>,
    /** Digest of exact query bounds plus the durable max row/revision watermark. */
    val sourceWatermarkDigest: String,
    /** False means the bounded page truncated the requested window; evaluation must ABSTAIN. */
    val complete: Boolean,
) {
    init {
        require(rows.size <= MAX_DURABLE_UTILITY_ROWS)
        require(rows.map { it.durableObservationIdentityDigest }.distinct().size == rows.size)
        require(sourceWatermarkDigest.matches(UTILITY_SHA256))
    }
}

sealed interface DurableObservedUtilityBatchResult {
    data class Ready(val batch: DurableObservedUtilityBatch) : DurableObservedUtilityBatchResult
    data object Unavailable : DurableObservedUtilityBatchResult
}

interface DurableObservedUtilitySource {
    /** Reads only bounded durable exposure/baseline/terminal projections. */
    suspend fun loadExact(request: ObservedUtilityRuntimeRequest): DurableObservedUtilityBatchResult

    /** Exact scope/revision/content/artifact recheck immediately before persistence. */
    suspend fun revalidatePolicyFence(fence: PolicyMutationFence): Boolean
}

enum class ObservedUtilityRuntimeStatus {
    ESTIMATED,
    ABSTAINED,
}

enum class ObservedUtilitySourceWatermarkStatus {
    KNOWN,
    UNKNOWN,
}

data class ObservedUtilityEvaluationReceipt(
    val fence: PolicyMutationFence,
    val contractVersion: Int,
    val designDigest: String,
    val targetPolicySetDigest: String,
    val sourceWindowStartMs: Long,
    val sourceWindowEndMs: Long,
    val sourceWatermarkDigest: String,
    val sourceWatermarkStatus: ObservedUtilitySourceWatermarkStatus,
    val cohortDigest: String,
    /** Null means no single observed cohort could be established. */
    val observedCohortDigest: String?,
    val status: ObservedUtilityRuntimeStatus,
    val resultCode: String,
    val assignmentMethod: ObservedUtilityAssignmentMethod,
    val selectionMethod: ObservedUtilitySelectionMethod,
    val metricName: String,
    val interpretationName: String,
    val observedUtilityDelta: Double?,
    val utilityUncertainty: Double?,
    val confidenceLevel: Double?,
    val confidenceLower: Double?,
    val confidenceUpper: Double?,
    val causalInterpretation: ObservedUtilityCausalInterpretation,
    /** Non-null only when an identifiable individual-Policy scalar may be projected. */
    val scalarProjectionPolicyId: String?,
    val sampleSize: Int,
    val exposedSampleSize: Int,
    val nonExposureSampleSize: Int,
    val unknownCount: Int,
    val censoredCount: Int,
    val evaluatedAtMs: Long,
    val receiptDigest: String,
) {
    init {
        require(contractVersion > 0)
        listOf(designDigest, targetPolicySetDigest, sourceWatermarkDigest, receiptDigest).forEach {
            require(it.matches(UTILITY_SHA256))
        }
        require(sourceWindowStartMs >= 0L && sourceWindowEndMs > sourceWindowStartMs)
        require(cohortDigest.matches(UTILITY_SHA256))
        require(observedCohortDigest == null || observedCohortDigest.matches(UTILITY_SHA256))
        require(resultCode.matches(Regex("[A-Z][A-Z0-9_]{0,95}")))
        require((observedUtilityDelta == null) == (utilityUncertainty == null))
        observedUtilityDelta?.let { require(it.isFinite() && it in -1.0..1.0) }
        utilityUncertainty?.let { require(it.isFinite() && it >= 0.0) }
        val confidence = listOf(confidenceLevel, confidenceLower, confidenceUpper)
        require(confidence.all { it == null } || confidence.all { it != null })
        confidenceLevel?.let { require(it.isFinite() && it in 0.0..1.0) }
        confidenceLower?.let { require(it.isFinite() && it in -1.0..1.0) }
        confidenceUpper?.let { require(it.isFinite() && it in -1.0..1.0) }
        if (confidenceLower != null && confidenceUpper != null) {
            require(confidenceLower <= confidenceUpper)
        }
        require(metricName == OBSERVED_UTILITY_METRIC_NAME)
        require(interpretationName == OBSERVED_UTILITY_INTERPRETATION_NAME)
        require(
            status == ObservedUtilityRuntimeStatus.ESTIMATED ==
                (observedUtilityDelta != null && confidenceLevel != null),
        )
        if (status == ObservedUtilityRuntimeStatus.ABSTAINED) {
            require(causalInterpretation == ObservedUtilityCausalInterpretation.NOT_CLAIMED)
            require(scalarProjectionPolicyId == null)
        } else {
            require(sourceWatermarkStatus == ObservedUtilitySourceWatermarkStatus.KNOWN)
            require(observedCohortDigest == cohortDigest)
        }
        scalarProjectionPolicyId?.let {
            require(it == fence.policyId)
            require(status == ObservedUtilityRuntimeStatus.ESTIMATED)
        }
        require(listOf(
            sampleSize,
            exposedSampleSize,
            nonExposureSampleSize,
            unknownCount,
            censoredCount,
        ).all { it >= 0 })
        require(sampleSize == exposedSampleSize + nonExposureSampleSize)
        require(evaluatedAtMs >= 0L)
        require(receiptDigest == canonicalDigest()) { "Non-canonical observed-utility receipt" }
    }
}

enum class ObservedUtilityPersistenceDisposition {
    APPLIED,
    DUPLICATE,
    CONFLICT,
    UNAVAILABLE,
}

fun interface ObservedUtilityEvaluationStore {
    /** Append receipt and update any scalar projection under the same exact fence. */
    suspend fun persistExact(
        receipt: ObservedUtilityEvaluationReceipt,
    ): ObservedUtilityPersistenceDisposition
}

fun interface ObservedUtilityAdvisoryPort {
    /** Implementations must route to the SafetyGovernor Advisory branch only. */
    suspend fun queueNegativeAssociation(
        fence: PolicyMutationFence,
        signal: PolicyAdvisoryHarmSignal,
        frozenNowMs: Long,
    ): PolicySafetyGovernorResult
}

class GovernorObservedUtilityAdvisoryPort(
    private val advisory: PolicySafetyAdvisoryRuntime,
) : ObservedUtilityAdvisoryPort {
    override suspend fun queueNegativeAssociation(
        fence: PolicyMutationFence,
        signal: PolicyAdvisoryHarmSignal,
        frozenNowMs: Long,
    ): PolicySafetyGovernorResult = advisory.queue(fence, signal, frozenNowMs)
}

enum class ObservedUtilityRuntimeAbstainReason {
    OUTCOME_WINDOW_OPEN,
    SOURCE_UNAVAILABLE,
    SOURCE_WINDOW_INCOMPLETE,
    POLICY_FENCE_CHANGED,
}

sealed interface ObservedUtilityRuntimeResult {
    data class Evaluated(
        val estimation: ObservedUtilityEstimationResult,
        val persistence: ObservedUtilityPersistenceDisposition,
        val advisory: PolicySafetyGovernorResult?,
    ) : ObservedUtilityRuntimeResult

    data class Abstained(
        val reason: ObservedUtilityRuntimeAbstainReason,
        val persistence: ObservedUtilityPersistenceDisposition,
        val receipt: ObservedUtilityEvaluationReceipt,
    ) : ObservedUtilityRuntimeResult
}

class ProductionObservedUtilityRuntime(
    private val source: DurableObservedUtilitySource,
    private val store: ObservedUtilityEvaluationStore,
    private val advisory: ObservedUtilityAdvisoryPort? = null,
) {
    suspend fun evaluate(
        request: ObservedUtilityRuntimeRequest,
        frozenNowMs: Long,
    ): ObservedUtilityRuntimeResult {
        require(frozenNowMs >= 0L)
        if (frozenNowMs < request.sourceWindowEndMs) return persistRuntimeAbstain(
            request = request,
            reason = ObservedUtilityRuntimeAbstainReason.OUTCOME_WINDOW_OPEN,
            batch = null,
            frozenNowMs = frozenNowMs,
        )
        val batch = try {
            when (val loaded = source.loadExact(request)) {
                is DurableObservedUtilityBatchResult.Ready -> loaded.batch
                DurableObservedUtilityBatchResult.Unavailable -> return persistRuntimeAbstain(
                    request = request,
                    reason = ObservedUtilityRuntimeAbstainReason.SOURCE_UNAVAILABLE,
                    batch = null,
                    frozenNowMs = frozenNowMs,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return persistRuntimeAbstain(
                request = request,
                reason = ObservedUtilityRuntimeAbstainReason.SOURCE_UNAVAILABLE,
                batch = null,
                frozenNowMs = frozenNowMs,
            )
        }
        if (!batch.complete) return persistRuntimeAbstain(
            request = request,
            reason = ObservedUtilityRuntimeAbstainReason.SOURCE_WINDOW_INCOMPLETE,
            batch = batch,
            frozenNowMs = frozenNowMs,
        )
        val observations = batch.rows.map(DurableObservedUtilityRow::toObservation)
        val estimation = if (batch.rows.any {
                observedUtilityCohortDigest(it.cohort) != request.expectedCohortDigest
            }
        ) {
            exactCohortAbstain(request, observations)
        } else {
            ObservedUtilityEstimator.estimate(request.design, observations)
        }
        if (!revalidate(request.fence)) return persistRuntimeAbstain(
            request = request,
            reason = ObservedUtilityRuntimeAbstainReason.POLICY_FENCE_CHANGED,
            batch = batch,
            frozenNowMs = frozenNowMs,
        )
        val receipt = evaluationReceipt(
            request,
            batch,
            estimation,
            frozenNowMs,
        )
        val persisted = persist(receipt)
        val advisoryResult = if (
            estimation is ObservedUtilityEstimationResult.Estimated &&
            estimation.estimate.confidenceInterval.upper < 0.0 && advisory != null &&
            persisted in setOf(
                ObservedUtilityPersistenceDisposition.APPLIED,
                ObservedUtilityPersistenceDisposition.DUPLICATE,
            )
        ) {
            advisory.queueNegativeAssociation(
                request.fence,
                PolicyAdvisoryHarmSignal(
                    source = PolicyAdvisoryHarmSource.MATCHED_COHORT_OBSERVED_UTILITY,
                    evidenceContractVersion = OBSERVED_UTILITY_RUNTIME_CONTRACT_VERSION,
                    evidenceDigest = receipt.receiptDigest,
                ),
                frozenNowMs,
            )
        } else null
        return ObservedUtilityRuntimeResult.Evaluated(estimation, persisted, advisoryResult)
    }

    private suspend fun revalidate(fence: PolicyMutationFence): Boolean = try {
        source.revalidatePolicyFence(fence)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun persistRuntimeAbstain(
        request: ObservedUtilityRuntimeRequest,
        reason: ObservedUtilityRuntimeAbstainReason,
        batch: DurableObservedUtilityBatch?,
        frozenNowMs: Long,
    ): ObservedUtilityRuntimeResult.Abstained {
        val receipt = evaluationReceipt(
            request = request,
            batch = batch,
            result = null,
            frozenNowMs = frozenNowMs,
            forcedResultCode = reason.name,
        )
        return ObservedUtilityRuntimeResult.Abstained(
            reason = reason,
            persistence = persist(receipt),
            receipt = receipt,
        )
    }

    private suspend fun persist(
        receipt: ObservedUtilityEvaluationReceipt,
    ): ObservedUtilityPersistenceDisposition = try {
        store.persistExact(receipt)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ObservedUtilityPersistenceDisposition.UNAVAILABLE
    }
}

private fun exactCohortAbstain(
    request: ObservedUtilityRuntimeRequest,
    observations: List<ObservedUtilityObservation>,
): ObservedUtilityEstimationResult.Abstained {
    val known = observations.filter {
        it.outcome == ObservedUtilityOutcome.SUCCESS || it.outcome == ObservedUtilityOutcome.FAILURE
    }
    return ObservedUtilityEstimationResult.Abstained(
        reason = ObservedUtilityAbstainReason.COHORT_MISMATCH,
        observationCount = observations.size,
        knownSampleSize = known.size,
        exposedKnownSampleSize = known.count { it.arm == ObservedUtilityArm.EXPOSED },
        nonExposureKnownSampleSize = known.count { it.arm == ObservedUtilityArm.NON_EXPOSURE },
        unknownCount = observations.count { it.outcome == ObservedUtilityOutcome.UNKNOWN },
        censoredCount = observations.count { it.outcome == ObservedUtilityOutcome.CENSORED },
        assignmentMethod = request.design.assignmentMethod,
        selectionMethod = request.design.selectionMethod,
        cohort = observations.map { it.cohort }.distinct().singleOrNull(),
    )
}

private fun evaluationReceipt(
    request: ObservedUtilityRuntimeRequest,
    batch: DurableObservedUtilityBatch?,
    result: ObservedUtilityEstimationResult?,
    frozenNowMs: Long,
    forcedResultCode: String? = null,
): ObservedUtilityEvaluationReceipt {
    val designDigest = observedUtilityDesignDigest(request.design)
    val estimate = (result as? ObservedUtilityEstimationResult.Estimated)?.estimate
    val abstained = result as? ObservedUtilityEstimationResult.Abstained
    val cohort = estimate?.cohort ?: abstained?.cohort
    val resultCode = forcedResultCode ?: abstained?.reason?.name ?: "ESTIMATED"
    val knownRows = batch?.rows.orEmpty().filter {
        it.authoritativeOutcome == PolicyAuthoritativeTerminalOutcome.SUCCESS ||
            it.authoritativeOutcome == PolicyAuthoritativeTerminalOutcome.FAILURE
    }
    val sampleSize = estimate?.sampleSize ?: abstained?.knownSampleSize ?: knownRows.size
    val exposedSize = estimate?.exposedSampleSize ?: abstained?.exposedKnownSampleSize ?:
        knownRows.count { it.arm == ObservedUtilityArm.EXPOSED }
    val controlSize = estimate?.nonExposureSampleSize ?: abstained?.nonExposureKnownSampleSize ?:
        knownRows.count { it.arm == ObservedUtilityArm.NON_EXPOSURE }
    val unknown = estimate?.unknownCount ?: abstained?.unknownCount ?: batch?.rows.orEmpty().count {
        it.authoritativeOutcome == PolicyAuthoritativeTerminalOutcome.UNKNOWN
    }
    val censored = estimate?.censoredCount ?: abstained?.censoredCount ?: batch?.rows.orEmpty().count {
        it.authoritativeOutcome == PolicyAuthoritativeTerminalOutcome.CENSORED
    }
    val uncertainty = estimate?.halfIntervalWidth()
    val sourceWatermarkStatus = if (batch == null) {
        ObservedUtilitySourceWatermarkStatus.UNKNOWN
    } else {
        ObservedUtilitySourceWatermarkStatus.KNOWN
    }
    val sourceWatermarkDigest = batch?.sourceWatermarkDigest ?: LearningCanonicalId.digest(
        domainVersion = "observed-utility-source-watermark-unknown-v1",
        fields = listOf(
            request.fence.scope.kind.name,
            request.fence.scope.storageId,
            request.fence.policyId,
            request.fence.expectedRevision.toString(),
            request.fence.expectedContentRevision.toString(),
            request.fence.expectedArtifactHash,
            designDigest,
            request.design.targetPolicySetDigest,
            request.expectedCohortDigest,
            request.sourceWindowStartMs.toString(),
            request.sourceWindowEndMs.toString(),
            resultCode,
        ),
    )
    val observedCohortDigest = cohort?.let(::observedUtilityCohortDigest) ?:
        batch?.rows.orEmpty().map { observedUtilityCohortDigest(it.cohort) }.distinct()
            .singleOrNull()
    val status = if (estimate == null || forcedResultCode != null) {
        ObservedUtilityRuntimeStatus.ABSTAINED
    } else {
        ObservedUtilityRuntimeStatus.ESTIMATED
    }
    val causalInterpretation = if (status == ObservedUtilityRuntimeStatus.ESTIMATED) {
        requireNotNull(estimate).causalInterpretation
    } else {
        ObservedUtilityCausalInterpretation.NOT_CLAIMED
    }
    val scalarProjectionPolicyId = if (
        status == ObservedUtilityRuntimeStatus.ESTIMATED &&
        request.design.attributionUnit ==
        me.rerere.rikkahub.learning.policy.ObservedUtilityAttributionUnit.INDIVIDUAL_POLICY &&
        request.design.targetPolicyId == request.fence.policyId
    ) {
        request.fence.policyId
    } else {
        null
    }
    val digest = LearningCanonicalId.digest(
        domainVersion = "observed-utility-evaluation-receipt-v1",
        fields = listOf(
            request.fence.scope.kind.name,
            request.fence.scope.storageId,
            request.fence.policyId,
            request.fence.expectedRevision.toString(),
            request.fence.expectedContentRevision.toString(),
            request.fence.expectedArtifactHash,
            designDigest,
            request.design.targetPolicySetDigest,
            request.sourceWindowStartMs.toString(),
            request.sourceWindowEndMs.toString(),
            sourceWatermarkDigest,
            sourceWatermarkStatus.name,
            request.expectedCohortDigest,
            observedCohortDigest.orEmpty(),
            status.name,
            resultCode,
            request.design.assignmentMethod.name,
            request.design.selectionMethod.name,
            OBSERVED_UTILITY_METRIC_NAME,
            OBSERVED_UTILITY_INTERPRETATION_NAME,
            estimate?.observedUtilityDelta?.toString().orEmpty(),
            uncertainty?.toString().orEmpty(),
            estimate?.confidenceInterval?.level?.toString().orEmpty(),
            estimate?.confidenceInterval?.lower?.toString().orEmpty(),
            estimate?.confidenceInterval?.upper?.toString().orEmpty(),
            causalInterpretation.name,
            scalarProjectionPolicyId.orEmpty(),
            sampleSize.toString(),
            exposedSize.toString(),
            controlSize.toString(),
            unknown.toString(),
            censored.toString(),
            frozenNowMs.toString(),
        ),
    )
    return ObservedUtilityEvaluationReceipt(
        fence = request.fence,
        contractVersion = OBSERVED_UTILITY_RUNTIME_CONTRACT_VERSION,
        designDigest = designDigest,
        targetPolicySetDigest = request.design.targetPolicySetDigest,
        sourceWindowStartMs = request.sourceWindowStartMs,
        sourceWindowEndMs = request.sourceWindowEndMs,
        sourceWatermarkDigest = sourceWatermarkDigest,
        sourceWatermarkStatus = sourceWatermarkStatus,
        cohortDigest = request.expectedCohortDigest,
        observedCohortDigest = observedCohortDigest,
        status = status,
        resultCode = resultCode,
        assignmentMethod = request.design.assignmentMethod,
        selectionMethod = request.design.selectionMethod,
        metricName = OBSERVED_UTILITY_METRIC_NAME,
        interpretationName = OBSERVED_UTILITY_INTERPRETATION_NAME,
        observedUtilityDelta = estimate?.observedUtilityDelta,
        utilityUncertainty = uncertainty,
        confidenceLevel = estimate?.confidenceInterval?.level,
        confidenceLower = estimate?.confidenceInterval?.lower,
        confidenceUpper = estimate?.confidenceInterval?.upper,
        causalInterpretation = causalInterpretation,
        scalarProjectionPolicyId = scalarProjectionPolicyId,
        sampleSize = sampleSize,
        exposedSampleSize = exposedSize,
        nonExposureSampleSize = controlSize,
        unknownCount = unknown,
        censoredCount = censored,
        evaluatedAtMs = frozenNowMs,
        receiptDigest = digest,
    )
}

private fun ObservedUtilityEstimate.halfIntervalWidth(): Double =
    (confidenceInterval.upper - confidenceInterval.lower) / 2.0

fun observedUtilityCohortDigest(
    cohort: me.rerere.rikkahub.learning.policy.ObservedUtilityCohortIdentity,
): String = LearningCanonicalId.digest(
    domainVersion = "observed-utility-cohort-receipt-v1",
    fields = listOf(
        cohort.taskSignature,
        cohort.taskSignatureVersion.toString(),
        cohort.modelIdentity,
        cohort.modelVersion,
        cohort.providerIdentity,
        cohort.providerVersion,
        cohort.providerConfigurationGeneration.toString(),
        cohort.toolsetFingerprint,
        cohort.toolSchemaVersion,
        cohort.producerModelIdentity,
        cohort.producerProviderIdentity,
        cohort.producerConfigurationIdentity,
        cohort.producerConfigurationGeneration.toString(),
        cohort.outcomeDefinitionVersion,
        cohort.outcomeWindowIdentity,
    ),
)

fun observedUtilityDesignDigest(design: ObservedUtilityDesign): String = LearningCanonicalId.digest(
    domainVersion = "observed-utility-design-receipt-v1",
    fields = listOf(
        design.targetPolicySetDigest,
        design.assignmentMethod.name,
        design.selectionMethod.name,
        design.preRegisteredDesignDigest.orEmpty(),
        design.exposureRecordingReliable.toString(),
        design.exposureContractVersion.toString(),
        design.eligibilityDeterminedBeforeTreatment.toString(),
        design.assignmentBeforeCompileOrInjection.toString(),
        design.fixedOutcomeWindow.toString(),
        design.randomizedAssignment.toString(),
        design.factorialIsolation.toString(),
        design.attributionUnit.name,
        design.targetPolicyId.orEmpty(),
    ),
)

/** Canonical append-only receipt identity; storage adapters reject any non-canonical row. */
fun ObservedUtilityEvaluationReceipt.canonicalDigest(): String = LearningCanonicalId.digest(
    domainVersion = "observed-utility-evaluation-receipt-v1",
    fields = listOf(
        fence.scope.kind.name,
        fence.scope.storageId,
        fence.policyId,
        fence.expectedRevision.toString(),
        fence.expectedContentRevision.toString(),
        fence.expectedArtifactHash,
        designDigest,
        targetPolicySetDigest,
        sourceWindowStartMs.toString(),
        sourceWindowEndMs.toString(),
        sourceWatermarkDigest,
        sourceWatermarkStatus.name,
        cohortDigest,
        observedCohortDigest.orEmpty(),
        status.name,
        resultCode,
        assignmentMethod.name,
        selectionMethod.name,
        metricName,
        interpretationName,
        observedUtilityDelta?.toString().orEmpty(),
        utilityUncertainty?.toString().orEmpty(),
        confidenceLevel?.toString().orEmpty(),
        confidenceLower?.toString().orEmpty(),
        confidenceUpper?.toString().orEmpty(),
        causalInterpretation.name,
        scalarProjectionPolicyId.orEmpty(),
        sampleSize.toString(),
        exposedSampleSize.toString(),
        nonExposureSampleSize.toString(),
        unknownCount.toString(),
        censoredCount.toString(),
        evaluatedAtMs.toString(),
    ),
)

package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.policy.OBSERVED_UTILITY_INTERPRETATION_NAME
import me.rerere.rikkahub.learning.policy.OBSERVED_UTILITY_METRIC_NAME
import me.rerere.rikkahub.learning.policy.ObservedUtilityArm
import me.rerere.rikkahub.learning.policy.ObservedUtilityAssignmentMethod
import me.rerere.rikkahub.learning.policy.ObservedUtilityAttributionUnit
import me.rerere.rikkahub.learning.policy.ObservedUtilityCausalInterpretation
import me.rerere.rikkahub.learning.policy.ObservedUtilityCohortIdentity
import me.rerere.rikkahub.learning.policy.ObservedUtilityDesign
import me.rerere.rikkahub.learning.policy.ObservedUtilityOutcome
import me.rerere.rikkahub.learning.policy.ObservedUtilitySelectionMethod
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeTerminalOutcome
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.runtime.OBSERVED_UTILITY_RUNTIME_CONTRACT_VERSION
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityEvaluationReceipt
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityOutcomeAuthority
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityOutcomeCommit
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityPreTreatmentAssignment
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilityRuntimeStatus
import me.rerere.rikkahub.learning.policy.runtime.ObservedUtilitySourceWatermarkStatus

/** Immutable, content-free assignment frozen before treatment/compile/injection. */
@Entity(
    tableName = "learning_observed_utility_assignments",
    foreignKeys = [
        ForeignKey(
            entity = LearningEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["episode_id"]),
        Index(
            value = [
                "stream_id",
                "replay_generation",
                "logical_run_id",
                "attempt_ordinal",
                "design_digest",
            ],
            unique = true,
        ),
        Index(
            value = [
                "scope_kind",
                "scope_id",
                "target_policy_id",
                "policy_set_digest",
                "design_digest",
                "cohort_digest",
                "source_window_end_ms",
                "id",
            ],
        ),
        Index(value = ["expected_exposure_id"]),
    ],
)
data class LearningObservedUtilityAssignmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "contract_version") val contractVersion: Int,
    @ColumnInfo(name = "stream_id") val streamId: String,
    @ColumnInfo(name = "replay_generation") val replayGeneration: Long,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "logical_run_id") val logicalRunId: String,
    @ColumnInfo(name = "attempt_ordinal") val attemptOrdinal: Int,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "target_policy_id") val targetPolicyId: String,
    @ColumnInfo(name = "target_policy_state_version") val targetPolicyStateVersion: Long,
    @ColumnInfo(name = "target_policy_content_revision") val targetPolicyContentRevision: Long,
    @ColumnInfo(name = "target_policy_artifact_sha256") val targetPolicyArtifactSha256: String,
    @ColumnInfo(name = "policy_set_digest") val policySetDigest: String,
    @ColumnInfo(name = "design_digest") val designDigest: String,
    @ColumnInfo(name = "cohort_digest") val cohortDigest: String,
    val arm: String,
    @ColumnInfo(name = "assignment_method") val assignmentMethod: String,
    @ColumnInfo(name = "selection_method") val selectionMethod: String,
    @ColumnInfo(name = "pre_registered_design_digest") val preRegisteredDesignDigest: String?,
    @ColumnInfo(name = "exposure_recording_reliable") val exposureRecordingReliable: Boolean,
    @ColumnInfo(name = "exposure_contract_version") val exposureContractVersion: Int,
    @ColumnInfo(name = "eligibility_before_treatment") val eligibilityBeforeTreatment: Boolean,
    @ColumnInfo(name = "assignment_before_compile_or_injection")
    val assignmentBeforeCompileOrInjection: Boolean,
    @ColumnInfo(name = "fixed_outcome_window") val fixedOutcomeWindow: Boolean,
    @ColumnInfo(name = "randomized_assignment") val randomizedAssignment: Boolean,
    @ColumnInfo(name = "factorial_isolation") val factorialIsolation: Boolean,
    @ColumnInfo(name = "attribution_unit") val attributionUnit: String,
    @ColumnInfo(name = "match_key_digest") val matchKeyDigest: String?,
    val propensity: Double?,
    @ColumnInfo(name = "expected_exposure_id") val expectedExposureId: String?,
    @ColumnInfo(name = "expected_exposure_state_version")
    val expectedExposureStateVersion: Long?,
    @ColumnInfo(name = "expected_exposure_receipt_digest")
    val expectedExposureReceiptDigest: String?,
    @ColumnInfo(name = "task_signature") val taskSignature: String,
    @ColumnInfo(name = "task_signature_version") val taskSignatureVersion: Int,
    @ColumnInfo(name = "model_identity") val modelIdentity: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "provider_identity") val providerIdentity: String,
    @ColumnInfo(name = "provider_version") val providerVersion: String,
    @ColumnInfo(name = "provider_configuration_generation")
    val providerConfigurationGeneration: Long,
    @ColumnInfo(name = "toolset_fingerprint") val toolsetFingerprint: String,
    @ColumnInfo(name = "tool_schema_version") val toolSchemaVersion: String,
    @ColumnInfo(name = "producer_model_identity") val producerModelIdentity: String,
    @ColumnInfo(name = "producer_provider_identity") val producerProviderIdentity: String,
    @ColumnInfo(name = "producer_configuration_identity")
    val producerConfigurationIdentity: String,
    @ColumnInfo(name = "producer_configuration_generation")
    val producerConfigurationGeneration: Long,
    @ColumnInfo(name = "outcome_definition_version") val outcomeDefinitionVersion: String,
    @ColumnInfo(name = "outcome_window_identity") val outcomeWindowIdentity: String,
    @ColumnInfo(name = "source_window_start_ms") val sourceWindowStartMs: Long,
    @ColumnInfo(name = "source_window_end_ms") val sourceWindowEndMs: Long,
    @ColumnInfo(name = "eligibility_determined_at_ms") val eligibilityDeterminedAtMs: Long,
    @ColumnInfo(name = "assigned_at_ms") val assignedAtMs: Long,
) {
    init {
        require(contractVersion == OBSERVED_UTILITY_RUNTIME_CONTRACT_VERSION)
        val restored = toDomain()
        require(id == restored.assignmentId) { "Observed-utility assignment ID mismatch" }
        require(designDigest == restored.designDigest) { "Observed-utility design digest mismatch" }
        require(cohortDigest == restored.cohortDigest) { "Observed-utility cohort digest mismatch" }
        require((expectedExposureStateVersion == null) == (expectedExposureReceiptDigest == null))
        require((expectedExposureId == null) == (expectedExposureStateVersion == null))
        expectedExposureStateVersion?.let { require(it >= 0L) }
        expectedExposureReceiptDigest?.let {
            requireSha256(it, "observed-utility exposure snapshot")
        }
    }

    fun toDomain(): ObservedUtilityPreTreatmentAssignment {
        val scope = requireNotNull(LearningScope.parseOrNull(scopeKind, scopeId))
        return ObservedUtilityPreTreatmentAssignment(
            streamId = Uuid.parse(streamId),
            replayGeneration = replayGeneration,
            episodeId = requireNotNull(EpisodeId.parseOrNull(episodeId)),
            logicalRunId = Uuid.parse(logicalRunId),
            attemptOrdinal = attemptOrdinal,
            fence = PolicyMutationFence(
                policyId = targetPolicyId,
                scope = scope,
                expectedRevision = targetPolicyStateVersion,
                expectedContentRevision = targetPolicyContentRevision,
                expectedArtifactHash = targetPolicyArtifactSha256,
            ),
            design = ObservedUtilityDesign(
                targetPolicySetDigest = policySetDigest,
                assignmentMethod = ObservedUtilityAssignmentMethod.valueOf(assignmentMethod),
                selectionMethod = ObservedUtilitySelectionMethod.valueOf(selectionMethod),
                preRegisteredDesignDigest = preRegisteredDesignDigest,
                exposureRecordingReliable = exposureRecordingReliable,
                exposureContractVersion = exposureContractVersion,
                eligibilityDeterminedBeforeTreatment = eligibilityBeforeTreatment,
                assignmentBeforeCompileOrInjection = assignmentBeforeCompileOrInjection,
                fixedOutcomeWindow = fixedOutcomeWindow,
                randomizedAssignment = randomizedAssignment,
                factorialIsolation = factorialIsolation,
                attributionUnit = ObservedUtilityAttributionUnit.valueOf(attributionUnit),
                targetPolicyId = targetPolicyId.takeIf {
                    attributionUnit == ObservedUtilityAttributionUnit.INDIVIDUAL_POLICY.name
                },
            ),
            cohort = ObservedUtilityCohortIdentity(
                taskSignature = taskSignature,
                taskSignatureVersion = taskSignatureVersion,
                modelIdentity = modelIdentity,
                modelVersion = modelVersion,
                providerIdentity = providerIdentity,
                providerVersion = providerVersion,
                providerConfigurationGeneration = providerConfigurationGeneration,
                toolsetFingerprint = toolsetFingerprint,
                toolSchemaVersion = toolSchemaVersion,
                producerModelIdentity = producerModelIdentity,
                producerProviderIdentity = producerProviderIdentity,
                producerConfigurationIdentity = producerConfigurationIdentity,
                producerConfigurationGeneration = producerConfigurationGeneration,
                outcomeDefinitionVersion = outcomeDefinitionVersion,
                outcomeWindowIdentity = outcomeWindowIdentity,
            ),
            arm = ObservedUtilityArm.valueOf(arm),
            matchKeyDigest = matchKeyDigest,
            propensity = propensity,
            expectedExposureId = expectedExposureId,
            sourceWindowStartMs = sourceWindowStartMs,
            sourceWindowEndMs = sourceWindowEndMs,
            eligibilityDeterminedAtMs = eligibilityDeterminedAtMs,
            assignedAtMs = assignedAtMs,
        )
    }

    override fun toString(): String =
        "LearningObservedUtilityAssignmentEntity(arm=$arm, windowEnd=$sourceWindowEndMs, " +
            "ids=<redacted>)"

    companion object {
        fun from(
            value: ObservedUtilityPreTreatmentAssignment,
            expectedExposureStateVersion: Long?,
            expectedExposureReceiptDigest: String?,
        ) =
            LearningObservedUtilityAssignmentEntity(
                id = value.assignmentId,
                contractVersion = OBSERVED_UTILITY_RUNTIME_CONTRACT_VERSION,
                streamId = value.streamId.toString(),
                replayGeneration = value.replayGeneration,
                episodeId = value.episodeId.value,
                logicalRunId = value.logicalRunId.toString(),
                attemptOrdinal = value.attemptOrdinal,
                scopeKind = value.fence.scope.kind.name,
                scopeId = value.fence.scope.storageId,
                targetPolicyId = value.fence.policyId,
                targetPolicyStateVersion = value.fence.expectedRevision,
                targetPolicyContentRevision = value.fence.expectedContentRevision,
                targetPolicyArtifactSha256 = value.fence.expectedArtifactHash,
                policySetDigest = value.design.targetPolicySetDigest,
                designDigest = value.designDigest,
                cohortDigest = value.cohortDigest,
                arm = value.arm.name,
                assignmentMethod = value.design.assignmentMethod.name,
                selectionMethod = value.design.selectionMethod.name,
                preRegisteredDesignDigest = value.design.preRegisteredDesignDigest,
                exposureRecordingReliable = value.design.exposureRecordingReliable,
                exposureContractVersion = value.design.exposureContractVersion,
                eligibilityBeforeTreatment = value.design.eligibilityDeterminedBeforeTreatment,
                assignmentBeforeCompileOrInjection =
                    value.design.assignmentBeforeCompileOrInjection,
                fixedOutcomeWindow = value.design.fixedOutcomeWindow,
                randomizedAssignment = value.design.randomizedAssignment,
                factorialIsolation = value.design.factorialIsolation,
                attributionUnit = value.design.attributionUnit.name,
                matchKeyDigest = value.matchKeyDigest,
                propensity = value.propensity,
                expectedExposureId = value.expectedExposureId,
                expectedExposureStateVersion = expectedExposureStateVersion,
                expectedExposureReceiptDigest = expectedExposureReceiptDigest,
                taskSignature = value.cohort.taskSignature,
                taskSignatureVersion = value.cohort.taskSignatureVersion,
                modelIdentity = value.cohort.modelIdentity,
                modelVersion = value.cohort.modelVersion,
                providerIdentity = value.cohort.providerIdentity,
                providerVersion = value.cohort.providerVersion,
                providerConfigurationGeneration = value.cohort.providerConfigurationGeneration,
                toolsetFingerprint = value.cohort.toolsetFingerprint,
                toolSchemaVersion = value.cohort.toolSchemaVersion,
                producerModelIdentity = value.cohort.producerModelIdentity,
                producerProviderIdentity = value.cohort.producerProviderIdentity,
                producerConfigurationIdentity = value.cohort.producerConfigurationIdentity,
                producerConfigurationGeneration = value.cohort.producerConfigurationGeneration,
                outcomeDefinitionVersion = value.cohort.outcomeDefinitionVersion,
                outcomeWindowIdentity = value.cohort.outcomeWindowIdentity,
                sourceWindowStartMs = value.sourceWindowStartMs,
                sourceWindowEndMs = value.sourceWindowEndMs,
                eligibilityDeterminedAtMs = value.eligibilityDeterminedAtMs,
                assignedAtMs = value.assignedAtMs,
            )
    }
}

/** Immutable closure for one assignment's fixed outcome window. */
@Entity(
    tableName = "learning_observed_utility_outcomes",
    foreignKeys = [
        ForeignKey(
            entity = LearningObservedUtilityAssignmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["assignment_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["outcome_receipt_digest"], unique = true)],
)
data class LearningObservedUtilityOutcomeEntity(
    @PrimaryKey
    @ColumnInfo(name = "assignment_id") val assignmentId: String,
    val outcome: String,
    @ColumnInfo(name = "authority_source_kind") val authoritySourceKind: String?,
    @ColumnInfo(name = "authority_source_id") val authoritySourceId: String?,
    @ColumnInfo(name = "authority_source_revision") val authoritySourceRevision: Long?,
    @ColumnInfo(name = "authority_evidence_digest") val authorityEvidenceDigest: String?,
    @ColumnInfo(name = "baseline_host_dispatched") val baselineHostDispatched: Boolean,
    @ColumnInfo(name = "baseline_progress_or_response")
    val baselineProgressOrResponse: Boolean,
    @ColumnInfo(name = "exposure_state_version") val exposureStateVersion: Long?,
    @ColumnInfo(name = "exposure_receipt_digest") val exposureReceiptDigest: String?,
    @ColumnInfo(name = "window_closed_at_ms") val windowClosedAtMs: Long,
    @ColumnInfo(name = "recorded_at_ms") val recordedAtMs: Long,
    @ColumnInfo(name = "outcome_receipt_digest") val outcomeReceiptDigest: String,
) {
    init {
        val authorityParts = listOf(
            authoritySourceKind,
            authoritySourceId,
            authoritySourceRevision,
            authorityEvidenceDigest,
        )
        require(authorityParts.all { it == null } || authorityParts.all { it != null })
        val authority = authoritySourceKind?.let {
            ObservedUtilityOutcomeAuthority(
                sourceKind = LearningSourceKind.valueOf(it),
                sourceId = requireNotNull(authoritySourceId),
                sourceRevision = requireNotNull(authoritySourceRevision),
            ).also { restored ->
                require(restored.evidenceDigest == authorityEvidenceDigest)
            }
        }
        ObservedUtilityOutcomeCommit(
            assignmentId = assignmentId,
            outcome = ObservedUtilityOutcome.valueOf(outcome),
            authority = authority,
            baselineHostDispatched = baselineHostDispatched,
            baselineProgressOrResponse = baselineProgressOrResponse,
            exposureStateVersion = exposureStateVersion,
            exposureReceiptDigest = exposureReceiptDigest,
            windowClosedAtMs = windowClosedAtMs,
            recordedAtMs = recordedAtMs,
        )
        require((exposureStateVersion == null) == (exposureReceiptDigest == null))
        exposureStateVersion?.let { require(it >= 0L) }
        exposureReceiptDigest?.let { requireSha256(it, "observed-utility exposure receipt") }
        requireSha256(outcomeReceiptDigest, "observed-utility outcome receipt")
        require(outcomeReceiptDigest == canonicalOutcomeReceiptDigest())
    }

    fun canonicalOutcomeReceiptDigest(): String = LearningCanonicalId.digest(
        domainVersion = "observed-utility-outcome-closure-v1",
        fields = listOf(
            assignmentId,
            outcome,
            authoritySourceKind.orEmpty(),
            authoritySourceId.orEmpty(),
            authoritySourceRevision?.toString().orEmpty(),
            authorityEvidenceDigest.orEmpty(),
            baselineHostDispatched.toString(),
            baselineProgressOrResponse.toString(),
            exposureStateVersion?.toString().orEmpty(),
            exposureReceiptDigest.orEmpty(),
            windowClosedAtMs.toString(),
            recordedAtMs.toString(),
        ),
    )

    override fun toString(): String =
        "LearningObservedUtilityOutcomeEntity(outcome=$outcome, authority=" +
            "${authoritySourceKind != null}, ids=<redacted>)"

    companion object {
        fun from(
            value: ObservedUtilityOutcomeCommit,
        ): LearningObservedUtilityOutcomeEntity {
            val digest = observedUtilityOutcomeReceiptDigest(
                assignmentId = value.assignmentId,
                outcome = value.outcome.name,
                authoritySourceKind = value.authority?.sourceKind?.name,
                authoritySourceId = value.authority?.sourceId,
                authoritySourceRevision = value.authority?.sourceRevision,
                authorityEvidenceDigest = value.authority?.evidenceDigest,
                baselineHostDispatched = value.baselineHostDispatched,
                baselineProgressOrResponse = value.baselineProgressOrResponse,
                exposureStateVersion = value.exposureStateVersion,
                exposureReceiptDigest = value.exposureReceiptDigest,
                windowClosedAtMs = value.windowClosedAtMs,
                recordedAtMs = value.recordedAtMs,
            )
            return LearningObservedUtilityOutcomeEntity(
                assignmentId = value.assignmentId,
                outcome = value.outcome.name,
                authoritySourceKind = value.authority?.sourceKind?.name,
                authoritySourceId = value.authority?.sourceId,
                authoritySourceRevision = value.authority?.sourceRevision,
                authorityEvidenceDigest = value.authority?.evidenceDigest,
                baselineHostDispatched = value.baselineHostDispatched,
                baselineProgressOrResponse = value.baselineProgressOrResponse,
                exposureStateVersion = value.exposureStateVersion,
                exposureReceiptDigest = value.exposureReceiptDigest,
                windowClosedAtMs = value.windowClosedAtMs,
                recordedAtMs = value.recordedAtMs,
                outcomeReceiptDigest = digest,
            )
        }
    }
}

/** Append-only evaluation audit. Scalars are optional projections, never causal truth. */
@Entity(
    tableName = "learning_observed_utility_evaluation_receipts",
    indices = [
        Index(
            value = [
                "scope_kind",
                "scope_id",
                "policy_id",
                "expected_state_version",
                "expected_content_revision",
                "expected_artifact_sha256",
                "design_digest",
                "cohort_digest",
                "source_window_start_ms",
                "source_window_end_ms",
            ],
        ),
        Index(value = ["evaluated_at_ms", "receipt_digest"]),
    ],
)
data class LearningObservedUtilityEvaluationReceiptEntity(
    @PrimaryKey @ColumnInfo(name = "receipt_digest") val receiptDigest: String,
    @ColumnInfo(name = "contract_version") val contractVersion: Int,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "expected_state_version") val expectedStateVersion: Long,
    @ColumnInfo(name = "expected_content_revision") val expectedContentRevision: Long,
    @ColumnInfo(name = "expected_artifact_sha256") val expectedArtifactSha256: String,
    @ColumnInfo(name = "design_digest") val designDigest: String,
    @ColumnInfo(name = "target_policy_set_digest") val targetPolicySetDigest: String,
    @ColumnInfo(name = "source_window_start_ms") val sourceWindowStartMs: Long,
    @ColumnInfo(name = "source_window_end_ms") val sourceWindowEndMs: Long,
    @ColumnInfo(name = "source_watermark_digest") val sourceWatermarkDigest: String,
    @ColumnInfo(name = "source_watermark_status") val sourceWatermarkStatus: String,
    @ColumnInfo(name = "cohort_digest") val cohortDigest: String,
    @ColumnInfo(name = "observed_cohort_digest") val observedCohortDigest: String?,
    val status: String,
    @ColumnInfo(name = "result_code") val resultCode: String,
    @ColumnInfo(name = "assignment_method") val assignmentMethod: String,
    @ColumnInfo(name = "selection_method") val selectionMethod: String,
    @ColumnInfo(name = "metric_name") val metricName: String,
    @ColumnInfo(name = "interpretation_name") val interpretationName: String,
    @ColumnInfo(name = "observed_utility_delta") val observedUtilityDelta: Double?,
    @ColumnInfo(name = "utility_uncertainty") val utilityUncertainty: Double?,
    @ColumnInfo(name = "confidence_level") val confidenceLevel: Double?,
    @ColumnInfo(name = "confidence_lower") val confidenceLower: Double?,
    @ColumnInfo(name = "confidence_upper") val confidenceUpper: Double?,
    @ColumnInfo(name = "causal_interpretation") val causalInterpretation: String,
    @ColumnInfo(name = "scalar_projection_policy_id") val scalarProjectionPolicyId: String?,
    @ColumnInfo(name = "sample_size") val sampleSize: Int,
    @ColumnInfo(name = "exposed_sample_size") val exposedSampleSize: Int,
    @ColumnInfo(name = "non_exposure_sample_size") val nonExposureSampleSize: Int,
    @ColumnInfo(name = "unknown_count") val unknownCount: Int,
    @ColumnInfo(name = "censored_count") val censoredCount: Int,
    @ColumnInfo(name = "evaluated_at_ms") val evaluatedAtMs: Long,
) {
    init {
        toDomain()
    }

    fun toDomain() = ObservedUtilityEvaluationReceipt(
        fence = PolicyMutationFence(
            policyId = policyId,
            scope = requireNotNull(LearningScope.parseOrNull(scopeKind, scopeId)),
            expectedRevision = expectedStateVersion,
            expectedContentRevision = expectedContentRevision,
            expectedArtifactHash = expectedArtifactSha256,
        ),
        contractVersion = contractVersion,
        designDigest = designDigest,
        targetPolicySetDigest = targetPolicySetDigest,
        sourceWindowStartMs = sourceWindowStartMs,
        sourceWindowEndMs = sourceWindowEndMs,
        sourceWatermarkDigest = sourceWatermarkDigest,
        sourceWatermarkStatus = ObservedUtilitySourceWatermarkStatus.valueOf(sourceWatermarkStatus),
        cohortDigest = cohortDigest,
        observedCohortDigest = observedCohortDigest,
        status = ObservedUtilityRuntimeStatus.valueOf(status),
        resultCode = resultCode,
        assignmentMethod = ObservedUtilityAssignmentMethod.valueOf(assignmentMethod),
        selectionMethod = ObservedUtilitySelectionMethod.valueOf(selectionMethod),
        metricName = metricName,
        interpretationName = interpretationName,
        observedUtilityDelta = observedUtilityDelta,
        utilityUncertainty = utilityUncertainty,
        confidenceLevel = confidenceLevel,
        confidenceLower = confidenceLower,
        confidenceUpper = confidenceUpper,
        causalInterpretation = ObservedUtilityCausalInterpretation.valueOf(causalInterpretation),
        scalarProjectionPolicyId = scalarProjectionPolicyId,
        sampleSize = sampleSize,
        exposedSampleSize = exposedSampleSize,
        nonExposureSampleSize = nonExposureSampleSize,
        unknownCount = unknownCount,
        censoredCount = censoredCount,
        evaluatedAtMs = evaluatedAtMs,
        receiptDigest = receiptDigest,
    )

    override fun toString(): String =
        "LearningObservedUtilityEvaluationReceiptEntity(status=$status, result=$resultCode, " +
            "n=$sampleSize, ids=<redacted>)"

    companion object {
        fun from(value: ObservedUtilityEvaluationReceipt) =
            LearningObservedUtilityEvaluationReceiptEntity(
                receiptDigest = value.receiptDigest,
                contractVersion = value.contractVersion,
                scopeKind = value.fence.scope.kind.name,
                scopeId = value.fence.scope.storageId,
                policyId = value.fence.policyId,
                expectedStateVersion = value.fence.expectedRevision,
                expectedContentRevision = value.fence.expectedContentRevision,
                expectedArtifactSha256 = value.fence.expectedArtifactHash,
                designDigest = value.designDigest,
                targetPolicySetDigest = value.targetPolicySetDigest,
                sourceWindowStartMs = value.sourceWindowStartMs,
                sourceWindowEndMs = value.sourceWindowEndMs,
                sourceWatermarkDigest = value.sourceWatermarkDigest,
                sourceWatermarkStatus = value.sourceWatermarkStatus.name,
                cohortDigest = value.cohortDigest,
                observedCohortDigest = value.observedCohortDigest,
                status = value.status.name,
                resultCode = value.resultCode,
                assignmentMethod = value.assignmentMethod.name,
                selectionMethod = value.selectionMethod.name,
                metricName = OBSERVED_UTILITY_METRIC_NAME,
                interpretationName = OBSERVED_UTILITY_INTERPRETATION_NAME,
                observedUtilityDelta = value.observedUtilityDelta,
                utilityUncertainty = value.utilityUncertainty,
                confidenceLevel = value.confidenceLevel,
                confidenceLower = value.confidenceLower,
                confidenceUpper = value.confidenceUpper,
                causalInterpretation = value.causalInterpretation.name,
                scalarProjectionPolicyId = value.scalarProjectionPolicyId,
                sampleSize = value.sampleSize,
                exposedSampleSize = value.exposedSampleSize,
                nonExposureSampleSize = value.nonExposureSampleSize,
                unknownCount = value.unknownCount,
                censoredCount = value.censoredCount,
                evaluatedAtMs = value.evaluatedAtMs,
            )
    }
}

internal fun LearningObservedUtilityOutcomeEntity.toAuthoritativeOutcome():
    PolicyAuthoritativeTerminalOutcome = when (ObservedUtilityOutcome.valueOf(outcome)) {
    ObservedUtilityOutcome.SUCCESS -> PolicyAuthoritativeTerminalOutcome.SUCCESS
    ObservedUtilityOutcome.FAILURE -> PolicyAuthoritativeTerminalOutcome.FAILURE
    ObservedUtilityOutcome.CENSORED -> PolicyAuthoritativeTerminalOutcome.CENSORED
    ObservedUtilityOutcome.UNKNOWN -> PolicyAuthoritativeTerminalOutcome.UNKNOWN
}

private fun observedUtilityOutcomeReceiptDigest(
    assignmentId: String,
    outcome: String,
    authoritySourceKind: String?,
    authoritySourceId: String?,
    authoritySourceRevision: Long?,
    authorityEvidenceDigest: String?,
    baselineHostDispatched: Boolean,
    baselineProgressOrResponse: Boolean,
    exposureStateVersion: Long?,
    exposureReceiptDigest: String?,
    windowClosedAtMs: Long,
    recordedAtMs: Long,
): String = LearningCanonicalId.digest(
    domainVersion = "observed-utility-outcome-closure-v1",
    fields = listOf(
        assignmentId,
        outcome,
        authoritySourceKind.orEmpty(),
        authoritySourceId.orEmpty(),
        authoritySourceRevision?.toString().orEmpty(),
        authorityEvidenceDigest.orEmpty(),
        baselineHostDispatched.toString(),
        baselineProgressOrResponse.toString(),
        exposureStateVersion?.toString().orEmpty(),
        exposureReceiptDigest.orEmpty(),
        windowClosedAtMs.toString(),
        recordedAtMs.toString(),
    ),
)

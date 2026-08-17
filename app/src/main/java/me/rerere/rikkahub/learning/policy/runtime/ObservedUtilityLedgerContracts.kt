package me.rerere.rikkahub.learning.policy.runtime

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.policy.ObservedUtilityArm
import me.rerere.rikkahub.learning.policy.ObservedUtilityAssignmentMethod
import me.rerere.rikkahub.learning.policy.ObservedUtilityAttributionUnit
import me.rerere.rikkahub.learning.policy.ObservedUtilityCohortIdentity
import me.rerere.rikkahub.learning.policy.ObservedUtilityDesign
import me.rerere.rikkahub.learning.policy.ObservedUtilityEstimationResult
import me.rerere.rikkahub.learning.policy.ObservedUtilityOutcome
import me.rerere.rikkahub.learning.policy.ObservedUtilitySelectionMethod
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.exposure.PolicyExposureMetadata
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation

private val LEDGER_SHA256 = Regex("[0-9a-f]{64}")
private val LEDGER_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")
private const val MIN_LEDGER_PROPENSITY = 0.01
private const val MAX_LEDGER_PROPENSITY = 0.99
const val MAX_OBSERVED_UTILITY_MAINTENANCE_DESIGNS = 64
private const val OBSERVED_UTILITY_COHORT_WINDOW_MS = 86_400_000L

/** Minimal provider-pipeline intent; the Room owner re-reads producer/fence fields. */
data class ObservedUtilityMatchedAssignmentIntent(
    val reservation: PolicyExposureReservation,
    val metadata: PolicyExposureMetadata,
    val primaryPolicyId: String,
    val arm: ObservedUtilityArm,
    val matchKeyDigest: String,
    val sourceWindowStartMs: Long,
    val sourceWindowEndMs: Long,
    val eligibilityDeterminedAtMs: Long,
    val assignedAtMs: Long,
) {
    init {
        require(reservation.key.policySetDigest == reservation.bundle.policySetDigest)
        require(reservation.bundle.policies.any { it.policyId == primaryPolicyId })
        require(reservation.bundle.policies.all { it.scope == metadata.scope })
        require(metadata.taskSignature.isNotEmpty())
        require(matchKeyDigest.matches(LEDGER_SHA256))
        require(sourceWindowStartMs >= 0L && sourceWindowEndMs > sourceWindowStartMs)
        require(eligibilityDeterminedAtMs in sourceWindowStartMs..assignedAtMs)
        require(assignedAtMs in sourceWindowStartMs until sourceWindowEndMs)
    }
}

fun interface ObservedUtilityMatchedAssignmentIntentPort {
    suspend fun reserveMatched(
        intent: ObservedUtilityMatchedAssignmentIntent,
    ): ObservedUtilityLedgerWriteResult
}

/** Frozen deterministic assignment; its name deliberately makes no causal claim. */
object ProductionMatchedObservedUtilityAssignmentPlanner {
    fun plan(
        reservation: PolicyExposureReservation,
        metadata: PolicyExposureMetadata,
        frozenNowMs: Long,
    ): ObservedUtilityMatchedAssignmentIntent {
        require(frozenNowMs >= 0L)
        val windowStart = frozenNowMs - frozenNowMs % OBSERVED_UTILITY_COHORT_WINDOW_MS
        val windowEnd = Math.addExact(windowStart, OBSERVED_UTILITY_COHORT_WINDOW_MS)
        val primary = reservation.bundle.policies.sortedWith(
            compareBy<me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef> { it.rank }
                .thenBy { it.policyId },
        ).first()
        val assignmentDigest = LearningCanonicalId.digest(
            domainVersion = "observed-utility-matched-assignment-arm-v1",
            fields = listOf(
                reservation.key.streamId.toString(),
                reservation.key.episodeId.value,
                reservation.key.logicalRunId.toString(),
                reservation.key.attemptOrdinal.toString(),
                reservation.bundle.policySetDigest,
                metadata.scope.kind.name,
                metadata.scope.storageId,
                metadata.taskSignature,
                metadata.modelIdentity,
                metadata.providerIdentity,
                metadata.providerGeneration.toString(),
                metadata.toolsetFingerprint,
                metadata.contextCompilerAbi,
            ),
        )
        val arm = if (assignmentDigest.last().digitToInt(16) % 2 == 0) {
            ObservedUtilityArm.EXPOSED
        } else {
            ObservedUtilityArm.NON_EXPOSURE
        }
        val matchKey = LearningCanonicalId.digest(
            domainVersion = "observed-utility-exact-matched-key-v1",
            fields = listOf(
                metadata.scope.kind.name,
                metadata.scope.storageId,
                metadata.taskSignature,
                reservation.bundle.policySetDigest,
                metadata.modelIdentity,
                metadata.providerIdentity,
                metadata.providerGeneration.toString(),
                metadata.toolsetFingerprint,
                metadata.contextCompilerAbi,
                windowStart.toString(),
                windowEnd.toString(),
            ),
        )
        return ObservedUtilityMatchedAssignmentIntent(
            reservation = reservation,
            metadata = metadata,
            primaryPolicyId = primary.policyId,
            arm = arm,
            matchKeyDigest = matchKey,
            sourceWindowStartMs = windowStart,
            sourceWindowEndMs = windowEnd,
            eligibilityDeterminedAtMs = frozenNowMs,
            assignedAtMs = frozenNowMs,
        )
    }
}

/**
 * An auditable assignment frozen before Policy compilation/injection. It stores only identities,
 * design facts and clocks; no prompt, response, tool argument/output or Policy body is accepted.
 */
data class ObservedUtilityPreTreatmentAssignment(
    val streamId: Uuid,
    val replayGeneration: Long,
    val episodeId: EpisodeId,
    val logicalRunId: Uuid,
    val attemptOrdinal: Int,
    val fence: PolicyMutationFence,
    val design: ObservedUtilityDesign,
    val cohort: ObservedUtilityCohortIdentity,
    val arm: ObservedUtilityArm,
    val matchKeyDigest: String?,
    val propensity: Double?,
    /** Deterministic reservation identity that must later be observed for EXPOSED. */
    val expectedExposureId: String?,
    /** Fixed experiment/source window shared by all assignments in one evaluation cohort. */
    val sourceWindowStartMs: Long,
    val sourceWindowEndMs: Long,
    val eligibilityDeterminedAtMs: Long,
    val assignedAtMs: Long,
) {
    init {
        require(replayGeneration >= 0L)
        require(attemptOrdinal > 0)
        require(design.targetPolicyId == null || design.targetPolicyId == fence.policyId)
        require(
            (design.attributionUnit == ObservedUtilityAttributionUnit.INDIVIDUAL_POLICY) ==
                (design.targetPolicyId != null),
        ) { "Only an exact individual-Policy design may name a target Policy" }
        require(design.exposureRecordingReliable && design.exposureContractVersion > 0) {
            "An operational utility assignment requires reliable exposure recording"
        }
        require(design.eligibilityDeterminedBeforeTreatment) {
            "Eligibility must be frozen before treatment"
        }
        if (design.assignmentMethod != ObservedUtilityAssignmentMethod.MATCHED_NON_EXPOSURE ||
            design.randomizedAssignment
        ) {
            require(design.assignmentBeforeCompileOrInjection) {
                "Holdout/propensity/randomized assignment must be frozen before compile/injection"
            }
        }
        require(design.fixedOutcomeWindow) { "Outcome/source window must be fixed" }
        require(sourceWindowStartMs >= 0L && sourceWindowEndMs > sourceWindowStartMs)
        require(eligibilityDeterminedAtMs in 0L..assignedAtMs)
        require(assignedAtMs in sourceWindowStartMs until sourceWindowEndMs)
        require(matchKeyDigest == null || matchKeyDigest.matches(LEDGER_SHA256))
        require(propensity == null || propensity.isFinite())
        require((arm == ObservedUtilityArm.EXPOSED) == (expectedExposureId != null))
        expectedExposureId?.let { require(it.matches(LEDGER_ID)) }
        when (design.assignmentMethod) {
            ObservedUtilityAssignmentMethod.MATCHED_NON_EXPOSURE -> {
                require(design.selectionMethod ==
                    ObservedUtilitySelectionMethod.EXACT_MATCHED_COHORT)
                require(matchKeyDigest != null) { "Matched assignment requires an exact match key" }
                require(propensity == null)
            }

            ObservedUtilityAssignmentMethod.EXPLICIT_HOLDOUT -> {
                require(design.selectionMethod ==
                    ObservedUtilitySelectionMethod.PRE_REGISTERED_HOLDOUT)
                require(design.preRegisteredDesignDigest != null)
                require(propensity == null)
            }

            ObservedUtilityAssignmentMethod.PROPENSITY_WEIGHTED -> {
                require(design.selectionMethod ==
                    ObservedUtilitySelectionMethod.PRE_REGISTERED_PROPENSITY)
                require(design.preRegisteredDesignDigest != null)
                require(propensity != null &&
                    propensity in MIN_LEDGER_PROPENSITY..MAX_LEDGER_PROPENSITY)
            }
        }
    }

    val designDigest: String
        get() = observedUtilityDesignDigest(design)

    val cohortDigest: String
        get() = observedUtilityCohortDigest(cohort)

    val assignmentIdentityDigest: String
        get() = LearningCanonicalId.digest(
            domainVersion = "observed-utility-pre-treatment-assignment-v1",
            fields = listOf(
                streamId.toString(),
                replayGeneration.toString(),
                episodeId.value,
                logicalRunId.toString(),
                attemptOrdinal.toString(),
                fence.scope.kind.name,
                fence.scope.storageId,
                fence.policyId,
                fence.expectedRevision.toString(),
                fence.expectedContentRevision.toString(),
                fence.expectedArtifactHash,
                designDigest,
                cohortDigest,
                arm.name,
                matchKeyDigest.orEmpty(),
                propensity?.toString().orEmpty(),
                expectedExposureId.orEmpty(),
                sourceWindowStartMs.toString(),
                sourceWindowEndMs.toString(),
                eligibilityDeterminedAtMs.toString(),
                assignedAtMs.toString(),
            ),
        )

    val assignmentId: String
        get() = "observed-utility-assignment-v1:$assignmentIdentityDigest"

    override fun toString(): String =
        "ObservedUtilityPreTreatmentAssignment(arm=$arm, scope=${fence.scope.kind}, " +
            "attempt=$attemptOrdinal, ids=<redacted>)"
}

/** Exact authority pointer; its digest is derived, never accepted as a caller assertion. */
data class ObservedUtilityOutcomeAuthority(
    val sourceKind: LearningSourceKind,
    val sourceId: String,
    val sourceRevision: Long,
) {
    init {
        require(sourceKind == LearningSourceKind.COMMAND ||
            sourceKind == LearningSourceKind.CONVERSATION_MESSAGE) {
            "Unsupported observed-utility outcome authority"
        }
        require(sourceId.matches(LEDGER_ID))
        require(sourceRevision > 0L)
    }

    val evidenceDigest: String
        get() = LearningCanonicalId.digest(
            domainVersion = "observed-utility-outcome-authority-v1",
            fields = listOf(sourceKind.name, sourceId, sourceRevision.toString()),
        )

    override fun toString(): String =
        "ObservedUtilityOutcomeAuthority(kind=$sourceKind, revision=$sourceRevision, id=<redacted>)"
}

/**
 * One immutable window closure. SUCCESS/FAILURE/CENSORED require committed terminal authority;
 * UNKNOWN deliberately has no fabricated authority and is closed only after the fixed window.
 */
data class ObservedUtilityOutcomeCommit(
    val assignmentId: String,
    val outcome: ObservedUtilityOutcome,
    val authority: ObservedUtilityOutcomeAuthority?,
    val baselineHostDispatched: Boolean,
    val baselineProgressOrResponse: Boolean,
    /** Exact exposure snapshot observed after OUTCOME_LINKED; null for NON_EXPOSURE. */
    val exposureStateVersion: Long? = null,
    val exposureReceiptDigest: String? = null,
    val windowClosedAtMs: Long,
    val recordedAtMs: Long,
) {
    init {
        require(assignmentId.matches(LEDGER_ID))
        require(outcome == ObservedUtilityOutcome.UNKNOWN || authority != null) {
            "Known/censored outcome requires terminal authority"
        }
        require((exposureStateVersion == null) == (exposureReceiptDigest == null))
        exposureStateVersion?.let { require(it >= 0L) }
        exposureReceiptDigest?.let { require(it.matches(LEDGER_SHA256)) }
        require(windowClosedAtMs >= 0L && recordedAtMs >= windowClosedAtMs)
    }

    override fun toString(): String =
        "ObservedUtilityOutcomeCommit(outcome=$outcome, authority=${authority != null}, " +
            "ids=<redacted>)"
}

enum class ObservedUtilityLedgerConflict {
    ASSIGNMENT_IDENTITY_MISMATCH,
    ASSIGNMENT_CONFLICT,
    EPISODE_NOT_FOUND,
    EPISODE_IDENTITY_MISMATCH,
    POLICY_FENCE_CHANGED,
    POLICY_NOT_ACTIVE,
    OUTCOME_CONFLICT,
    OUTCOME_WINDOW_OPEN,
    EXPOSURE_MISSING,
    EXPOSURE_IDENTITY_MISMATCH,
    EXPOSURE_NOT_OUTCOME_LINKED,
    OUTCOME_AUTHORITY_MISMATCH,
}

sealed interface ObservedUtilityLedgerWriteResult {
    data class Applied(val identity: String) : ObservedUtilityLedgerWriteResult
    data class Duplicate(val identity: String) : ObservedUtilityLedgerWriteResult
    data class Conflict(val reason: ObservedUtilityLedgerConflict) :
        ObservedUtilityLedgerWriteResult
    data object Unavailable : ObservedUtilityLedgerWriteResult
}

fun interface ObservedUtilityPreTreatmentAssignmentPort {
    suspend fun reserve(
        assignment: ObservedUtilityPreTreatmentAssignment,
    ): ObservedUtilityLedgerWriteResult
}

fun interface ObservedUtilityOutcomeCommitPort {
    suspend fun commit(outcome: ObservedUtilityOutcomeCommit): ObservedUtilityLedgerWriteResult
}

data class ObservedUtilityMaintenanceCursor(
    val sourceWindowEndMs: Long,
    val designDigest: String,
    val cohortDigest: String,
    val policyId: String,
    val representativeAssignmentId: String,
) {
    init {
        require(sourceWindowEndMs >= 0L)
        require(designDigest.isEmpty() || designDigest.matches(LEDGER_SHA256))
        require(cohortDigest.isEmpty() || cohortDigest.matches(LEDGER_SHA256))
        require(policyId.isEmpty() || policyId.matches(LEDGER_ID))
        require(representativeAssignmentId.isEmpty() || representativeAssignmentId.matches(LEDGER_ID))
        require(
            listOf(designDigest, cohortDigest, policyId, representativeAssignmentId)
                .all { it.isEmpty() } ||
                listOf(designDigest, cohortDigest, policyId, representativeAssignmentId)
                    .none { it.isEmpty() },
        )
    }

    companion object {
        val START = ObservedUtilityMaintenanceCursor(0L, "", "", "", "")
    }
}

data class ObservedUtilityMaintenanceCandidate(
    val request: ObservedUtilityRuntimeRequest,
    val representativeAssignmentId: String,
) {
    init {
        require(representativeAssignmentId.matches(LEDGER_ID))
    }

    val cursor: ObservedUtilityMaintenanceCursor = ObservedUtilityMaintenanceCursor(
        sourceWindowEndMs = request.sourceWindowEndMs,
        designDigest = observedUtilityDesignDigest(request.design),
        cohortDigest = request.expectedCohortDigest,
        policyId = request.fence.policyId,
        representativeAssignmentId = representativeAssignmentId,
    )

    /** Stable retry clock; receipt identity does not change when a maintenance job is retried. */
    val evaluatedAtMs: Long
        get() = request.sourceWindowEndMs
}

data class ObservedUtilityMaintenancePage(
    val candidates: List<ObservedUtilityMaintenanceCandidate>,
    val hasMore: Boolean,
) {
    init {
        require(candidates.size <= MAX_OBSERVED_UTILITY_MAINTENANCE_DESIGNS)
        require(!hasMore || candidates.isNotEmpty())
        require(candidates.zipWithNext().all { (left, right) ->
            compareMaintenanceCursor(left.cursor, right.cursor) < 0
        })
    }
}

fun interface ObservedUtilityMaintenanceCandidateSource {
    /** SQL/storage applies frozen-window/unprocessed filters; runtime then revalidates the fence. */
    suspend fun listDue(
        after: ObservedUtilityMaintenanceCursor,
        frozenNowMs: Long,
        limit: Int,
    ): ObservedUtilityMaintenancePage
}

sealed interface ObservedUtilityMaintenancePageResult {
    data class Processed(
        val nextCursor: ObservedUtilityMaintenanceCursor?,
        val complete: Boolean,
        val estimatedCount: Int,
        val abstainedCount: Int,
        val conflictCount: Int,
    ) : ObservedUtilityMaintenancePageResult

    /** Retry from [resumeFrom]; no later candidate in the page was consumed. */
    data class Unavailable(
        val resumeFrom: ObservedUtilityMaintenanceCursor,
    ) : ObservedUtilityMaintenancePageResult
}

/** One bounded page per invocation; scheduling/retry remains owned by the fenced Learning job. */
class ObservedUtilityMaintenanceCoordinator(
    private val candidates: ObservedUtilityMaintenanceCandidateSource,
    private val runtime: ProductionObservedUtilityRuntime,
) {
    suspend fun runPage(
        after: ObservedUtilityMaintenanceCursor,
        frozenNowMs: Long,
        limit: Int,
    ): ObservedUtilityMaintenancePageResult {
        require(frozenNowMs >= 0L)
        require(limit in 1..MAX_OBSERVED_UTILITY_MAINTENANCE_DESIGNS)
        val page = try {
            candidates.listDue(after, frozenNowMs, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ObservedUtilityMaintenancePageResult.Unavailable(after)
        }
        require(page.candidates.size <= limit)
        var committedCursor = after
        var estimated = 0
        var abstained = 0
        var conflicts = 0
        for (candidate in page.candidates) {
            require(compareMaintenanceCursor(committedCursor, candidate.cursor) < 0)
            val result = try {
                runtime.evaluate(candidate.request, candidate.evaluatedAtMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ObservedUtilityMaintenancePageResult.Unavailable(committedCursor)
            }
            val persistence = when (result) {
                is ObservedUtilityRuntimeResult.Evaluated -> {
                    when (result.estimation) {
                        is ObservedUtilityEstimationResult.Estimated -> estimated += 1
                        is ObservedUtilityEstimationResult.Abstained -> abstained += 1
                    }
                    result.persistence
                }
                is ObservedUtilityRuntimeResult.Abstained -> {
                    abstained += 1
                    result.persistence
                }
            }
            if (persistence == ObservedUtilityPersistenceDisposition.UNAVAILABLE) {
                return ObservedUtilityMaintenancePageResult.Unavailable(committedCursor)
            }
            if (persistence == ObservedUtilityPersistenceDisposition.CONFLICT) conflicts += 1
            committedCursor = candidate.cursor
        }
        val complete = !page.hasMore
        return ObservedUtilityMaintenancePageResult.Processed(
            nextCursor = if (complete) null else committedCursor,
            complete = complete,
            estimatedCount = estimated,
            abstainedCount = abstained,
            conflictCount = conflicts,
        )
    }
}

internal fun compareMaintenanceCursor(
    left: ObservedUtilityMaintenanceCursor,
    right: ObservedUtilityMaintenanceCursor,
): Int = compareValuesBy(
    left,
    right,
    ObservedUtilityMaintenanceCursor::sourceWindowEndMs,
    ObservedUtilityMaintenanceCursor::designDigest,
    ObservedUtilityMaintenanceCursor::cohortDigest,
    ObservedUtilityMaintenanceCursor::policyId,
    ObservedUtilityMaintenanceCursor::representativeAssignmentId,
)

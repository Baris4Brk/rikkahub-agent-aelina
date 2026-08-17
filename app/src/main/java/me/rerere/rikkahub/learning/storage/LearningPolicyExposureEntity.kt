package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

/**
 * Content-free P2 observation ledger for one Policy-bearing provider attempt.
 *
 * The row records only durable identities and milestones visible to the application. In
 * particular, HOST_DISPATCHED does not claim that a remote endpoint received every byte, and no
 * milestone claims that a model read or followed a Policy.
 */
@Entity(
    tableName = "learning_policy_exposures",
    foreignKeys = [
        ForeignKey(
            entity = LearningEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = [
                "stream_id",
                "episode_id",
                "logical_run_id",
                "attempt_ordinal",
                "policy_set_digest",
            ],
            unique = true,
        ),
        Index(value = ["scope_kind", "scope_id", "task_signature", "furthest_state"]),
        Index(value = ["episode_id", "logical_run_id", "attempt_ordinal"]),
        Index(value = ["scope_kind", "scope_id", "updated_at_ms", "id"]),
    ],
)
data class LearningPolicyExposureEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "stream_id")
    val streamId: String,
    @ColumnInfo(name = "replay_generation")
    val replayGeneration: Long,
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "logical_run_id")
    val logicalRunId: String,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "task_signature")
    val taskSignature: String,
    @ColumnInfo(name = "policy_set_digest")
    val policySetDigest: String,
    @ColumnInfo(name = "treatment_arm")
    val treatmentArm: String,
    @ColumnInfo(name = "model_identity")
    val modelIdentity: String,
    @ColumnInfo(name = "provider_identity")
    val providerIdentity: String,
    @ColumnInfo(name = "provider_generation")
    val providerGeneration: Long,
    @ColumnInfo(name = "toolset_fingerprint")
    val toolsetFingerprint: String,
    @ColumnInfo(name = "context_compiler_abi")
    val contextCompilerAbi: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "furthest_state")
    val furthestState: String,
    @ColumnInfo(name = "retrieved_at_ms")
    val retrievedAtMs: Long?,
    @ColumnInfo(name = "compiled_at_ms")
    val compiledAtMs: Long?,
    @ColumnInfo(name = "injected_at_ms")
    val injectedAtMs: Long?,
    @ColumnInfo(name = "host_dispatched_at_ms")
    val hostDispatchedAtMs: Long?,
    @ColumnInfo(name = "first_progress_at_ms")
    val firstProgressAtMs: Long?,
    @ColumnInfo(name = "response_finished_at_ms")
    val responseFinishedAtMs: Long?,
    @ColumnInfo(name = "outcome_linked_at_ms")
    val outcomeLinkedAtMs: Long?,
    @ColumnInfo(name = "terminal_outcome")
    val terminalOutcome: String?,
    @ColumnInfo(name = "terminal_at_ms")
    val terminalAtMs: Long?,
    @ColumnInfo(name = "outcome_source_type")
    val outcomeSourceType: String?,
    @ColumnInfo(name = "outcome_source_id")
    val outcomeSourceId: String?,
    @ColumnInfo(name = "outcome_source_revision")
    val outcomeSourceRevision: Long?,
    @ColumnInfo(name = "attribution_state")
    val attributionState: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
) {
    init {
        requireLearningStorageId(id, "Policy exposure ID")
        requireNonNilUuid(streamId, "Policy exposure stream ID")
        require(replayGeneration >= 0L) { "Negative Policy exposure replay generation" }
        requireLearningStorageId(episodeId, "Policy exposure Episode ID")
        requireNonNilUuid(logicalRunId, "Policy exposure logical run ID")
        require(attemptOrdinal > 0) { "Policy exposure attempt ordinal must be positive" }
        requireLearningScope(scopeKind, scopeId)
        requireLearningIdentity(taskSignature, "Policy exposure task signature")
        requireSha256(policySetDigest, "Policy exposure set digest")
        requireLearningCode(treatmentArm, "Policy exposure treatment arm")
        requireLearningIdentity(modelIdentity, "Policy exposure model identity")
        requireLearningIdentity(providerIdentity, "Policy exposure provider identity")
        require(providerGeneration >= 0L) { "Negative Policy exposure provider generation" }
        requireSha256(toolsetFingerprint, "Policy exposure toolset fingerprint")
        requireLearningIdentity(contextCompilerAbi, "Policy exposure context compiler ABI")
        require(stateVersion >= 0L) { "Negative Policy exposure state version" }

        val parsedFurthestState = requireNotNull(
            LearningPolicyExposureState.entries.firstOrNull { it.name == furthestState },
        ) { "Invalid Policy exposure milestone" }
        val parsedTerminal = terminalOutcome?.let { value ->
            requireNotNull(
                LearningPolicyExposureTerminalOutcome.entries.firstOrNull { it.name == value },
            ) { "Invalid Policy exposure terminal outcome" }
        }
        val parsedAttribution = requireNotNull(
            LearningPolicyExposureAttributionState.entries.firstOrNull {
                it.name == attributionState
            },
        ) { "Invalid Policy exposure attribution state" }

        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs) {
            "Invalid Policy exposure clock"
        }
        val milestoneTimes = listOf(
            LearningPolicyExposureState.RETRIEVED to retrievedAtMs,
            LearningPolicyExposureState.COMPILED to compiledAtMs,
            LearningPolicyExposureState.INJECTED to injectedAtMs,
            LearningPolicyExposureState.HOST_DISPATCHED to hostDispatchedAtMs,
            LearningPolicyExposureState.FIRST_PROGRESS to firstProgressAtMs,
            LearningPolicyExposureState.RESPONSE_FINISHED to responseFinishedAtMs,
            LearningPolicyExposureState.OUTCOME_LINKED to outcomeLinkedAtMs,
        )
        milestoneTimes.mapNotNull { it.second }.forEach { timestamp ->
            require(timestamp in createdAtMs..updatedAtMs) {
                "Policy exposure milestone is outside its durable clock"
            }
        }
        require(retrievedAtMs != null) { "Policy exposure is missing RETRIEVED" }
        require(compiledAtMs == null || compiledAtMs >= retrievedAtMs) {
            "Policy exposure COMPILED predates RETRIEVED"
        }
        require(injectedAtMs == null || compiledAtMs != null && injectedAtMs >= compiledAtMs) {
            "Policy exposure INJECTED lacks ordered COMPILED"
        }
        require(
            hostDispatchedAtMs == null ||
                injectedAtMs != null && hostDispatchedAtMs >= injectedAtMs
        ) { "Policy exposure HOST_DISPATCHED lacks ordered INJECTED" }
        require(
            firstProgressAtMs == null ||
                hostDispatchedAtMs != null && firstProgressAtMs >= hostDispatchedAtMs
        ) { "Policy exposure FIRST_PROGRESS lacks ordered HOST_DISPATCHED" }
        require(
            responseFinishedAtMs == null ||
                hostDispatchedAtMs != null && responseFinishedAtMs >= hostDispatchedAtMs
        ) { "Policy exposure RESPONSE_FINISHED lacks ordered HOST_DISPATCHED" }
        require(
            firstProgressAtMs == null || responseFinishedAtMs == null ||
                responseFinishedAtMs >= firstProgressAtMs
        ) { "Policy exposure response finished before first progress" }

        val observedMilestones = milestoneTimes.filter { it.second != null }.map { it.first }
        require(observedMilestones.maxBy { it.ordinal } == parsedFurthestState) {
            "Policy exposure furthest state disagrees with milestone timestamps"
        }

        require((parsedTerminal == null) == (terminalAtMs == null)) {
            "Policy exposure terminal outcome is incomplete"
        }
        terminalAtMs?.let { terminalAt ->
            require(injectedAtMs != null && terminalAt in injectedAtMs..updatedAtMs) {
                "Policy exposure terminal predates injection or exceeds update clock"
            }
        }
        val minimumStateVersion = (observedMilestones.size - 1).toLong() +
            if (parsedTerminal == null) 0L else 1L
        require(stateVersion >= minimumStateVersion) {
            "Policy exposure state version predates its durable milestones"
        }
        when (parsedTerminal) {
            LearningPolicyExposureTerminalOutcome.COMPLETED -> require(
                responseFinishedAtMs != null && terminalAtMs!! >= responseFinishedAtMs,
            ) { "Completed Policy exposure has no finished response" }

            LearningPolicyExposureTerminalOutcome.STALLED_RETRY -> require(
                hostDispatchedAtMs != null && responseFinishedAtMs == null,
            ) { "Stalled Policy exposure is not an unfinished dispatched attempt" }

            LearningPolicyExposureTerminalOutcome.FAILED,
            LearningPolicyExposureTerminalOutcome.CANCELLED,
            LearningPolicyExposureTerminalOutcome.STEERING_CANCELLED,
            LearningPolicyExposureTerminalOutcome.UNKNOWN,
            null,
            -> Unit
        }

        val outcomeReferenceParts = listOf(
            outcomeSourceType,
            outcomeSourceId,
            outcomeSourceRevision,
        )
        require(outcomeReferenceParts.all { it == null } || outcomeReferenceParts.all { it != null }) {
            "Policy exposure outcome source reference is incomplete"
        }
        outcomeSourceType?.let { requireLearningCode(it, "Policy exposure outcome source type") }
        outcomeSourceId?.let { requireLearningStorageId(it, "Policy exposure outcome source ID") }
        require(outcomeSourceRevision == null || outcomeSourceRevision > 0L) {
            "Invalid Policy exposure outcome source revision"
        }
        require(
            outcomeLinkedAtMs == null ||
                terminalAtMs != null && outcomeLinkedAtMs >= terminalAtMs
        ) { "Policy exposure outcome link predates its terminal fact" }
        when (parsedAttribution) {
            LearningPolicyExposureAttributionState.KNOWN -> require(
                outcomeLinkedAtMs != null && outcomeReferenceParts.all { it != null },
            ) { "Known Policy attribution lacks an authoritative outcome link" }

            LearningPolicyExposureAttributionState.UNKNOWN -> require(
                outcomeLinkedAtMs == null && outcomeReferenceParts.all { it == null },
            ) { "Unknown Policy attribution contains an authoritative outcome link" }
        }
    }

    override fun toString(): String =
        "LearningPolicyExposureEntity(attempt=$attemptOrdinal, state=$furthestState, " +
            "terminal=$terminalOutcome, attribution=$attributionState, ids=<redacted>)"
}

/** Content-free per-Policy observation within an exposure bundle. */
@Entity(
    tableName = "learning_policy_exposure_items",
    primaryKeys = ["exposure_id", "policy_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearningPolicyExposureEntity::class,
            parentColumns = ["id"],
            childColumns = ["exposure_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LearningPolicyExposureItemEntity(
    @ColumnInfo(name = "exposure_id")
    val exposureId: String,
    @ColumnInfo(name = "policy_id")
    val policyId: String,
    /** Exact [LearningPolicyEntity.contentRevision], never its lifecycle state version. */
    @ColumnInfo(name = "policy_revision")
    val policyRevision: Long,
    @ColumnInfo(name = "artifact_sha256")
    val artifactSha256: String,
    @ColumnInfo(name = "applicability_cohort_digest")
    val applicabilityCohortDigest: String,
    val rank: Int,
    @ColumnInfo(name = "estimated_tokens")
    val estimatedTokens: Int,
    @ColumnInfo(name = "drop_reason")
    val dropReason: String?,
    @ColumnInfo(name = "retrieved_at_ms")
    val retrievedAtMs: Long,
    @ColumnInfo(name = "compiled_at_ms")
    val compiledAtMs: Long?,
    @ColumnInfo(name = "injected_at_ms")
    val injectedAtMs: Long?,
) {
    init {
        requireLearningStorageId(exposureId, "Policy exposure item parent ID")
        requireLearningStorageId(policyId, "Policy exposure item Policy ID")
        require(policyRevision > 0L) { "Policy exposure item revision must be positive" }
        requireSha256(artifactSha256, "Policy exposure item artifact")
        requireSha256(applicabilityCohortDigest, "Policy exposure item applicability cohort")
        require(rank > 0) { "Policy exposure item rank must be positive" }
        require(estimatedTokens >= 0) { "Negative Policy exposure item token estimate" }
        dropReason?.let { requireLearningCode(it, "Policy exposure item drop reason") }
        require(retrievedAtMs >= 0L) { "Negative Policy exposure item retrieval clock" }
        require(compiledAtMs == null || compiledAtMs >= retrievedAtMs) {
            "Policy exposure item compilation predates retrieval"
        }
        require(injectedAtMs == null || compiledAtMs != null && injectedAtMs >= compiledAtMs) {
            "Policy exposure item injection lacks ordered compilation"
        }
        require(dropReason == null || injectedAtMs == null) {
            "Dropped Policy exposure item cannot be injected"
        }
    }

    override fun toString(): String =
        "LearningPolicyExposureItemEntity(revision=$policyRevision, rank=$rank, " +
            "tokens=$estimatedTokens, dropped=${dropReason != null}, ids=<redacted>)"
}

enum class LearningPolicyExposureState {
    RETRIEVED,
    COMPILED,
    INJECTED,
    HOST_DISPATCHED,
    FIRST_PROGRESS,
    RESPONSE_FINISHED,
    OUTCOME_LINKED,
}

enum class LearningPolicyExposureTerminalOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
    STEERING_CANCELLED,
    STALLED_RETRY,
    UNKNOWN,
}

enum class LearningPolicyExposureAttributionState {
    KNOWN,
    UNKNOWN,
}

private val NIL_LEARNING_EXPOSURE_UUID = Uuid.parse("00000000-0000-0000-0000-000000000000")

private fun requireNonNilUuid(value: String, label: String) {
    val parsed = runCatching { Uuid.parse(value) }.getOrNull()
    require(parsed != null && parsed != NIL_LEARNING_EXPOSURE_UUID) { "Invalid $label" }
}

package me.rerere.rikkahub.learning.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.learning.workflow.model.isCanonicalWorkflowSha256
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState

enum class LearnedWorkflowCandidateRevisionReason {
    CREATED,
    USER_EDITED,
    VALIDATION_STARTED,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
    SCHEMA_DRIFT,
    SOURCE_INVALIDATED,
    AUTHORITY_DRIFT,
    PROMOTION_STARTED,
    PROMOTED_DISABLED,
    REJECTED,
    ARCHIVED,
    RETENTION_EXPIRED,
}

enum class LearnedWorkflowCandidateRevisionActor {
    COMPILER,
    USER,
    VALIDATOR,
    SOURCE_RECONCILER,
    AUTHORITY_RECONCILER,
    PROMOTION_SERVICE,
    RETENTION,
}

@Entity(
    tableName = "learned_workflow_candidate_revisions",
    primaryKeys = ["candidate_id", "state_version"],
    foreignKeys = [
        ForeignKey(
            entity = LearnedWorkflowCandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["candidate_id", "created_at_ms"]),
        Index(value = ["created_at_ms", "candidate_id", "state_version"]),
    ],
)
data class LearnedWorkflowCandidateRevisionEntity(
    @ColumnInfo(name = "candidate_id") val candidateId: String,
    @ColumnInfo(name = "candidate_version") val candidateVersion: Long,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    @ColumnInfo(name = "previous_state_version") val previousStateVersion: Long?,
    val state: String,
    @ColumnInfo(name = "artifact_sha256") val artifactSha256: String,
    @ColumnInfo(name = "previous_artifact_sha256") val previousArtifactSha256: String?,
    @ColumnInfo(name = "snapshot_wire") val snapshotWire: String,
    @ColumnInfo(name = "reason_code") val reasonCode: String,
    val actor: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
) {
    init {
        require(candidateId.length in 1..128)
        require(candidateVersion > 0L && stateVersion >= candidateVersion)
        require(
            (stateVersion == 1L && previousStateVersion == null) ||
                (stateVersion > 1L && previousStateVersion == stateVersion - 1L),
        )
        require(artifactSha256.isCanonicalWorkflowSha256())
        previousArtifactSha256?.let { require(it.isCanonicalWorkflowSha256()) }
        require((stateVersion == 1L) == (previousArtifactSha256 == null))
        require(snapshotWire.toByteArray(Charsets.UTF_8).size in 2..MAX_SNAPSHOT_BYTES)
        require(LearnedWorkflowCandidateState.entries.any { it.name == state })
        require(LearnedWorkflowCandidateRevisionReason.entries.any { it.name == reasonCode })
        require(LearnedWorkflowCandidateRevisionActor.entries.any { it.name == actor })
        require(createdAtMs >= 0L)
    }

    override fun toString(): String =
        "LearnedWorkflowCandidateRevisionEntity(candidateVersion=$candidateVersion, " +
            "stateVersion=$stateVersion, reason=$reasonCode, snapshot=<redacted>, ids=<redacted>)"
}

fun LearnedWorkflowCandidateEntity.toRevisionEntity(
    previousArtifactSha256: String?,
    reason: LearnedWorkflowCandidateRevisionReason,
    actor: LearnedWorkflowCandidateRevisionActor,
): LearnedWorkflowCandidateRevisionEntity = LearnedWorkflowCandidateRevisionEntity(
    candidateId = id,
    candidateVersion = candidateVersion,
    stateVersion = stateVersion,
    previousStateVersion = stateVersion.takeIf { it > 1L }?.minus(1L),
    state = state,
    artifactSha256 = artifactSha256,
    previousArtifactSha256 = previousArtifactSha256,
    snapshotWire = JsonObject(
        linkedMapOf(
            "artifact_sha256" to JsonPrimitive(artifactSha256),
            "candidate_version" to JsonPrimitive(candidateVersion),
            "state" to JsonPrimitive(state),
            "state_version" to JsonPrimitive(stateVersion),
            "template_sha256" to JsonPrimitive(
                me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer.sha256(
                    canonicalTemplateJson,
                ),
            ),
            "verification_present" to JsonPrimitive(verificationReportWire != null),
        ),
    ).toString(),
    reasonCode = reason.name,
    actor = actor.name,
    createdAtMs = updatedAtMs,
)

private const val MAX_SNAPSHOT_BYTES = 4 * 1_024

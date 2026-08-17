package me.rerere.rikkahub.learning.storage.curator

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.rerere.rikkahub.learning.curator.isCuratorSha256
import me.rerere.rikkahub.learning.curator.isSafeCuratorId
import me.rerere.rikkahub.learning.curator.isSafeCuratorPlanId

/** Content-free lifecycle receipt; candidate and plan bodies remain only on the fenced head row. */
@Entity(
    tableName = "curator_delta_revisions",
    primaryKeys = ["candidate_id", "state_version"],
    foreignKeys = [
        ForeignKey(
            entity = CuratorDeltaCandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["candidate_id", "created_at_ms"])],
)
data class CuratorDeltaRevisionEntity(
    @ColumnInfo(name = "candidate_id") val candidateId: String,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    @ColumnInfo(name = "previous_state_version") val previousStateVersion: Long?,
    val state: String,
    @ColumnInfo(name = "candidate_sha256") val candidateSha256: String,
    @ColumnInfo(name = "apply_plan_id") val applyPlanId: String?,
    @ColumnInfo(name = "reason_code") val reasonCode: String,
    val actor: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
) {
    init {
        require(candidateId.isSafeCuratorId())
        require(stateVersion > 0L)
        require((stateVersion == 1L) == (previousStateVersion == null))
        previousStateVersion?.let { require(it == stateVersion - 1L) }
        require(CuratorDeltaStoredState.entries.any { it.name == state })
        require(candidateSha256.isCuratorSha256())
        applyPlanId?.let { require(it.isSafeCuratorPlanId()) }
        require(CuratorDeltaRevisionReason.entries.any { it.name == reasonCode })
        require(CuratorDeltaRevisionActor.entries.any { it.name == actor })
        require(createdAtMs >= 0L)
    }

    override fun toString(): String =
        "CuratorDeltaRevisionEntity(state=$state, stateVersion=$stateVersion, " +
            "reason=$reasonCode, content=<redacted>, ids=<redacted>)"
}

enum class CuratorDeltaRevisionReason {
    CREATED,
    USER_APPROVED,
    USER_REJECTED,
    APPLY_STARTED,
    APPLY_COMMITTED,
    APPLY_CONFLICT,
    ROLLBACK_STARTED,
    ROLLBACK_COMMITTED,
    ROLLBACK_CONFLICT,
    ARCHIVED,
    SOURCE_REDACTED,
}

enum class CuratorDeltaRevisionActor {
    CURATOR_MODEL,
    USER,
    APPLY_ENGINE,
    ROLLBACK_ENGINE,
    PRIVACY,
    RETENTION,
}

fun CuratorDeltaCandidateEntity.toRevisionEntity(
    reason: CuratorDeltaRevisionReason,
    actor: CuratorDeltaRevisionActor,
): CuratorDeltaRevisionEntity = CuratorDeltaRevisionEntity(
    candidateId = id,
    stateVersion = stateVersion,
    previousStateVersion = stateVersion.takeIf { it > 1L }?.minus(1L),
    state = state,
    candidateSha256 = candidateSha256,
    applyPlanId = applyPlanId,
    reasonCode = reason.name,
    actor = actor.name,
    createdAtMs = updatedAtMs,
)

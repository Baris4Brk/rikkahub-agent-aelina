package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearningRewardSignalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSignalIgnore(entity: LearningRewardSignalEntity): Long

    @Query("SELECT * FROM learning_reward_signals WHERE id = :id LIMIT 1")
    suspend fun findSignal(id: String): LearningRewardSignalEntity?

    @Query(
        "SELECT * FROM learning_reward_signals WHERE episode_id = :episodeId " +
            "ORDER BY occurred_at_ms ASC, id ASC LIMIT :limit",
    )
    suspend fun listSignalsForEpisode(
        episodeId: String,
        limit: Int,
    ): List<LearningRewardSignalEntity>

    @Query(
        "SELECT rs.* FROM learning_reward_signals rs " +
            "JOIN learning_episodes ep ON ep.id = rs.episode_id " +
            "JOIN learning_source_validity sv ON sv.stream_id = rs.stream_id " +
            "AND sv.replay_generation = rs.replay_generation " +
            "AND sv.scope_kind = rs.scope_kind AND sv.scope_id = rs.scope_id " +
            "AND sv.source_type = rs.source_type AND sv.source_id = rs.source_id " +
            "AND sv.source_revision = rs.source_revision " +
            "WHERE rs.episode_id = :episodeId AND sv.state = 'VALID' " +
            "AND ep.stream_id = rs.stream_id AND ep.replay_generation = rs.replay_generation " +
            "AND ep.scope_kind = rs.scope_kind AND ep.scope_id = rs.scope_id " +
            "AND sv.integrity_sha256 IS NOT NULL " +
            "AND sv.integrity_sha256 = rs.source_integrity_sha256 " +
            "ORDER BY rs.occurred_at_ms ASC, rs.id ASC LIMIT :limit",
    )
    suspend fun listValidSignalsForEpisode(
        episodeId: String,
        limit: Int,
    ): List<LearningRewardSignalEntity>

    @Query(
        "SELECT rs.id AS reward_signal_id, CASE WHEN ep.id IS NOT NULL " +
            "AND ep.stream_id = rs.stream_id AND ep.replay_generation = rs.replay_generation " +
            "AND ep.scope_kind = rs.scope_kind AND ep.scope_id = rs.scope_id " +
            "AND sv.source_id IS NOT NULL " +
            "AND sv.state = 'VALID' AND sv.integrity_sha256 IS NOT NULL " +
            "AND sv.integrity_sha256 = rs.source_integrity_sha256 THEN 1 ELSE 0 END " +
            "AS source_valid FROM learning_reward_signals rs " +
            "LEFT JOIN learning_episodes ep ON ep.id = rs.episode_id " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = rs.stream_id " +
            "AND sv.replay_generation = rs.replay_generation " +
            "AND sv.scope_kind = rs.scope_kind AND sv.scope_id = rs.scope_id " +
            "AND sv.source_type = rs.source_type AND sv.source_id = rs.source_id " +
            "AND sv.source_revision = rs.source_revision WHERE rs.episode_id = :episodeId " +
            "ORDER BY rs.occurred_at_ms ASC, rs.id ASC LIMIT :limit",
    )
    suspend fun listSignalValidity(
        episodeId: String,
        limit: Int,
    ): List<LearningRewardSignalValidityRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPolicyRewardEvidenceIgnore(entity: PolicyRewardEvidenceEntity): Long

    @Query(
        "SELECT * FROM policy_reward_evidence WHERE policy_id = :policyId " +
            "AND episode_id = :episodeId ORDER BY reward_signal_id ASC LIMIT :limit",
    )
    suspend fun listPolicyRewardEvidence(
        policyId: String,
        episodeId: String,
        limit: Int,
    ): List<PolicyRewardEvidenceEntity>

    @Query(
        "SELECT pre.policy_id, pre.episode_id, pre.reward_signal_id, CASE WHEN " +
            "rs.id IS NOT NULL AND rs.source_type = pre.source_type " +
            "AND rs.source_id = pre.source_id AND rs.source_revision = pre.source_revision " +
            "AND rs.source_integrity_sha256 = pre.source_integrity_sha256 " +
            "AND sv.source_id IS NOT NULL AND sv.state = 'VALID' " +
            "AND sv.integrity_sha256 IS NOT NULL " +
            "AND sv.integrity_sha256 = pre.source_integrity_sha256 THEN 1 ELSE 0 END " +
            "AS source_valid FROM policy_reward_evidence pre " +
            "LEFT JOIN learning_reward_signals rs ON rs.episode_id = pre.episode_id " +
            "AND rs.id = pre.reward_signal_id " +
            "LEFT JOIN learning_episodes ep ON ep.id = pre.episode_id " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pre.source_type AND sv.source_id = pre.source_id " +
            "AND sv.source_revision = pre.source_revision WHERE pre.policy_id = :policyId " +
            "ORDER BY pre.episode_id ASC, pre.reward_signal_id ASC LIMIT :limit",
    )
    suspend fun listPolicyRewardEvidenceValidity(
        policyId: String,
        limit: Int,
    ): List<PolicyRewardEvidenceValidityRow>

    @Query(
        "UPDATE learning_reward_windows SET revision = revision + 1, state = :state, " +
            "goal_knowledge = :goalKnowledge, goal_value = :goalValue, " +
            "goal_unknown_reason = :goalUnknownReason, " +
            "goal_evidence_sha256 = :goalEvidenceSha256, goal_signal_kind = :goalSignalKind, " +
            "process_knowledge = :processKnowledge, process_value = :processValue, " +
            "process_unknown_reason = :processUnknownReason, " +
            "process_evidence_sha256 = :processEvidenceSha256, " +
            "process_signal_kind = :processSignalKind, user_knowledge = :userKnowledge, " +
            "user_value = :userValue, user_unknown_reason = :userUnknownReason, " +
            "user_evidence_sha256 = :userEvidenceSha256, user_signal_kind = :userSignalKind, " +
            "weak_label = :weakLabel, signal_set_sha256 = :signalSetSha256, " +
            "authority_outcome = :authorityOutcome, last_signal_at_ms = :lastSignalAtMs, " +
            "closed_at_ms = :closedAtMs, updated_at_ms = :updatedAtMs WHERE id = :id " +
            "AND revision = :expectedRevision " +
            "AND (state = :state OR (state = 'OPEN' AND :state = 'CLOSED')) " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun updateRewardWindowIfCurrent(
        id: String,
        expectedRevision: Long,
        state: String,
        goalKnowledge: String,
        goalValue: Double?,
        goalUnknownReason: String?,
        goalEvidenceSha256: String?,
        goalSignalKind: String?,
        processKnowledge: String,
        processValue: Double?,
        processUnknownReason: String?,
        processEvidenceSha256: String?,
        processSignalKind: String?,
        userKnowledge: String,
        userValue: Double?,
        userUnknownReason: String?,
        userEvidenceSha256: String?,
        userSignalKind: String?,
        weakLabel: Double?,
        signalSetSha256: String,
        authorityOutcome: String,
        lastSignalAtMs: Long?,
        closedAtMs: Long?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "DELETE FROM policy_reward_evidence WHERE policy_id = :policyId " +
            "AND episode_id = :episodeId AND reward_signal_id = :rewardSignalId",
    )
    suspend fun deletePolicyRewardEvidence(
        policyId: String,
        episodeId: String,
        rewardSignalId: String,
    ): Int

    @Query(
        "DELETE FROM learning_reward_signals WHERE episode_id IN " +
            "(SELECT id FROM learning_episodes WHERE scope_kind = :scopeKind AND scope_id = :scopeId)",
    )
    suspend fun deleteSignalsByScope(scopeKind: String, scopeId: String): Int

    @Query("DELETE FROM policy_reward_evidence")
    suspend fun deleteAllPolicyRewardEvidence(): Int

    @Query("DELETE FROM learning_reward_signals")
    suspend fun deleteAllSignals(): Int

    /** Policy-linked signals are evidence capsules and are never selected by this sweep. */
    @Query(
        "DELETE FROM learning_reward_signals WHERE id IN (" +
            "SELECT rs.id FROM learning_reward_signals rs WHERE rs.created_at_ms < :createdBeforeMs " +
            "AND NOT EXISTS (SELECT 1 FROM policy_reward_evidence pre " +
            "WHERE pre.reward_signal_id = rs.id) " +
            "ORDER BY rs.created_at_ms ASC, rs.id ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredUnreferencedSignalsPage(
        createdBeforeMs: Long,
        limit: Int,
    ): Int
}

data class LearningRewardSignalValidityRow(
    @ColumnInfo(name = "reward_signal_id") val rewardSignalId: String,
    @ColumnInfo(name = "source_valid") val sourceValid: Boolean,
)

data class PolicyRewardEvidenceValidityRow(
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    @ColumnInfo(name = "reward_signal_id") val rewardSignalId: String,
    @ColumnInfo(name = "source_valid") val sourceValid: Boolean,
)

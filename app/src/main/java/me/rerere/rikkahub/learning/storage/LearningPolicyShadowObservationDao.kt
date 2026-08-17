package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearningPolicyShadowObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservationIgnore(entity: LearningPolicyShadowObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<LearningPolicyShadowObservationItemEntity>)

    @Query(
        "SELECT * FROM learning_policy_shadow_observations " +
            "WHERE request_identity = :requestIdentity LIMIT 1",
    )
    suspend fun findObservation(
        requestIdentity: String,
    ): LearningPolicyShadowObservationEntity?

    @Query(
        "SELECT * FROM learning_policy_shadow_observation_items " +
            "WHERE request_identity = :requestIdentity ORDER BY rank ASC, policy_id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 21 THEN :limit ELSE 0 END",
    )
    suspend fun listItems(
        requestIdentity: String,
        limit: Int,
    ): List<LearningPolicyShadowObservationItemEntity>

    /** Shadow recall is deliberately aggregated from Stage-D rows, never P2 exposure rows. */
    @Query(
        "SELECT COUNT(*) AS recall_count, " +
            "COALESCE(SUM(CASE WHEN i.exact_task_match = 1 THEN 1 ELSE 0 END), 0) " +
            "AS exact_task_recall_count, COALESCE(SUM(i.estimated_tokens), 0) " +
            "AS estimated_token_cost, MAX(o.observed_at_ms) AS last_observed_at_ms " +
            "FROM learning_policy_shadow_observation_items i " +
            "JOIN learning_policy_shadow_observations o " +
            "ON o.request_identity = i.request_identity WHERE i.policy_id = :policyId",
    )
    suspend fun aggregateForPolicyReview(policyId: String): PolicyReviewShadowAggregate

    /** Exact-scope privacy erase; item rows cascade in the same LearningDatabase transaction. */
    @Query(
        "DELETE FROM learning_policy_shadow_observations WHERE " +
            "scope_kind = :scopeKind AND scope_id = :scopeId",
    )
    suspend fun deleteByScope(scopeKind: String, scopeId: String): Int

    /** Bounded retention page; no Policy or request identity escapes the storage boundary. */
    @Query(
        "DELETE FROM learning_policy_shadow_observations WHERE request_identity IN " +
            "(SELECT request_identity FROM learning_policy_shadow_observations " +
            "WHERE observed_at_ms < :cutoffMs ORDER BY observed_at_ms ASC, request_identity ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END)",
    )
    suspend fun deleteExpiredPage(cutoffMs: Long, limit: Int): Int

    @Query("DELETE FROM learning_policy_shadow_observations")
    suspend fun deleteAll(): Int
}

data class PolicyReviewShadowAggregate(
    @ColumnInfo(name = "recall_count") val recallCount: Long,
    @ColumnInfo(name = "exact_task_recall_count") val exactTaskRecallCount: Long,
    @ColumnInfo(name = "estimated_token_cost") val estimatedTokenCost: Long,
    @ColumnInfo(name = "last_observed_at_ms") val lastObservedAtMs: Long?,
) {
    init {
        require(recallCount >= 0L && exactTaskRecallCount in 0L..recallCount)
        require(estimatedTokenCost >= 0L)
        require(lastObservedAtMs == null || lastObservedAtMs >= 0L)
        require((recallCount == 0L) == (lastObservedAtMs == null))
    }
}

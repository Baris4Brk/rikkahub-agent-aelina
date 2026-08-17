package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Storage-only provider operation primitives. Cross-table admission is owned by a Room transaction. */
@Dao
interface LearningProviderExecutionDao {
    @Query("SELECT MAX(configuration_generation) FROM learning_provider_config_cohorts")
    suspend fun maxConfigurationGeneration(): Long?

    @Query("SELECT MAX(updated_at_ms) FROM learning_provider_attempts")
    suspend fun maxAttemptUpdatedAtMs(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConfigCohortIgnore(entity: LearningProviderConfigCohortEntity): Long

    @Query(
        "SELECT * FROM learning_provider_config_cohorts WHERE provider_kind = :providerKind " +
            "AND provider_identity_sha256 = :providerIdentitySha256 " +
            "AND model_identity_sha256 = :modelIdentitySha256 " +
            "AND configuration_identity_sha256 = :configurationIdentitySha256 " +
            "AND configuration_generation = :configurationGeneration LIMIT 2",
    )
    suspend fun findExactConfigCohort(
        providerKind: String,
        providerIdentitySha256: String,
        modelIdentitySha256: String,
        configurationIdentitySha256: String,
        configurationGeneration: Long,
    ): List<LearningProviderConfigCohortEntity>

    @Query(
        "SELECT * FROM learning_provider_config_cohorts WHERE provider_kind = :providerKind " +
            "AND provider_identity_sha256 = :providerIdentitySha256 " +
            "AND model_identity_sha256 = :modelIdentitySha256 " +
            "AND configuration_identity_sha256 = :configurationIdentitySha256 LIMIT 2",
    )
    suspend fun findReusableConfigCohort(
        providerKind: String,
        providerIdentitySha256: String,
        modelIdentitySha256: String,
        configurationIdentitySha256: String,
    ): List<LearningProviderConfigCohortEntity>

    @Query("SELECT * FROM learning_provider_config_cohorts WHERE id = :id LIMIT 1")
    suspend fun findConfigCohort(id: String): LearningProviderConfigCohortEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJobManifestIgnore(entity: LearningProviderJobManifestEntity): Long

    @Query("SELECT * FROM learning_provider_job_manifests WHERE job_id = :jobId LIMIT 1")
    suspend fun findJobManifest(jobId: String): LearningProviderJobManifestEntity?

    @Query(
        "SELECT m.* FROM learning_provider_job_manifests m " +
            "JOIN learning_provider_config_cohorts c ON c.id = m.cohort_id " +
            "WHERE m.job_id = :jobId AND c.provider_kind = :providerKind " +
            "AND c.provider_identity_sha256 = :providerIdentitySha256 " +
            "AND c.model_identity_sha256 = :modelIdentitySha256 " +
            "AND c.configuration_identity_sha256 = :configurationIdentitySha256 " +
            "AND c.configuration_generation = :configurationGeneration LIMIT 2",
    )
    suspend fun findExactJobManifest(
        jobId: String,
        providerKind: String,
        providerIdentitySha256: String,
        modelIdentitySha256: String,
        configurationIdentitySha256: String,
        configurationGeneration: Long,
    ): List<LearningProviderJobManifestEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttemptIgnore(entity: LearningProviderAttemptEntity): Long

    @Query(
        "SELECT * FROM learning_provider_attempts WHERE job_id = :jobId " +
            "AND attempt_ordinal = :attemptOrdinal LIMIT 1",
    )
    suspend fun findAttempt(jobId: String, attemptOrdinal: Int): LearningProviderAttemptEntity?

    @Query(
        "SELECT * FROM learning_provider_attempts WHERE job_id = :jobId " +
            "AND attempt_ordinal = :attemptOrdinal AND state = :state " +
            "AND lease_process_session_id = :processSessionId AND lease_worker_id = :workerId " +
            "AND lease_generation = :leaseGeneration LIMIT 1",
    )
    suspend fun findOwnedAttempt(
        jobId: String,
        attemptOrdinal: Int,
        state: LearningProviderAttemptState,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
    ): LearningProviderAttemptEntity?

    @Query(
        "SELECT * FROM learning_provider_attempts WHERE job_id = :jobId " +
            "ORDER BY attempt_ordinal DESC LIMIT 1",
    )
    suspend fun findLatestAttempt(jobId: String): LearningProviderAttemptEntity?

    @Query(
        "SELECT * FROM learning_provider_attempts WHERE state IN " +
            "('RESERVED', 'DISPATCH_STARTED') ORDER BY created_at_ms ASC, job_id ASC, " +
            "attempt_ordinal ASC LIMIT :limit",
    )
    suspend fun listUnfinishedAttempts(limit: Int): List<LearningProviderAttemptEntity>

    @Query(
        "UPDATE learning_provider_attempts SET state = 'DISPATCH_STARTED', " +
            "dispatch_knowledge = 'POSSIBLY_DISPATCHED', dispatch_started_at_ms = :nowMs, " +
            "updated_at_ms = :nowMs WHERE job_id = :jobId AND attempt_ordinal = :attemptOrdinal " +
            "AND state = 'RESERVED' AND dispatch_knowledge = 'NOT_DISPATCHED' " +
            "AND budget_state = 'RESERVED' AND lease_process_session_id = :processSessionId " +
            "AND lease_worker_id = :workerId AND lease_generation = :leaseGeneration " +
            "AND lease_until_ms > :nowMs AND updated_at_ms <= :nowMs",
    )
    suspend fun markDispatchStartedIfReserved(
        jobId: String,
        attemptOrdinal: Int,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE learning_provider_attempts SET lease_until_ms = :newLeaseUntilMs, " +
            "updated_at_ms = :nowMs WHERE job_id = :jobId AND attempt_ordinal = :attemptOrdinal " +
            "AND state IN ('RESERVED', 'DISPATCH_STARTED') " +
            "AND lease_process_session_id = :processSessionId AND lease_worker_id = :workerId " +
            "AND lease_generation = :leaseGeneration AND lease_until_ms = :expectedLeaseUntilMs " +
            "AND lease_until_ms > :nowMs AND lease_until_ms < :newLeaseUntilMs " +
            "AND updated_at_ms <= :nowMs",
    )
    suspend fun extendReservedOrDispatchedLeaseIfOwned(
        jobId: String,
        attemptOrdinal: Int,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        expectedLeaseUntilMs: Long,
        newLeaseUntilMs: Long,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE learning_provider_attempts SET state = 'TERMINAL', " +
            "dispatch_knowledge = 'TERMINAL_OBSERVED', budget_state = 'COMMITTED', " +
            "actual_provider_calls = 1, actual_input_tokens = :actualInputTokens, " +
            "actual_output_tokens = :actualOutputTokens, actual_cost_micros = :actualCostMicros, " +
            "terminal_outcome = :terminalOutcome, terminal_observed_at_ms = :nowMs, " +
            "updated_at_ms = :nowMs, finished_at_ms = :nowMs " +
            "WHERE job_id = :jobId AND attempt_ordinal = :attemptOrdinal " +
            "AND state = 'DISPATCH_STARTED' AND dispatch_knowledge = 'POSSIBLY_DISPATCHED' " +
            "AND budget_state = 'RESERVED' AND lease_process_session_id = :processSessionId " +
            "AND lease_worker_id = :workerId AND lease_generation = :leaseGeneration " +
            "AND lease_until_ms > :nowMs AND updated_at_ms <= :nowMs",
    )
    suspend fun markTerminalIfOwned(
        jobId: String,
        attemptOrdinal: Int,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        terminalOutcome: LearningProviderTerminalOutcome,
        actualInputTokens: Long?,
        actualOutputTokens: Long?,
        actualCostMicros: Long?,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE learning_provider_attempts SET state = 'RELEASED', " +
            "budget_state = 'RELEASED', actual_provider_calls = 0, actual_input_tokens = 0, " +
            "actual_output_tokens = 0, actual_cost_micros = 0, updated_at_ms = :nowMs, " +
            "finished_at_ms = :nowMs WHERE job_id = :jobId " +
            "AND attempt_ordinal = :attemptOrdinal AND state = 'RESERVED' " +
            "AND dispatch_knowledge = 'NOT_DISPATCHED' AND budget_state = 'RESERVED' " +
            "AND lease_process_session_id = :processSessionId AND lease_worker_id = :workerId " +
            "AND lease_generation = :leaseGeneration AND lease_until_ms > :nowMs " +
            "AND updated_at_ms <= :nowMs",
    )
    suspend fun releaseUndispatchedIfOwned(
        jobId: String,
        attemptOrdinal: Int,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE learning_provider_attempts SET state = 'INDETERMINATE', " +
            "budget_state = 'INDETERMINATE', updated_at_ms = :nowMs, finished_at_ms = :nowMs " +
            "WHERE state = 'DISPATCH_STARTED' AND dispatch_knowledge = 'POSSIBLY_DISPATCHED' " +
            "AND budget_state = 'RESERVED' AND updated_at_ms <= :nowMs AND " +
            "(lease_until_ms <= :nowMs OR lease_process_session_id != :currentProcessSessionId)",
    )
    suspend fun markOrphanedDispatchesIndeterminate(
        currentProcessSessionId: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE learning_provider_attempts SET state = 'RELEASED', budget_state = 'RELEASED', " +
            "actual_provider_calls = 0, actual_input_tokens = 0, actual_output_tokens = 0, " +
            "actual_cost_micros = 0, updated_at_ms = :nowMs, finished_at_ms = :nowMs " +
            "WHERE state = 'RESERVED' AND dispatch_knowledge = 'NOT_DISPATCHED' " +
            "AND budget_state = 'RESERVED' AND updated_at_ms <= :nowMs AND " +
            "(lease_until_ms <= :nowMs OR lease_process_session_id != :currentProcessSessionId)",
    )
    suspend fun releaseOrphanedUndispatchedReservations(
        currentProcessSessionId: String,
        nowMs: Long,
    ): Int

    /** LOCAL and REMOTE have independent call/input/output/cost envelopes. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN a.actual_provider_calls > a.reserved_provider_calls " +
            "THEN a.actual_provider_calls ELSE a.reserved_provider_calls END), 0) " +
            "AS reserved_provider_calls, COALESCE(SUM(CASE WHEN a.actual_input_tokens > " +
            "a.reserved_input_tokens THEN a.actual_input_tokens ELSE a.reserved_input_tokens END), 0) " +
            "AS reserved_input_tokens, COALESCE(SUM(CASE WHEN a.actual_output_tokens > " +
            "a.reserved_output_tokens THEN a.actual_output_tokens ELSE a.reserved_output_tokens END), 0) " +
            "AS reserved_output_tokens, COALESCE(SUM(CASE WHEN a.actual_cost_micros > " +
            "a.reserved_cost_micros THEN a.actual_cost_micros ELSE a.reserved_cost_micros END), 0) " +
            "AS reserved_cost_micros FROM learning_provider_attempts a " +
            "JOIN learning_provider_job_manifests m ON m.job_id = a.job_id " +
            "JOIN learning_provider_config_cohorts c ON c.id = m.cohort_id " +
            "WHERE c.provider_kind = :providerKind " +
            "AND a.budget_window_start_ms = :windowStartMs " +
            "AND a.budget_window_end_ms = :windowEndMs AND a.budget_state IN " +
            "('RESERVED', 'COMMITTED', 'INDETERMINATE')",
    )
    suspend fun readReservedBudgetForProviderKind(
        providerKind: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): LearningProviderReservedBudgetRow

    @Query(
        "SELECT j.* FROM learning_jobs j LEFT JOIN learning_provider_job_manifests m " +
            "ON m.job_id = j.id WHERE j.job_type IN ('REFLECT_EPISODE_V1', 'DISTILL_POLICY_V1') " +
            "AND j.state IN ('PENDING', 'RETRY', 'RUNNING') AND m.job_id IS NULL " +
            "ORDER BY j.created_at_ms ASC, j.id ASC LIMIT :limit",
    )
    suspend fun listActiveProviderJobsMissingManifest(limit: Int): List<LearningJobEntity>

    @Query("DELETE FROM learning_provider_attempts")
    suspend fun deleteAllAttempts(): Int

    @Query("DELETE FROM learning_provider_job_manifests")
    suspend fun deleteAllManifests(): Int

    @Query(
        "DELETE FROM learning_provider_config_cohorts WHERE NOT EXISTS " +
            "(SELECT 1 FROM learning_provider_job_manifests m WHERE m.cohort_id = " +
            "learning_provider_config_cohorts.id)",
    )
    suspend fun deleteUnreferencedConfigCohorts(): Int

    @Query(
        "DELETE FROM learning_provider_config_cohorts WHERE id IN (" +
            "SELECT c.id FROM learning_provider_config_cohorts c WHERE NOT EXISTS " +
            "(SELECT 1 FROM learning_provider_job_manifests m WHERE m.cohort_id = c.id) " +
            "ORDER BY c.created_at_ms ASC, c.id ASC LIMIT :limit)",
    )
    suspend fun deleteUnreferencedConfigCohortsPage(limit: Int): Int
}

data class LearningProviderReservedBudgetRow(
    @androidx.room.ColumnInfo(name = "reserved_provider_calls")
    val reservedProviderCalls: Long,
    @androidx.room.ColumnInfo(name = "reserved_input_tokens")
    val reservedInputTokens: Long,
    @androidx.room.ColumnInfo(name = "reserved_output_tokens")
    val reservedOutputTokens: Long,
    @androidx.room.ColumnInfo(name = "reserved_cost_micros")
    val reservedCostMicros: Long,
)

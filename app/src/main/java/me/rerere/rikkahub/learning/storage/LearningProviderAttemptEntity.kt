package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlin.uuid.Uuid

/**
 * Durable provider side-effect ledger. A process death can leave an attempt INDETERMINATE, but it
 * can never turn "possibly dispatched" back into "not dispatched" or release its reservation.
 */
@Entity(
    tableName = "learning_provider_attempts",
    primaryKeys = ["job_id", "attempt_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = LearningProviderJobManifestEntity::class,
            parentColumns = ["job_id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["attempt_identity_sha256"], unique = true),
        Index(value = ["state", "updated_at_ms", "job_id"]),
        Index(value = ["budget_window_start_ms", "budget_window_end_ms", "budget_state"]),
        Index(value = ["lease_process_session_id", "lease_worker_id", "lease_generation"]),
    ],
)
data class LearningProviderAttemptEntity(
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo(name = "attempt_identity_sha256")
    val attemptIdentitySha256: String,
    val state: String,
    @ColumnInfo(name = "dispatch_knowledge")
    val dispatchKnowledge: String,
    @ColumnInfo(name = "budget_state")
    val budgetState: String,
    @ColumnInfo(name = "budget_authorization_sha256")
    val budgetAuthorizationSha256: String,
    @ColumnInfo(name = "budget_window_start_ms")
    val budgetWindowStartMs: Long,
    @ColumnInfo(name = "budget_window_end_ms")
    val budgetWindowEndMs: Long,
    @ColumnInfo(name = "reserved_provider_calls")
    val reservedProviderCalls: Int,
    @ColumnInfo(name = "reserved_input_tokens")
    val reservedInputTokens: Long,
    @ColumnInfo(name = "reserved_output_tokens")
    val reservedOutputTokens: Long,
    @ColumnInfo(name = "reserved_cost_micros")
    val reservedCostMicros: Long,
    @ColumnInfo(name = "actual_provider_calls")
    val actualProviderCalls: Int?,
    @ColumnInfo(name = "actual_input_tokens")
    val actualInputTokens: Long?,
    @ColumnInfo(name = "actual_output_tokens")
    val actualOutputTokens: Long?,
    @ColumnInfo(name = "actual_cost_micros")
    val actualCostMicros: Long?,
    @ColumnInfo(name = "terminal_outcome")
    val terminalOutcome: String?,
    @ColumnInfo(name = "lease_process_session_id")
    val leaseProcessSessionId: String,
    @ColumnInfo(name = "lease_worker_id")
    val leaseWorkerId: String,
    @ColumnInfo(name = "lease_generation")
    val leaseGeneration: Long,
    @ColumnInfo(name = "lease_until_ms")
    val leaseUntilMs: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "dispatch_started_at_ms")
    val dispatchStartedAtMs: Long?,
    @ColumnInfo(name = "terminal_observed_at_ms")
    val terminalObservedAtMs: Long?,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long?,
) {
    init {
        requireLearningStorageId(jobId, "provider attempt job ID")
        require(attemptOrdinal > 0) { "Provider attempt ordinal must be positive" }
        requireSha256(attemptIdentitySha256, "provider attempt identity")
        val parsedState = requireNotNull(
            LearningProviderAttemptState.entries.firstOrNull { it.name == state },
        ) { "Invalid provider attempt state" }
        val parsedKnowledge = requireNotNull(
            LearningProviderDispatchKnowledge.entries.firstOrNull { it.name == dispatchKnowledge },
        ) { "Invalid provider dispatch knowledge" }
        val parsedBudget = requireNotNull(
            LearningProviderBudgetState.entries.firstOrNull { it.name == budgetState },
        ) { "Invalid provider budget state" }
        terminalOutcome?.let { outcome ->
            require(LearningProviderTerminalOutcome.entries.any { it.name == outcome }) {
                "Invalid provider terminal outcome"
            }
        }
        requireSha256(budgetAuthorizationSha256, "provider budget authorization")
        require(budgetWindowStartMs >= 0L && budgetWindowEndMs > budgetWindowStartMs) {
            "Invalid provider budget window"
        }
        require(
            budgetWindowStartMs % PROVIDER_BUDGET_UTC_DAY_MS == 0L &&
                budgetWindowEndMs - budgetWindowStartMs == PROVIDER_BUDGET_UTC_DAY_MS
        ) { "Provider budget window is not one canonical UTC day" }
        require(reservedProviderCalls == 1) { "A provider attempt reserves exactly one call" }
        require(reservedInputTokens > 0L && reservedOutputTokens > 0L) {
            "Provider attempt token reservation must be positive"
        }
        require(reservedCostMicros >= 0L) { "Negative provider cost reservation" }
        require(actualProviderCalls == null || actualProviderCalls in 0..1) {
            "Invalid actual provider call count"
        }
        listOfNotNull(actualInputTokens, actualOutputTokens, actualCostMicros).forEach { value ->
            require(value >= 0L) { "Negative actual provider usage" }
        }
        require(isNonNilProviderUuid(leaseProcessSessionId)) {
            "Provider attempt requires a non-nil process-session owner"
        }
        require(isNonNilProviderUuid(leaseWorkerId)) {
            "Provider attempt requires a non-nil worker owner"
        }
        require(leaseGeneration > 0L) { "Provider attempt requires a positive lease generation" }
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs && leaseUntilMs > createdAtMs) {
            "Invalid provider attempt clock or lease"
        }
        require(createdAtMs >= budgetWindowStartMs && createdAtMs < budgetWindowEndMs) {
            "Provider reservation is outside its budget window"
        }
        listOfNotNull(dispatchStartedAtMs, terminalObservedAtMs, finishedAtMs).forEach { value ->
            require(value >= createdAtMs) { "Provider attempt event predates reservation" }
        }
        require(dispatchStartedAtMs == null || dispatchStartedAtMs <= updatedAtMs) {
            "Provider dispatch starts after the update clock"
        }
        require(terminalObservedAtMs == null || terminalObservedAtMs <= updatedAtMs) {
            "Provider terminal observation is after the update clock"
        }
        require(finishedAtMs == null || finishedAtMs == updatedAtMs) {
            "Provider terminal attempt must freeze its final update clock"
        }
        if (dispatchStartedAtMs != null && terminalObservedAtMs != null) {
            require(terminalObservedAtMs >= dispatchStartedAtMs) {
                "Provider terminal observation predates dispatch"
            }
        }

        when (parsedState) {
            LearningProviderAttemptState.RESERVED -> require(
                parsedKnowledge == LearningProviderDispatchKnowledge.NOT_DISPATCHED &&
                    parsedBudget == LearningProviderBudgetState.RESERVED &&
                    dispatchStartedAtMs == null && terminalObservedAtMs == null &&
                    finishedAtMs == null && terminalOutcome == null && actualProviderCalls == null &&
                    actualInputTokens == null && actualOutputTokens == null &&
                    actualCostMicros == null,
            ) { "Invalid reserved provider attempt" }

            LearningProviderAttemptState.DISPATCH_STARTED -> require(
                parsedKnowledge == LearningProviderDispatchKnowledge.POSSIBLY_DISPATCHED &&
                    parsedBudget == LearningProviderBudgetState.RESERVED &&
                    dispatchStartedAtMs != null && terminalObservedAtMs == null &&
                    finishedAtMs == null && terminalOutcome == null && actualProviderCalls == null &&
                    actualInputTokens == null && actualOutputTokens == null &&
                    actualCostMicros == null,
            ) { "Invalid dispatched provider attempt" }

            LearningProviderAttemptState.TERMINAL -> require(
                parsedKnowledge == LearningProviderDispatchKnowledge.TERMINAL_OBSERVED &&
                    parsedBudget == LearningProviderBudgetState.COMMITTED &&
                    dispatchStartedAtMs != null && terminalObservedAtMs != null &&
                    finishedAtMs != null && terminalOutcome != null && actualProviderCalls == 1,
            ) { "Invalid terminal provider attempt" }

            LearningProviderAttemptState.RELEASED -> require(
                parsedKnowledge == LearningProviderDispatchKnowledge.NOT_DISPATCHED &&
                    parsedBudget == LearningProviderBudgetState.RELEASED &&
                    dispatchStartedAtMs == null && terminalObservedAtMs == null &&
                    finishedAtMs != null && terminalOutcome == null && actualProviderCalls == 0 &&
                    actualInputTokens == 0L && actualOutputTokens == 0L &&
                    actualCostMicros == 0L,
            ) { "Invalid released provider attempt" }

            LearningProviderAttemptState.INDETERMINATE -> require(
                parsedKnowledge == LearningProviderDispatchKnowledge.POSSIBLY_DISPATCHED &&
                    parsedBudget == LearningProviderBudgetState.INDETERMINATE &&
                    dispatchStartedAtMs != null && terminalObservedAtMs == null &&
                    finishedAtMs != null && terminalOutcome == null && actualProviderCalls == null &&
                    actualInputTokens == null && actualOutputTokens == null &&
                    actualCostMicros == null,
            ) { "Invalid indeterminate provider attempt" }
        }
    }

    override fun toString(): String =
        "LearningProviderAttemptEntity(ordinal=$attemptOrdinal, state=$state, " +
            "dispatchKnowledge=$dispatchKnowledge, budgetState=$budgetState, usage=<redacted>, " +
            "identities=<redacted>)"
}

enum class LearningProviderAttemptState {
    RESERVED,
    DISPATCH_STARTED,
    TERMINAL,
    RELEASED,
    INDETERMINATE,
}

enum class LearningProviderDispatchKnowledge {
    NOT_DISPATCHED,
    POSSIBLY_DISPATCHED,
    TERMINAL_OBSERVED,
}

enum class LearningProviderBudgetState {
    RESERVED,
    COMMITTED,
    RELEASED,
    INDETERMINATE,
}

enum class LearningProviderTerminalOutcome {
    SUCCESS,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}

private const val NIL_PROVIDER_UUID = "00000000-0000-0000-0000-000000000000"
private const val PROVIDER_BUDGET_UTC_DAY_MS = 86_400_000L

private fun isNonNilProviderUuid(value: String): Boolean =
    value != NIL_PROVIDER_UUID && runCatching { Uuid.parse(value) }.isSuccess

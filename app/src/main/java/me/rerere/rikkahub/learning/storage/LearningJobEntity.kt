package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.isSafeLearningIdentifier
import kotlin.uuid.Uuid

/**
 * Durable work metadata. Inputs are recovered from typed source references at execution time; raw
 * prompts, model output, exception text and arbitrary payload JSON are forbidden here.
 */
@Entity(
    tableName = "learning_jobs",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index(value = ["state", "priority", "not_before_ms", "created_at_ms"]),
        Index(value = ["stream_id", "source_event_id"]),
        Index(value = ["lease_process_session_id", "state"]),
    ],
)
data class LearningJobEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "job_type")
    val jobType: String,
    @ColumnInfo(name = "job_schema_version")
    val jobSchemaVersion: Int,
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,
    @ColumnInfo(name = "stream_id")
    val streamId: String,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    val state: String,
    val priority: Int,
    val attempts: Int,
    @ColumnInfo(name = "max_attempts")
    val maxAttempts: Int,
    @ColumnInfo(name = "not_before_ms")
    val notBeforeMs: Long,
    @ColumnInfo(name = "lease_process_session_id")
    val leaseProcessSessionId: String?,
    @ColumnInfo(name = "lease_worker_id")
    val leaseWorkerId: String?,
    @ColumnInfo(name = "lease_generation")
    val leaseGeneration: Long,
    @ColumnInfo(name = "lease_until_ms")
    val leaseUntilMs: Long?,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long?,
    @ColumnInfo(name = "replay_generation")
    val replayGeneration: Long,
    @ColumnInfo(name = "algorithm_identity")
    val algorithmIdentity: String? = null,
    @ColumnInfo(name = "prompt_identity")
    val promptIdentity: String? = null,
    @ColumnInfo(name = "provider_kind_identity")
    val providerKindIdentity: String? = null,
    @ColumnInfo(name = "model_identity")
    val modelIdentity: String? = null,
    @ColumnInfo(name = "provider_identity")
    val providerIdentity: String? = null,
    @ColumnInfo(name = "provider_configuration_identity")
    val providerConfigurationIdentity: String? = null,
    @ColumnInfo(name = "provider_config_generation")
    val providerConfigGeneration: Long? = null,
    @ColumnInfo(name = "source_schema_identity")
    val sourceSchemaIdentity: String? = null,
    @ColumnInfo(name = "toolset_identity")
    val toolsetIdentity: String? = null,
    @ColumnInfo(name = "output_schema_identity")
    val outputSchemaIdentity: String? = null,
) {
    init {
        listOf(id, dedupeKey, sourceEventId).forEach { value ->
            require(isSafeLearningIdentifier(value, MAX_JOB_REFERENCE_ID_CHARS)) {
                "Invalid learning job identifier"
            }
        }
        require(runCatching { Uuid.parse(streamId) }.isSuccess) { "Invalid learning stream ID" }
        require(LearningScope.parseOrNull(scopeKind, scopeId) != null) {
            "Invalid learning job scope"
        }
        val parsedType = requireNotNull(LearningJobType.entries.firstOrNull { it.name == jobType }) {
            "Invalid learning job type"
        }
        require(jobSchemaVersion > 0) { "Invalid learning job schema version" }
        val parsedState = requireNotNull(LearningJobState.entries.firstOrNull { it.name == state }) {
            "Invalid learning job state"
        }
        require(lastErrorCode == null || LearningJobErrorCode.entries.any { it.name == lastErrorCode }) {
            "Invalid learning job error code"
        }
        require(attempts >= 0 && maxAttempts > 0 && attempts <= maxAttempts) {
            "Invalid learning job attempt count"
        }
        require(leaseGeneration >= 0L) { "Negative learning job lease generation" }
        listOfNotNull(leaseProcessSessionId, leaseWorkerId).forEach { value ->
            require(isSafeLearningIdentifier(value, MAX_JOB_REFERENCE_ID_CHARS)) {
                "Invalid learning job lease identifier"
            }
        }
        if (parsedState == LearningJobState.RUNNING) {
            val runningLeaseUntilMs = requireNotNull(leaseUntilMs) {
                "A running learning job requires a lease deadline"
            }
            require(
                leaseProcessSessionId != null &&
                    leaseWorkerId != null &&
                    leaseGeneration > 0L
            ) { "A running learning job requires a complete lease fence" }
            require(requireNotNull(leaseProcessSessionId).isNonNilUuid()) {
                "A running learning job requires a UUID process-session owner"
            }
            require(requireNotNull(leaseWorkerId).isNonNilUuid()) {
                "A running learning job requires a UUID worker owner"
            }
            require(runningLeaseUntilMs > updatedAtMs) {
                "Learning job lease does not advance its clock"
            }
        } else {
            require(leaseProcessSessionId == null && leaseWorkerId == null && leaseUntilMs == null) {
                "A non-running learning job cannot retain a lease owner"
            }
        }

        val isTerminal = parsedState == LearningJobState.DONE ||
            parsedState == LearningJobState.DEAD_LETTER ||
            parsedState == LearningJobState.CANCELLED
        require((finishedAtMs != null) == isTerminal) {
            "Learning job terminal timestamp does not match its state"
        }
        when (parsedState) {
            LearningJobState.PENDING,
            LearningJobState.RUNNING,
            LearningJobState.DONE -> require(lastErrorCode == null) {
                "Learning job state cannot retain an error code"
            }

            LearningJobState.RETRY,
            LearningJobState.DEAD_LETTER,
            LearningJobState.CANCELLED -> require(lastErrorCode != null) {
                "Learning job state requires a bounded error code"
            }
        }
        require(notBeforeMs >= 0L) { "Negative learning job schedule time" }
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs) {
            "Invalid learning job authority clock"
        }
        require(notBeforeMs >= createdAtMs) { "Learning job is scheduled before creation" }
        require(finishedAtMs == null || finishedAtMs >= updatedAtMs) {
            "Learning job finishes before its last update"
        }
        require(replayGeneration >= 0L) { "Negative replay generation" }
        val frozenIdentityFields = listOf(
            algorithmIdentity,
            promptIdentity,
            providerKindIdentity,
            modelIdentity,
            providerIdentity,
            providerConfigurationIdentity,
            sourceSchemaIdentity,
            toolsetIdentity,
            outputSchemaIdentity,
        )
        require(frozenIdentityFields.all { it == null } || frozenIdentityFields.all { it != null }) {
            "Learning job execution identity is incomplete"
        }
        require((providerConfigGeneration == null) == frozenIdentityFields.all { it == null }) {
            "Learning job provider generation is incomplete"
        }
        frozenIdentityFields.filterNotNull().forEach { identity ->
            require(identity.matches(SAFE_JOB_EXECUTION_IDENTITY)) {
                "Invalid frozen learning job identity"
            }
        }
        require(
            providerKindIdentity == null ||
                providerKindIdentity in setOf("none", "local_litert", "remote")
        ) { "Invalid frozen provider kind identity" }
        require(providerConfigGeneration == null || providerConfigGeneration >= 0L) {
            "Negative frozen provider config generation"
        }
        if (parsedType.requiresFrozenP1ExecutionIdentity) {
            require(frozenIdentityFields.all { it != null } && providerConfigGeneration != null) {
                "P1 learning job requires a frozen execution identity"
            }
            when (providerKindIdentity) {
                "none" -> require(
                    modelIdentity == "no-provider-model-v1" &&
                        providerIdentity == "no-provider-v1" &&
                        providerConfigurationIdentity == "no-provider-configuration-v1"
                ) { "Provider-free P1 job has a non-canonical identity" }
                "local_litert", "remote" -> require(
                    requireNotNull(modelIdentity).isLowerSha256() &&
                        requireNotNull(providerIdentity).isLowerSha256() &&
                        requireNotNull(providerConfigurationIdentity).isLowerSha256()
                ) { "Provider-backed P1 job requires exact SHA-256 identities" }
            }
        } else {
            require(frozenIdentityFields.all { it == null } && providerConfigGeneration == null) {
                "P0 structural job cannot claim a provider execution identity"
            }
        }
    }

    override fun toString(): String =
        "LearningJobEntity(type=$jobType, state=$state, attempts=$attempts/$maxAttempts, " +
            "leaseGeneration=$leaseGeneration, scope=$scopeKind, ids=<redacted>)"
}

private const val MAX_JOB_REFERENCE_ID_CHARS = 256
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"

private fun String.isNonNilUuid(): Boolean =
    this != NIL_UUID && runCatching { Uuid.parse(this) }.isSuccess

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

enum class LearningJobState {
    PENDING,
    RUNNING,
    RETRY,
    DONE,
    DEAD_LETTER,
    CANCELLED,
}

enum class LearningJobType {
    ASSEMBLE_EPISODE_SHADOW,
    RECONCILE_SOURCE,
    REFLECT_EPISODE_V1,
    CLOSE_REWARD_WINDOW_V1,
    DISTILL_POLICY_V1,
    INVALIDATE_SOURCE_V1,
}

internal val LearningJobType.requiresFrozenP1ExecutionIdentity: Boolean
    get() = when (this) {
        LearningJobType.ASSEMBLE_EPISODE_SHADOW,
        LearningJobType.RECONCILE_SOURCE,
        -> false

        LearningJobType.REFLECT_EPISODE_V1,
        LearningJobType.CLOSE_REWARD_WINDOW_V1,
        LearningJobType.DISTILL_POLICY_V1,
        LearningJobType.INVALIDATE_SOURCE_V1,
        -> true
    }

enum class LearningJobErrorCode {
    LOST_LEASE,
    LEASE_EXPIRED,
    ATTEMPTS_EXHAUSTED,
    CLOCK_ROLLBACK,
    SOURCE_MISSING,
    SOURCE_STALE,
    SOURCE_TOMBSTONED,
    WAITING_CONFIGURATION,
    DEADLINE_EXCEEDED,
    INVALID_JOB_SPEC,
    CANCELLED_BY_RESET,
    INTERNAL,
    UNKNOWN,
}

private val SAFE_JOB_EXECUTION_IDENTITY = Regex("^[a-z0-9][a-z0-9._:@/-]{0,159}$")

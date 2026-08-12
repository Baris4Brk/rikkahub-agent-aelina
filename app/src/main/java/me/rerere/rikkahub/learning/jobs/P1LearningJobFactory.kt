package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobState
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.requiresFrozenP1ExecutionIdentity

/** Immutable cohort identity captured before a P1 job becomes claimable. It never contains a key. */
data class P1LearningJobFrozenSpec(
    val jobType: LearningJobType,
    val algorithmIdentity: String,
    val promptIdentity: String,
    val providerKindIdentity: String,
    val modelIdentity: String,
    val providerIdentity: String,
    val providerConfigurationIdentity: String,
    /** Reserved cohort counter; exact current configuration is [providerConfigurationIdentity]. */
    val providerConfigGeneration: Long,
    val sourceSchemaIdentity: String,
    val toolsetIdentity: String,
    val outputSchemaIdentity: String,
) {
    init {
        require(jobType.requiresFrozenP1ExecutionIdentity) { "Not a P1 job type" }
        require(jobType !in P0_STRUCTURAL_JOB_TYPES) { "Structural source jobs use the inbox factory" }
        // Reuse the execution contract as the single syntax/length validator.
        LearningJobExecutionSpecV1(
            jobType = jobType,
            jobSchemaVersion = P1_JOB_SCHEMA_VERSION,
            algorithmIdentity = algorithmIdentity,
            promptIdentity = promptIdentity,
            providerKindIdentity = providerKindIdentity,
            modelIdentity = modelIdentity,
            providerIdentity = providerIdentity,
            providerConfigurationIdentity = providerConfigurationIdentity,
            providerConfigGeneration = providerConfigGeneration,
            sourceSchemaIdentity = sourceSchemaIdentity,
            toolsetIdentity = toolsetIdentity,
            outputSchemaIdentity = outputSchemaIdentity,
        )
    }
}

/**
 * Deterministic P1 enqueue factory. Every producer/tool/schema/config identity participates in both
 * the row and its dedupe digest; changing any one creates a new cohort instead of relabeling work.
 */
object P1LearningJobFactory {
    fun create(
        source: LearningInboxEventEntity,
        frozen: P1LearningJobFrozenSpec,
        createdAtMs: Long,
        notBeforeMs: Long = createdAtMs,
        priority: Int = 0,
        maxAttempts: Int = DEFAULT_P1_MAX_ATTEMPTS,
    ): LearningJobEntity {
        require(source.decodeState == LearningEventDecodeState.KNOWN.name) {
            "P1 job source is not a known event"
        }
        require(source.eventSchemaVersion == P1_EVENT_SCHEMA_VERSION) {
            "P1 job source schema is not exactly supported"
        }
        val scopeKind = requireNotNull(source.scopeKind)
        val scopeId = requireNotNull(source.scopeId)
        require(LearningScope.parseOrNull(scopeKind, scopeId) != null)
        require(createdAtMs >= source.createdAtMs) { "P1 job predates its source" }
        require(notBeforeMs >= createdAtMs) { "P1 job schedule predates creation" }
        require(priority in MIN_P1_PRIORITY..MAX_P1_PRIORITY) { "Unsafe P1 job priority" }
        require(maxAttempts in 1..DEFAULT_P1_MAX_ATTEMPTS) { "Unsafe P1 job attempt limit" }
        val digest = LearningCanonicalId.digest(
            domainVersion = P1_JOB_ID_DOMAIN,
            fields = listOf(
                source.streamId,
                source.eventId,
                source.eventTypeCode,
                source.eventSchemaVersion.toString(),
                source.interpretationVersion.toString(),
                source.replayGeneration.toString(),
                scopeKind,
                scopeId,
                frozen.jobType.name,
                P1_JOB_SCHEMA_VERSION.toString(),
                frozen.algorithmIdentity,
                frozen.promptIdentity,
                frozen.providerKindIdentity,
                frozen.modelIdentity,
                frozen.providerIdentity,
                frozen.providerConfigurationIdentity,
                frozen.providerConfigGeneration.toString(),
                frozen.sourceSchemaIdentity,
                frozen.toolsetIdentity,
                frozen.outputSchemaIdentity,
            ),
        )
        return LearningJobEntity(
            id = "learning-p1-job-v1:$digest",
            jobType = frozen.jobType.name,
            jobSchemaVersion = P1_JOB_SCHEMA_VERSION,
            dedupeKey = "learning-p1-job-dedupe-v1:$digest",
            streamId = source.streamId,
            sourceEventId = source.eventId,
            scopeKind = scopeKind,
            scopeId = scopeId,
            state = LearningJobState.PENDING.name,
            priority = priority,
            attempts = 0,
            maxAttempts = maxAttempts,
            notBeforeMs = notBeforeMs,
            leaseProcessSessionId = null,
            leaseWorkerId = null,
            leaseGeneration = 0L,
            leaseUntilMs = null,
            lastErrorCode = null,
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
            finishedAtMs = null,
            replayGeneration = source.replayGeneration,
            algorithmIdentity = frozen.algorithmIdentity,
            promptIdentity = frozen.promptIdentity,
            providerKindIdentity = frozen.providerKindIdentity,
            modelIdentity = frozen.modelIdentity,
            providerIdentity = frozen.providerIdentity,
            providerConfigurationIdentity = frozen.providerConfigurationIdentity,
            providerConfigGeneration = frozen.providerConfigGeneration,
            sourceSchemaIdentity = frozen.sourceSchemaIdentity,
            toolsetIdentity = frozen.toolsetIdentity,
            outputSchemaIdentity = frozen.outputSchemaIdentity,
        )
    }
}

private const val P1_JOB_SCHEMA_VERSION = 1
private const val P1_EVENT_SCHEMA_VERSION = 2
private const val P1_JOB_ID_DOMAIN = "learning-p1-job-v1"
private const val DEFAULT_P1_MAX_ATTEMPTS = 5
private const val MIN_P1_PRIORITY = -100
private const val MAX_P1_PRIORITY = 100
private val P0_STRUCTURAL_JOB_TYPES = setOf(
    LearningJobType.ASSEMBLE_EPISODE_SHADOW,
    LearningJobType.RECONCILE_SOURCE,
)

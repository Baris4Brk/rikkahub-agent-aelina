package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.storage.LearningJobEntity
import me.rerere.rikkahub.learning.storage.LearningJobType
import me.rerere.rikkahub.learning.storage.requiresFrozenP1ExecutionIdentity

/**
 * Versioned, content-free execution identity frozen by [LearningJobEntity.jobSchemaVersion].
 *
 * P0 jobs are structural handoff placeholders: they never authorize a provider call and have no
 * canonical output table. P1 must enqueue a new job schema/spec instead of silently changing any
 * identity below. Credentials are absent; only a one-way provider-configuration digest is frozen.
 */
data class LearningJobExecutionSpecV1(
    val jobType: LearningJobType,
    val jobSchemaVersion: Int,
    val algorithmIdentity: String,
    val promptIdentity: String,
    val providerKindIdentity: String,
    val modelIdentity: String,
    val providerIdentity: String,
    val providerConfigurationIdentity: String,
    /** Reserved cohort counter; zero until Settings exposes an authoritative monotonic revision. */
    val providerConfigGeneration: Long,
    val sourceSchemaIdentity: String,
    val toolsetIdentity: String,
    val outputSchemaIdentity: String,
) {
    init {
        require(jobSchemaVersion > 0) { "Invalid job schema version" }
        listOf(
            algorithmIdentity,
            promptIdentity,
            providerKindIdentity,
            modelIdentity,
            providerIdentity,
            providerConfigurationIdentity,
            sourceSchemaIdentity,
            toolsetIdentity,
            outputSchemaIdentity,
        ).forEach { identity ->
            require(identity.matches(SAFE_EXECUTION_IDENTITY)) {
                "Invalid learning execution identity"
            }
        }
        require(providerConfigGeneration >= 0L) { "Negative provider config generation" }
        require(LearningJobProviderKindIdentity.entries.any { it.wireCode == providerKindIdentity }) {
            "Invalid provider kind identity"
        }
        val providerKind = LearningJobProviderKindIdentity.entries.single {
            it.wireCode == providerKindIdentity
        }
        if (providerKind == LearningJobProviderKindIdentity.NONE) {
            require(
                modelIdentity == NO_PROVIDER_MODEL_IDENTITY &&
                    providerIdentity == NO_PROVIDER_IDENTITY &&
                    providerConfigurationIdentity == NO_PROVIDER_CONFIGURATION_IDENTITY
            ) { "A provider-free job must use the canonical NONE identity" }
        } else {
            require(modelIdentity.isLowerSha256()) { "Invalid frozen model digest" }
            require(providerIdentity.isLowerSha256()) { "Invalid frozen provider digest" }
            require(providerConfigurationIdentity.isLowerSha256()) {
                "Invalid frozen provider configuration digest"
            }
        }
    }

    override fun toString(): String =
        "LearningJobExecutionSpecV1(type=$jobType, schema=$jobSchemaVersion, identities=<opaque>)"
}

/** The only P0 execution specs. Neither has a production handler. */
object LearningJobExecutionSpecs {
    private const val P0_JOB_SCHEMA_VERSION = 1
    private const val P0_JOB_ALGORITHM_IDENTITY = "p0-structural-handoff-v1"
    private const val P0_JOB_PROMPT_IDENTITY = "no-provider-prompt-v1"
    private const val P0_JOB_SOURCE_SCHEMA_IDENTITY = "learning-outbox-event-v1"
    private const val P0_JOB_TOOLSET_IDENTITY = "authority-event-only-v1"
    private const val P0_JOB_OUTPUT_SCHEMA_IDENTITY = "no-canonical-output-v1"

    private val p0Specs = setOf(
        LearningJobType.ASSEMBLE_EPISODE_SHADOW,
        LearningJobType.RECONCILE_SOURCE,
    ).associateWith { type ->
        LearningJobExecutionSpecV1(
            jobType = type,
            jobSchemaVersion = P0_JOB_SCHEMA_VERSION,
            algorithmIdentity = P0_JOB_ALGORITHM_IDENTITY,
            promptIdentity = P0_JOB_PROMPT_IDENTITY,
            providerKindIdentity = LearningJobProviderKindIdentity.NONE.wireCode,
            modelIdentity = NO_PROVIDER_MODEL_IDENTITY,
            providerIdentity = NO_PROVIDER_IDENTITY,
            providerConfigurationIdentity = NO_PROVIDER_CONFIGURATION_IDENTITY,
            providerConfigGeneration = 0L,
            sourceSchemaIdentity = P0_JOB_SOURCE_SCHEMA_IDENTITY,
            toolsetIdentity = P0_JOB_TOOLSET_IDENTITY,
            outputSchemaIdentity = P0_JOB_OUTPUT_SCHEMA_IDENTITY,
        )
    }

    fun forNewP0Job(type: LearningJobType): LearningJobExecutionSpecV1 =
        checkNotNull(p0Specs[type])

    fun resolve(job: LearningJobEntity): LearningJobExecutionSpecV1? {
        val type = LearningJobType.entries.firstOrNull { it.name == job.jobType } ?: return null
        if (!type.requiresFrozenP1ExecutionIdentity) {
            return p0Specs[type]?.takeIf { it.jobSchemaVersion == job.jobSchemaVersion }
        }
        if (job.jobSchemaVersion != P1_JOB_SCHEMA_VERSION) return null
        return runCatching {
            LearningJobExecutionSpecV1(
                jobType = type,
                jobSchemaVersion = job.jobSchemaVersion,
                algorithmIdentity = requireNotNull(job.algorithmIdentity),
                promptIdentity = requireNotNull(job.promptIdentity),
                providerKindIdentity = requireNotNull(job.providerKindIdentity),
                modelIdentity = requireNotNull(job.modelIdentity),
                providerIdentity = requireNotNull(job.providerIdentity),
                providerConfigurationIdentity = requireNotNull(
                    job.providerConfigurationIdentity,
                ),
                providerConfigGeneration = requireNotNull(job.providerConfigGeneration),
                sourceSchemaIdentity = requireNotNull(job.sourceSchemaIdentity),
                toolsetIdentity = requireNotNull(job.toolsetIdentity),
                outputSchemaIdentity = requireNotNull(job.outputSchemaIdentity),
            )
        }.getOrNull()
    }

    private const val P1_JOB_SCHEMA_VERSION = 1
}

enum class LearningJobProviderKindIdentity(val wireCode: String) {
    NONE("none"),
    LOCAL_LITERT("local_litert"),
    REMOTE("remote"),
}

internal const val NO_PROVIDER_MODEL_IDENTITY = "no-provider-model-v1"
internal const val NO_PROVIDER_IDENTITY = "no-provider-v1"
internal const val NO_PROVIDER_CONFIGURATION_IDENTITY = "no-provider-configuration-v1"

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private val SAFE_EXECUTION_IDENTITY = Regex("^[a-z0-9][a-z0-9._:@/-]{0,159}$")

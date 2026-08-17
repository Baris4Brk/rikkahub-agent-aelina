package me.rerere.rikkahub.learning.policy

import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.learning.trace.SanitizedTraceSummary

enum class PolicyCandidateType {
    PROCEDURE,
    PREFERENCE,
    VERIFICATION,
    AVOID,
    FAILURE_MODE,
}

enum class PolicyEvidenceAuthorityOutcome {
    SUCCESS,
    FAILURE,
    UNKNOWN,
    CENSORED,
}

data class PolicyEvidenceHandle(
    val lessonId: String,
    val episodeId: EpisodeId,
    val scope: LearningScope,
    val lessonRevision: Long,
    val sourceValid: Boolean,
    val authorityOutcome: PolicyEvidenceAuthorityOutcome,
) {
    init {
        require(lessonId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
        require(lessonRevision > 0L)
    }

    override fun toString(): String =
        "PolicyEvidenceHandle(valid=$sourceValid, outcome=$authorityOutcome, " +
            "scope=${scope.kind}, ids=<redacted>)"
}

data class PolicyCandidateDraft(
    val candidateId: String,
    val scope: LearningScope,
    val taskSignature: TaskSignatureV1,
    val type: PolicyCandidateType,
    val trigger: SanitizedTraceSummary,
    val procedure: SanitizedTraceSummary,
    val verification: SanitizedTraceSummary,
    val boundary: SanitizedTraceSummary,
    val failureMode: SanitizedTraceSummary,
    val evidence: List<PolicyEvidenceHandle>,
    val applicableToolSchemas: Set<String>,
    /** Exact non-secret runtime identities this draft may later be offered to. */
    val applicableModelIdentity: String,
    val applicableProviderIdentity: String,
    /** Exact public template ABI under which this applicability claim was distilled. */
    val applicableTemplateIdentity: String,
    /** Exact public request-affecting provider/model configuration identity. */
    val applicableConfigurationIdentity: String,
    /** Stable positive public configuration generation frozen by the distillation job claim. */
    val applicableConfigurationGeneration: Long,
    /** Deterministic tool capability/authority baselines. Null means not safely derivable. */
    val applicableCapabilityDigest: String?,
    val applicableAuthorityDigest: String?,
    val inputSetHash: String,
    val artifactHash: String,
    val producerIdentity: String,
    val modelIdentity: String,
    val promptVersion: String,
    val schemaVersion: Int,
) {
    init {
        require(candidateId.matches(Regex("policy-candidate-v2:[0-9a-f]{64}")))
        require(evidence.size in 1..16 && evidence.distinctBy { it.lessonId }.size == evidence.size)
        require(applicableToolSchemas.size <= 16)
        require(applicableToolSchemas.all { it.matches(Regex("[0-9a-f]{64}")) })
        listOf(
            applicableModelIdentity,
            applicableProviderIdentity,
            applicableTemplateIdentity,
            applicableConfigurationIdentity,
        ).forEach { require(it.matches(LOWER_SHA256)) }
        require(applicableConfigurationGeneration > 0L)
        applicableCapabilityDigest?.let { require(it.matches(LOWER_SHA256)) }
        applicableAuthorityDigest?.let { require(it.matches(LOWER_SHA256)) }
        require(inputSetHash.matches(Regex("[0-9a-f]{64}")))
        require(artifactHash.matches(Regex("[0-9a-f]{64}")))
        require(producerIdentity.matches(SAFE_VERSION))
        require(modelIdentity.matches(SAFE_VERSION))
        require(promptVersion.matches(SAFE_VERSION))
        require(schemaVersion == 2)
    }

    val distinctEpisodeSupport: Int
        get() = evidence.map(PolicyEvidenceHandle::episodeId).distinct().size

    override fun toString(): String =
        "PolicyCandidateDraft(type=$type, support=$distinctEpisodeSupport, " +
            "scope=${scope.kind}, text=<redacted>, ids=<redacted>)"

    companion object {
        internal val SAFE_VERSION = Regex("[a-z0-9][a-z0-9._-]{0,95}")
    }
}

object PolicyCandidateIdFactory {
    fun inputSetHash(evidence: List<PolicyEvidenceHandle>): String = LearningCanonicalId.digest(
        domainVersion = "policy-input-set-v1",
        fields = evidence.sortedWith(compareBy({ it.episodeId.value }, { it.lessonId })).flatMap {
            listOf(
                it.episodeId.value,
                it.lessonId,
                it.lessonRevision.toString(),
                it.authorityOutcome.name,
            )
        },
    )

    fun candidateId(
        scope: LearningScope,
        taskSignature: TaskSignatureV1,
        inputSetHash: String,
        producerIdentity: String,
        modelIdentity: String,
        promptVersion: String,
        schemaVersion: Int,
        applicability: PolicyCandidateApplicabilityIdentity,
    ): String = "policy-candidate-v2:" + LearningCanonicalId.digest(
        domainVersion = "policy-candidate-v2",
        fields = listOf(
            scope.kind.name,
            scope.storageId,
            taskSignature.value,
            inputSetHash,
            producerIdentity,
            modelIdentity,
            promptVersion,
            schemaVersion.toString(),
            applicability.modelIdentity,
            applicability.providerIdentity,
            applicability.templateIdentity,
            applicability.configurationIdentity,
            applicability.configurationGeneration.toString(),
            applicability.capabilityDigest.orEmpty(),
            applicability.authorityDigest.orEmpty(),
            *applicability.toolSchemaFingerprints.sorted().toTypedArray(),
        ),
    )
}

/** Canonical non-secret applicability/cohort identity shared by parser, validator and committer. */
data class PolicyCandidateApplicabilityIdentity(
    val toolSchemaFingerprints: Set<String>,
    val modelIdentity: String,
    val providerIdentity: String,
    val templateIdentity: String,
    val configurationIdentity: String,
    val configurationGeneration: Long,
    val capabilityDigest: String?,
    val authorityDigest: String?,
) {
    init {
        require(toolSchemaFingerprints.size <= 16)
        require(toolSchemaFingerprints.all(LOWER_SHA256::matches))
        listOf(modelIdentity, providerIdentity, templateIdentity, configurationIdentity).forEach {
            require(it.matches(LOWER_SHA256))
        }
        require(configurationGeneration > 0L)
        capabilityDigest?.let { require(it.matches(LOWER_SHA256)) }
        authorityDigest?.let { require(it.matches(LOWER_SHA256)) }
    }
}

val PolicyCandidateDraft.applicabilityIdentity: PolicyCandidateApplicabilityIdentity
    get() = PolicyCandidateApplicabilityIdentity(
        toolSchemaFingerprints = applicableToolSchemas,
        modelIdentity = applicableModelIdentity,
        providerIdentity = applicableProviderIdentity,
        templateIdentity = applicableTemplateIdentity,
        configurationIdentity = applicableConfigurationIdentity,
        configurationGeneration = applicableConfigurationGeneration,
        capabilityDigest = applicableCapabilityDigest,
        authorityDigest = applicableAuthorityDigest,
    )

private val LOWER_SHA256 = Regex("[0-9a-f]{64}")

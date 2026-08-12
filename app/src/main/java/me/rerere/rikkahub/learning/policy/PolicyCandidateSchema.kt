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
    val inputSetHash: String,
    val artifactHash: String,
    val producerIdentity: String,
    val modelIdentity: String,
    val promptVersion: String,
    val schemaVersion: Int,
) {
    init {
        require(candidateId.matches(Regex("policy-candidate-v1:[0-9a-f]{64}")))
        require(evidence.size in 1..16 && evidence.distinctBy { it.lessonId }.size == evidence.size)
        require(applicableToolSchemas.size <= 16)
        require(applicableToolSchemas.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(inputSetHash.matches(Regex("[0-9a-f]{64}")))
        require(artifactHash.matches(Regex("[0-9a-f]{64}")))
        require(producerIdentity.matches(SAFE_VERSION))
        require(modelIdentity.matches(SAFE_VERSION))
        require(promptVersion.matches(SAFE_VERSION))
        require(schemaVersion == 1)
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
    ): String = "policy-candidate-v1:" + LearningCanonicalId.digest(
        domainVersion = "policy-candidate-v1",
        fields = listOf(
            scope.kind.name,
            scope.storageId,
            taskSignature.value,
            inputSetHash,
            producerIdentity,
            modelIdentity,
            promptVersion,
            schemaVersion.toString(),
        ),
    )
}

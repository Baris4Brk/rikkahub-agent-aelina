package me.rerere.rikkahub.learning.policy

import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot

enum class LearnedPolicyWorkflowEvidencePolarity {
    POSITIVE,
    NEGATIVE,
}

/** Content-free evidence anchor used by P4 compilation; no Episode or message text is copied. */
data class LearnedPolicyWorkflowEvidenceAnchor(
    val evidenceId: String,
    val polarity: LearnedPolicyWorkflowEvidencePolarity,
    val sourceRevision: Long,
    val sourceIntegritySha256: String,
) {
    init {
        require(evidenceId.length in 1..256)
        require(sourceRevision >= 0L)
        require(sourceIntegritySha256.length == 64 &&
            sourceIntegritySha256.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

data class LearnedWorkflowActionProposal(
    val toolName: String,
    val args: JsonObject,
    val timeoutSeconds: Int = 30,
)

/**
 * Ephemeral compiler input. Policy prose is accepted only to prove the required reviewed fields
 * exist; it is never copied to the Workflow candidate tables or artifact diagnostics.
 */
data class LearnedPolicyProposal(
    val policyId: String,
    val policyRevision: Long,
    val policyArtifactSha256: String,
    val exactGrant: PolicyGrantAuthoritySnapshot,
    val consumingAssistantId: String,
    val trigger: String,
    val procedure: String,
    val verification: String,
    val boundary: String,
    val evidence: List<LearnedPolicyWorkflowEvidenceAnchor>,
    val actions: List<LearnedWorkflowActionProposal>,
    val typedSlots: List<LearnedWorkflowTypedSlot>,
    val name: String,
    val description: String?,
    val producerProviderIdentity: String,
    val producerModelIdentity: String,
    val producerConfigurationIdentity: String,
    val producerConfigGeneration: Long,
    val compilerVersion: String,
    val promptVersion: String,
    val templateVersion: String,
    val validatorVersion: String,
    val verifierVersion: String,
    val maxOutputUtf8Bytes: Int,
    val frozenNowMs: Long,
) {
    init {
        require(exactGrant.state == PolicyGrantAuthorityState.GRANTED) {
            "Workflow compilation requires a live exact grant"
        }
        require(policyId == exactGrant.policyId)
        require(policyRevision == exactGrant.contentRevision)
        require(policyArtifactSha256 == exactGrant.artifactSha256)
        require(consumingAssistantId == exactGrant.consumingAssistantId.toString())
        require(trigger.isNotBlank() && procedure.isNotBlank())
        require(verification.isNotBlank() && boundary.isNotBlank())
        listOf(trigger, procedure, verification, boundary).forEach {
            require(it.length <= MAX_REVIEWED_POLICY_FIELD_CHARS)
        }
        require(evidence.isNotEmpty() && evidence.size <= 64)
        require(evidence.map { it.evidenceId }.distinct().size == evidence.size)
        require(evidence.any { it.polarity == LearnedPolicyWorkflowEvidencePolarity.POSITIVE }) {
            "Workflow compilation requires a positive evidence anchor"
        }
        require(actions.isNotEmpty() && actions.size <= 8)
        require(typedSlots.size <= 32)
        require(name.length in 1..80)
        require(description == null || description.length <= 500)
        require(producerConfigGeneration >= 0L && frozenNowMs >= 0L)
    }

    override fun toString(): String =
        "LearnedPolicyProposal(actions=${actions.size}, evidence=${evidence.size}, ids=<redacted>)"
}

private const val MAX_REVIEWED_POLICY_FIELD_CHARS = 4_096

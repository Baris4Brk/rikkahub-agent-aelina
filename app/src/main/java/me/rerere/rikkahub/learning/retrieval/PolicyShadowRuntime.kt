package me.rerere.rikkahub.learning.retrieval

import me.rerere.rikkahub.learning.exposure.PolicyLearningCommandContext
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.policy.P1_SHADOW_ADMISSION_GATE_ID
import me.rerere.rikkahub.learning.task.TaskSignatureV1

private val POLICY_SHADOW_REQUEST_ID = Regex("policy-shadow-request-v1:[0-9a-f]{64}")

/**
 * Stage-D identity is derived only from frozen command/scope identities. Query or Policy text is
 * neither persisted nor hashed, and a retry of the same logical request addresses the same row.
 */
data class PolicyShadowRuntimeRequest(
    val retrieval: PolicyRetrievalRequest,
    val requestIdentity: String,
    val admissionGateIdentity: String = P1_SHADOW_ADMISSION_GATE_ID,
) {
    init {
        require(requestIdentity.matches(POLICY_SHADOW_REQUEST_ID))
        require(admissionGateIdentity == P1_SHADOW_ADMISSION_GATE_ID)
    }

    override fun toString(): String =
        "PolicyShadowRuntimeRequest(scope=${retrieval.scope.kind}, request=<redacted>, " +
            "query=<redacted>)"

    companion object {
        fun forCommand(
            command: PolicyLearningCommandContext,
            taskSignature: TaskSignatureV1,
            query: String,
            maxCandidates: Int = 5,
            maxEstimatedTokens: Int = 1_024,
            maxLatencyMicros: Long = 50_000L,
        ): PolicyShadowRuntimeRequest {
            val branchAnchorRevision = requireNotNull(command.branchAnchorMessageRevision) {
                "Stage-D request requires the authoritative branch revision"
            }
            val retrieval = PolicyRetrievalRequest(
                scope = command.scope,
                taskSignature = taskSignature,
                query = query,
                maxCandidates = maxCandidates,
                maxEstimatedTokens = maxEstimatedTokens,
                maxLatencyMicros = maxLatencyMicros,
            )
            return PolicyShadowRuntimeRequest(
                retrieval = retrieval,
                requestIdentity = "policy-shadow-request-v1:" + LearningCanonicalId.digest(
                    domainVersion = "policy-shadow-request-v1",
                    fields = listOf(
                        command.scope.kind.name,
                        command.scope.storageId,
                        command.consumingAssistantId.toString(),
                        command.lineageId.toString(),
                        command.branchAnchorMessageId.toString(),
                        branchAnchorRevision.toString(),
                        command.logicalRunId.toString(),
                        taskSignature.value,
                        P1_SHADOW_ADMISSION_GATE_ID,
                        maxCandidates.toString(),
                        maxEstimatedTokens.toString(),
                        maxLatencyMicros.toString(),
                    ),
                ),
                admissionGateIdentity = P1_SHADOW_ADMISSION_GATE_ID,
            )
        }
    }
}

sealed interface PolicyShadowRuntimeResult {
    data class Completed(val trace: PolicyRetrievalTrace) : PolicyShadowRuntimeResult
    data object Disabled : PolicyShadowRuntimeResult
    data object Unavailable : PolicyShadowRuntimeResult
}

/** P1 exposes only the content-free trace; candidate text and IDs never leave the runtime fence. */
fun interface PolicyShadowRuntimePort {
    suspend fun retrieveShadow(request: PolicyShadowRuntimeRequest): PolicyShadowRuntimeResult
}

internal class PolicyShadowFeatureGate(
    private val flags: LearningFeatureFlagSource,
) {
    fun enabled(): Boolean = runCatching { flags.current() }
        .getOrNull()
        ?.let {
            it.isValid && it.effective.policyCandidate &&
                it.effective.policyRetrievalShadow
        }
        ?: false

    val gateIdentity: String get() = P1_SHADOW_ADMISSION_GATE_ID
}

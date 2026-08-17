package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyDriftGovernorTest {
    @Test
    fun `source revision drift atomically embeds evidence in stale source mutation`() = runBlocking {
        val calls = mutableListOf<String>()
        var mutationRequest: PolicyMutationRequest.Transition? = null
        val governor = governor(
            onMutation = { request ->
                calls += "mutation"
                mutationRequest = request
            },
        )

        val result = governor.evaluate(
            PolicyDriftCommand(
                fence = FENCE,
                evidence = PolicySourceDriftEvidence(
                    expectedSourceRevision = 4L,
                    currentSourceRevision = 5L,
                    authorityState = PolicySourceAuthorityState.CURRENT,
                    evidenceContractVersion = 2,
                    evidenceDigest = "b".repeat(64),
                ),
                frozenNowMs = 100L,
            ),
        )

        assertTrue(result is PolicyDriftGovernorResult.DowngradeApplied)
        assertEquals(listOf("mutation"), calls)
        val evidenceRecord = mutationRequest?.lifecycleEvidence
        assertEquals(PolicyLifecycleEvidenceKind.SOURCE_REVISION_DRIFT, evidenceRecord?.evidenceKind)
        assertEquals(LearningPolicyStatus.STALE_SOURCE, mutationRequest?.target)
        assertEquals(PolicyLifecycleReason.SOURCE_INVALIDATED, mutationRequest?.reason)
        assertEquals(PolicyMutationActor.SOURCE_INVALIDATOR, mutationRequest?.actor)
        assertEquals(evidenceRecord, mutationRequest?.lifecycleEvidence)
    }

    @Test
    fun `schema authority and capability hard drift map to typed stale states`() = runBlocking {
        val requests = mutableListOf<PolicyMutationRequest.Transition>()
        val governor = governor(onMutation = { requests += it })
        val schema = "1".repeat(64)

        val cases = listOf(
            PolicyToolSchemaDriftEvidence(
                expectedToolSchemaFingerprints = setOf(schema),
                availableToolSchemaFingerprints = emptySet(),
                evidenceContractVersion = 1,
                evidenceDigest = "2".repeat(64),
            ) to Pair(LearningPolicyStatus.STALE_SCHEMA, PolicyLifecycleReason.TOOL_SCHEMA_CHANGED),
            PolicyAuthorityDriftEvidence(
                expectedAuthorityDigest = "3".repeat(64),
                currentAuthorityDigest = null,
                accessState = PolicyAuthorityAccessState.DENIED,
                evidenceContractVersion = 1,
                evidenceDigest = "4".repeat(64),
            ) to Pair(LearningPolicyStatus.STALE_AUTHORITY, PolicyLifecycleReason.AUTHORITY_CHANGED),
            PolicyCapabilityDriftEvidence(
                expectedCapabilityDigest = "5".repeat(64),
                currentCapabilityDigest = "6".repeat(64),
                capabilityState = PolicyCapabilityState.AVAILABLE,
                evidenceContractVersion = 1,
                evidenceDigest = "7".repeat(64),
            ) to Pair(LearningPolicyStatus.STALE_SCHEMA, PolicyLifecycleReason.CAPABILITY_CHANGED),
        )

        cases.forEach { (evidence, expected) ->
            val result = governor.evaluate(PolicyDriftCommand(FENCE, evidence, 101L))
            assertTrue(result is PolicyDriftGovernorResult.DowngradeApplied)
            assertEquals(expected.first, requests.last().target)
            assertEquals(expected.second, requests.last().reason)
        }
        assertEquals(3, requests.size)
    }

    @Test
    fun `unknown authority evidence abstains without mutation`() = runBlocking {
        var mutationCalls = 0
        val governor = governor(
            onMutation = { mutationCalls += 1 },
        )

        val result = governor.evaluate(
            PolicyDriftCommand(
                fence = FENCE,
                evidence = PolicyAuthorityDriftEvidence(
                    expectedAuthorityDigest = "1".repeat(64),
                    currentAuthorityDigest = null,
                    accessState = PolicyAuthorityAccessState.UNKNOWN,
                    evidenceContractVersion = 1,
                    evidenceDigest = "2".repeat(64),
                ),
                frozenNowMs = 102L,
            ),
        )

        assertEquals(
            PolicyDriftGovernorResult.Abstained(PolicyDriftAbstainReason.EVIDENCE_UNKNOWN),
            result,
        )
        assertEquals(0, mutationCalls)
    }

    @Test
    fun `producer model or provider changes split cohort and never mutate lifecycle`() = runBlocking {
        var mutationCalls = 0
        val governor = governor(
            onMutation = { mutationCalls += 1 },
        )

        val result = governor.evaluate(
            PolicyDriftCommand(
                fence = FENCE,
                evidence = PolicyProducerCohortDriftEvidence(
                    expectedProducerModelIdentity = "1".repeat(64),
                    currentProducerModelIdentity = "2".repeat(64),
                    expectedProducerProviderIdentity = "3".repeat(64),
                    currentProducerProviderIdentity = "3".repeat(64),
                    evidenceContractVersion = 1,
                    evidenceDigest = "4".repeat(64),
                ),
                frozenNowMs = 103L,
            ),
        )

        assertEquals(PolicyDriftGovernorResult.CohortBoundaryRequired(true, false), result)
        assertEquals(0, mutationCalls)
    }

    @Test
    fun `mutation storage failure abstains rather than claiming a downgrade`() = runBlocking {
        val governor = PolicyDriftGovernor(
            PolicyMutationStore { error("storage unavailable") },
        )

        val result = governor.evaluate(
            PolicyDriftCommand(
                FENCE,
                PolicySourceDriftEvidence(
                    expectedSourceRevision = 1,
                    currentSourceRevision = null,
                    authorityState = PolicySourceAuthorityState.TOMBSTONED,
                    evidenceContractVersion = 1,
                    evidenceDigest = "8".repeat(64),
                ),
                frozenNowMs = 104L,
            ),
        )

        assertEquals(
            PolicyDriftGovernorResult.Abstained(
                PolicyDriftAbstainReason.MUTATION_UNAVAILABLE,
            ),
            result,
        )
    }

    private fun governor(
        onMutation: (PolicyMutationRequest.Transition) -> Unit = {},
    ): PolicyDriftGovernor {
        val validatingStore = ValidatingPolicyMutationStore(
            PolicyMutationTransaction { request ->
                val transition = request as PolicyMutationRequest.Transition
                onMutation(transition)
                PolicyMutationResult.Applied(
                    policyId = transition.fence.policyId,
                    revision = transition.fence.expectedRevision + 1L,
                    status = transition.target,
                )
            },
        )
        return PolicyDriftGovernor(validatingStore)
    }

    private companion object {
        val SCOPE = LearningScope.Assistant(
            Uuid.parse("00000000-0000-0000-0000-000000000201"),
        )
        val FENCE = PolicyMutationFence(
            policyId = "policy-one",
            scope = SCOPE,
            expectedRevision = 7L,
            expectedContentRevision = 3L,
            expectedArtifactHash = "a".repeat(64),
        )
    }
}

package me.rerere.rikkahub.learning.policy.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyDriftGovernor
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.policy.PolicyMutationStore
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

class PolicyApplicabilityDriftRuntimeTest {
    @Test
    fun `exact dispatch observer mutation matrix is fail closed and capability stays unknown`() =
        runBlocking {
            val current = catalog(tool("get_time_info"))
            val policy = policy(current).copy(expectedCapabilityDigest = null)
            val writes = mutableListOf<PolicyMutationRequest>()
            val observer = PolicyExactDispatchSchemaObserver(
                PolicyDriftGovernor(
                    PolicyMutationStore { request ->
                        writes += request
                        PolicyMutationResult.Applied(
                            policy.fence.policyId,
                            policy.fence.expectedRevision + 1L,
                            LearningPolicyStatus.STALE_SCHEMA,
                        )
                    },
                ),
            )

            val exact = observer.observe(
                policy = policy,
                availableToolSchemaFingerprints = current.entries
                    .mapTo(linkedSetOf()) { it.schemaFingerprint },
                frozenNowMs = 200L,
                revalidateExact = { true },
            )
            assertEquals(PolicyDriftObservationKind.CAPABILITY_BASELINE_UNKNOWN, exact.kind)
            assertEquals(0, writes.size)

            val missing = observer.observe(
                policy = policy,
                availableToolSchemaFingerprints = emptySet(),
                frozenNowMs = 200L,
                revalidateExact = { true },
            )
            assertEquals(PolicyDriftObservationKind.TOOL_SCHEMA_DOWNGRADE, missing.kind)
            assertEquals(1, writes.size)
            assertEquals(policy.fence, (writes.single() as PolicyMutationRequest.Transition).fence)

            writes.clear()
            val raced = observer.observe(
                policy = policy,
                availableToolSchemaFingerprints = emptySet(),
                frozenNowMs = 200L,
                revalidateExact = { false },
            )
            assertEquals(PolicyDriftObservationKind.CONFLICT, raced.kind)
            assertEquals(0, writes.size)
        }

    @Test
    fun `missing current schema writes exact evidence fenced downgrade`() = runBlocking {
        val expectedCatalog = catalog(tool("get_time_info"))
        val policy = policy(expectedCatalog)
        val writes = mutableListOf<PolicyMutationRequest>()
        val runtime = runtime(
            policy,
            CurrentPolicyApplicabilitySurface(catalog = catalog()),
            writes,
        )

        val result = runtime.runPage(frozenNowMs = 200L)

        assertEquals(1, result.scanned)
        assertEquals(
            PolicyDriftObservationKind.TOOL_SCHEMA_DOWNGRADE,
            result.observations.single().kind,
        )
        val transition = writes.single() as PolicyMutationRequest.Transition
        assertEquals(FENCE, transition.fence)
        assertNotNull(transition.lifecycleEvidence)
        assertEquals(FENCE, transition.lifecycleEvidence?.fence)
    }

    @Test
    fun `unknown catalog and missing capability baseline abstain without write`() = runBlocking {
        val current = catalog(tool("get_time_info"))
        val writes = mutableListOf<PolicyMutationRequest>()
        val unknown = runtime(policy(current), surface = null, writes = writes)
            .runPage(frozenNowMs = 200L)
        assertEquals(
            PolicyDriftObservationKind.CURRENT_SURFACE_UNKNOWN,
            unknown.observations.single().kind,
        )
        val noBaseline = runtime(
            policy(current).copy(expectedCapabilityDigest = null),
            CurrentPolicyApplicabilitySurface(current),
            writes,
        ).runPage(frozenNowMs = 200L)
        assertEquals(
            PolicyDriftObservationKind.CAPABILITY_BASELINE_UNKNOWN,
            noBaseline.observations.single().kind,
        )
        assertEquals(0, writes.size)
    }

    @Test
    fun `capability metadata drift downgrades while producer drift only splits cohort`() = runBlocking {
        val current = catalog(tool("get_time_info"))
        val policy = policy(current)
        val writes = mutableListOf<PolicyMutationRequest>()

        val capabilityChanged = policy.copy(expectedCapabilityDigest = "9".repeat(64))
        val capability = runtime(
            capabilityChanged,
            CurrentPolicyApplicabilitySurface(current),
            writes,
        ).runPage(frozenNowMs = 200L)
        assertEquals(
            PolicyDriftObservationKind.CAPABILITY_DOWNGRADE,
            capability.observations.single().kind,
        )
        assertEquals(1, writes.size)
        writes.clear()

        val cohort = runtime(
            policy,
            CurrentPolicyApplicabilitySurface(
                catalog = current,
                currentProducerModelIdentity = "7".repeat(64),
                currentProducerProviderIdentity = "8".repeat(64),
            ),
            writes,
        ).runPage(frozenNowMs = 200L)
        assertEquals(
            PolicyDriftObservationKind.COHORT_BOUNDARY,
            cohort.observations.single().kind,
        )
        assertEquals(0, writes.size)
    }

    private fun runtime(
        policy: ActivePolicyApplicabilitySnapshot,
        surface: CurrentPolicyApplicabilitySurface?,
        writes: MutableList<PolicyMutationRequest>,
    ) = PolicyApplicabilityDriftRuntime(
        policies = object : ActivePolicyDriftSource {
            override suspend fun listActivePage(
                cursor: PolicyDriftPageCursor,
                limit: Int,
            ) = ActivePolicyDriftPageResult.Ready(ActivePolicyDriftPage(listOf(policy), null))

            override suspend fun revalidateExact(
                snapshot: ActivePolicyApplicabilitySnapshot,
            ): Boolean = snapshot == policy
        },
        surfaces = CurrentPolicyApplicabilitySurfaceSource { surface },
        governor = PolicyDriftGovernor(
            PolicyMutationStore { request ->
                writes += request
                PolicyMutationResult.Applied(
                    policy.fence.policyId,
                    policy.fence.expectedRevision + 1L,
                    LearningPolicyStatus.STALE_SCHEMA,
                )
            },
        ),
    )

    private fun policy(catalog: ToolCatalogSnapshot): ActivePolicyApplicabilitySnapshot {
        val schemas = catalog.entries.map { it.schemaFingerprint }.toSet()
        return ActivePolicyApplicabilitySnapshot(
            fence = FENCE,
            status = LearningPolicyStatus.ACTIVE,
            expectedToolSchemaFingerprints = schemas,
            expectedCapabilityDigest = policyCapabilityDigestForCatalog(schemas, catalog),
            producerModelIdentity = "5".repeat(64),
            producerProviderIdentity = "6".repeat(64),
            updatedAtMs = 100L,
        )
    }

    private fun catalog(vararg tools: Tool) = ToolCatalogSnapshot.fromDefinitions(tools.toList())

    private fun tool(name: String) = Tool(
        name = name,
        description = "bounded drift fixture",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = { error("drift observer must never execute a Tool") },
    )

    private companion object {
        val FENCE = PolicyMutationFence(
            policyId = "policy-drift-runtime",
            scope = LearningScope.Assistant(
                Uuid.parse("00000000-0000-0000-0000-000000000621"),
            ),
            expectedRevision = 7L,
            expectedContentRevision = 3L,
            expectedArtifactHash = "a".repeat(64),
        )
    }
}

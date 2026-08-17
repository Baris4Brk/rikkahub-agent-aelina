package me.rerere.rikkahub.learning.policy.runtime

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyCapabilityDriftEvidence
import me.rerere.rikkahub.learning.policy.PolicyCapabilityState
import me.rerere.rikkahub.learning.policy.PolicyDriftCommand
import me.rerere.rikkahub.learning.policy.PolicyDriftGovernor
import me.rerere.rikkahub.learning.policy.PolicyDriftGovernorResult
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicyProducerCohortDriftEvidence
import me.rerere.rikkahub.learning.policy.PolicyToolSchemaDriftEvidence
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

private const val POLICY_APPLICABILITY_DRIFT_CONTRACT_VERSION = 1
private const val MAX_ACTIVE_POLICY_DRIFT_PAGE = 64
private const val MAX_CURRENT_CATALOG_SCHEMAS = 512
private val POLICY_DRIFT_SHA256 = Regex("[0-9a-f]{64}")

data class ActivePolicyApplicabilitySnapshot(
    val fence: PolicyMutationFence,
    val status: LearningPolicyStatus,
    val expectedToolSchemaFingerprints: Set<String>,
    /** Null means the old Policy has no durable capability baseline; this must ABSTAIN. */
    val expectedCapabilityDigest: String?,
    val producerModelIdentity: String,
    val producerProviderIdentity: String,
    val updatedAtMs: Long,
) {
    init {
        require(status == LearningPolicyStatus.ACTIVE)
        require(expectedToolSchemaFingerprints.size <= 16)
        require(expectedToolSchemaFingerprints.all(POLICY_DRIFT_SHA256::matches))
        require(expectedCapabilityDigest == null || expectedCapabilityDigest.matches(POLICY_DRIFT_SHA256))
        require(producerModelIdentity.matches(POLICY_DRIFT_SHA256))
        require(producerProviderIdentity.matches(POLICY_DRIFT_SHA256))
        require(updatedAtMs >= 0L)
    }
}

data class PolicyDriftPageCursor(
    val afterUpdatedAtMs: Long,
    val afterPolicyId: String,
) {
    init {
        require(afterUpdatedAtMs >= 0L)
        require(afterPolicyId.length <= 256)
    }

    companion object {
        val START = PolicyDriftPageCursor(0L, "")
    }
}

data class ActivePolicyDriftPage(
    val policies: List<ActivePolicyApplicabilitySnapshot>,
    val nextCursor: PolicyDriftPageCursor?,
) {
    init {
        require(policies.size <= MAX_ACTIVE_POLICY_DRIFT_PAGE)
        require(policies.map { it.fence.policyId }.distinct().size == policies.size)
    }
}

sealed interface ActivePolicyDriftPageResult {
    data class Ready(val page: ActivePolicyDriftPage) : ActivePolicyDriftPageResult
    data object Unavailable : ActivePolicyDriftPageResult
}

interface ActivePolicyDriftSource {
    /** Stable global keyset page containing ACTIVE heads only. */
    suspend fun listActivePage(
        cursor: PolicyDriftPageCursor,
        limit: Int,
    ): ActivePolicyDriftPageResult

    /** Last exact revision/content/artifact/scope check before a governor may write. */
    suspend fun revalidateExact(snapshot: ActivePolicyApplicabilitySnapshot): Boolean
}

data class CurrentPolicyApplicabilitySurface(
    val catalog: ToolCatalogSnapshot,
    /** Optional new execution cohort. A change creates a cohort boundary, never a downgrade. */
    val currentProducerModelIdentity: String? = null,
    val currentProducerProviderIdentity: String? = null,
) {
    init {
        require((currentProducerModelIdentity == null) == (currentProducerProviderIdentity == null))
        currentProducerModelIdentity?.let { require(it.matches(POLICY_DRIFT_SHA256)) }
        currentProducerProviderIdentity?.let { require(it.matches(POLICY_DRIFT_SHA256)) }
    }
}

fun interface CurrentPolicyApplicabilitySurfaceSource {
    /** Rebuilds the exact current ToolCatalog for this Policy's concrete consuming surface. */
    suspend fun current(
        policy: ActivePolicyApplicabilitySnapshot,
    ): CurrentPolicyApplicabilitySurface?
}

enum class PolicyDriftObservationKind {
    NO_DRIFT,
    TOOL_SCHEMA_DOWNGRADE,
    CAPABILITY_DOWNGRADE,
    CAPABILITY_BASELINE_UNKNOWN,
    CURRENT_SURFACE_UNKNOWN,
    COHORT_BOUNDARY,
    CONFLICT,
    UNAVAILABLE,
}

data class PolicyDriftPolicyObservation(
    val policyId: String,
    val kind: PolicyDriftObservationKind,
    val governorResult: PolicyDriftGovernorResult? = null,
)

data class PolicyDriftMaintenanceResult(
    val scanned: Int,
    val observations: List<PolicyDriftPolicyObservation>,
    val nextCursor: PolicyDriftPageCursor?,
) {
    init {
        require(scanned == observations.size)
        require(scanned <= MAX_ACTIVE_POLICY_DRIFT_PAGE)
    }
}

/**
 * Dispatch-time schema observer over the exact provider-visible schema set. Capability metadata
 * is deliberately absent from this contract; a schema match therefore reports
 * [PolicyDriftObservationKind.CAPABILITY_BASELINE_UNKNOWN] instead of claiming full capability
 * health.
 */
class PolicyExactDispatchSchemaObserver(
    private val governor: PolicyDriftGovernor,
) {
    suspend fun observe(
        policy: ActivePolicyApplicabilitySnapshot,
        availableToolSchemaFingerprints: Set<String>,
        frozenNowMs: Long,
        revalidateExact: suspend (ActivePolicyApplicabilitySnapshot) -> Boolean,
    ): PolicyDriftPolicyObservation {
        require(frozenNowMs >= 0L)
        if (availableToolSchemaFingerprints.size > MAX_CURRENT_CATALOG_SCHEMAS ||
            availableToolSchemaFingerprints.any { !it.matches(POLICY_DRIFT_SHA256) }
        ) return observe(policy, PolicyDriftObservationKind.CURRENT_SURFACE_UNKNOWN)
        val exact = try {
            revalidateExact(policy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!exact) return observe(policy, PolicyDriftObservationKind.CONFLICT)
        val evidence = PolicyToolSchemaDriftEvidence(
            expectedToolSchemaFingerprints = policy.expectedToolSchemaFingerprints,
            availableToolSchemaFingerprints = availableToolSchemaFingerprints,
            evidenceContractVersion = POLICY_APPLICABILITY_DRIFT_CONTRACT_VERSION,
            evidenceDigest = driftDigest(
                "TOOL_SCHEMA",
                policy,
                policy.expectedToolSchemaFingerprints.sorted() +
                    availableToolSchemaFingerprints.sorted(),
            ),
        )
        val result = governor.evaluate(PolicyDriftCommand(policy.fence, evidence, frozenNowMs))
        return when (result) {
            is PolicyDriftGovernorResult.DowngradeApplied,
            is PolicyDriftGovernorResult.DowngradeDuplicate,
            -> observe(policy, PolicyDriftObservationKind.TOOL_SCHEMA_DOWNGRADE, result)
            is PolicyDriftGovernorResult.DowngradeConflict ->
                observe(policy, PolicyDriftObservationKind.CONFLICT, result)
            is PolicyDriftGovernorResult.Abstained ->
                observe(policy, PolicyDriftObservationKind.CURRENT_SURFACE_UNKNOWN, result)
            PolicyDriftGovernorResult.NoDrift -> observe(
                policy,
                if (policy.expectedCapabilityDigest == null) {
                    PolicyDriftObservationKind.CAPABILITY_BASELINE_UNKNOWN
                } else {
                    PolicyDriftObservationKind.NO_DRIFT
                },
                result,
            )
            is PolicyDriftGovernorResult.CohortBoundaryRequired ->
                observe(policy, PolicyDriftObservationKind.COHORT_BOUNDARY, result)
        }
    }
}

class PolicyApplicabilityDriftRuntime(
    private val policies: ActivePolicyDriftSource,
    private val surfaces: CurrentPolicyApplicabilitySurfaceSource,
    private val governor: PolicyDriftGovernor,
) {
    suspend fun runPage(
        cursor: PolicyDriftPageCursor = PolicyDriftPageCursor.START,
        limit: Int = MAX_ACTIVE_POLICY_DRIFT_PAGE,
        frozenNowMs: Long,
    ): PolicyDriftMaintenanceResult {
        require(limit in 1..MAX_ACTIVE_POLICY_DRIFT_PAGE)
        require(frozenNowMs >= 0L)
        val page = try {
            when (val result = policies.listActivePage(cursor, limit)) {
                is ActivePolicyDriftPageResult.Ready -> result.page
                ActivePolicyDriftPageResult.Unavailable -> return PolicyDriftMaintenanceResult(
                    scanned = 0,
                    observations = emptyList(),
                    nextCursor = cursor,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PolicyDriftMaintenanceResult(0, emptyList(), cursor)
        }
        val observations = mutableListOf<PolicyDriftPolicyObservation>()
        page.policies.forEach { policy ->
            observations += evaluateOne(policy, frozenNowMs)
        }
        return PolicyDriftMaintenanceResult(page.policies.size, observations, page.nextCursor)
    }

    private suspend fun evaluateOne(
        policy: ActivePolicyApplicabilitySnapshot,
        frozenNowMs: Long,
    ): PolicyDriftPolicyObservation {
        val surface = try {
            surfaces.current(policy)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return observe(policy, PolicyDriftObservationKind.CURRENT_SURFACE_UNKNOWN)
        val availableSchemas = surface.catalog.entries.map { it.schemaFingerprint }.toSet()
        if (availableSchemas.size > MAX_CURRENT_CATALOG_SCHEMAS ||
            availableSchemas.any { !it.matches(POLICY_DRIFT_SHA256) }
        ) return observe(policy, PolicyDriftObservationKind.CURRENT_SURFACE_UNKNOWN)

        if (!revalidate(policy)) return observe(policy, PolicyDriftObservationKind.CONFLICT)
        val schemaEvidence = PolicyToolSchemaDriftEvidence(
            expectedToolSchemaFingerprints = policy.expectedToolSchemaFingerprints,
            availableToolSchemaFingerprints = availableSchemas,
            evidenceContractVersion = POLICY_APPLICABILITY_DRIFT_CONTRACT_VERSION,
            evidenceDigest = driftDigest(
                "TOOL_SCHEMA",
                policy,
                policy.expectedToolSchemaFingerprints.sorted() + availableSchemas.sorted(),
            ),
        )
        val schemaResult = governor.evaluate(
            PolicyDriftCommand(policy.fence, schemaEvidence, frozenNowMs),
        )
        if (schemaResult.isDowngradeOrConflict()) {
            return observe(
                policy,
                if (schemaResult is PolicyDriftGovernorResult.DowngradeApplied ||
                    schemaResult is PolicyDriftGovernorResult.DowngradeDuplicate
                ) PolicyDriftObservationKind.TOOL_SCHEMA_DOWNGRADE
                else PolicyDriftObservationKind.CONFLICT,
                schemaResult,
            )
        }
        if (schemaResult is PolicyDriftGovernorResult.Abstained) {
            return observe(policy, PolicyDriftObservationKind.CURRENT_SURFACE_UNKNOWN, schemaResult)
        }

        val expectedCapability = policy.expectedCapabilityDigest
            ?: return observe(
                policy,
                PolicyDriftObservationKind.CAPABILITY_BASELINE_UNKNOWN,
                schemaResult,
            )
        val currentCapability = policyCapabilityDigestForCatalog(
            policy.expectedToolSchemaFingerprints,
            surface.catalog,
        ) ?: return observe(policy, PolicyDriftObservationKind.CURRENT_SURFACE_UNKNOWN, schemaResult)
        if (!revalidate(policy)) return observe(policy, PolicyDriftObservationKind.CONFLICT)
        val capabilityEvidence = PolicyCapabilityDriftEvidence(
            expectedCapabilityDigest = expectedCapability,
            currentCapabilityDigest = currentCapability,
            capabilityState = PolicyCapabilityState.AVAILABLE,
            evidenceContractVersion = POLICY_APPLICABILITY_DRIFT_CONTRACT_VERSION,
            evidenceDigest = driftDigest(
                "CAPABILITY",
                policy,
                listOf(expectedCapability, currentCapability),
            ),
        )
        val capabilityResult = governor.evaluate(
            PolicyDriftCommand(policy.fence, capabilityEvidence, frozenNowMs),
        )
        if (capabilityResult.isDowngradeOrConflict()) {
            return observe(
                policy,
                if (capabilityResult is PolicyDriftGovernorResult.DowngradeApplied ||
                    capabilityResult is PolicyDriftGovernorResult.DowngradeDuplicate
                ) PolicyDriftObservationKind.CAPABILITY_DOWNGRADE
                else PolicyDriftObservationKind.CONFLICT,
                capabilityResult,
            )
        }

        val currentModel = surface.currentProducerModelIdentity
        val currentProvider = surface.currentProducerProviderIdentity
        if (currentModel != null && currentProvider != null) {
            val cohortEvidence = PolicyProducerCohortDriftEvidence(
                expectedProducerModelIdentity = policy.producerModelIdentity,
                currentProducerModelIdentity = currentModel,
                expectedProducerProviderIdentity = policy.producerProviderIdentity,
                currentProducerProviderIdentity = currentProvider,
                evidenceContractVersion = POLICY_APPLICABILITY_DRIFT_CONTRACT_VERSION,
                evidenceDigest = driftDigest(
                    "PRODUCER_COHORT",
                    policy,
                    listOf(
                        policy.producerModelIdentity,
                        currentModel,
                        policy.producerProviderIdentity,
                        currentProvider,
                    ),
                ),
            )
            val cohortResult = governor.evaluate(
                PolicyDriftCommand(policy.fence, cohortEvidence, frozenNowMs),
            )
            if (cohortResult is PolicyDriftGovernorResult.CohortBoundaryRequired) {
                return observe(policy, PolicyDriftObservationKind.COHORT_BOUNDARY, cohortResult)
            }
        }
        return observe(policy, PolicyDriftObservationKind.NO_DRIFT, capabilityResult)
    }

    private suspend fun revalidate(policy: ActivePolicyApplicabilitySnapshot): Boolean = try {
        policies.revalidateExact(policy)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

internal fun policyCapabilityDigestForCatalog(
    expectedSchemas: Set<String>,
    catalog: ToolCatalogSnapshot,
): String? {
    val matched = expectedSchemas.sorted().map { schema ->
        val entries = catalog.entries.filter { it.schemaFingerprint == schema }
        if (entries.size != 1) return null
        entries.single()
    }
    return LearningCanonicalId.digest(
        domainVersion = "policy-tool-capability-snapshot-v1",
        fields = matched.flatMap { entry ->
            listOf(
                entry.schemaFingerprint,
                entry.toolName,
                entry.capabilityId.orEmpty(),
                entry.risk?.name.orEmpty(),
                entry.allowedOrigins.map(Enum<*>::name).sorted().joinToString(","),
                entry.currentlyInjectable.toString(),
                entry.externalUntrusted.toString(),
            )
        },
    )
}

private fun driftDigest(
    kind: String,
    policy: ActivePolicyApplicabilitySnapshot,
    material: List<String>,
): String = LearningCanonicalId.digest(
    domainVersion = "policy-applicability-drift-evidence-v1",
    fields = listOf(
        kind,
        policy.fence.scope.kind.name,
        policy.fence.scope.storageId,
        policy.fence.policyId,
        policy.fence.expectedRevision.toString(),
        policy.fence.expectedContentRevision.toString(),
        policy.fence.expectedArtifactHash,
        POLICY_APPLICABILITY_DRIFT_CONTRACT_VERSION.toString(),
    ) + material,
)

private fun PolicyDriftGovernorResult.isDowngradeOrConflict(): Boolean =
    this is PolicyDriftGovernorResult.DowngradeApplied ||
        this is PolicyDriftGovernorResult.DowngradeDuplicate ||
        this is PolicyDriftGovernorResult.DowngradeConflict

private fun observe(
    policy: ActivePolicyApplicabilitySnapshot,
    kind: PolicyDriftObservationKind,
    result: PolicyDriftGovernorResult? = null,
) = PolicyDriftPolicyObservation(policy.fence.policyId, kind, result)

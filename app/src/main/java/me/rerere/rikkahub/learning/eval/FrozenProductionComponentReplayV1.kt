package me.rerere.rikkahub.learning.eval

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.data.ai.RECALL_PROMPT_COMPILER_REVISION
import me.rerere.rikkahub.data.ai.RecallDreamClaimIdentity
import me.rerere.rikkahub.data.ai.RecallDreamContextItem
import me.rerere.rikkahub.data.ai.RecallPromptBudget
import me.rerere.rikkahub.data.ai.RecallRequestPurpose
import me.rerere.rikkahub.data.ai.compileRecallPrompt
import me.rerere.rikkahub.learning.adapters.DreamingIdentityAdapter
import me.rerere.rikkahub.learning.api.IdentityContextBudget
import me.rerere.rikkahub.learning.api.IdentityContextRequest
import me.rerere.rikkahub.learning.api.IdentityContextResult
import me.rerere.rikkahub.learning.episode.EpisodeIdFactory
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposureMutationResult
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.exposure.PolicyExposureStateMachine
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.retrieval.LearnedPolicyContextItem
import me.rerere.rikkahub.learning.retrieval.PolicyRetrievalRequest
import me.rerere.rikkahub.learning.retrieval.PolicyRetriever
import me.rerere.rikkahub.learning.retrieval.PolicyShadowCandidate
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.memory.dreaming.model.DREAM_SNAPSHOT_SCHEMA_VERSION
import me.rerere.rikkahub.memory.dreaming.model.DreamClaimState
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamSha256
import me.rerere.rikkahub.memory.dreaming.model.DreamStorageClass
import me.rerere.rikkahub.memory.dreaming.model.DreamingFeatureFlags
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeClaimProjection
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeClaimRef
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeFragmentIntegrity
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimePayloadIntegrity
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeReadConsistency
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeSnapshotStatus
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeSourceFence
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeSourceValidity
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjection
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionReader
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingFeatureFlagSource
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotSection
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState

/**
 * Checked-in, non-user P5 fixture. It contains only bounded invented strings and stable identities;
 * adapters never open AppDatabase/LearningDatabase, files, providers, tools, or the network.
 */
object FrozenProductionComponentReplayV1 {
    const val FIXTURE_VERSION: String = "p5-production-component-fixture-v2"

    /** Digest of observed process facts and explicit build inputs, never a caller-chosen label. */
    val environmentDigestSha256: String
        get() = ProductionEvalRuntimeEnvironment.capture().digestSha256

    val adapters: ProductionComponentReplayAdapters by lazy {
        ProductionComponentReplayAdapters(
            dreaming = FrozenDreamProjectionPort,
            retrieval = FrozenPolicyRetrievalPort,
            recall = FrozenRecallCompilerPort,
            exposure = FrozenPolicyExposurePort,
            outcome = FrozenPolicyOutcomePort,
        )
    }

    internal fun fixture(unit: OfflineReplayUnit): FrozenProductionFixture? =
        FIXTURES[unit.fixtureId]?.takeIf { it.unitId == unit.unitId }

    private val FIXTURES: Map<String, FrozenProductionFixture> = FrozenReplayCorpusV1.units
        .associate { unit ->
            unit.fixtureId to FrozenProductionFixture(
                unitId = unit.unitId,
                scope = if (unit.slice.scope == "assistant") {
                    LearningScope.Assistant(FIXTURE_ASSISTANT_ID)
                } else {
                    LearningScope.AuthoritySubject("offline-eval-authority-v1")
                },
                taskSignature = signature(unit.fixtureId),
                baseInputTokens = 96 + unit.unitId.takeLast(2).toInt(),
            )
        }

    private fun signature(fixtureId: String): TaskSignatureV1 = requireNotNull(
        TaskSignatureV1.parseOrNull(
            "task-signature-v1:" + LearningCanonicalId.digest(
                "p5-production-fixture-task-v1",
                listOf(fixtureId),
            ),
        ),
    )

}

internal data class FrozenProductionFixture(
    val unitId: String,
    val scope: LearningScope,
    val taskSignature: TaskSignatureV1,
    val baseInputTokens: Int,
)

private object FrozenDreamProjectionPort : DreamProjectionReplayPort {
    override val identity = adapterIdentity(
        ProductionReplayComponent.DREAM_PROJECTION,
        "dreaming-identity-adapter+runtime-fence-selector-v1",
    )

    override suspend fun project(
        request: ProductionComponentReplayRequest,
    ): ProductionComponentReplayResult<DreamProjectionReplayObservation> {
        val fixture = FrozenProductionComponentReplayV1.fixture(request.unit)
            ?: return abstain(ProductionComponentAbstainReason.FIXTURE_NOT_AVAILABLE)
        val adapter = DreamingIdentityAdapter(
            featureFlags = DreamingFeatureFlagSource {
                DreamingFeatureFlags(schemaReady = true, use = true)
            },
            projectionReader = DreamSnapshotProjectionReader { read ->
                fixedDreamProjection(read.scopeId, read.frozenNowEpochMs)
            },
        )
        val result = adapter.queryRelevantIdentity(
            IdentityContextRequest(
                expectedScope = fixture.scope,
                taskSignature = fixture.taskSignature,
                budget = IdentityContextBudget(maxItems = 4, maxChars = 4_096),
                frozenNowEpochMs = FROZEN_NOW_MS,
            ),
        )
        val count = when (result) {
            is IdentityContextResult.Available -> result.block.items.size
            // AUTHORITY_SUBJECT has no Dream authority counterpart in V1. That is a fully
            // observed production decision, not a missing fixture or adapter abstention.
            is IdentityContextResult.Unavailable -> 0
        }
        return observed(
            DreamProjectionReplayObservation(count),
            operations = 18L + count * 7L,
            allocations = 4L + count * 2L,
        )
    }
}

private object FrozenPolicyRetrievalPort : PolicyRetrievalReplayPort {
    override val identity = adapterIdentity(
        ProductionReplayComponent.POLICY_RETRIEVAL,
        "policy-retriever-fts-manager-hard-fences-v1",
    )

    override suspend fun retrieve(
        request: ProductionComponentReplayRequest,
        dream: DreamProjectionReplayObservation?,
    ): ProductionComponentReplayResult<PolicyRetrievalReplayObservation> {
        val fixture = FrozenProductionComponentReplayV1.fixture(request.unit)
            ?: return abstain(ProductionComponentAbstainReason.FIXTURE_NOT_AVAILABLE)
        val artifact = EvalDigest.sha256(
            "p5-fixture-policy-artifact-v1",
            listOf(request.unit.fixtureId),
        )
        val candidate = PolicyShadowCandidate(
            policyId = "offline-policy-${request.unit.fixtureId}",
            scope = fixture.scope,
            taskSignature = fixture.taskSignature,
            status = LearningPolicyStatus.SHADOW,
            artifactHash = artifact,
            sourceValid = true,
            toolSchemaValid = true,
            searchableText = FIXED_POLICY_FRAGMENT,
            estimatedTokens = FIXED_POLICY_TOKENS,
            updatedAtMs = FROZEN_NOW_MS,
        )
        val result = PolicyRetriever(
            traceKey = ByteArray(32) { index -> (index + 1).toByte() },
            monotonicNanos = { 0L },
        ).retrieve(
            PolicyRetrievalRequest(
                scope = fixture.scope,
                taskSignature = fixture.taskSignature,
                query = FIXED_POLICY_QUERY,
                maxCandidates = 3,
                maxEstimatedTokens = 256,
            ),
            listOf(candidate),
        )
        return observed(
            PolicyRetrievalReplayObservation(
                candidateCount = result.hits.size,
                retrievalTokens = result.trace.estimatedTokens,
                scopeLeakCount = result.hits.count { it.candidate.scope != fixture.scope },
                staleHitCount = result.hits.count {
                    !it.candidate.sourceValid || !it.candidate.toolSchemaValid
                },
            ),
            operations = 22L + result.trace.queryTermCount * 3L + result.hits.size * 8L,
            allocations = 6L + result.hits.size * 3L,
        )
    }
}

private object FrozenRecallCompilerPort : RecallCompilerReplayPort {
    override val identity = adapterIdentity(
        ProductionReplayComponent.RECALL_COMPILER,
        "$RECALL_PROMPT_COMPILER_REVISION+atomic-budget-v1",
    )

    override suspend fun compile(
        request: RecallCompilerReplayRequest,
    ): ProductionComponentReplayResult<RecallCompilerReplayObservation> {
        val fixture = FrozenProductionComponentReplayV1.fixture(request.replay.unit)
            ?: return abstain(ProductionComponentAbstainReason.FIXTURE_NOT_AVAILABLE)
        val dreams = if ((request.dream?.projectedItemCount ?: 0) > 0) {
            listOf(
                RecallDreamContextItem(
                    scopeId = "offline-eval-dream-scope-v1",
                    claims = listOf(RecallDreamClaimIdentity("offline-eval-claim-v1", 1L)),
                    renderedFragment = FIXED_DREAM_FRAGMENT,
                    compilerRevision = "dream-runtime-context-v1",
                ),
            )
        } else {
            emptyList()
        }
        val policies = if ((request.retrieval?.candidateCount ?: 0) > 0) {
            listOf(fixedPolicyContext(request.replay, fixture))
        } else {
            emptyList()
        }
        val compiled = compileRecallPrompt(
            memory = emptyList(),
            policies = policies,
            budget = RecallPromptBudget(
                maxTokens = 1_024,
                maxChars = 8_192,
                maxPolicyTokens = 256,
                maxPolicyItems = 3,
            ),
            requestPurpose = RecallRequestPurpose.NORMAL,
            dreams = dreams,
        )
        if (policies.isNotEmpty() && compiled.manifest.actualPolicyItems.isEmpty()) {
            return abstain(ProductionComponentAbstainReason.COMPONENT_REJECTED)
        }
        if (dreams.isNotEmpty() && compiled.manifest.actualDreamItems.isEmpty()) {
            return abstain(ProductionComponentAbstainReason.COMPONENT_REJECTED)
        }
        return observed(
            RecallCompilerReplayObservation(
                inputTokens = fixture.baseInputTokens + compiled.estimatedTokens,
                contextTokens = compiled.estimatedTokens,
            ),
            operations = 28L + compiled.manifest.actualItems.size * 9L + compiled.dropped.size * 3L,
            allocations = 7L + compiled.manifest.actualItems.size * 4L + compiled.dropped.size,
        )
    }
}

private object FrozenPolicyExposurePort : PolicyExposureReplayPort {
    override val identity = adapterIdentity(
        ProductionReplayComponent.POLICY_EXPOSURE,
        "policy-exposure-state-machine-v1",
    )

    override suspend fun expose(
        request: PolicyExposureReplayRequest,
    ): ProductionComponentReplayResult<PolicyExposureReplayObservation> {
        if (request.retrieval.candidateCount <= 0 || request.recall.contextTokens <= 0) {
            return abstain(ProductionComponentAbstainReason.INPUT_NOT_AVAILABLE)
        }
        val receipt = exposureReceipt(request.replay, linkOutcome = false)
            ?: return abstain(ProductionComponentAbstainReason.COMPONENT_REJECTED)
        return observed(
            PolicyExposureReplayObservation(
                compiledCount = if (receipt.hasObserved(PolicyExposureState.COMPILED)) 1 else 0,
                dispatchCount = if (receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED)) 1 else 0,
            ),
            operations = 36L,
            allocations = 8L,
        )
    }
}

private object FrozenPolicyOutcomePort : PolicyOutcomeReplayPort {
    override val identity = adapterIdentity(
        ProductionReplayComponent.POLICY_OUTCOME,
        "policy-exposure-outcome-link+arm-blind-authority-trace-v2",
    )

    override suspend fun observe(
        request: PolicyOutcomeReplayRequest,
    ): ProductionComponentReplayResult<PolicyOutcomeReplayObservation> {
        if (FrozenProductionComponentReplayV1.fixture(request.replay.unit) == null) {
            return abstain(ProductionComponentAbstainReason.FIXTURE_NOT_AVAILABLE)
        }
        val authorityTrace = FrozenArmBlindAuthorityTraceV1.recordFor(request.replay.unit.unitId)
            ?: return abstain(ProductionComponentAbstainReason.AUTHORITY_UNAVAILABLE)
        val taskOutcome = authorityTrace.taskOutcome
        val reviewed = request.replay.arm.requiresReviewedPolicyForFixture()
        val linked = if (reviewed) {
            exposureReceipt(request.replay, linkOutcome = true)
                ?: return abstain(ProductionComponentAbstainReason.COMPONENT_REJECTED)
        } else {
            null
        }
        if (reviewed && linked?.canAttributeObservedUtility != true) {
            return abstain(ProductionComponentAbstainReason.COMPONENT_REJECTED)
        }
        val policyOutcome = if (reviewed) taskOutcome else {
            BinaryObservation.Unknown(BinaryUnknownReason.OUTCOME_NOT_RECORDED)
        }
        return observed(
            PolicyOutcomeReplayObservation(
                taskOutcome = taskOutcome,
                harmfulOutcome = authorityTrace.harmfulOutcome,
                userCorrectionCount = authorityTrace.userCorrectionCount,
                outputTokens = authorityTrace.outputTokens,
                toolCalls = authorityTrace.toolCalls,
                toolRetries = authorityTrace.toolRetries,
                recordedLatency = authorityTrace.recordedLatency,
                policyOutcome = policyOutcome,
                deterministicJudge = authorityTrace.deterministicJudge,
                humanJudge = authorityTrace.humanJudge,
                llmJudge = authorityTrace.llmJudge,
                scriptActionCount = authorityTrace.scriptActionCount,
            ),
            operations = if (reviewed) 54L else 16L,
            allocations = if (reviewed) 12L else 4L,
        )
    }
}

private fun fixedDreamProjection(
    scopeId: DreamScopeId,
    frozenNowMs: Long,
): DreamSnapshotProjection.Available {
    val claimHash = DreamSha256("1".repeat(64))
    val claim = DreamRuntimeClaimProjection(
        ref = DreamRuntimeClaimRef("offline-eval-dream-claim-v1", 1L),
        scopeId = scopeId,
        section = DreamSnapshotSection.CURRENT_PROJECTS,
        ordinal = 0,
        snapshotState = DreamClaimState.ACTIVE_CONTEXTUAL,
        currentState = DreamClaimState.ACTIVE_CONTEXTUAL,
        currentRevision = 1L,
        currentVersionHash = claimHash,
        storageClass = DreamStorageClass.EPISODIC,
        epistemicType = DreamEpistemicType.PROJECT_STATE,
        title = "Offline evaluation project",
        statement = FIXED_DREAM_FRAGMENT,
        confidencePermille = 900,
        temporalState = TemporalState.CURRENT,
        validFromEpochMs = null,
        validToEpochMs = null,
        versionHash = claimHash,
        fragmentIntegrity = DreamRuntimeFragmentIntegrity.VERIFIED,
        sourceFence = DreamRuntimeSourceFence(
            validity = DreamRuntimeSourceValidity.CURRENT_CONFIRMED,
            validatedAtEpochMs = frozenNowMs,
            validatedClaimRevision = 1L,
            directAuthoritySourceCount = 1,
            directSupportingSourceCount = 1,
            indirectDerivedSourceCount = 0,
        ),
    )
    return DreamSnapshotProjection.Available(
        scopeId = scopeId,
        schemaVersion = DREAM_SNAPSHOT_SCHEMA_VERSION,
        snapshotId = "offline-eval-dream-snapshot-v1",
        activeSnapshotId = "offline-eval-dream-snapshot-v1",
        snapshotStatus = DreamRuntimeSnapshotStatus.ACTIVE,
        snapshotRevision = 1L,
        sourceMemoryEpoch = 1L,
        currentMemoryEpoch = 1L,
        committedDreamRevision = 1L,
        currentDreamRevision = 1L,
        payloadHash = DreamSha256("2".repeat(64)),
        payloadIntegrity = DreamRuntimePayloadIntegrity.VERIFIED,
        snapshotCompilerRevision = "dream-snapshot-v1",
        expectedClaimCount = 1,
        readConsistency = DreamRuntimeReadConsistency.ATOMIC,
        claims = listOf(claim),
    )
}

private fun fixedPolicyContext(
    request: ProductionComponentReplayRequest,
    fixture: FrozenProductionFixture,
): LearnedPolicyContextItem = LearnedPolicyContextItem(
    policyId = "offline-policy-${request.unit.fixtureId}",
    policyRevision = 1L,
    scope = fixture.scope,
    artifactSha256 = EvalDigest.sha256(
        "p5-fixture-policy-artifact-v1",
        listOf(request.unit.fixtureId),
    ),
    renderedFragment = FIXED_POLICY_FRAGMENT,
    estimatedTokens = FIXED_POLICY_TOKENS,
    priority = 100,
    rank = 1,
    policyCompilerRevision = "policy-context-v1",
    applicableModelIdentity = "EXACT_V1:${"b".repeat(64)}",
    applicableProviderIdentity = "EXACT_V1:${"c".repeat(64)}",
    applicableTemplateIdentity = "e".repeat(64),
    applicableConfigurationIdentity = "d".repeat(64),
    applicableConfigurationGeneration = 1L,
    applicableCapabilityDigest = null,
    applicableAuthorityDigest = null,
)

private fun exposureReceipt(
    request: ProductionComponentReplayRequest,
    linkOutcome: Boolean,
): PolicyExposureReceipt? {
    val fixture = FrozenProductionComponentReplayV1.fixture(request.unit) ?: return null
    val artifact = EvalDigest.sha256(
        "p5-fixture-policy-artifact-v1",
        listOf(request.unit.fixtureId),
    )
    val bundle = PolicyExposureBundle.create(
        listOf(
            PolicyExposurePolicyRef(
                policyId = "offline-policy-${request.unit.fixtureId}",
                policyRevision = 1L,
                artifactSha256 = artifact,
                scope = fixture.scope,
                rank = 1,
                estimatedTokens = FIXED_POLICY_TOKENS,
            applicabilityCohortDigest = "a".repeat(64),
            ),
        ),
    )
    val stream = stableUuid("stream", request.unit.fixtureId)
    val lineage = stableUuid("lineage", request.unit.fixtureId)
    val anchor = stableUuid("anchor", request.unit.fixtureId)
    val logicalRun = stableUuid("run-${request.arm.name}", request.unit.fixtureId)
    val reservation = PolicyExposureReservation(
        PolicyExposureReservationKey(
            streamId = stream,
            episodeId = EpisodeIdFactory.create(stream, lineage, anchor),
            logicalRunId = logicalRun,
            attemptOrdinal = 1,
            policySetDigest = bundle.policySetDigest,
        ),
        bundle,
    )
    var receipt = PolicyExposureReceipt.initial(reservation)
    val states = buildList {
        add(PolicyExposureState.COMPILED)
        add(PolicyExposureState.INJECTED)
        add(PolicyExposureState.HOST_DISPATCHED)
        if (linkOutcome) {
            add(PolicyExposureState.FIRST_PROGRESS)
            add(PolicyExposureState.RESPONSE_FINISHED)
        }
    }
    states.forEach { state ->
        receipt = when (
            val mutation = PolicyExposureStateMachine.observe(
                receipt,
                receipt.stateVersion,
                state,
            )
        ) {
            is PolicyExposureMutationResult.Applied -> mutation.receipt
            else -> return null
        }
    }
    if (linkOutcome) {
        receipt = when (
            val terminal = PolicyExposureStateMachine.recordTerminal(
                receipt,
                receipt.stateVersion,
                ProviderAttemptTerminalOutcome.COMPLETED,
            )
        ) {
            is PolicyExposureMutationResult.Applied -> terminal.receipt
            else -> return null
        }
        receipt = when (
            val linked = PolicyExposureStateMachine.observe(
                receipt,
                receipt.stateVersion,
                PolicyExposureState.OUTCOME_LINKED,
            )
        ) {
            is PolicyExposureMutationResult.Applied -> linked.receipt
            else -> return null
        }
    }
    return receipt
}

private fun stableUuid(domain: String, fixtureId: String): Uuid {
    val hex = LearningCanonicalId.digest("p5-fixture-uuid-v1", listOf(domain, fixtureId))
    val uuid = buildString(36) {
        append(hex.substring(0, 8)); append('-')
        append(hex.substring(8, 12)); append('-')
        append('4'); append(hex.substring(13, 16)); append('-')
        append('8'); append(hex.substring(17, 20)); append('-')
        append(hex.substring(20, 32))
    }
    return Uuid.parse(uuid)
}

private fun adapterIdentity(
    component: ProductionReplayComponent,
    implementation: String,
) = ProductionComponentAdapterIdentity(
    component = component,
    adapterVersion = FrozenProductionComponentReplayV1.FIXTURE_VERSION,
    implementationSha256 = EvalDigest.sha256(
        "p5-production-component-adapter-v2",
        listOf(
            component.name,
            implementation,
            FrozenProductionAdapterSourceBindingsV1.digestFor(component),
            FrozenProductionComponentReplayV1.FIXTURE_VERSION,
        ),
    ),
)

/**
 * Reviewed normalized-source snapshots of the production implementations exercised by each port.
 * Production identity uses only these embedded digests; a JVM contract test recomputes them from
 * checked-in UTF-8 source so implementation drift requires an explicit manifest re-baseline.
 */
internal object FrozenProductionAdapterSourceBindingsV1 {
    data class SourceBinding(val path: String, val normalizedUtf8Sha256: String)

    val sources: List<SourceBinding> = listOf(
        SourceBinding(
            "app/src/main/java/me/rerere/rikkahub/learning/adapters/DreamingIdentityAdapter.kt",
            "aae073bfd885ca34122a0e1050381239d26e7a19732c7fc85e254ad61035719b",
        ),
        SourceBinding(
            "app/src/main/java/me/rerere/rikkahub/learning/retrieval/PolicyRetriever.kt",
            "0563794c8f04ca1cd4c8c0d1394a443880af6f92f2ff0e53bbd3893564920507",
        ),
        SourceBinding(
            "app/src/main/java/me/rerere/rikkahub/data/ai/RecallPromptCompiler.kt",
            "2d47976179ee163367ec8a33e0a16ff7d54672c2caeb2ef2eb02fd83e88e2815",
        ),
        SourceBinding(
            "app/src/main/java/me/rerere/rikkahub/learning/exposure/PolicyExposureContracts.kt",
            "e836f3cef701fca10d287dbf46913a0a1c67cdb86da456d3c1f8f91de61c8fce",
        ),
        SourceBinding(
            "app/src/main/java/me/rerere/rikkahub/learning/eval/FrozenArmBlindAuthorityTraceV1.kt",
            "1288dd9785d8fe83985033d5e4483de8c59b363c4e29cb6e510b652f0e0e9edc",
        ),
    )

    fun digestFor(component: ProductionReplayComponent): String {
        val indexes = when (component) {
            ProductionReplayComponent.DREAM_PROJECTION -> listOf(0)
            ProductionReplayComponent.POLICY_RETRIEVAL -> listOf(1)
            ProductionReplayComponent.RECALL_COMPILER -> listOf(2)
            ProductionReplayComponent.POLICY_EXPOSURE -> listOf(3)
            ProductionReplayComponent.POLICY_OUTCOME -> listOf(3, 4)
        }
        return EvalDigest.sha256(
            "p5-production-adapter-source-binding-v1",
            indexes.flatMap { index ->
                val binding = sources[index]
                listOf(binding.path, binding.normalizedUtf8Sha256)
            },
        )
    }
}

private fun <T> observed(
    value: T,
    operations: Long,
    allocations: Long,
) = ProductionComponentReplayResult.Observed(
    value,
    DeterministicComponentWork(operations, allocations),
)

private fun abstain(reason: ProductionComponentAbstainReason) =
    ProductionComponentReplayResult.Abstained(reason)

private fun OfflineEvalArm.requiresReviewedPolicyForFixture(): Boolean =
    this == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
        this == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS

private const val FROZEN_NOW_MS = 1_700_000_000_000L
private val FIXTURE_ASSISTANT_ID = Uuid.parse("11111111-1111-4111-8111-111111111111")
private const val FIXED_POLICY_QUERY = "reviewed retry strategy"
private const val FIXED_POLICY_FRAGMENT =
    "Verify the bounded result once; retry only after an explicit transient failure."
private const val FIXED_POLICY_TOKENS = 18
private const val FIXED_DREAM_FRAGMENT =
    "The offline fixture project is currently validating deterministic replay."

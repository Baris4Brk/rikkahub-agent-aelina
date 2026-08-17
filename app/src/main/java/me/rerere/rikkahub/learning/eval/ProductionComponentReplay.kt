package me.rerere.rikkahub.learning.eval

import kotlinx.coroutines.CancellationException

/** Production components exercised independently by the fixed four-arm replay runner. */
enum class ProductionReplayComponent {
    DREAM_PROJECTION,
    POLICY_RETRIEVAL,
    RECALL_COMPILER,
    POLICY_EXPOSURE,
    POLICY_OUTCOME,
}

data class ProductionComponentAdapterIdentity(
    val component: ProductionReplayComponent,
    val adapterVersion: String,
    val implementationSha256: String,
) {
    init {
        requireSafeEvalLabel(adapterVersion)
        require(implementationSha256.isEvalSha256())
    }
}

/** Counters returned by the invoked component adapter, not inferred from fixture scenarios. */
data class DeterministicComponentWork(
    val operationUnits: Long,
    val logicalAllocationUnits: Long,
) {
    init {
        require(operationUnits >= 0L && logicalAllocationUnits >= 0L)
    }

    operator fun plus(other: DeterministicComponentWork): DeterministicComponentWork =
        DeterministicComponentWork(
            operationUnits = Math.addExact(operationUnits, other.operationUnits),
            logicalAllocationUnits = Math.addExact(
                logicalAllocationUnits,
                other.logicalAllocationUnits,
            ),
        )

    companion object {
        val ZERO = DeterministicComponentWork(0L, 0L)
    }
}

enum class ProductionComponentAbstainReason {
    NOT_CONFIGURED,
    FIXTURE_NOT_AVAILABLE,
    AUTHORITY_UNAVAILABLE,
    INPUT_NOT_AVAILABLE,
    COMPONENT_REJECTED,
    COMPONENT_EXCEPTION,
    BLOCKED_BY_DEPENDENCY,
}

sealed interface ProductionComponentReplayResult<out T> {
    data class Observed<T>(
        val observation: T,
        val work: DeterministicComponentWork,
    ) : ProductionComponentReplayResult<T>

    data class Abstained(
        val reason: ProductionComponentAbstainReason,
        val work: DeterministicComponentWork = DeterministicComponentWork.ZERO,
    ) : ProductionComponentReplayResult<Nothing>
}

data class ProductionComponentReplayRequest(
    val unit: OfflineReplayUnit,
    val arm: OfflineEvalArm,
)

data class DreamProjectionReplayObservation(
    val projectedItemCount: Int,
) {
    init {
        require(projectedItemCount >= 0)
    }
}

data class PolicyRetrievalReplayObservation(
    val candidateCount: Int,
    val retrievalTokens: Int,
    val scopeLeakCount: Int,
    val staleHitCount: Int,
) {
    init {
        require(candidateCount >= 0 && retrievalTokens >= 0)
        require(scopeLeakCount >= 0 && staleHitCount >= 0)
    }
}

data class RecallCompilerReplayObservation(
    val inputTokens: Int,
    val contextTokens: Int,
) {
    init {
        require(inputTokens >= 0 && contextTokens >= 0)
    }
}

data class PolicyExposureReplayObservation(
    val compiledCount: Int,
    val dispatchCount: Int,
) {
    init {
        require(compiledCount >= 0 && dispatchCount in 0..compiledCount)
    }
}

/** Final trace observation returned by the actual outcome adapter. */
data class PolicyOutcomeReplayObservation(
    val taskOutcome: BinaryObservation,
    val harmfulOutcome: BinaryObservation,
    val userCorrectionCount: Int,
    val outputTokens: Int,
    val toolCalls: Int,
    val toolRetries: Int,
    val recordedLatency: RecordedLatencyObservation,
    val policyOutcome: BinaryObservation,
    val deterministicJudge: JudgeVerdict,
    val humanJudge: JudgeVerdict,
    val llmJudge: JudgeVerdict,
    val scriptActionCount: Int,
) {
    init {
        require(userCorrectionCount >= 0 && outputTokens >= 0)
        require(toolCalls >= 0 && toolRetries in 0..toolCalls)
        require(scriptActionCount >= 0)
    }
}

data class RecallCompilerReplayRequest(
    val replay: ProductionComponentReplayRequest,
    val dream: DreamProjectionReplayObservation?,
    val retrieval: PolicyRetrievalReplayObservation?,
)

data class PolicyExposureReplayRequest(
    val replay: ProductionComponentReplayRequest,
    val retrieval: PolicyRetrievalReplayObservation,
    val recall: RecallCompilerReplayObservation,
)

data class PolicyOutcomeReplayRequest(
    val replay: ProductionComponentReplayRequest,
    val dream: DreamProjectionReplayObservation?,
    val retrieval: PolicyRetrievalReplayObservation?,
    val recall: RecallCompilerReplayObservation,
    val exposure: PolicyExposureReplayObservation?,
)

interface DreamProjectionReplayPort {
    val identity: ProductionComponentAdapterIdentity

    /** Invoke the real Dream projection adapter for this frozen fixture identity. */
    suspend fun project(
        request: ProductionComponentReplayRequest,
    ): ProductionComponentReplayResult<DreamProjectionReplayObservation>
}

interface PolicyRetrievalReplayPort {
    val identity: ProductionComponentAdapterIdentity

    /** Invoke the real reviewed-Policy retrieval adapter. */
    suspend fun retrieve(
        request: ProductionComponentReplayRequest,
        dream: DreamProjectionReplayObservation?,
    ): ProductionComponentReplayResult<PolicyRetrievalReplayObservation>
}

interface RecallCompilerReplayPort {
    val identity: ProductionComponentAdapterIdentity

    /** Invoke the real Recall compiler with only outputs returned by earlier adapters. */
    suspend fun compile(
        request: RecallCompilerReplayRequest,
    ): ProductionComponentReplayResult<RecallCompilerReplayObservation>
}

interface PolicyExposureReplayPort {
    val identity: ProductionComponentAdapterIdentity

    /** Invoke the real exposure/dispatch accounting adapter. */
    suspend fun expose(
        request: PolicyExposureReplayRequest,
    ): ProductionComponentReplayResult<PolicyExposureReplayObservation>
}

interface PolicyOutcomeReplayPort {
    val identity: ProductionComponentAdapterIdentity

    /** Invoke the real outcome-linking projection and return its recorded trace observation. */
    suspend fun observe(
        request: PolicyOutcomeReplayRequest,
    ): ProductionComponentReplayResult<PolicyOutcomeReplayObservation>
}

data class ProductionComponentReplayAdapters(
    val dreaming: DreamProjectionReplayPort,
    val retrieval: PolicyRetrievalReplayPort,
    val recall: RecallCompilerReplayPort,
    val exposure: PolicyExposureReplayPort,
    val outcome: PolicyOutcomeReplayPort,
) {
    init {
        require(dreaming.identity.component == ProductionReplayComponent.DREAM_PROJECTION)
        require(retrieval.identity.component == ProductionReplayComponent.POLICY_RETRIEVAL)
        require(recall.identity.component == ProductionReplayComponent.RECALL_COMPILER)
        require(exposure.identity.component == ProductionReplayComponent.POLICY_EXPOSURE)
        require(outcome.identity.component == ProductionReplayComponent.POLICY_OUTCOME)
    }

    val identities: List<ProductionComponentAdapterIdentity> = listOf(
        dreaming.identity,
        retrieval.identity,
        recall.identity,
        exposure.identity,
        outcome.identity,
    ).also { identities ->
        require(identities.map { it.component }.toSet() == ProductionReplayComponent.entries.toSet())
        require(identities.map { it.component }.distinct().size == identities.size)
    }.sortedBy { it.component.ordinal }

    companion object {
        /** Fail-closed default. It can produce only abstentions, never synthetic success. */
        fun unconfigured(): ProductionComponentReplayAdapters = ProductionComponentReplayAdapters(
            dreaming = unavailableDreamProjectionPort(),
            retrieval = unavailablePolicyRetrievalPort(),
            recall = unavailableRecallCompilerPort(),
            exposure = unavailablePolicyExposurePort(),
            outcome = unavailablePolicyOutcomePort(),
        )
    }
}

enum class ProductionComponentReceiptState {
    OBSERVED,
    SKIPPED_BY_ARM,
    ABSTAINED,
}

data class ProductionComponentReplayReceipt(
    val unitId: String,
    val arm: OfflineEvalArm,
    val component: ProductionReplayComponent,
    val state: ProductionComponentReceiptState,
    val abstainReason: ProductionComponentAbstainReason?,
    val work: DeterministicComponentWork,
) {
    init {
        requireSafeEvalLabel(unitId)
        require((state == ProductionComponentReceiptState.ABSTAINED) == (abstainReason != null))
        if (state == ProductionComponentReceiptState.SKIPPED_BY_ARM) {
            require(work == DeterministicComponentWork.ZERO)
        }
    }
}

data class ProductionComponentReplayRun(
    val observations: List<OfflineReplayObservation>,
    val receipts: List<ProductionComponentReplayReceipt>,
    val report: OfflineEvalReport,
    val adapterIdentities: List<ProductionComponentAdapterIdentity>,
) {
    init {
        require(observations.size == FrozenReplayCorpusV1.units.size * OfflineEvalArm.entries.size)
        require(
            receipts.size == observations.size * ProductionReplayComponent.entries.size,
        )
        require(adapterIdentities.map { it.component } ==
            ProductionReplayComponent.entries.toList())
    }

    val abstainedComponentCount: Int
        get() = receipts.count { it.state == ProductionComponentReceiptState.ABSTAINED }

    val componentCoverage: List<ProductionComponentCoverageSummary>
        get() = ProductionReplayComponent.entries.map { component ->
            val componentReceipts = receipts.filter { it.component == component }
            ProductionComponentCoverageSummary(
                component = component,
                observedCount = componentReceipts.count {
                    it.state == ProductionComponentReceiptState.OBSERVED
                },
                skippedCount = componentReceipts.count {
                    it.state == ProductionComponentReceiptState.SKIPPED_BY_ARM
                },
                abstainedCount = componentReceipts.count {
                    it.state == ProductionComponentReceiptState.ABSTAINED
                },
            )
        }

    /** Exact sum of work returned by component adapters; excludes harness aggregation work. */
    val runnerPerformance: PerformanceCounterSnapshot
        get() = receipts.fold(DeterministicComponentWork.ZERO) { total, receipt ->
            total + receipt.work
        }.let { work ->
            PerformanceCounterSnapshot(
                deterministicOperationUnits = work.operationUnits,
                logicalAllocationUnits = work.logicalAllocationUnits,
            )
        }
}

data class ProductionComponentCoverageSummary(
    val component: ProductionReplayComponent,
    val observedCount: Int,
    val skippedCount: Int,
    val abstainedCount: Int,
) {
    init {
        require(observedCount >= 0 && skippedCount >= 0 && abstainedCount >= 0)
        require(
            observedCount + skippedCount + abstainedCount == FrozenReplayCorpusV1.units.size *
                OfflineEvalArm.entries.size,
        )
    }
}

/**
 * Fixed four-arm fixture runner. Scenario labels are never consulted for outcomes: every observed
 * value and every component counter comes from an injected adapter result.
 */
class ProductionFourArmFixtureRunner(
    private val adapters: ProductionComponentReplayAdapters =
        ProductionComponentReplayAdapters.unconfigured(),
) {
    suspend fun run(): ProductionComponentReplayRun {
        val corpus = FrozenReplayCorpusV1.units
        val plan = FrozenOfflineLearningEvaluation.plan
        val attempts = mutableListOf<ProductionReplayAttempt>()
        for (unit in corpus.sortedBy(OfflineReplayUnit::unitId)) {
            for (arm in plan.arms) attempts += replay(unit, arm)
        }
        val observations = attempts.map(ProductionReplayAttempt::observation)
        val report = OfflineLearningEvalHarness.summarizeObserved(
            corpus = corpus,
            plan = plan,
            observations = observations,
            corpusId = FrozenReplayCorpusV1.CORPUS_ID,
        )
        return ProductionComponentReplayRun(
            observations = observations,
            receipts = attempts.flatMap(ProductionReplayAttempt::receipts),
            report = report,
            adapterIdentities = adapters.identities,
        )
    }

    private suspend fun replay(
        unit: OfflineReplayUnit,
        arm: OfflineEvalArm,
    ): ProductionReplayAttempt {
        val request = ProductionComponentReplayRequest(unit, arm)
        val receipts = mutableListOf<ProductionComponentReplayReceipt>()
        var blocked = false

        val dream = if (arm.requiresDreamProjection()) {
            invoke(
                request,
                ProductionReplayComponent.DREAM_PROJECTION,
                receipts,
                adapters.dreaming::project,
            ).also { if (it == null) blocked = true }
        } else {
            receipts += request.skipped(ProductionReplayComponent.DREAM_PROJECTION)
            null
        }

        val retrieval = if (arm.requiresReviewedPolicy() && !blocked) {
            invoke(request, ProductionReplayComponent.POLICY_RETRIEVAL, receipts) {
                adapters.retrieval.retrieve(it, dream)
            }.also { if (it == null) blocked = true }
        } else {
            receipts += if (blocked && arm.requiresReviewedPolicy()) {
                request.blocked(ProductionReplayComponent.POLICY_RETRIEVAL)
            } else {
                request.skipped(ProductionReplayComponent.POLICY_RETRIEVAL)
            }
            null
        }

        val recall = if (!blocked) {
            invoke(request, ProductionReplayComponent.RECALL_COMPILER, receipts) {
                adapters.recall.compile(RecallCompilerReplayRequest(it, dream, retrieval))
            }.also { if (it == null) blocked = true }
        } else {
            receipts += request.blocked(ProductionReplayComponent.RECALL_COMPILER)
            null
        }

        val exposure = if (arm.requiresReviewedPolicy() && !blocked &&
            retrieval != null && recall != null
        ) {
            invoke(request, ProductionReplayComponent.POLICY_EXPOSURE, receipts) {
                adapters.exposure.expose(PolicyExposureReplayRequest(it, retrieval, recall))
            }.also { if (it == null) blocked = true }
        } else {
            receipts += if (blocked && arm.requiresReviewedPolicy()) {
                request.blocked(ProductionReplayComponent.POLICY_EXPOSURE)
            } else {
                request.skipped(ProductionReplayComponent.POLICY_EXPOSURE)
            }
            null
        }

        val outcome = if (!blocked && recall != null) {
            invoke(request, ProductionReplayComponent.POLICY_OUTCOME, receipts) {
                adapters.outcome.observe(
                    PolicyOutcomeReplayRequest(it, dream, retrieval, recall, exposure),
                )
            }
        } else {
            receipts += request.blocked(ProductionReplayComponent.POLICY_OUTCOME)
            null
        }

        require(receipts.map { it.component }.toSet() == ProductionReplayComponent.entries.toSet())
        val work = receipts.fold(DeterministicComponentWork.ZERO) { total, receipt ->
            total + receipt.work
        }
        val observation = if (outcome == null || recall == null) {
            abstainedObservation(request, retrieval, exposure, work)
        } else {
            observedResult(request, recall, retrieval, exposure, outcome, work)
        }
        return ProductionReplayAttempt(observation, receipts.sortedBy { it.component.ordinal })
    }

    private suspend fun <T> invoke(
        request: ProductionComponentReplayRequest,
        component: ProductionReplayComponent,
        receipts: MutableList<ProductionComponentReplayReceipt>,
        call: suspend (ProductionComponentReplayRequest) -> ProductionComponentReplayResult<T>,
    ): T? {
        val result = try {
            call(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ProductionComponentReplayResult.Abstained(
                ProductionComponentAbstainReason.COMPONENT_EXCEPTION,
            )
        }
        return when (result) {
            is ProductionComponentReplayResult.Observed -> {
                receipts += request.observed(component, result.work)
                result.observation
            }
            is ProductionComponentReplayResult.Abstained -> {
                receipts += request.abstained(component, result.reason, result.work)
                null
            }
        }
    }
}

private data class ProductionReplayAttempt(
    val observation: OfflineReplayObservation,
    val receipts: List<ProductionComponentReplayReceipt>,
)

private fun observedResult(
    request: ProductionComponentReplayRequest,
    recall: RecallCompilerReplayObservation,
    retrieval: PolicyRetrievalReplayObservation?,
    exposure: PolicyExposureReplayObservation?,
    outcome: PolicyOutcomeReplayObservation,
    work: DeterministicComponentWork,
): OfflineReplayObservation {
    val candidateCount = retrieval?.candidateCount ?: 0
    val compiledCount = exposure?.compiledCount ?: 0
    val dispatchCount = exposure?.dispatchCount ?: 0
    require(compiledCount <= candidateCount)
    if (!request.arm.requiresReviewedPolicy()) {
        require(retrieval == null && exposure == null)
    }
    return OfflineReplayObservation(
        unitId = request.unit.unitId,
        arm = request.arm,
        taskOutcome = outcome.taskOutcome,
        harmfulOutcome = outcome.harmfulOutcome,
        userCorrectionCount = outcome.userCorrectionCount,
        resources = ReplayResourceObservation(
            inputTokens = recall.inputTokens,
            outputTokens = outcome.outputTokens,
            retrievalTokens = retrieval?.retrievalTokens ?: 0,
            contextTokens = recall.contextTokens,
            toolCalls = outcome.toolCalls,
            toolRetries = outcome.toolRetries,
        ),
        recordedLatency = outcome.recordedLatency,
        policy = PolicyFunnelObservation(
            candidateCount = candidateCount,
            compiledCount = compiledCount,
            dispatchCount = dispatchCount,
            outcome = outcome.policyOutcome,
        ),
        scopeLeakCount = retrieval?.scopeLeakCount ?: 0,
        staleHitCount = retrieval?.staleHitCount ?: 0,
        deterministicJudge = outcome.deterministicJudge,
        humanJudge = outcome.humanJudge,
        llmJudge = outcome.llmJudge,
        scriptActionCount = outcome.scriptActionCount,
        jvmTrend = JvmTrendObservation(work.operationUnits, work.logicalAllocationUnits),
    )
}

private fun abstainedObservation(
    request: ProductionComponentReplayRequest,
    retrieval: PolicyRetrievalReplayObservation?,
    exposure: PolicyExposureReplayObservation?,
    work: DeterministicComponentWork,
): OfflineReplayObservation = OfflineReplayObservation(
    unitId = request.unit.unitId,
    arm = request.arm,
    taskOutcome = BinaryObservation.Unknown(
        BinaryUnknownReason.PRODUCTION_COMPONENT_ABSTAINED,
    ),
    harmfulOutcome = BinaryObservation.Unknown(
        BinaryUnknownReason.PRODUCTION_COMPONENT_ABSTAINED,
    ),
    userCorrectionCount = 0,
    resources = ReplayResourceObservation(
        inputTokens = 0,
        outputTokens = 0,
        retrievalTokens = retrieval?.retrievalTokens ?: 0,
        contextTokens = 0,
        toolCalls = 0,
        toolRetries = 0,
    ),
    recordedLatency = RecordedLatencyObservation(null, null),
    policy = PolicyFunnelObservation(
        candidateCount = retrieval?.candidateCount ?: 0,
        compiledCount = exposure?.compiledCount ?: 0,
        dispatchCount = exposure?.dispatchCount ?: 0,
        outcome = BinaryObservation.Unknown(
            BinaryUnknownReason.PRODUCTION_COMPONENT_ABSTAINED,
        ),
    ),
    scopeLeakCount = retrieval?.scopeLeakCount ?: 0,
    staleHitCount = retrieval?.staleHitCount ?: 0,
    deterministicJudge = JudgeVerdict.UNKNOWN,
    humanJudge = JudgeVerdict.UNKNOWN,
    llmJudge = JudgeVerdict.UNKNOWN,
    scriptActionCount = 0,
    jvmTrend = JvmTrendObservation(work.operationUnits, work.logicalAllocationUnits),
)

private fun OfflineEvalArm.requiresDreamProjection(): Boolean =
    this != OfflineEvalArm.A_NO_LEARNING

private fun OfflineEvalArm.requiresReviewedPolicy(): Boolean =
    this == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
        this == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS

private fun ProductionComponentReplayRequest.observed(
    component: ProductionReplayComponent,
    work: DeterministicComponentWork,
) = ProductionComponentReplayReceipt(
    unitId = unit.unitId,
    arm = arm,
    component = component,
    state = ProductionComponentReceiptState.OBSERVED,
    abstainReason = null,
    work = work,
)

private fun ProductionComponentReplayRequest.skipped(component: ProductionReplayComponent) =
    ProductionComponentReplayReceipt(
        unitId = unit.unitId,
        arm = arm,
        component = component,
        state = ProductionComponentReceiptState.SKIPPED_BY_ARM,
        abstainReason = null,
        work = DeterministicComponentWork.ZERO,
    )

private fun ProductionComponentReplayRequest.blocked(component: ProductionReplayComponent) =
    abstained(
        component,
        ProductionComponentAbstainReason.BLOCKED_BY_DEPENDENCY,
        DeterministicComponentWork.ZERO,
    )

private fun ProductionComponentReplayRequest.abstained(
    component: ProductionReplayComponent,
    reason: ProductionComponentAbstainReason,
    work: DeterministicComponentWork,
) = ProductionComponentReplayReceipt(
    unitId = unit.unitId,
    arm = arm,
    component = component,
    state = ProductionComponentReceiptState.ABSTAINED,
    abstainReason = reason,
    work = work,
)

private fun unavailableIdentity(component: ProductionReplayComponent) =
    ProductionComponentAdapterIdentity(
        component = component,
        adapterVersion = "production-component-unconfigured-v1",
        implementationSha256 = EvalDigest.sha256(
            "production-component-unconfigured-v1",
            listOf(component.name),
        ),
    )

private fun unavailableDreamProjectionPort() = object : DreamProjectionReplayPort {
    override val identity = unavailableIdentity(ProductionReplayComponent.DREAM_PROJECTION)
    override suspend fun project(request: ProductionComponentReplayRequest) =
        ProductionComponentReplayResult.Abstained(ProductionComponentAbstainReason.NOT_CONFIGURED)
}

private fun unavailablePolicyRetrievalPort() = object : PolicyRetrievalReplayPort {
    override val identity = unavailableIdentity(ProductionReplayComponent.POLICY_RETRIEVAL)
    override suspend fun retrieve(
        request: ProductionComponentReplayRequest,
        dream: DreamProjectionReplayObservation?,
    ) = ProductionComponentReplayResult.Abstained(
        ProductionComponentAbstainReason.NOT_CONFIGURED,
    )
}

private fun unavailableRecallCompilerPort() = object : RecallCompilerReplayPort {
    override val identity = unavailableIdentity(ProductionReplayComponent.RECALL_COMPILER)
    override suspend fun compile(request: RecallCompilerReplayRequest) =
        ProductionComponentReplayResult.Abstained(ProductionComponentAbstainReason.NOT_CONFIGURED)
}

private fun unavailablePolicyExposurePort() = object : PolicyExposureReplayPort {
    override val identity = unavailableIdentity(ProductionReplayComponent.POLICY_EXPOSURE)
    override suspend fun expose(request: PolicyExposureReplayRequest) =
        ProductionComponentReplayResult.Abstained(ProductionComponentAbstainReason.NOT_CONFIGURED)
}

private fun unavailablePolicyOutcomePort() = object : PolicyOutcomeReplayPort {
    override val identity = unavailableIdentity(ProductionReplayComponent.POLICY_OUTCOME)
    override suspend fun observe(request: PolicyOutcomeReplayRequest) =
        ProductionComponentReplayResult.Abstained(ProductionComponentAbstainReason.NOT_CONFIGURED)
}

internal fun String.isEvalSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

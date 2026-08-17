package me.rerere.rikkahub.learning.eval

import kotlin.math.ceil

object OfflineLearningEvalHarness {
    fun run(
        corpus: List<OfflineReplayUnit>,
        plan: OfflineEvalPlan,
        executor: OfflineReplayExecutor,
        corpusId: String,
    ): OfflineEvalReport {
        val observations = corpus.sortedBy(OfflineReplayUnit::unitId).flatMap { unit ->
            plan.arms.map { arm ->
                executor.replay(unit, arm).also { observed ->
                    require(observed.unitId == unit.unitId) { "Replay executor changed unit identity" }
                    require(observed.arm == arm) { "Replay executor changed arm identity" }
                    require(observed.scriptActionCount == 0) {
                        "P5 full reviewed runtime forbids JS/script replay actions"
                    }
                }
            }
        }
        return summarizeObserved(corpus, plan, observations, corpusId)
    }

    /**
     * Summarizes observations returned by an independently invoked production-component runner.
     * The method never fills a missing arm, guesses an outcome, or calls the synthetic fixture.
     */
    fun summarizeObserved(
        corpus: List<OfflineReplayUnit>,
        plan: OfflineEvalPlan,
        observations: List<OfflineReplayObservation>,
        corpusId: String,
    ): OfflineEvalReport {
        validateCorpus(corpus, corpusId)
        validateObservations(corpus, plan, observations)
        val planDigest = plan.digestSha256()
        val assignments = PreRegisteredAssignmentEngine.assign(corpus, plan)
        val assignmentByUnit = assignments.associateBy(PreRegisteredAssignment::unitId)
        val work = EvalWorkLedger(
            operationUnits = Math.addExact(
                observations.fold(0L) { total, observation ->
                    Math.addExact(total, observation.jvmTrend.operationUnits)
                },
                corpus.size.toLong() * ASSIGNMENT_OPERATION_UNITS,
            ),
            allocationUnits = Math.addExact(
                observations.fold(0L) { total, observation ->
                    Math.addExact(total, observation.jvmTrend.logicalAllocationUnits)
                },
                observations.size.toLong(),
            ),
        )
        val armSummaries = plan.arms.map { arm ->
            summarizeArm(
                arm = arm,
                observations = observations.filter { it.arm == arm },
                plan = plan,
                planDigest = planDigest,
                work = work,
            )
        }
        val partitionSummaries = EvalPartition.entries.flatMap { partition ->
            plan.arms.map { arm ->
                val selected = observations.filter { observation ->
                    observation.arm == arm &&
                        assignmentByUnit.getValue(observation.unitId).partition == partition
                }
                PartitionArmSummary(
                    partition = partition,
                    arm = arm,
                    sampleSize = selected.size,
                    taskSuccess = summarizeBinary(
                        selected.map(OfflineReplayObservation::taskOutcome),
                        plan,
                        planDigest,
                        "partition-${partition.name}-${arm.name}",
                        work,
                    ),
                )
            }
        }
        val slices = sliceReports(corpus, observations, plan, planDigest, work)
        val associations = plan.arms.drop(1).map { comparison ->
            matchedAssociation(observations, comparison, plan, planDigest, work)
        }
        val matchedCounts = observations.groupBy(OfflineReplayObservation::unitId)
            .values.map { rows -> rows.map(OfflineReplayObservation::arm).toSet().size }
        work.addOperations(observations.size.toLong() * JUDGE_COMPARISON_OPERATION_UNITS)
        work.addAllocations(
            (armSummaries.size + partitionSummaries.size + slices.size + associations.size).toLong(),
        )
        return OfflineEvalReport(
            schemaVersion = 1,
            corpusId = corpusId,
            corpusDigestSha256 = corpusDigest(corpusId, corpus),
            planId = plan.planId,
            planDigestSha256 = planDigest,
            assignmentManifestSha256 = PreRegisteredAssignmentEngine.manifestDigest(assignments),
            matchedCohortCount = matchedCounts.count { it == plan.arms.size },
            incompleteMatchedCohortCount = matchedCounts.count { it != plan.arms.size },
            holdoutUnitCount = assignments.count { it.partition == EvalPartition.HOLDOUT },
            assignments = assignments,
            arms = armSummaries,
            partitions = partitionSummaries,
            slices = slices,
            associations = associations,
            judgeDivergence = judgeDivergence(observations),
            performance = PerformanceCounterSnapshot(
                deterministicOperationUnits = work.operationUnits,
                logicalAllocationUnits = work.allocationUnits,
            ),
            energy = EnergyAssessment.offlineJvm(),
        )
    }

    private fun validateObservations(
        corpus: List<OfflineReplayUnit>,
        plan: OfflineEvalPlan,
        observations: List<OfflineReplayObservation>,
    ) {
        val expected = corpus.flatMap { unit -> plan.arms.map { arm -> unit.unitId to arm } }.toSet()
        val actual = observations.map { observation -> observation.unitId to observation.arm }
        require(actual.size == expected.size && actual.toSet() == expected) {
            "Replay observations do not form the exact frozen four-arm matrix"
        }
        require(actual.distinct().size == actual.size) { "Duplicate replay observation" }
        require(observations.all { it.scriptActionCount == 0 }) {
            "P5 full reviewed runtime forbids JS/script replay actions"
        }
    }

    private fun validateCorpus(corpus: List<OfflineReplayUnit>, corpusId: String) {
        requireSafeEvalLabel(corpusId)
        require(corpus.size in 4..MAX_CORPUS_UNITS)
        require(corpus.map(OfflineReplayUnit::unitId).distinct().size == corpus.size)
        require(corpus.map(OfflineReplayUnit::matchedCohortId).distinct().size == corpus.size) {
            "Each frozen unit is one matched four-arm replay cohort"
        }
        EvalSliceDimension.entries.forEach { dimension ->
            require(corpus.map { it.slice.dimensions().getValue(dimension) }.distinct().size >= 2) {
                "Corpus does not cover slice $dimension"
            }
        }
    }

    private fun summarizeArm(
        arm: OfflineEvalArm,
        observations: List<OfflineReplayObservation>,
        plan: OfflineEvalPlan,
        planDigest: String,
        work: EvalWorkLedger,
    ): ArmEvalSummary = ArmEvalSummary(
        arm = arm,
        sampleSize = observations.size,
        taskSuccess = summarizeBinary(
            observations.map(OfflineReplayObservation::taskOutcome),
            plan,
            planDigest,
            "task-success-${arm.name}",
            work,
        ),
        harmfulRate = summarizeBinary(
            observations.map(OfflineReplayObservation::harmfulOutcome),
            plan,
            planDigest,
            "harmful-${arm.name}",
            work,
        ),
        userCorrectionCount = observations.sumOf { it.userCorrectionCount.toLong() },
        toolCallCount = observations.sumOf { it.resources.toolCalls.toLong() },
        toolRetryCount = observations.sumOf { it.resources.toolRetries.toLong() },
        inputTokens = observations.sumOf { it.resources.inputTokens.toLong() },
        outputTokens = observations.sumOf { it.resources.outputTokens.toLong() },
        retrievalTokens = observations.sumOf { it.resources.retrievalTokens.toLong() },
        contextTokens = observations.sumOf { it.resources.contextTokens.toLong() },
        recordedTtft = recordedDistribution(observations.map { it.recordedLatency.ttftMicros }),
        recordedToolToNextModel = recordedDistribution(
            observations.map { it.recordedLatency.toolToNextModelMicros },
        ),
        policyCandidateCount = observations.sumOf { it.policy.candidateCount.toLong() },
        policyCompiledCount = observations.sumOf { it.policy.compiledCount.toLong() },
        policyDispatchCount = observations.sumOf { it.policy.dispatchCount.toLong() },
        policyOutcome = summarizeBinary(
            observations.map { it.policy.outcome },
            plan,
            planDigest,
            "policy-outcome-${arm.name}",
            work,
        ),
        scopeLeakCount = observations.sumOf { it.scopeLeakCount.toLong() },
        staleHitCount = observations.sumOf { it.staleHitCount.toLong() },
        scriptActionCount = observations.sumOf { it.scriptActionCount.toLong() },
    )

    private fun sliceReports(
        corpus: List<OfflineReplayUnit>,
        observations: List<OfflineReplayObservation>,
        plan: OfflineEvalPlan,
        planDigest: String,
        work: EvalWorkLedger,
    ): List<SliceEvalSummary> {
        val unitById = corpus.associateBy(OfflineReplayUnit::unitId)
        val rows = buildList {
            EvalSliceDimension.entries.forEach { dimension ->
                val values = corpus.map { it.slice.dimensions().getValue(dimension) }
                    .distinct().sorted()
                values.forEach { value ->
                    plan.arms.forEach { arm ->
                        val selected = observations.filter { observation ->
                            observation.arm == arm && unitById.getValue(observation.unitId)
                                .slice.dimensions().getValue(dimension) == value
                        }
                        add(
                            SliceEvalSummary(
                                dimension = dimension,
                                value = value,
                                arm = arm,
                                sampleSize = selected.size,
                                taskSuccess = summarizeBinary(
                                    selected.map(OfflineReplayObservation::taskOutcome),
                                    plan,
                                    planDigest,
                                    "slice-${dimension.name}-$value-${arm.name}",
                                    work,
                                ),
                                scopeLeakCount = selected.sumOf { it.scopeLeakCount.toLong() },
                                staleHitCount = selected.sumOf { it.staleHitCount.toLong() },
                                harmfulRate = summarizeBinary(
                                    selected.map(OfflineReplayObservation::harmfulOutcome),
                                    plan,
                                    planDigest,
                                    "slice-harm-${dimension.name}-$value-${arm.name}",
                                    work,
                                ),
                            ),
                        )
                    }
                }
            }
        }
        require(rows.size <= MAX_EVAL_SLICE_REPORTS)
        work.addOperations(observations.size.toLong() * EvalSliceDimension.entries.size)
        return rows.sortedWith(
            compareBy<SliceEvalSummary> { it.dimension.ordinal }
                .thenBy(SliceEvalSummary::value)
                .thenBy { it.arm.ordinal },
        )
    }

    private fun summarizeBinary(
        values: List<BinaryObservation>,
        plan: OfflineEvalPlan,
        planDigest: String,
        metricKey: String,
        work: EvalWorkLedger,
    ): BinaryMetricSummary {
        val observed = values.filterIsInstance<BinaryObservation.Observed>()
        val samples = observed.map { if (it.value) 1.0 else 0.0 }
        work.addOperations(values.size.toLong())
        if (samples.isNotEmpty()) {
            work.addOperations(samples.size.toLong() * plan.bootstrap.resamples)
            work.addAllocations(plan.bootstrap.resamples + 1L)
        }
        return BinaryMetricSummary(
            observedCount = observed.size,
            positiveCount = observed.count(BinaryObservation.Observed::value),
            unknownCount = values.count { it is BinaryObservation.Unknown },
            censoredCount = values.count { it is BinaryObservation.Censored },
            estimate = if (samples.isEmpty()) null else samples.average(),
            bootstrapCi = DeterministicBootstrap.mean(
                samples,
                plan.bootstrap,
                planDigest,
                metricKey,
            ),
        )
    }

    private fun recordedDistribution(values: List<Long?>): RecordedDistributionSummary {
        val measured = values.filterNotNull().sorted()
        if (measured.isEmpty()) {
            return RecordedDistributionSummary(
                MeasurementKnowledge.UNMEASURED,
                sampleCount = 0,
                p50 = null,
                p95 = null,
            )
        }
        return RecordedDistributionSummary(
            MeasurementKnowledge.MEASURED,
            sampleCount = measured.size,
            p50 = percentile(measured, 0.50),
            p95 = percentile(measured, 0.95),
        )
    }

    private fun matchedAssociation(
        observations: List<OfflineReplayObservation>,
        comparison: OfflineEvalArm,
        plan: OfflineEvalPlan,
        planDigest: String,
        work: EvalWorkLedger,
    ): MatchedObservedAssociation {
        val byUnit = observations.groupBy(OfflineReplayObservation::unitId)
        val differences = mutableListOf<Double>()
        var unknown = 0
        var censored = 0
        byUnit.toSortedMap().values.forEach { rows ->
            val baseline = rows.single { it.arm == OfflineEvalArm.A_NO_LEARNING }.taskOutcome
            val compared = rows.single { it.arm == comparison }.taskOutcome
            when {
                baseline is BinaryObservation.Censored || compared is BinaryObservation.Censored ->
                    censored++
                baseline is BinaryObservation.Unknown || compared is BinaryObservation.Unknown ->
                    unknown++
                baseline is BinaryObservation.Observed && compared is BinaryObservation.Observed ->
                    differences += (if (compared.value) 1.0 else 0.0) -
                        (if (baseline.value) 1.0 else 0.0)
            }
        }
        work.addOperations(byUnit.size.toLong())
        if (differences.isNotEmpty()) {
            work.addOperations(differences.size.toLong() * plan.bootstrap.resamples)
            work.addAllocations(plan.bootstrap.resamples + 1L)
        }
        return MatchedObservedAssociation(
            baselineArm = OfflineEvalArm.A_NO_LEARNING,
            comparisonArm = comparison,
            pairedObservedCount = differences.size,
            unknownPairCount = unknown,
            censoredPairCount = censored,
            successRateDifference = DeterministicBootstrap.mean(
                differences,
                plan.bootstrap,
                planDigest,
                "matched-success-${comparison.name}",
            ),
        )
    }

    private fun judgeDivergence(
        observations: List<OfflineReplayObservation>,
    ): JudgeDivergenceSummary {
        val deterministicPairs = observations.filter {
            it.llmJudge != JudgeVerdict.UNKNOWN && it.deterministicJudge != JudgeVerdict.UNKNOWN
        }
        val humanPairs = observations.filter {
            it.llmJudge != JudgeVerdict.UNKNOWN && it.humanJudge != JudgeVerdict.UNKNOWN
        }
        return JudgeDivergenceSummary(
            llmVsDeterministicComparableCount = deterministicPairs.size,
            llmVsDeterministicDivergenceCount = deterministicPairs.count {
                it.llmJudge != it.deterministicJudge
            },
            llmVsHumanComparableCount = humanPairs.size,
            llmVsHumanDivergenceCount = humanPairs.count { it.llmJudge != it.humanJudge },
        )
    }

    private fun corpusDigest(corpusId: String, corpus: List<OfflineReplayUnit>): String =
        EvalDigest.sha256(
            domain = "offline-replay-corpus-v1",
            fields = corpus.sortedBy(OfflineReplayUnit::unitId).flatMap { unit ->
                listOf(
                    unit.unitId,
                    unit.matchedCohortId,
                    unit.fixtureId,
                    unit.slice.model,
                    unit.slice.toolSchema,
                    unit.slice.taskClass,
                    unit.slice.scope,
                    unit.slice.language,
                    unit.scenario.name,
                )
            },
        ).also {
            if (corpusId == FrozenReplayCorpusV1.CORPUS_ID) {
                require(it == FrozenReplayCorpusV1.digestSha256)
            }
        }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        val index = (ceil(values.size * fraction).toInt() - 1).coerceIn(values.indices)
        return values[index]
    }
}

object FrozenOfflineLearningEvaluation {
    val plan: OfflineEvalPlan = OfflineEvalPlan(
        planId = "p5-offline-eval-plan-v1",
        assignmentSalt = "pre-registered-2026-08-13",
        holdoutBasisPoints = 2_500,
        bootstrap = BootstrapConfig(
            resamples = 1_000,
            confidenceLevelBasisPoints = 9_500,
        ),
    )

    /** An executor is mandatory: production evaluation has no synthetic-success default. */
    fun run(executor: OfflineReplayExecutor): OfflineEvalReport = OfflineLearningEvalHarness.run(
        corpus = FrozenReplayCorpusV1.units,
        plan = plan,
        executor = executor,
        corpusId = FrozenReplayCorpusV1.CORPUS_ID,
    )
}

private data class EvalWorkLedger(
    var operationUnits: Long,
    var allocationUnits: Long,
) {
    fun addOperations(value: Long) {
        operationUnits = Math.addExact(operationUnits, value)
    }

    fun addAllocations(value: Long) {
        allocationUnits = Math.addExact(allocationUnits, value)
    }
}

private const val MAX_CORPUS_UNITS = 10_000
private const val ASSIGNMENT_OPERATION_UNITS = 3L
private const val JUDGE_COMPARISON_OPERATION_UNITS = 2L

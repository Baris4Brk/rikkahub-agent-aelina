package me.rerere.rikkahub.learning.eval

/**
 * Versioned, content-free P5 replay corpus. Fixture IDs resolve only inside the pure Kotlin
 * executor below; no provider, Tool, file, network, Android service, or user database is touched.
 */
object FrozenReplayCorpusV1 {
    const val CORPUS_ID: String = "agent-learning-offline-replay-v1"

    val units: List<OfflineReplayUnit> = listOf(
        unit("u01", "model-v1", "tools-v1", "information", "assistant", "zh", ReplayFixtureScenario.LEARNING_ASSISTED),
        unit("u02", "model-v1", "tools-v1", "automation", "authority-subject", "en", ReplayFixtureScenario.USER_CORRECTION),
        unit("u03", "model-v2", "tools-v1", "recovery", "assistant", "ja", ReplayFixtureScenario.BASELINE_SUCCESS),
        unit("u04", "model-v2", "tools-v2", "safety", "authority-subject", "zh", ReplayFixtureScenario.UNKNOWN_AUTHORITY),
        unit("u05", "model-v1", "tools-v2", "information", "assistant", "en", ReplayFixtureScenario.CENSORED_TIMEOUT),
        unit("u06", "model-v2", "tools-v2", "automation", "authority-subject", "ja", ReplayFixtureScenario.JUDGE_DIVERGENCE),
        unit("u07", "model-v1", "tools-v1", "recovery", "assistant", "zh", ReplayFixtureScenario.USER_CORRECTION),
        unit("u08", "model-v1", "tools-v2", "safety", "authority-subject", "en", ReplayFixtureScenario.STALE_GUARD),
        unit("u09", "model-v2", "tools-v1", "information", "assistant", "ja", ReplayFixtureScenario.SCOPE_GUARD),
        unit("u10", "model-v2", "tools-v2", "automation", "authority-subject", "zh", ReplayFixtureScenario.TOKEN_HEAVY),
        unit("u11", "model-v1", "tools-v1", "recovery", "assistant", "en", ReplayFixtureScenario.TOOL_RETRY),
        unit("u12", "model-v1", "tools-v2", "safety", "authority-subject", "ja", ReplayFixtureScenario.LEARNING_ASSISTED),
        unit("u13", "model-v2", "tools-v2", "information", "assistant", "zh", ReplayFixtureScenario.BASELINE_SUCCESS),
        unit("u14", "model-v2", "tools-v1", "automation", "authority-subject", "en", ReplayFixtureScenario.LEARNING_ASSISTED),
        unit("u15", "model-v1", "tools-v2", "recovery", "assistant", "ja", ReplayFixtureScenario.CENSORED_TIMEOUT),
        unit("u16", "model-v1", "tools-v1", "safety", "authority-subject", "zh", ReplayFixtureScenario.UNKNOWN_AUTHORITY),
        unit("u17", "model-v2", "tools-v1", "information", "authority-subject", "en", ReplayFixtureScenario.JUDGE_DIVERGENCE),
        unit("u18", "model-v2", "tools-v2", "automation", "assistant", "ja", ReplayFixtureScenario.TOOL_RETRY),
        unit("u19", "model-v1", "tools-v2", "recovery", "authority-subject", "zh", ReplayFixtureScenario.STALE_GUARD),
        unit("u20", "model-v2", "tools-v1", "safety", "assistant", "en", ReplayFixtureScenario.SCOPE_GUARD),
    )

    val digestSha256: String = EvalDigest.sha256(
        domain = "offline-replay-corpus-v1",
        fields = units.sortedBy(OfflineReplayUnit::unitId).flatMap { unit ->
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
    )

    private fun unit(
        suffix: String,
        model: String,
        schema: String,
        task: String,
        scope: String,
        language: String,
        scenario: ReplayFixtureScenario,
    ): OfflineReplayUnit = OfflineReplayUnit(
        unitId = "replay-$suffix",
        matchedCohortId = "matched-$suffix",
        fixtureId = "fixture-$suffix",
        slice = OfflineEvalSlice(model, schema, task, scope, language),
        scenario = scenario,
    )
}

/**
 * Synthetic golden used only to test aggregation/math. Production rollout evaluation must use
 * [ProductionFourArmFixtureRunner]; this object is never a default and cannot publish a gate.
 */
object FrozenFixtureReplayExecutor : OfflineReplayExecutor {
    override fun replay(unit: OfflineReplayUnit, arm: OfflineEvalArm): OfflineReplayObservation {
        require(unit in FrozenReplayCorpusV1.units) { "Replay unit is not in the frozen corpus" }
        val taskOutcome = taskOutcome(unit.scenario, arm)
        val harmful = when (taskOutcome) {
            is BinaryObservation.Unknown -> BinaryObservation.Unknown(taskOutcome.reason)
            is BinaryObservation.Censored -> BinaryObservation.Censored(taskOutcome.reason)
            is BinaryObservation.Observed -> BinaryObservation.Observed(false)
        }
        val learningLevel = arm.ordinal
        val baseCalls = when (unit.scenario) {
            ReplayFixtureScenario.TOOL_RETRY -> 3
            ReplayFixtureScenario.CENSORED_TIMEOUT -> 2
            else -> 1
        }
        val retries = when (unit.scenario) {
            ReplayFixtureScenario.TOOL_RETRY -> (2 - learningLevel / 2).coerceAtLeast(0)
            ReplayFixtureScenario.CENSORED_TIMEOUT -> 1
            else -> 0
        }
        val inputTokens = 96 + learningLevel * 12 +
            if (unit.scenario == ReplayFixtureScenario.TOKEN_HEAVY) 320 else 0
        val retrievalTokens = if (arm.ordinal >= OfflineEvalArm.C_DREAMING_REVIEWED_POLICY.ordinal) {
            24
        } else {
            0
        }
        val contextTokens = when (arm) {
            OfflineEvalArm.A_NO_LEARNING -> 0
            OfflineEvalArm.B_DREAMING_ONLY -> 20
            OfflineEvalArm.C_DREAMING_REVIEWED_POLICY -> 44
            OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS -> 52
        }
        val policyEnabled = arm.ordinal >= OfflineEvalArm.C_DREAMING_REVIEWED_POLICY.ordinal
        val policyOutcome = if (policyEnabled) taskOutcome else {
            BinaryObservation.Unknown(BinaryUnknownReason.OUTCOME_NOT_RECORDED)
        }
        val deterministic = taskOutcome.toJudgeVerdict()
        val human = deterministic
        val llm = if (unit.scenario == ReplayFixtureScenario.JUDGE_DIVERGENCE &&
            deterministic != JudgeVerdict.UNKNOWN
        ) {
            if (deterministic == JudgeVerdict.SUCCESS) JudgeVerdict.FAILURE
            else JudgeVerdict.SUCCESS
        } else {
            deterministic
        }
        return OfflineReplayObservation(
            unitId = unit.unitId,
            arm = arm,
            taskOutcome = taskOutcome,
            harmfulOutcome = harmful,
            userCorrectionCount = when {
                unit.scenario != ReplayFixtureScenario.USER_CORRECTION -> 0
                arm == OfflineEvalArm.A_NO_LEARNING -> 2
                arm == OfflineEvalArm.B_DREAMING_ONLY -> 1
                else -> 0
            },
            resources = ReplayResourceObservation(
                inputTokens = inputTokens,
                outputTokens = 40 + learningLevel * 2,
                retrievalTokens = retrievalTokens,
                contextTokens = contextTokens,
                toolCalls = baseCalls,
                toolRetries = retries,
            ),
            // These are frozen trace observations, not current JVM timings.
            recordedLatency = RecordedLatencyObservation(
                ttftMicros = if (taskOutcome is BinaryObservation.Censored) null
                    else 1_000L + learningLevel * 50L,
                toolToNextModelMicros = if (baseCalls == 0 || taskOutcome is BinaryObservation.Censored) {
                    null
                } else {
                    2_000L + learningLevel * 80L
                },
            ),
            policy = PolicyFunnelObservation(
                candidateCount = if (policyEnabled) 1 else 0,
                compiledCount = if (policyEnabled) 1 else 0,
                dispatchCount = if (policyEnabled) 1 else 0,
                outcome = policyOutcome,
            ),
            scopeLeakCount = 0,
            staleHitCount = 0,
            deterministicJudge = deterministic,
            humanJudge = human,
            llmJudge = llm,
            scriptActionCount = 0,
            jvmTrend = JvmTrendObservation(
                operationUnits = 12L + baseCalls * 3L + learningLevel * 4L,
                logicalAllocationUnits = 4L + learningLevel * 2L,
            ),
        )
    }

    private fun taskOutcome(
        scenario: ReplayFixtureScenario,
        arm: OfflineEvalArm,
    ): BinaryObservation = when (scenario) {
        ReplayFixtureScenario.UNKNOWN_AUTHORITY ->
            BinaryObservation.Unknown(BinaryUnknownReason.AUTHORITY_MISSING)
        ReplayFixtureScenario.CENSORED_TIMEOUT ->
            BinaryObservation.Censored(BinaryCensorReason.FIXTURE_TIMEOUT)
        ReplayFixtureScenario.BASELINE_SUCCESS,
        ReplayFixtureScenario.JUDGE_DIVERGENCE,
        ReplayFixtureScenario.STALE_GUARD,
        ReplayFixtureScenario.SCOPE_GUARD,
        ReplayFixtureScenario.TOKEN_HEAVY,
        -> BinaryObservation.Observed(true)
        ReplayFixtureScenario.LEARNING_ASSISTED -> BinaryObservation.Observed(
            arm == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
                arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS,
        )
        ReplayFixtureScenario.USER_CORRECTION -> BinaryObservation.Observed(
            arm == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
                arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS,
        )
        ReplayFixtureScenario.TOOL_RETRY -> BinaryObservation.Observed(
            arm != OfflineEvalArm.A_NO_LEARNING,
        )
    }
}

private fun BinaryObservation.toJudgeVerdict(): JudgeVerdict = when (this) {
    is BinaryObservation.Observed -> if (value) JudgeVerdict.SUCCESS else JudgeVerdict.FAILURE
    is BinaryObservation.Unknown,
    is BinaryObservation.Censored,
    -> JudgeVerdict.UNKNOWN
}

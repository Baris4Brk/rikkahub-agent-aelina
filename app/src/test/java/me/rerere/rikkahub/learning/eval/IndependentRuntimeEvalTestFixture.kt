package me.rerere.rikkahub.learning.eval

/** Test-only non-frozen adapter set for exercising the independent-evidence verifier contract. */
internal class IndependentRuntimeEvalTestFixture(
    private val treatmentGain: Boolean,
) {
    private fun identity(component: ProductionReplayComponent) =
        ProductionComponentAdapterIdentity(
            component = component,
            adapterVersion = "independent-runtime-test-adapter-v1",
            implementationSha256 = EvalDigest.sha256(
                "independent-runtime-test-adapter-v1",
                listOf(component.name, treatmentGain.toString()),
            ),
        )

    private fun <T> observed(value: T) = ProductionComponentReplayResult.Observed(
        observation = value,
        work = DeterministicComponentWork(1L, 1L),
    )

    val adapters = ProductionComponentReplayAdapters(
        dreaming = object : DreamProjectionReplayPort {
            override val identity = identity(ProductionReplayComponent.DREAM_PROJECTION)
            override suspend fun project(request: ProductionComponentReplayRequest) =
                observed(DreamProjectionReplayObservation(1))
        },
        retrieval = object : PolicyRetrievalReplayPort {
            override val identity = identity(ProductionReplayComponent.POLICY_RETRIEVAL)
            override suspend fun retrieve(
                request: ProductionComponentReplayRequest,
                dream: DreamProjectionReplayObservation?,
            ) = observed(PolicyRetrievalReplayObservation(1, 4, 0, 0))
        },
        recall = object : RecallCompilerReplayPort {
            override val identity = identity(ProductionReplayComponent.RECALL_COMPILER)
            override suspend fun compile(request: RecallCompilerReplayRequest) =
                observed(RecallCompilerReplayObservation(64, 8))
        },
        exposure = object : PolicyExposureReplayPort {
            override val identity = identity(ProductionReplayComponent.POLICY_EXPOSURE)
            override suspend fun expose(request: PolicyExposureReplayRequest) =
                observed(PolicyExposureReplayObservation(1, 1))
        },
        outcome = object : PolicyOutcomeReplayPort {
            override val identity = identity(ProductionReplayComponent.POLICY_OUTCOME)
            override suspend fun observe(request: PolicyOutcomeReplayRequest):
                ProductionComponentReplayResult<PolicyOutcomeReplayObservation> {
                val arm = request.replay.arm
                val success = !treatmentGain || arm == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
                    arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
                val deterministic = if (success) JudgeVerdict.SUCCESS else JudgeVerdict.FAILURE
                val human = if (request.replay.unit.unitId.endsWith("02")) {
                    deterministic.opposite()
                } else deterministic
                val llm = if (request.replay.unit.unitId.endsWith("03")) {
                    deterministic.opposite()
                } else deterministic
                return observed(
                    PolicyOutcomeReplayObservation(
                        taskOutcome = BinaryObservation.Observed(success),
                        harmfulOutcome = BinaryObservation.Observed(false),
                        userCorrectionCount = if (success) 0 else 1,
                        outputTokens = 32,
                        toolCalls = 1,
                        toolRetries = 0,
                        recordedLatency = RecordedLatencyObservation(1_000L, 2_000L),
                        policyOutcome = if (
                            arm == OfflineEvalArm.C_DREAMING_REVIEWED_POLICY ||
                            arm == OfflineEvalArm.D_FULL_REVIEWED_RUNTIME_NO_JS
                        ) {
                            BinaryObservation.Observed(success)
                        } else {
                            BinaryObservation.Unknown(BinaryUnknownReason.OUTCOME_NOT_RECORDED)
                        },
                        deterministicJudge = deterministic,
                        humanJudge = human,
                        llmJudge = llm,
                        scriptActionCount = 0,
                    ),
                )
            }
        },
    )

    val judgeSources = DurableJudgeSourceIdentities(
        deterministicSourceId = "deterministic-terminal-authority-v1",
        humanSourceId = "blinded-human-review-stream-v1",
        llmSourceId = "separate-llm-review-stream-v1",
    )

    fun authorityRows(run: ProductionComponentReplayRun): Map<String, DurableAuthorityTraceRecord> =
        run.observations.associate { observation ->
            val key = "${observation.unitId}:${observation.arm.name}"
            key to DurableAuthorityTraceRecord(
                unitId = "authority-${observation.unitId}-${observation.arm.ordinal}",
                taskOutcome = observation.taskOutcome,
                harmfulOutcome = observation.harmfulOutcome,
                userCorrectionCount = observation.userCorrectionCount,
                outputTokens = observation.resources.outputTokens,
                toolCalls = observation.resources.toolCalls,
                toolRetries = observation.resources.toolRetries,
                recordedLatency = observation.recordedLatency,
                deterministicJudge = observation.deterministicJudge,
                humanJudge = observation.humanJudge,
                llmJudge = observation.llmJudge,
                scriptActionCount = observation.scriptActionCount,
            )
        }
}

private fun JudgeVerdict.opposite(): JudgeVerdict = when (this) {
    JudgeVerdict.SUCCESS -> JudgeVerdict.FAILURE
    JudgeVerdict.FAILURE -> JudgeVerdict.SUCCESS
    JudgeVerdict.UNKNOWN -> JudgeVerdict.UNKNOWN
}

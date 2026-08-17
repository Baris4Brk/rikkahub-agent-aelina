package me.rerere.rikkahub.learning.eval

/**
 * Content-free terminal authority facts for the disposable P5 replay.
 *
 * This table is deliberately keyed only by the pre-registered unit identity. It never reads the
 * fixture scenario and it has no arm parameter, so an evaluation arm cannot select a favourable
 * outcome. The four arms must all project the same record before durable evidence can pass.
 */
internal object FrozenArmBlindAuthorityTraceV1 {
    const val TRACE_VERSION: String = "p5-arm-blind-authority-trace-v2"

    val records: List<DurableAuthorityTraceRecord> = listOf(
        observed("replay-u01", true, outputTokens = 33),
        observed("replay-u02", false, corrections = 1, outputTokens = 34),
        observed("replay-u03", true, outputTokens = 35),
        unknown("replay-u04", outputTokens = 36),
        censored("replay-u05", outputTokens = 32),
        observed(
            "replay-u06",
            true,
            outputTokens = 33,
            llmJudge = JudgeVerdict.FAILURE,
        ),
        observed("replay-u07", false, corrections = 1, outputTokens = 34),
        observed("replay-u08", true, outputTokens = 35),
        observed("replay-u09", true, outputTokens = 36),
        observed("replay-u10", true, outputTokens = 32),
        observed("replay-u11", true, outputTokens = 33, toolCalls = 2, toolRetries = 1),
        observed("replay-u12", true, outputTokens = 34),
        observed("replay-u13", true, outputTokens = 35),
        observed("replay-u14", true, outputTokens = 36),
        censored("replay-u15", outputTokens = 32),
        unknown("replay-u16", outputTokens = 33),
        observed(
            "replay-u17",
            true,
            outputTokens = 34,
            humanJudge = JudgeVerdict.FAILURE,
        ),
        observed("replay-u18", true, outputTokens = 35, toolCalls = 2, toolRetries = 1),
        observed("replay-u19", true, outputTokens = 36),
        observed("replay-u20", true, outputTokens = 32),
    ).also { rows ->
        require(rows.size == FrozenReplayCorpusV1.units.size)
        require(rows.map(DurableAuthorityTraceRecord::unitId).toSet() ==
            FrozenReplayCorpusV1.units.map(OfflineReplayUnit::unitId).toSet())
    }

    val manifestDigestSha256: String = EvalDigest.sha256(
        domain = "p5-arm-blind-authority-trace-manifest-v2",
        fields = listOf(TRACE_VERSION) + records.sortedBy { it.unitId }.map { it.digestSha256() },
    )

    private val byUnit = records.associateBy(DurableAuthorityTraceRecord::unitId)

    fun recordFor(unitId: String): DurableAuthorityTraceRecord? = byUnit[unitId]

    private fun observed(
        unitId: String,
        success: Boolean,
        corrections: Int = 0,
        outputTokens: Int,
        toolCalls: Int = 1,
        toolRetries: Int = 0,
        deterministicJudge: JudgeVerdict? = null,
        humanJudge: JudgeVerdict? = null,
        llmJudge: JudgeVerdict? = null,
    ): DurableAuthorityTraceRecord {
        val task = BinaryObservation.Observed(success)
        val judge = if (success) JudgeVerdict.SUCCESS else JudgeVerdict.FAILURE
        return DurableAuthorityTraceRecord(
            unitId = unitId,
            taskOutcome = task,
            harmfulOutcome = BinaryObservation.Observed(false),
            userCorrectionCount = corrections,
            outputTokens = outputTokens,
            toolCalls = toolCalls,
            toolRetries = toolRetries,
            recordedLatency = RecordedLatencyObservation(1_200L, 2_400L),
            deterministicJudge = deterministicJudge ?: judge,
            humanJudge = humanJudge ?: judge,
            llmJudge = llmJudge ?: judge,
            scriptActionCount = 0,
        )
    }

    private fun unknown(
        unitId: String,
        outputTokens: Int,
    ): DurableAuthorityTraceRecord = DurableAuthorityTraceRecord(
        unitId = unitId,
        taskOutcome = BinaryObservation.Unknown(BinaryUnknownReason.AUTHORITY_MISSING),
        harmfulOutcome = BinaryObservation.Unknown(BinaryUnknownReason.AUTHORITY_MISSING),
        userCorrectionCount = 0,
        outputTokens = outputTokens,
        toolCalls = 1,
        toolRetries = 0,
        recordedLatency = RecordedLatencyObservation(1_200L, 2_400L),
        deterministicJudge = JudgeVerdict.UNKNOWN,
        humanJudge = JudgeVerdict.UNKNOWN,
        llmJudge = JudgeVerdict.UNKNOWN,
        scriptActionCount = 0,
    )

    private fun censored(
        unitId: String,
        outputTokens: Int,
    ): DurableAuthorityTraceRecord = DurableAuthorityTraceRecord(
        unitId = unitId,
        taskOutcome = BinaryObservation.Censored(BinaryCensorReason.FIXTURE_TIMEOUT),
        harmfulOutcome = BinaryObservation.Censored(BinaryCensorReason.FIXTURE_TIMEOUT),
        userCorrectionCount = 0,
        outputTokens = outputTokens,
        toolCalls = 1,
        toolRetries = 0,
        recordedLatency = RecordedLatencyObservation(null, null),
        deterministicJudge = JudgeVerdict.UNKNOWN,
        humanJudge = JudgeVerdict.UNKNOWN,
        llmJudge = JudgeVerdict.UNKNOWN,
        scriptActionCount = 0,
    )
}

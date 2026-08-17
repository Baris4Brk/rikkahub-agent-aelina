package me.rerere.rikkahub.learning.eval

import java.util.Locale

object RedactedEvalReportRenderer {
    const val MAX_REPORT_CHARS: Int = 16_384
    const val MIN_COMPLETE_REPORT_CHARS: Int = 8_192

    fun render(report: OfflineEvalReport, maxChars: Int = MAX_REPORT_CHARS): String {
        require(maxChars in MIN_COMPLETE_REPORT_CHARS..MAX_REPORT_CHARS)
        val writer = BoundedReportWriter(maxChars)
        val mandatoryLines = buildList {
            add("report=agent_learning_offline_eval_redacted_v2")
            add("corpus=${report.corpusId}")
            add("corpus_sha256=${report.corpusDigestSha256}")
            add("plan=${report.planId}")
            add("plan_sha256=${report.planDigestSha256}")
            add("assignment_sha256=${report.assignmentManifestSha256}")
            add("matched_cohorts=${report.matchedCohortCount}")
            add("incomplete_matched_cohorts=${report.incompleteMatchedCohortCount}")
            add("holdout_units=${report.holdoutUnitCount}")
            add("energy=${report.energy.state.name}")
            add("energy_reason=${report.energy.reasonCode}")
            add("dedicated_odpm_device_used=${report.energy.dedicatedOdpmDeviceUsed}")
            add(
                "primary_honor_device_testing_prohibited=" +
                    report.energy.primaryHonorDeviceTestingProhibited,
            )
            add("slice_encoding=n,o,p,u,c,se,slo,shi,leak,stale,ho,hp,hunk,hc,he,hlo,hhi")
            add("slice_rows=${report.slices.size}")
            report.slices.sortedWith(
                compareBy<SliceEvalSummary>({ it.dimension.ordinal }, { it.value }, { it.arm.ordinal }),
            ).forEach { slice -> add(slice.renderCompactSlice()) }
            add("slice_coverage_complete=true")
        }
        val mandatoryChars = mandatoryLines.sumOf { it.length + 1 } +
            BoundedReportWriter.TRUNCATION_LINE.length
        require(mandatoryChars <= maxChars) {
            "The bounded report budget cannot hold the complete frozen slice matrix"
        }
        mandatoryLines.forEach { line -> check(writer.line(line)) }
        report.arms.forEach { arm ->
            val prefix = "arm.${arm.arm.name}"
            writer.line("$prefix.n=${arm.sampleSize}")
            writer.line("$prefix.task_success=${arm.taskSuccess.renderRate()}")
            writer.line("$prefix.harmful=${arm.harmfulRate.renderRate()}")
            writer.line("$prefix.user_corrections=${arm.userCorrectionCount}")
            writer.line("$prefix.tool_calls=${arm.toolCallCount}")
            writer.line("$prefix.tool_retries=${arm.toolRetryCount}")
            writer.line("$prefix.tokens=${arm.inputTokens},${arm.outputTokens},${arm.retrievalTokens},${arm.contextTokens}")
            writer.line("$prefix.recorded_ttft=${arm.recordedTtft.renderDistribution()}")
            writer.line("$prefix.recorded_tool_next_model=${arm.recordedToolToNextModel.renderDistribution()}")
            writer.line("$prefix.policy=${arm.policyCandidateCount},${arm.policyCompiledCount},${arm.policyDispatchCount},${arm.policyOutcome.renderRate()}")
            writer.line("$prefix.scope_leaks=${arm.scopeLeakCount}")
            writer.line("$prefix.stale_hits=${arm.staleHitCount}")
            writer.line("$prefix.script_actions=${arm.scriptActionCount}")
        }
        report.associations.forEach { association ->
            writer.line(
                "observed_association.${association.comparisonArm.name}=" +
                    "paired:${association.pairedObservedCount},unknown:${association.unknownPairCount}," +
                    "censored:${association.censoredPairCount}," +
                    "difference:${association.successRateDifference.renderCi()}," +
                    "interpretation:${association.interpretation.name}",
            )
        }
        writer.line(
            "judge.llm_vs_deterministic=" +
                "${report.judgeDivergence.llmVsDeterministicDivergenceCount}/" +
                report.judgeDivergence.llmVsDeterministicComparableCount,
        )
        writer.line(
            "judge.llm_vs_human=${report.judgeDivergence.llmVsHumanDivergenceCount}/" +
                report.judgeDivergence.llmVsHumanComparableCount,
        )
        writer.line("jvm.operation_units=${report.performance.deterministicOperationUnits}")
        writer.line("jvm.logical_allocation_units=${report.performance.logicalAllocationUnits}")
        report.partitions.forEach { partition ->
            writer.line(
                "partition.${partition.partition.name}.${partition.arm.name}=" +
                    "n:${partition.sampleSize},success:${partition.taskSuccess.renderRate()}",
            )
        }
        return writer.finish()
    }

    private fun SliceEvalSummary.renderCompactSlice(): String =
        "slice.${dimension.name}.${value}.${arm.name}=" + listOf(
            sampleSize,
            taskSuccess.observedCount,
            taskSuccess.positiveCount,
            taskSuccess.unknownCount,
            taskSuccess.censoredCount,
            taskSuccess.estimate.formatCompact(),
            taskSuccess.bootstrapCi?.lower.formatCompact(),
            taskSuccess.bootstrapCi?.upper.formatCompact(),
            scopeLeakCount,
            staleHitCount,
            harmfulRate.observedCount,
            harmfulRate.positiveCount,
            harmfulRate.unknownCount,
            harmfulRate.censoredCount,
            harmfulRate.estimate.formatCompact(),
            harmfulRate.bootstrapCi?.lower.formatCompact(),
            harmfulRate.bootstrapCi?.upper.formatCompact(),
        ).joinToString(",")

    private fun Double?.formatCompact(): String = this?.let {
        String.format(Locale.ROOT, "%.4f", it)
    } ?: "x"

    private fun BinaryMetricSummary.renderRate(): String = buildString {
        append("observed:").append(observedCount)
        append(",positive:").append(positiveCount)
        append(",unknown:").append(unknownCount)
        append(",censored:").append(censoredCount)
        append(",estimate:").append(estimate.formatOrUnmeasured())
        append(",ci:").append(bootstrapCi.renderCi())
    }

    private fun RecordedDistributionSummary.renderDistribution(): String =
        "${knowledge.name},n:$sampleCount,p50:${p50 ?: "UNMEASURED"},p95:${p95 ?: "UNMEASURED"}"

    private fun ConfidenceInterval?.renderCi(): String = this?.let {
        "${it.lower.format()},${it.estimate.format()},${it.upper.format()}," +
            "bp:${it.confidenceLevelBasisPoints},r:${it.resamples}"
    } ?: "UNMEASURED"

    private fun Double?.formatOrUnmeasured(): String = this?.format() ?: "UNMEASURED"
    private fun Double.format(): String = String.format(Locale.ROOT, "%.6f", this)
}

private class BoundedReportWriter(private val maxChars: Int) {
    private val builder = StringBuilder(minOf(maxChars, 4_096))
    private var truncated = false

    fun line(value: String): Boolean {
        if (truncated) return false
        require(value.none { it == '\r' || it == '\n' || it.code < 0x20 })
        if (builder.length + value.length + 1 + TRUNCATION_LINE.length > maxChars) {
            truncated = true
            return false
        }
        builder.append(value).append('\n')
        return true
    }

    fun finish(): String {
        if (truncated) builder.append(TRUNCATION_LINE)
        return builder.toString().take(maxChars)
    }

    companion object {
        const val TRUNCATION_LINE = "report_truncated=true\n"
    }
}

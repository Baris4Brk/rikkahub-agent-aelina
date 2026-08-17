package me.rerere.rikkahub.learning.eval

import me.rerere.rikkahub.learning.privacy.forbiddenLearningCorpus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactedEvalReportRendererTest {
    private val report by lazy {
        FrozenOfflineLearningEvaluation.run(FrozenFixtureReplayExecutor)
    }

    @Test
    fun `report is bounded and truncation remains explicit`() {
        val compact = RedactedEvalReportRenderer.render(
            report,
            maxChars = RedactedEvalReportRenderer.MIN_COMPLETE_REPORT_CHARS,
        )
        val full = RedactedEvalReportRenderer.render(report)
        assertTrue(compact.length <= RedactedEvalReportRenderer.MIN_COMPLETE_REPORT_CHARS)
        assertTrue(compact.contains("report_truncated=true"))
        assertTrue(compact.contains("slice_coverage_complete=true"))
        assertTrue(full.length <= RedactedEvalReportRenderer.MAX_REPORT_CHARS)
    }

    @Test
    fun `report contains aggregate identities not raw fixture or conversation content`() {
        val rendered = RedactedEvalReportRenderer.render(report)
        assertTrue(rendered.contains("agent_learning_offline_eval_redacted_v2"))
        assertTrue(rendered.contains("observed_association"))
        assertFalse(rendered.contains("fixture-u"))
        assertFalse(rendered.contains("prompt="))
        assertFalse(rendered.contains("output="))
        assertFalse(rendered.contains("conversation"))
        forbiddenLearningCorpus().forEach { value ->
            assertFalse("redacted eval export leaked release-forbidden corpus", value in rendered)
        }
    }

    @Test
    fun `every model tool task scope and language slice survives the bounded export`() {
        val rendered = RedactedEvalReportRenderer.render(report)
        assertTrue(rendered.contains("slice_rows=${report.slices.size}"))
        assertTrue(rendered.contains("slice_coverage_complete=true"))
        report.slices.forEach { slice ->
            assertTrue(
                "missing compact slice ${slice.dimension}/${slice.value}/${slice.arm}",
                rendered.contains("slice.${slice.dimension.name}.${slice.value}.${slice.arm.name}="),
            )
        }
        EvalSliceDimension.entries.forEach { dimension ->
            assertTrue(report.slices.any { it.dimension == dimension })
        }
    }

    @Test
    fun `energy is explicitly unmeasured and primary Honor testing stays prohibited`() {
        val rendered = RedactedEvalReportRenderer.render(report)
        assertTrue(rendered.contains("energy=UNMEASURED"))
        assertTrue(rendered.contains("dedicated_odpm_device_used=false"))
        assertTrue(rendered.contains("primary_honor_device_testing_prohibited=true"))
    }
}

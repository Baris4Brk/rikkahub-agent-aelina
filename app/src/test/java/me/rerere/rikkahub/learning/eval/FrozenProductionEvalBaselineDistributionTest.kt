package me.rerere.rikkahub.learning.eval

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FrozenProductionEvalBaselineDistributionTest {
    @Test
    fun `checked-in baseline does not present duplicated constants as reviewed runs`() {
        val descriptor = Json.parseToJsonElement(
            Files.readString(repositoryRoot().resolve(BASELINE_PATH)),
        ).jsonObject
        val evidence = descriptor.getValue("measurementEvidence").jsonObject

        assertEquals("DRAFT", descriptor.getValue("status").jsonPrimitive.content)
        assertEquals(
            "UNVERIFIED",
            descriptor.getValue("evidenceVerification").jsonPrimitive.content,
        )
        assertEquals(
            "CANDIDATE_EXPECTATION_NOT_VERIFIED_MEASUREMENT",
            descriptor.getValue("counterRole").jsonPrimitive.content,
        )
        assertEquals(
            0,
            evidence.getValue("independentSerialRunCount").jsonPrimitive.content.toInt(),
        )
        assertEquals(0, evidence.getValue("auditableRunArtifacts").jsonArray.size)
        assertEquals(
            "NO_INDEPENDENT_MATCHED_ENVIRONMENT_RUN_ARTIFACTS",
            evidence.getValue("verificationReason").jsonPrimitive.content,
        )
        assertFalse(
            evidence.getValue("duplicatedConstantsAreMeasurements").jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            "NOT_ENFORCED",
            descriptor.getValue("performanceGateState").jsonPrimitive.content,
        )
        assertEquals("ABSTAIN", descriptor.getValue("rolloutDecision").jsonPrimitive.content)
        assertEquals(
            "PERFORMANCE_NOT_ENFORCED",
            descriptor.getValue("rolloutReason").jsonPrimitive.content,
        )
    }

    private fun repositoryRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            if (Files.isRegularFile(cursor.resolve(BASELINE_PATH))) return cursor
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate P5 baseline descriptor")
    }

    private companion object {
        const val BASELINE_PATH =
            "docs/agent-learning/baselines/p5-production-components-baseline-v1.json"
    }
}

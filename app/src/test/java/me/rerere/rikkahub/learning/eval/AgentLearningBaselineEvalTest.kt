package me.rerere.rikkahub.learning.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * Synthetic, deterministic PRE baseline. It does not claim a policy effect before P2 has a real
 * retrieval/compiler/exposure path.
 */
class AgentLearningBaselineEvalTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun corpusCoversFrozenSlicesAndBaselineHasNoLearningEffect() {
        val cases = loadCases()
        validateCases(cases)
        val categories = cases.flatMap(AgentLearningEvalCase::categories).toSet()
        REQUIRED_CATEGORIES.forEach { category ->
            assertTrue("Missing learning eval slice: $category", category in categories)
        }

        val observations = cases.associate { case ->
            case.caseId to AgentLearningObservation(
                retrievedPolicyIds = emptyList(),
                standingPolicyIds = emptyList(),
                actualExposurePolicyIds = emptyList(),
                promptTokens = 0,
                tokenBudget = case.tokenBudget,
                latencyMicros = null,
                supportEpisodeIds = emptyList(),
                toolAttemptCount = case.episodes.size,
            )
        }
        val report = AgentLearningMetrics.calculate(cases, observations)
        val encoded = json.encodeToString(report)

        assertEquals(cases.size, report.caseCount)
        assertEquals(0, report.scopeLeakCount)
        assertEquals(0, report.staleHitCount)
        assertEquals(0, report.standingFalsePromotionCount)
        assertEquals(0, report.tokenOverflowCount)
        assertEquals(MeasurementState.UNMEASURED, report.futurePolicyRecallState)
        assertNull(report.futurePolicyRecallAt1)
        assertNull(report.futurePolicyRecallAt3)
        assertNull(report.futurePolicyRecallAt5)
        assertEquals(MeasurementState.UNMEASURED, report.latencyState)
        assertEquals(MeasurementState.UNMEASURED, report.energyState)
        assertEquals(MeasurementState.UNMEASURED, report.calibrationState)
        assertEquals(MeasurementState.UNMEASURED, report.correctionState)
        assertTrue(report.toolAttemptCount >= report.distinctTaskEpisodeCount)
        assertTrue(report.toolRetryCount >= 0)
        assertTrue(encoded.contains("\"energyState\":\"UNMEASURED\""))
        assertFalse(encoded.contains("energyMilli", ignoreCase = true))
    }

    @Test
    fun calculatorDeduplicatesRetriesAndCountsHardFailuresByReason() {
        val case = loadCases().first { it.caseId == "retry_same_episode" }
        val observations = mapOf(
            case.caseId to AgentLearningObservation(
                retrievedPolicyIds = listOf("policy-cross-scope", "policy-stale"),
                standingPolicyIds = listOf("policy-untrusted"),
                actualExposurePolicyIds = emptyList(),
                promptTokens = 101,
                tokenBudget = 100,
                latencyMicros = 30,
                supportEpisodeIds = listOf("episode-r1", "episode-r1", "episode-r1"),
                toolAttemptCount = 3,
            ),
        )
        val report = AgentLearningMetrics.calculate(listOf(case), observations)
        assertEquals(1, report.scopeLeakCount)
        assertEquals(1, report.staleHitCount)
        assertEquals(1, report.standingFalsePromotionCount)
        assertEquals(1, report.tokenOverflowCount)
        assertEquals(2, report.duplicateSupportCount)
        assertEquals(3, report.toolAttemptCount)
        assertEquals(1, report.distinctTaskEpisodeCount)
        assertEquals(2, report.toolRetryCount)
        assertEquals(30L, report.latencyP50Micros)
        assertEquals(MeasurementState.MEASURED, report.latencyState)
    }

    private fun loadCases(): List<AgentLearningEvalCase> {
        val resource = requireNotNull(
            javaClass.getResourceAsStream("/learning_eval/zh_agent_learning_v1.jsonl"),
        )
        return resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).map { line ->
                json.decodeFromString<AgentLearningEvalCase>(line)
            }.toList()
        }
    }

    private fun validateCases(cases: List<AgentLearningEvalCase>) {
        assertEquals(25, cases.size)
        assertEquals(cases.size, cases.map(AgentLearningEvalCase::caseId).distinct().size)
        cases.forEach { case ->
            assertEquals(1, case.schemaVersion)
            assertTrue(case.caseId.matches(Regex("[a-z0-9_]{1,64}")))
            assertTrue(case.categories.isNotEmpty())
            assertTrue(case.tokenBudget > 0)
            assertEquals(case.episodes.size, case.episodes.map(EvalEpisode::episodeId).distinct().size)
            assertTrue(case.gold.distinctSupport >= 0)
            assertTrue(case.gold.distinctSupport <= case.episodes.map(EvalEpisode::rootEpisodeId).distinct().size)
            assertTrue(case.gold.baselineExpectedPolicyIds.isEmpty())
            assertTrue(case.gold.baselineExpectedStandingIds.isEmpty())
            assertTrue(case.gold.futureRelevantPolicyIds.none(case.gold.forbiddenPolicyIds::contains))
        }
    }

    private companion object {
        val REQUIRED_CATEGORIES = setOf(
            "success_failure_contrast",
            "single_success_no_promotion",
            "retry_deduplication",
            "assistant_scope_isolation",
            "authority_scope_isolation",
            "tool_schema_drift",
            "model_drift",
            "task_version_drift",
            "source_deleted",
            "source_edited",
            "no_resurrection",
            "chinese_paraphrase",
            "negated_condition",
            "prompt_injection",
            "token_budget",
            "tool_retry",
            "unknown_outcome",
        )
    }
}

private object AgentLearningMetrics {
    fun calculate(
        cases: List<AgentLearningEvalCase>,
        observations: Map<String, AgentLearningObservation>,
    ): AgentLearningEvalReport {
        val pairs = cases.map { case -> case to requireNotNull(observations[case.caseId]) }
        val latency = pairs.mapNotNull { it.second.latencyMicros }.sorted()
        return AgentLearningEvalReport(
            schemaVersion = 1,
            caseCount = cases.size,
            scopeLeakCount = pairs.sumOf { (case, observed) ->
                observed.retrievedPolicyIds.count(case.gold.crossScopePolicyIds.toSet()::contains)
            },
            staleHitCount = pairs.sumOf { (case, observed) ->
                observed.retrievedPolicyIds.count(case.gold.stalePolicyIds.toSet()::contains)
            },
            standingFalsePromotionCount = pairs.sumOf { (case, observed) ->
                observed.standingPolicyIds.count(case.gold.forbiddenStandingPolicyIds.toSet()::contains)
            },
            tokenOverflowCount = pairs.count { (_, observed) ->
                observed.promptTokens > observed.tokenBudget
            },
            duplicateSupportCount = pairs.sumOf { (_, observed) ->
                observed.supportEpisodeIds.size - observed.supportEpisodeIds.distinct().size
            },
            toolAttemptCount = pairs.sumOf { (_, observed) -> observed.toolAttemptCount },
            distinctTaskEpisodeCount = pairs.sumOf { (case, _) ->
                case.episodes.map(EvalEpisode::rootEpisodeId).distinct().size
            },
            toolRetryCount = pairs.sumOf { (case, observed) ->
                (observed.toolAttemptCount -
                    case.episodes.map(EvalEpisode::rootEpisodeId).distinct().size).coerceAtLeast(0)
            },
            futurePolicyRecallState = MeasurementState.UNMEASURED,
            futurePolicyRecallAt1 = null,
            futurePolicyRecallAt3 = null,
            futurePolicyRecallAt5 = null,
            latencyState = if (latency.isEmpty()) MeasurementState.UNMEASURED else MeasurementState.MEASURED,
            latencyP50Micros = percentileOrNull(latency, 0.50),
            latencyP95Micros = percentileOrNull(latency, 0.95),
            latencyP99Micros = percentileOrNull(latency, 0.99),
            energyState = MeasurementState.UNMEASURED,
            calibrationState = MeasurementState.UNMEASURED,
            correctionState = MeasurementState.UNMEASURED,
            energyNote = "Requires a disposable Pixel 6+; emulator and Honor AAK-AN00 are forbidden.",
        )
    }

    private fun percentileOrNull(values: List<Long>, fraction: Double): Long? {
        if (values.isEmpty()) return null
        val index = (ceil(fraction * values.size).toInt() - 1).coerceIn(values.indices)
        return values[index]
    }
}

@Serializable
private data class AgentLearningEvalCase(
    val schemaVersion: Int,
    val caseId: String,
    val categories: List<String>,
    val scope: EvalScope,
    val episodes: List<EvalEpisode>,
    val tokenBudget: Int,
    val gold: EvalGold,
)

@Serializable
private data class EvalScope(val kind: String, val id: String)

@Serializable
private data class EvalEpisode(
    val episodeId: String,
    val rootEpisodeId: String,
    val attemptId: String,
    val outcome: String,
    val sourceRevision: Long? = null,
    val sourceState: String,
    val toolSchemaVersion: String,
    val modelVersion: String,
    val taskVersion: String,
    val utterance: String,
)

@Serializable
private data class EvalGold(
    val distinctSupport: Int,
    val promotableInFuture: Boolean,
    val forbiddenReasons: List<String>,
    val baselineExpectedPolicyIds: List<String>,
    val baselineExpectedStandingIds: List<String>,
    val futureRelevantPolicyIds: List<String>,
    val forbiddenPolicyIds: List<String>,
    val crossScopePolicyIds: List<String>,
    val stalePolicyIds: List<String>,
    val forbiddenStandingPolicyIds: List<String>,
)

private data class AgentLearningObservation(
    val retrievedPolicyIds: List<String>,
    val standingPolicyIds: List<String>,
    val actualExposurePolicyIds: List<String>,
    val promptTokens: Int,
    val tokenBudget: Int,
    val latencyMicros: Long?,
    val supportEpisodeIds: List<String>,
    val toolAttemptCount: Int,
)

@Serializable
private enum class MeasurementState {
    MEASURED,
    UNMEASURED,
}

@Serializable
private data class AgentLearningEvalReport(
    val schemaVersion: Int,
    val caseCount: Int,
    val scopeLeakCount: Int,
    val staleHitCount: Int,
    val standingFalsePromotionCount: Int,
    val tokenOverflowCount: Int,
    val duplicateSupportCount: Int,
    val toolAttemptCount: Int,
    val distinctTaskEpisodeCount: Int,
    val toolRetryCount: Int,
    val futurePolicyRecallState: MeasurementState,
    val futurePolicyRecallAt1: Double?,
    val futurePolicyRecallAt3: Double?,
    val futurePolicyRecallAt5: Double?,
    val latencyState: MeasurementState,
    val latencyP50Micros: Long?,
    val latencyP95Micros: Long?,
    val latencyP99Micros: Long?,
    val energyState: MeasurementState,
    val calibrationState: MeasurementState,
    val correctionState: MeasurementState,
    val energyNote: String,
)

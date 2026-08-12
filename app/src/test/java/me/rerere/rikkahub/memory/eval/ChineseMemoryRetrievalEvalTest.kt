package me.rerere.rikkahub.memory.eval

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.ai.compileMemoryPrompt
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryIndexSearchRequest
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetrievalQuerySource
import me.rerere.rikkahub.data.repository.MemoryRetrievalRequest
import me.rerere.rikkahub.data.repository.MemoryRetriever
import me.rerere.rikkahub.data.repository.MemorySearchCandidate
import me.rerere.rikkahub.data.repository.MemorySearchIndex
import me.rerere.rikkahub.data.repository.memoryQueryTerms
import me.rerere.rikkahub.memory.MemoryApprovalSource
import me.rerere.rikkahub.memory.MemoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.ceil
import kotlin.uuid.Uuid

/**
 * Deterministic, synthetic-only P0 evaluation. Gold labels are exact ids and policy predicates;
 * no model output or LLM judge participates in either retrieval or scoring.
 */
class ChineseMemoryRetrievalEvalTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `Chinese retrieval hard gates and cost report`() = runBlocking {
        val cases = loadCases()
        validateGold(cases)
        assertEquals(32, cases.size)

        val assistantId = Uuid.random()
        val otherAssistantId = Uuid.random()
        val runs = cases.associateWith { case ->
            val retriever = MemoryRetriever(
                index = FixtureMemoryIndex(case, assistantId, otherAssistantId),
            )
            val retrieval = retriever.retrieve(
                MemoryRetrievalRequest(
                    assistantId = assistantId,
                    query = case.query,
                    includeGlobal = case.includeGlobal,
                    frozenNowMs = case.fixedNowMs,
                    querySource = MemoryRetrievalQuerySource.OFFLINE_EVAL,
                ),
            )
            val byId = case.memories.associateBy(ZhEvalMemory::id)
            val promptItems = retrieval.matches.map { match ->
                val fixture = byId.getValue(match.memory.id)
                AssistantMemory(
                    id = fixture.id,
                    title = fixture.title,
                    content = fixture.content,
                    kind = MemoryKind.valueOf(fixture.kind),
                    approvalSource = MemoryApprovalSource.valueOf(fixture.approval),
                )
            }
            val prompt = compileMemoryPrompt(
                memories = promptItems,
                maxTokens = case.memoryPromptTokenBudget,
            )
            ZhEvalRun(
                retrievedIds = retrieval.matches.map { it.memory.id },
                standingIds = prompt.actualStandingIds,
                promptText = prompt.text,
                promptTokens = prompt.estimatedTokens,
                latencyMicros = retrieval.trace.timings.totalMicros,
            )
        }

        // V44 now has authoritative relation/lifecycle state and the lexical paraphrase slice is
        // stable enough to be a real regression gate. No published case is silently deferred.
        val hardCases = cases
        val recallAt = listOf(1, 3, 5, 8).associate { k ->
            "Recall@$k" to macroRecallAt(hardCases, runs, k)
        }
        val forbiddenHitCount = hardCases.sumOf { case ->
            val retrieved = runs.getValue(case).retrievedIds.toSet()
            case.gold.forbiddenByReason.values.flatten().count(retrieved::contains)
        }
        val scopeLeakCount = hardCases.sumOf { case ->
            val retrieved = runs.getValue(case).retrievedIds.toSet()
            case.gold.forbiddenByReason["CROSS_SCOPE"].orEmpty().count(retrieved::contains)
        }
        val staleOrContradictionReasons = setOf(
            "EXPIRED",
            "ARCHIVED",
            "DISPUTED",
            "SUPERSEDED",
            "PROVISIONAL",
        )
        val staleContradictionHitCount = hardCases.sumOf { case ->
            val retrieved = runs.getValue(case).retrievedIds.toSet()
            case.gold.forbiddenByReason
                .filterKeys(staleOrContradictionReasons::contains)
                .values
                .flatten()
                .count(retrieved::contains)
        }
        val staleContradictionForbiddenTotal = hardCases.sumOf { case ->
            case.gold.forbiddenByReason
                .filterKeys(staleOrContradictionReasons::contains)
                .values
                .sumOf { ids -> ids.size }
        }
        val standingFalsePromotionCount = hardCases.sumOf { case ->
            val standing = runs.getValue(case).standingIds.toSet()
            case.gold.forbiddenStandingIds.count(standing::contains)
        }
        val missingExpectedStandingCount = hardCases.sumOf { case ->
            val standing = runs.getValue(case).standingIds.toSet()
            case.gold.expectedStandingIds.count { it !in standing }
        }
        val tokenBudgetViolationCount = hardCases.count { case ->
            runs.getValue(case).promptTokens > case.memoryPromptTokenBudget
        }
        val boundaryCase = cases.single { it.caseId == "zh_024_prompt_boundary" }
        assertTrue(
            !runs.getValue(boundaryCase).promptText.contains(
                "</provider_runtime_context>",
                ignoreCase = true,
            ),
        )

        val report = ZhMemoryEvalReport(
            caseCount = cases.size,
            hardCaseCount = hardCases.size,
            observationalParaphraseCaseCount = cases.count {
                "paraphrase_observational" in it.categories
            },
            deferredRelationCaseCount = cases.count(ZhEvalCase::requiresRelationSchema),
            recallAt = recallAt,
            forbiddenHitCount = forbiddenHitCount,
            scopeLeakCount = scopeLeakCount,
            staleContradictionHitCount = staleContradictionHitCount,
            staleContradictionForbiddenTotal = staleContradictionForbiddenTotal,
            staleContradictionSuppression = if (staleContradictionForbiddenTotal == 0) {
                1.0
            } else {
                1.0 - staleContradictionHitCount.toDouble() / staleContradictionForbiddenTotal
            },
            standingFalsePromotionCount = standingFalsePromotionCount,
            missingExpectedStandingCount = missingExpectedStandingCount,
            tokenBudgetViolationCount = tokenBudgetViolationCount,
            promptTokenP50 = percentile(runs.values.map { it.promptTokens.toLong() }, 0.50),
            promptTokenP95 = percentile(runs.values.map { it.promptTokens.toLong() }, 0.95),
            retrievalLatencyP50Micros = percentile(
                runs.values.map(ZhEvalRun::latencyMicros),
                0.50,
            ),
            retrievalLatencyP95Micros = percentile(
                runs.values.map(ZhEvalRun::latencyMicros),
                0.95,
            ),
            tokenEstimator = "ApproximateContextTokenEstimator",
            latencyMeasurement = "JVM_IN_MEMORY_POLICY_HARNESS_OBSERVATIONAL",
            energyMeasurement = EnergyMeasurement.UNMEASURED,
            energyNote = "Requires a disposable Pixel 6+ with ODPM/PowerMetric; emulator and the primary device are forbidden.",
        )
        val encodedReport = json.encodeToString(report)
        println("ZH_MEMORY_EVAL_REPORT=$encodedReport")

        assertTrue("lexical Recall@3 regressed: $report", recallAt.getValue("Recall@3") >= 0.85)
        assertTrue("lexical Recall@8 regressed: $report", recallAt.getValue("Recall@8") >= 0.95)
        assertEquals(0, forbiddenHitCount)
        assertEquals(0, scopeLeakCount)
        assertEquals(0, staleContradictionHitCount)
        assertEquals(0, standingFalsePromotionCount)
        assertEquals(0, missingExpectedStandingCount)
        assertEquals(0, tokenBudgetViolationCount)
        assertEquals(0, report.deferredRelationCaseCount)
        assertEquals(EnergyMeasurement.UNMEASURED, report.energyMeasurement)
        assertTrue(encodedReport.contains("\"energyMeasurement\":\"UNMEASURED\""))
        assertTrue(!encodedReport.contains("energyMilliJoules", ignoreCase = true))
    }

    private fun loadCases(): List<ZhEvalCase> {
        val resource = requireNotNull(
            javaClass.getResourceAsStream("/memory_eval/zh_retrieval_v1.jsonl"),
        )
        return resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).map { line ->
                json.decodeFromString<ZhEvalCase>(line)
            }.toList()
        }
    }

    private fun validateGold(cases: List<ZhEvalCase>) {
        assertEquals(cases.size, cases.map(ZhEvalCase::caseId).distinct().size)
        cases.forEach { case ->
            assertEquals(1, case.schemaVersion)
            val memoryIds = case.memories.map(ZhEvalMemory::id)
            assertEquals(memoryIds.size, memoryIds.distinct().size)
            val knownIds = memoryIds.toSet()
            val relevant = case.gold.relevantIds.toSet()
            val forbidden = case.gold.forbiddenByReason.values.flatten().toSet()
            assertTrue(case.gold.relevantIds.all(knownIds::contains))
            assertTrue(forbidden.all(knownIds::contains))
            assertTrue(relevant.intersect(forbidden).isEmpty())
            assertTrue(case.gold.expectedStandingIds.all(relevant::contains))
            assertTrue(case.gold.forbiddenStandingIds.all(knownIds::contains))
        }
    }

    private fun macroRecallAt(
        cases: List<ZhEvalCase>,
        runs: Map<ZhEvalCase, ZhEvalRun>,
        k: Int,
    ): Double {
        val eligible = cases.filter { it.gold.relevantIds.isNotEmpty() }
        return eligible.map { case ->
            val retrieved = runs.getValue(case).retrievedIds.take(k).toSet()
            case.gold.relevantIds.count(retrieved::contains).toDouble() / case.gold.relevantIds.size
        }.average()
    }

    private fun percentile(values: List<Long>, fraction: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val index = (ceil(fraction * sorted.size).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }
}

private class FixtureMemoryIndex(
    private val case: ZhEvalCase,
    private val assistantId: Uuid,
    private val otherAssistantId: Uuid,
) : MemorySearchIndex {
    override suspend fun search(
        scopeId: String,
        query: String,
        limit: Int,
    ): List<MemorySearchCandidate> = error("evaluation requires the frozen request API")

    override suspend fun search(request: MemoryIndexSearchRequest): List<MemorySearchCandidate> {
        val terms = memoryQueryTerms(request.query)
        return case.memories.asSequence()
            .filter { memory -> memory.scopeId() == request.scopeId }
            .filter { memory -> memory.lifecycle == "ACTIVE" }
            .filter { memory -> memory.truth == "CONFIRMED" }
            .filter { memory -> memory.expiresAtMs == null || memory.expiresAtMs > request.frozenNowMs }
            .mapNotNull { memory ->
                val searchable = (memory.title.orEmpty() + " " + memory.content).lowercase(Locale.ROOT)
                val matchedCount = terms.count(searchable::contains)
                if (matchedCount == 0) return@mapNotNull null
                MemorySearchCandidate(
                    id = memory.id,
                    title = memory.title,
                    content = memory.content,
                    updatedAtMs = memory.updatedAtMs,
                    importance = memory.importance,
                    ftsRank = -matchedCount.toDouble(),
                )
            }
            .sortedWith(compareBy<MemorySearchCandidate> { it.ftsRank }.thenBy { it.id })
            .take(request.limit)
            .toList()
    }

    private fun ZhEvalMemory.scopeId(): String = when (scope) {
        "ASSISTANT" -> assistantId.toString()
        "OTHER_ASSISTANT" -> otherAssistantId.toString()
        "GLOBAL" -> MemoryRepository.GLOBAL_MEMORY_ID
        else -> error("Unknown synthetic scope $scope")
    }
}

@Serializable
private data class ZhEvalCase(
    val schemaVersion: Int,
    val caseId: String,
    val categories: List<String>,
    val requiresRelationSchema: Boolean = false,
    val fixedNowMs: Long,
    val includeGlobal: Boolean = false,
    val query: String,
    val memoryPromptTokenBudget: Int = 1_024,
    val memories: List<ZhEvalMemory>,
    val gold: ZhEvalGold,
)

@Serializable
private data class ZhEvalMemory(
    val id: Int,
    val scope: String,
    val title: String? = null,
    val content: String,
    val updatedAtMs: Long = 1_000_000L,
    val importance: Float = 0.5f,
    val expiresAtMs: Long? = null,
    val lifecycle: String = "ACTIVE",
    val truth: String = "CONFIRMED",
    val kind: String = "OTHER",
    val approval: String = "AUTO_SAFE",
)

@Serializable
private data class ZhEvalGold(
    val relevantIds: List<Int>,
    val forbiddenByReason: Map<String, List<Int>> = emptyMap(),
    val expectedStandingIds: List<Int> = emptyList(),
    val forbiddenStandingIds: List<Int> = emptyList(),
)

private data class ZhEvalRun(
    val retrievedIds: List<Int>,
    val standingIds: List<Int>,
    val promptText: String,
    val promptTokens: Int,
    val latencyMicros: Long,
)

@Serializable
private data class ZhMemoryEvalReport(
    val schemaVersion: Int = 1,
    val caseCount: Int,
    val hardCaseCount: Int,
    val observationalParaphraseCaseCount: Int,
    val deferredRelationCaseCount: Int,
    val recallAt: Map<String, Double>,
    val forbiddenHitCount: Int,
    val scopeLeakCount: Int,
    val staleContradictionHitCount: Int,
    val staleContradictionForbiddenTotal: Int,
    val staleContradictionSuppression: Double,
    val standingFalsePromotionCount: Int,
    val missingExpectedStandingCount: Int,
    val tokenBudgetViolationCount: Int,
    val promptTokenP50: Long,
    val promptTokenP95: Long,
    val retrievalLatencyP50Micros: Long,
    val retrievalLatencyP95Micros: Long,
    val tokenEstimator: String,
    val latencyMeasurement: String,
    val energyMeasurement: EnergyMeasurement,
    val energyNote: String,
)

@Serializable
private enum class EnergyMeasurement {
    UNMEASURED,
}

package me.rerere.rikkahub.learning.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.retrieval.PolicyRetrievalRequest
import me.rerere.rikkahub.learning.retrieval.PolicyRetriever
import me.rerere.rikkahub.learning.retrieval.PolicyShadowCandidate
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frozen P1 Chinese shadow gate. It compares local retrieval only and never calls a provider. */
class PolicyShadowRetrievalEvalTest {
    private val json = Json { ignoreUnknownKeys = false }
    private val signature = TaskSignatureV1.create(
        LearningTaskClass.INFORMATION,
        LearningLanguageClass.CHINESE,
        LearningModalityClass.TEXT_ONLY,
        emptySet(),
    )

    @Test
    fun chineseShadowMeetsFrozenRecallAndSafetyGate() {
        val cases = loadCases()
        val candidates = policyCorpus(cases)
        val runs = cases.map { case ->
            val scope = parseScope(case.scopeKind, case.scopeId)
            case to PolicyRetriever(ByteArray(32) { 19 }).retrieve(
                request = PolicyRetrievalRequest(
                    scope = scope,
                    taskSignature = signature,
                    query = case.query,
                    maxCandidates = 5,
                    maxEstimatedTokens = 512,
                ),
                candidates = candidates,
            )
        }

        val recallAt1 = macroRecall(runs, 1)
        val recallAt3 = macroRecall(runs, 3)
        val recallAt5 = macroRecall(runs, 5)
        assertTrue("Recall@1 regressed: $recallAt1", recallAt1 >= 0.80)
        assertTrue("Recall@3 regressed: $recallAt3", recallAt3 >= 0.95)
        assertTrue("Recall@5 regressed: $recallAt5", recallAt5 >= 1.0)
        runs.forEach { (case, result) ->
            assertTrue(
                "scope leak in ${case.caseId}",
                result.hits.all { it.candidate.scope == parseScope(case.scopeKind, case.scopeId) },
            )
            assertTrue("stale/schema-invalid hit", result.hits.all {
                it.candidate.sourceValid && it.candidate.toolSchemaValid
            })
            assertEquals(result.hits.map { it.candidate.policyId },
                PolicyRetriever(ByteArray(32) { 19 }).retrieve(
                    PolicyRetrievalRequest(
                        parseScope(case.scopeKind, case.scopeId), signature, case.query,
                    ),
                    candidates,
                ).hits.map { it.candidate.policyId },
            )
        }
    }

    private fun loadCases(): List<PolicyShadowEvalCase> = requireNotNull(
        javaClass.getResourceAsStream("/learning_eval/zh_policy_shadow_v1.jsonl"),
    ).bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.filter(String::isNotBlank)
            .map { line -> json.decodeFromString<PolicyShadowEvalCase>(line) }
            .toList()
    }.also { cases ->
        assertEquals(10, cases.size)
        assertEquals(cases.size, cases.map(PolicyShadowEvalCase::caseId).distinct().size)
    }

    private fun policyCorpus(cases: List<PolicyShadowEvalCase>): List<PolicyShadowCandidate> {
        val textById = mapOf(
            "policy-check-before-submit" to "提交以前先检查文件，验证有效后再提交。",
            "policy-save-before-submit" to "提交草稿以前先保存，确认草稿已经存好。",
            "policy-local-on-offline" to "离线时保留设备本地结果，不要丢弃。",
            "policy-confirm-before-delete" to "没有明确同意就不能执行删除。",
            "policy-retry-network-timeout-once" to "不要自动重试；只有网络超时可以重试一次。",
            "policy-a" to "助手甲需要遵守的步骤。",
            "policy-b" to "助手乙需要遵守的步骤。",
            "policy-auth-s" to "同一授权主体的经验。",
            "policy-stop-on-verification-failure" to "验证失败以后立即停止并报告。",
            "policy-preserve-negation-exception" to "否定条件与例外必须完整保留，不能截断。",
        )
        val valid = cases.flatMap { case ->
            case.goldPolicyIds.map { id ->
                candidate(id, parseScope(case.scopeKind, case.scopeId), textById.getValue(id))
            }
        }.distinctBy(PolicyShadowCandidate::policyId)
        val scopeA = LearningScope.Assistant(Uuid.parse("00000000-0000-0000-0000-00000000000a"))
        return valid + listOf(
            candidate("policy-stale", scopeA, "验证失败以后立即停止并报告。", sourceValid = false),
            candidate("policy-schema-invalid", scopeA, "提交以前先检查文件。", schemaValid = false),
            candidate("policy-cross-scope", LearningScope.Assistant(Uuid.random()), "明确同意以后删除。"),
            candidate("policy-distractor", scopeA, "在回复中说明天气情况。"),
        )
    }

    private fun candidate(
        id: String,
        scope: LearningScope,
        text: String,
        sourceValid: Boolean = true,
        schemaValid: Boolean = true,
    ) = PolicyShadowCandidate(
        policyId = id,
        scope = scope,
        taskSignature = signature,
        status = LearningPolicyStatus.SHADOW,
        artifactHash = me.rerere.rikkahub.learning.model.LearningCanonicalId.digest(
            "shadow-eval-policy-v1",
            listOf(id),
        ),
        sourceValid = sourceValid,
        toolSchemaValid = schemaValid,
        searchableText = text,
        estimatedTokens = 24,
        updatedAtMs = 1,
    )

    private fun parseScope(kind: String, id: String): LearningScope = requireNotNull(
        LearningScope.parseOrNull(kind, id),
    )

    private fun macroRecall(
        runs: List<Pair<PolicyShadowEvalCase, me.rerere.rikkahub.learning.retrieval.PolicyRetrievalResult>>,
        k: Int,
    ): Double = runs.map { (case, result) ->
        val gold = case.goldPolicyIds.toSet()
        result.hits.take(k).map { it.candidate.policyId }.count(gold::contains).toDouble() /
            gold.size.toDouble()
    }.average()
}

@Serializable
private data class PolicyShadowEvalCase(
    val schemaVersion: Int,
    val caseId: String,
    val scopeKind: String,
    val scopeId: String,
    val query: String,
    val goldPolicyIds: List<String>,
) {
    init {
        require(schemaVersion == 1)
        require(caseId.matches(Regex("[a-z0-9_]{1,64}")))
        require(query.length in 1..2_048)
        require(goldPolicyIds.isNotEmpty())
    }
}

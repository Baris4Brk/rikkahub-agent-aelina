package me.rerere.rikkahub.learning.retrieval

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.task.LearningLanguageClass
import me.rerere.rikkahub.learning.task.LearningModalityClass
import me.rerere.rikkahub.learning.task.LearningTaskClass
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PolicyRetrieverTest {
    @Test
    fun scopeSourceAndSchemaAreFilteredBeforeSelection() {
        val scope = LearningScope.Assistant(Uuid.random())
        val other = LearningScope.Assistant(Uuid.random())
        val signature = signature()
        val candidates = listOf(
            candidate("good", scope, signature, true, true, "先检查输入，再执行安全步骤"),
            candidate("leak", other, signature, true, true, "先检查输入"),
            candidate("stale", scope, signature, false, true, "先检查输入"),
            candidate("schema", scope, signature, true, false, "先检查输入"),
        )

        val result = PolicyRetriever(ByteArray(32) { 7 }).retrieve(
            PolicyRetrievalRequest(scope, signature, "检查 输入"),
            candidates,
        )

        assertEquals(listOf("good"), result.hits.map { it.candidate.policyId })
        assertEquals(1, result.trace.dropReasonCounts[PolicyRetrievalDropReason.SCOPE_MISMATCH])
        assertEquals(1, result.trace.dropReasonCounts[PolicyRetrievalDropReason.SOURCE_STALE])
        assertEquals(1, result.trace.dropReasonCounts[PolicyRetrievalDropReason.TOOL_SCHEMA_STALE])
        assertFalse(result.trace.toString().contains("good"))
    }

    @Test
    fun chineseLongQueryIsBoundedAndOrderingReplaysDeterministically() {
        val scope = LearningScope.Assistant(Uuid.random())
        val signature = signature()
        val query = "验证失败后回滚".repeat(500)
        val prepared = PolicyFtsManager.prepareQuery(query)
        assertTrue(prepared.normalized.length <= PolicyFtsManager.MAX_QUERY_CHARS)
        assertTrue(prepared.terms.size <= PolicyFtsManager.MAX_QUERY_TERMS)

        val candidates = listOf(
            candidate("b", scope, signature, true, true, "验证失败后回滚", updatedAt = 2),
            candidate("a", scope, signature, true, true, "验证失败后回滚", updatedAt = 2),
        )
        fun ids() = PolicyRetriever(ByteArray(32) { 9 }).retrieve(
            PolicyRetrievalRequest(scope, signature, query),
            candidates,
        ).hits.map { it.candidate.policyId }

        assertEquals(listOf("a", "b"), ids())
        assertEquals(ids(), ids())
    }

    @Test
    fun oversizedInputAndCandidateSetsAreRejectedBeforeWork() {
        val scope = LearningScope.Assistant(Uuid.random())
        val signature = signature()
        assertThrows(IllegalArgumentException::class.java) {
            PolicyRetrievalRequest(scope, signature, "x".repeat(MAX_POLICY_RAW_QUERY_CHARS + 1))
        }
        val one = candidate("one", scope, signature, true, true, "safe candidate")
        assertThrows(IllegalArgumentException::class.java) {
            PolicyRetriever(ByteArray(32) { 3 }).retrieve(
                PolicyRetrievalRequest(scope, signature, "safe"),
                List(MAX_POLICY_RETRIEVAL_INPUT_CANDIDATES + 1) { index ->
                    one.copy(policyId = "candidate-$index")
                },
            )
        }
    }

    @Test
    fun latencyBudgetStopsSelectionBeforeOpaqueIdWorkContinues() {
        val scope = LearningScope.Assistant(Uuid.random())
        val signature = signature()
        var now = 0L
        val retriever = PolicyRetriever(
            opaqueIds = PolicyOpaqueIdFactory { "policy-hit-v1:" + "a".repeat(64) },
            monotonicNanos = { now.also { now += 30_000L } },
        )
        val result = retriever.retrieve(
            PolicyRetrievalRequest(
                scope = scope,
                taskSignature = signature,
                query = "检查输入",
                maxLatencyMicros = 100,
            ),
            listOf(candidate("first", scope, signature, true, true, "检查输入")),
        )
        assertTrue(result.hits.size <= 1)
        assertTrue(
            result.trace.dropReasonCounts.getValue(PolicyRetrievalDropReason.LATENCY_BUDGET) > 0,
        )
    }

    private fun signature() = TaskSignatureV1.create(
        LearningTaskClass.INFORMATION,
        LearningLanguageClass.CHINESE,
        LearningModalityClass.TEXT_ONLY,
        emptySet(),
    )

    private fun candidate(
        id: String,
        scope: LearningScope,
        signature: TaskSignatureV1,
        sourceValid: Boolean,
        schemaValid: Boolean,
        text: String,
        updatedAt: Long = 1L,
    ) = PolicyShadowCandidate(
        policyId = id,
        scope = scope,
        taskSignature = signature,
        status = LearningPolicyStatus.SHADOW,
        artifactHash = id.first().code.toString(16).padStart(64, '0').takeLast(64),
        sourceValid = sourceValid,
        toolSchemaValid = schemaValid,
        searchableText = text,
        estimatedTokens = 20,
        updatedAtMs = updatedAt,
    )
}

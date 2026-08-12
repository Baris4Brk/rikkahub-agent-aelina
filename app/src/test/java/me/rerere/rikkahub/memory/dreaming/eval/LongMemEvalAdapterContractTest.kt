package me.rerere.rikkahub.memory.dreaming.eval

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LongMemEvalAdapterContractTest {
    @Test
    fun `V1 contract locks six qtypes thirty abstentions and every reproducibility field`() {
        val lock = BenchmarkLocks.longMemEvalV1ContractFixture()
        BenchmarkMetadataValidator.validateContract(lock)

        assertEquals("9e0b455f4ef0e2ab8f2e582289761153549043fc", lock.repositoryCommit)
        assertEquals("xiaowu0162/longmemeval-cleaned", lock.dataset.identifier)
        assertEquals(500, lock.expectedQuestionCount)
        assertEquals(30, lock.expectedAbstentionCount)
        assertEquals(LONG_MEM_EVAL_QTYPES, lock.questionCategories)
        assertEquals(listOf(1, 3, 5, 10, 30, 50), lock.run.topK)
        assertEquals(4_096, lock.run.tokenBudget)
        assertEquals(setOf(CacheMode.COLD, CacheMode.WARM), lock.run.cacheModes)
        assertTrue(lock.run.waitForConsolidation)
        assertTrue(lock.run.runs >= 2)
        assertTrue(lock.run.seed != 0L)
        assertTrue(lock.run.readerModelIdentity.isNotBlank())
        assertTrue(lock.run.tokenizerIdentity.isNotBlank())
        assertTrue(lock.run.extractorIdentity.isNotBlank())
        assertTrue(lock.run.judgeIdentity.isNotBlank())
        assertTrue(lock.run.promptSha256.matches(SHA256))
        assertThrows(IllegalArgumentException::class.java) {
            BenchmarkMetadataValidator.validatePublishable(lock)
        }
    }

    @Test
    fun `adapter gives backend only opaque invocation question and frozen date`() {
        val gold = LongMemEvalGoldCase(
            questionId = "secret-question-id_abs",
            questionType = "knowledge-update",
            question = "纯合成问题：当前项目是什么？",
            frozenQuestionDate = "2026-08-12",
            answer = "secret-gold-answer",
            evidenceSessionIds = listOf("secret-evidence-id"),
        )
        val probe = RecordingBackend()
        LongMemEvalPrivacyAdapter(probe).query(gold)
        val received = requireNotNull(probe.received)
        val visible = listOf(received.opaqueInvocationId, received.question, received.frozenQuestionDate)
            .joinToString("|")

        assertEquals(gold.question, received.question)
        assertEquals(gold.frozenQuestionDate, received.frozenQuestionDate)
        assertTrue(received.opaqueInvocationId.matches(Regex("lme-[0-9a-f]{24}")))
        assertFalse(visible.contains(gold.questionId))
        assertFalse(visible.contains(gold.questionType))
        assertFalse(visible.contains(gold.answer))
        assertFalse(visible.contains(gold.evidenceSessionIds.single()))
    }

    @Test
    fun `V2 keeps five abilities and treats query latency only as a dimension`() {
        val lock = BenchmarkLocks.longMemEvalV2ContractFixture()
        BenchmarkMetadataValidator.validateContract(lock)

        assertEquals("2cc8c540bdb87fe6761629b585e727e1c4704520", lock.repositoryCommit)
        assertEquals("xiaowu0162/longmemeval-v2", lock.dataset.identifier)
        assertEquals(451, lock.expectedQuestionCount)
        assertEquals(LONG_MEM_EVAL_V2_ABILITIES, lock.questionCategories)
        assertEquals(setOf("answer_accuracy", "query_latency"), lock.evaluationDimensions)
        assertFalse("query_latency" in lock.questionCategories)
    }

    @Test
    fun `report template refuses unresolved dataset SHA instead of claiming a score`() {
        val template = requireNotNull(
            javaClass.getResourceAsStream("/dreaming_eval/benchmark_report_template.properties"),
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }

        REQUIRED_REPORT_FIELDS.forEach { field ->
            assertTrue("Report template misses $field", template.lineSequence().any { it.startsWith("$field=") })
        }
        assertTrue(template.contains("dataset_sha256=<REQUIRED_64_HEX>"))
        assertTrue(template.contains("energy_measurement=UNMEASURED"))
    }

    private class RecordingBackend : MemoryBackendProbe {
        var received: BackendQuery? = null
        override fun query(request: BackendQuery): List<String> {
            received = request
            return emptyList()
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        val REQUIRED_REPORT_FIELDS = setOf(
            "repository_commit",
            "dataset_identifier",
            "dataset_version",
            "dataset_sha256",
            "question_categories",
            "top_k",
            "reader_model_identity",
            "tokenizer_identity",
            "extractor_identity",
            "prompt_sha256",
            "judge_identity",
            "seed",
            "runs",
            "cache_modes",
            "consolidation_mode",
            "energy_measurement",
        )
    }
}

internal val LONG_MEM_EVAL_QTYPES = setOf(
    "single-session-user",
    "single-session-assistant",
    "single-session-preference",
    "temporal-reasoning",
    "knowledge-update",
    "multi-session",
)

internal val LONG_MEM_EVAL_V2_ABILITIES = setOf(
    "static_state_recall",
    "dynamic_state_tracking",
    "workflow_knowledge",
    "environment_gotchas",
    "premise_awareness",
)

internal enum class ArtifactStatus { CONTRACT_FIXTURE, VERIFIED_DATASET }
internal enum class CacheMode { COLD, WARM }

internal data class DatasetArtifactLock(
    val identifier: String,
    val version: String,
    val sha256: String,
    val status: ArtifactStatus,
)

internal data class BenchmarkRunLock(
    val topK: List<Int>,
    val tokenBudget: Int,
    val readerModelIdentity: String,
    val tokenizerIdentity: String,
    val extractorIdentity: String,
    val promptSha256: String,
    val judgeIdentity: String,
    val seed: Long,
    val runs: Int,
    val cacheModes: Set<CacheMode>,
    val consolidationMode: String,
    val waitForConsolidation: Boolean,
)

internal data class BenchmarkMetadataLock(
    val benchmarkId: String,
    val repositoryUrl: String,
    val repositoryCommit: String,
    val dataset: DatasetArtifactLock,
    val expectedQuestionCount: Int,
    val expectedAbstentionCount: Int,
    val questionCategories: Set<String>,
    val evaluationDimensions: Set<String>,
    val run: BenchmarkRunLock,
)

internal object BenchmarkMetadataValidator {
    private val commitPattern = Regex("[0-9a-f]{40}")
    private val shaPattern = Regex("[0-9a-f]{64}")

    fun validateContract(lock: BenchmarkMetadataLock) {
        require(lock.repositoryCommit.matches(commitPattern))
        require(lock.dataset.identifier.isNotBlank())
        require(lock.dataset.version.isNotBlank())
        require(lock.dataset.sha256.matches(shaPattern))
        require(lock.questionCategories.isNotEmpty())
        require(lock.run.topK.isNotEmpty() && lock.run.topK.all { it > 0 })
        require(lock.run.tokenBudget > 0)
        require(lock.run.readerModelIdentity.isNotBlank())
        require(lock.run.tokenizerIdentity.isNotBlank())
        require(lock.run.extractorIdentity.isNotBlank())
        require(lock.run.promptSha256.matches(shaPattern))
        require(lock.run.judgeIdentity.isNotBlank())
        require(lock.run.runs > 0)
        require(lock.run.cacheModes == setOf(CacheMode.COLD, CacheMode.WARM))
        require(lock.run.consolidationMode.isNotBlank())
    }

    fun validatePublishable(lock: BenchmarkMetadataLock) {
        validateContract(lock)
        require(lock.dataset.status == ArtifactStatus.VERIFIED_DATASET) {
            "Contract fixture cannot be published as an official benchmark run"
        }
    }
}

internal object BenchmarkLocks {
    fun longMemEvalV1ContractFixture() = BenchmarkMetadataLock(
        benchmarkId = "LongMemEval",
        repositoryUrl = "https://github.com/xiaowu0162/LongMemEval",
        repositoryCommit = "9e0b455f4ef0e2ab8f2e582289761153549043fc",
        dataset = DatasetArtifactLock(
            identifier = "xiaowu0162/longmemeval-cleaned",
            version = "2025-09-cleaned-CONTRACT-FIXTURE",
            sha256 = sha256("local-contract-fixture:longmemeval-cleaned"),
            status = ArtifactStatus.CONTRACT_FIXTURE,
        ),
        expectedQuestionCount = 500,
        expectedAbstentionCount = 30,
        questionCategories = LONG_MEM_EVAL_QTYPES,
        evaluationDimensions = setOf("retrieval", "state", "evidence_id", "answer_quality"),
        run = frozenRun(topK = listOf(1, 3, 5, 10, 30, 50)),
    )

    fun longMemEvalV2ContractFixture() = BenchmarkMetadataLock(
        benchmarkId = "LongMemEval-V2",
        repositoryUrl = "https://github.com/xiaowu0162/LongMemEval-V2",
        repositoryCommit = "2cc8c540bdb87fe6761629b585e727e1c4704520",
        dataset = DatasetArtifactLock(
            identifier = "xiaowu0162/longmemeval-v2",
            version = "2026-05-public-CONTRACT-FIXTURE",
            sha256 = sha256("local-contract-fixture:longmemeval-v2"),
            status = ArtifactStatus.CONTRACT_FIXTURE,
        ),
        expectedQuestionCount = 451,
        expectedAbstentionCount = 0,
        questionCategories = LONG_MEM_EVAL_V2_ABILITIES,
        evaluationDimensions = setOf("answer_accuracy", "query_latency"),
        run = frozenRun(topK = listOf(10)),
    )

    private fun frozenRun(topK: List<Int>) = BenchmarkRunLock(
        topK = topK,
        tokenBudget = 4_096,
        readerModelIdentity = "OFFLINE_CONTRACT_NO_MODEL_CALL",
        tokenizerIdentity = "UTF8_BYTES_CEIL_DIV_4_V1",
        extractorIdentity = "EVIDENCE_ID_FIRST_V1",
        promptSha256 = sha256("dreaming-eval-prompt-contract-v1"),
        judgeIdentity = "NONE_CONTRACT_ONLY",
        seed = 20_260_812L,
        runs = 3,
        cacheModes = setOf(CacheMode.COLD, CacheMode.WARM),
        consolidationMode = "WAIT_UNTIL_COMPLETE",
        waitForConsolidation = true,
    )
}

internal data class LongMemEvalGoldCase(
    val questionId: String,
    val questionType: String,
    val question: String,
    val frozenQuestionDate: String,
    val answer: String,
    val evidenceSessionIds: List<String>,
)

internal data class BackendQuery(
    val opaqueInvocationId: String,
    val question: String,
    val frozenQuestionDate: String,
)

internal fun interface MemoryBackendProbe {
    fun query(request: BackendQuery): List<String>
}

internal class LongMemEvalPrivacyAdapter(
    private val backend: MemoryBackendProbe,
) {
    fun query(gold: LongMemEvalGoldCase): List<String> {
        val opaque = sha256("${gold.question}\u0000${gold.frozenQuestionDate}").take(24)
        return backend.query(
            BackendQuery(
                opaqueInvocationId = "lme-$opaque",
                question = gold.question,
                frozenQuestionDate = gold.frozenQuestionDate,
            ),
        )
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

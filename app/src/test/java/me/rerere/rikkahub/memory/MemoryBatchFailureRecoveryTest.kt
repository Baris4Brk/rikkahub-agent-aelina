package me.rerere.rikkahub.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBatchFailureRecoveryTest {
    @Test
    fun `provider role words are normalized before a candidate is committed`() =
        runBlocking {
            val store = RecordingStore(listOf(capture(1)))
            val coordinator = DefaultMemoryV2Coordinator(
                captureStore = NoopCaptureStore,
                workScheduler = NoopWorkScheduler,
                processingStore = store,
                extractor = MemoryExtractor {
                    MemoryExtractorResult.Success(
                        """{
                          "version":2,
                          "proposals":[{
                            "proposalKey":"p1",
                            "action":"create",
                            "title":"用户和助手的长期偏好",
                            "content":"USER 与 ASSISTANT 确认了一个长期偏好。",
                            "kind":"preference",
                            "attribution":"shared",
                            "participants":["用户","forged"],
                            "tags":["用户偏好"],
                            "importance":0.8,
                            "confidence":0.95,
                            "evidenceMessageIds":["T1"],
                            "reason":"assistant observed a stable preference"
                          }],
                          "relations":[]
                        }""".trimIndent(),
                    )
                },
                narrativeIdentityResolver = MemoryNarrativeIdentityResolver {
                    MemoryNarrativeIdentity(selfName = "角色甲", companionName = "角色乙")
                },
                idGenerator = { "generated" },
                nowMs = { 10_000L },
            )

            coordinator.process(MemoryProcessRequest(TEST_SCOPE, "worker"))

            val proposal = store.commits.single().candidates.single().proposal
            assertEquals("角色甲和角色乙的长期偏好", proposal.title)
            assertEquals("角色甲 与 角色乙 确认了一个长期偏好。", proposal.content)
            assertEquals("角色乙 observed a stable preference", proposal.reason)
            assertEquals(listOf("USER", "ASSISTANT"), proposal.participants)
            assertEquals(listOf("角色甲偏好"), proposal.tags)
        }

    @Test
    fun `extraction receives configured readable names instead of protocol role labels`() =
        runBlocking {
            val store = RecordingStore(listOf(capture(1)))
            var received: MemoryNarrativeIdentity? = null
            val coordinator = DefaultMemoryV2Coordinator(
                captureStore = NoopCaptureStore,
                workScheduler = NoopWorkScheduler,
                processingStore = store,
                extractor = MemoryExtractor { request ->
                    received = request.narrativeIdentity
                    MemoryExtractorResult.Success("""{"version":2,"proposals":[],"relations":[]}""")
                },
                narrativeIdentityResolver = MemoryNarrativeIdentityResolver {
                    MemoryNarrativeIdentity(selfName = "角色甲", companionName = "角色乙")
                },
                idGenerator = { "generated" },
                nowMs = { 10_000L },
            )

            coordinator.process(MemoryProcessRequest(TEST_SCOPE, "worker"))

            assertEquals("角色甲", received?.selfName)
            assertEquals("角色乙", received?.companionName)
        }

    @Test
    fun `long conversation is compacted before its one unified extraction request`() =
        runBlocking {
            val captures = (1..12).map(::capture)
            val store = RecordingStore(captures)
            var extractionRequest: MemoryExtractionRequest? = null
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = NoopCaptureStore,
                workScheduler = NoopWorkScheduler,
                processingStore = store,
                extractor = MemoryExtractor { request ->
                    extractionRequest = request
                    if (!request.isConversationContextCompacted ||
                        request.turns.sumOf { it.userText.length + it.assistantText.length } >
                        MAX_MEMORY_EXTRACTION_CONTEXT_CHARS
                    ) {
                        MemoryExtractorResult.Failure(
                            code = "memory_extraction_provider_error",
                            message = "Failed to get response: 500: Internal server error",
                            retryPolicy = MemoryFailureRetryPolicy.AUTOMATIC,
                        )
                    } else {
                        MemoryExtractorResult.Success(
                            """{"version":2,"proposals":[],"relations":[]}""",
                        )
                    }
                },
                idGenerator = { "generated" },
                nowMs = { 10_000L },
            )

            val result = coordinator.process(MemoryProcessRequest(TEST_SCOPE, "worker"))

            assertEquals(
                MemoryProcessResult.Completed(
                    processedCaptures = 12,
                    autoApplied = 0,
                    pendingReview = 0,
                    superseded = 0,
                    rejectedProposals = 0,
                    failedCaptures = 0,
                    automaticRetryFailedCaptures = 0,
                ),
                result,
            )
            assertEquals(captures.map { it.id }.toSet(), store.committedCaptureIds.toSet())
            assertEquals(captures.size, store.committedCaptureIds.size)
            assertTrue(store.failedCaptureIds.isEmpty())
            assertTrue(extractionRequest?.isConversationContextCompacted == true)
            assertEquals(12, extractionRequest?.turns?.size)
        }

    @Test
    fun `thirty connected turns are extracted as one compact conversation and one candidate`() =
        runBlocking {
            val captures = (1..30).map { index -> capture(index, contextTurns = 30) }
            val store = RecordingStore(captures)
            var extractionRequest: MemoryExtractionRequest? = null
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = NoopCaptureStore,
                workScheduler = NoopWorkScheduler,
                processingStore = store,
                extractor = MemoryExtractor { request ->
                    extractionRequest = request
                    MemoryExtractorResult.Success(
                        """{
                          "version":2,
                          "proposals":[{
                            "proposalKey":"p1",
                            "action":"create",
                            "title":"Long project discussion decision",
                            "content":"The user and assistant completed one connected long project discussion and agreed on its durable direction.",
                            "kind":"project_fact",
                            "tags":["project"],
                            "importance":0.8,
                            "confidence":0.95,
                            "evidenceMessageIds":["T30"],
                            "reason":"One connected conversation"
                          }],
                          "relations":[]
                        }""".trimIndent(),
                    )
                },
                idGenerator = { "generated" },
                nowMs = { 10_000L },
            )

            val result = coordinator.process(MemoryProcessRequest(TEST_SCOPE, "worker"))

            assertEquals(30, (result as MemoryProcessResult.Completed).processedCaptures)
            assertEquals(1, result.autoApplied)
            assertEquals(1, store.commits.size)
            assertEquals(30, store.commits.single().captures.size)
            assertEquals(
                listOf("user-30", "assistant-30"),
                store.commits.single().candidates.single().proposal.evidenceMessageIds,
            )
            assertTrue(extractionRequest?.isConversationContextCompacted == true)
            assertEquals((1..30).map { "T$it" }, extractionRequest?.turns?.map { it.evidenceRef })
        }

    private class RecordingStore(
        captures: List<MemoryCaptureRecord>,
    ) : MemoryProcessingStore {
        private val remaining = captures.toMutableList()
        val committedCaptureIds = mutableListOf<String>()
        val failedCaptureIds = mutableListOf<String>()
        val commits = mutableListOf<MemoryProcessCommit>()

        override suspend fun claim(request: MemoryClaimRequest): List<MemoryCaptureRecord> {
            val frozenContextTurns = remaining.firstOrNull()?.conversationContextTurns
                ?: return emptyList()
            return remaining.take(
                minOf(
                    request.maxTurnsPerConversation,
                    request.maxCaptures,
                    frozenContextTurns,
                ),
            )
        }

        override suspend fun findExisting(
            scopeId: String,
            query: String,
            limit: Int,
            frozenNowMs: Long,
        ): List<ExistingMemoryRecord> = emptyList()

        override suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult {
            commits += commit
            committedCaptureIds += commit.captures.map { it.id }
            remaining.removeAll { capture -> commit.captures.any { it.id == capture.id } }
            return MemoryCommitResult(
                autoApplied = commit.candidates.count {
                    it.disposition == MemoryCandidateDisposition.AUTO_APPLY
                },
                pendingReview = commit.candidates.count {
                    it.disposition == MemoryCandidateDisposition.REVIEW
                },
                superseded = commit.candidates.count {
                    it.disposition == MemoryCandidateDisposition.SUPERSEDE
                },
            )
        }

        override suspend fun markFailed(
            captureIds: List<String>,
            scopeId: String,
            workerId: String,
            code: String,
            message: String?,
            retryPolicy: MemoryFailureRetryPolicy,
            nowMs: Long,
        ) {
            failedCaptureIds += captureIds
            remaining.removeAll { it.id in captureIds }
        }

        override suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long) = Unit

        override suspend fun review(
            command: MemoryReviewCommand,
            nowMs: Long,
        ): MemoryReviewResult = MemoryReviewResult.NotFound
    }

    private companion object {
        const val TEST_SCOPE = "__global__"

        val NoopCaptureStore = object : MemoryCaptureStore {
            override suspend fun insert(record: MemoryCaptureRecord): MemoryCaptureInsertResult =
                MemoryCaptureInsertResult.Inserted

            override suspend fun pendingCount(scopeId: String): Int = 0
        }

        val NoopWorkScheduler = object : MemoryWorkScheduler {
            override suspend fun schedule(request: MemoryWorkRequest) = Unit
        }

        fun capture(
            index: Int,
            contextTurns: Int = MEMORY_DEFAULT_CONVERSATION_CONTEXT_TURNS,
        ) = MemoryCaptureRecord(
            id = "capture-$index",
            assistantId = "assistant-id",
            scopeId = TEST_SCOPE,
            conversationId = "conversation-id",
            userMessageId = "user-$index",
            assistantMessageId = "assistant-$index",
            origin = MemoryCaptureOrigin.APP_UI,
            autoSaveMode = MemoryAutoSaveMode.SAFE_NEW_ONLY,
            userText = "user turn $index",
            assistantText = "assistant turn $index",
            createdAtMs = index.toLong(),
            conversationContextTurns = contextTurns,
        )
    }
}

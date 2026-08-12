package me.rerere.rikkahub.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryV2CoordinatorTest {
    @Test
    fun `lease fencing uses a fresh clock while proposal validation keeps its frozen clock`() =
        runBlocking {
            var clockReads = 0
            val processStore = RecordingMemoryProcessingStore(
                claimed = listOf(memoryCaptureRecord(index = 1)),
            )
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = InMemoryMemoryCaptureStore(),
                workScheduler = RecordingMemoryWorkScheduler(),
                processingStore = processStore,
                extractor = MemoryExtractor {
                    MemoryExtractorResult.Success("""{"version":1,"proposals":[]}""")
                },
                idGenerator = { Uuid.random().toString() },
                nowMs = { if (clockReads++ == 0) 1_000L else 2_000L },
            )

            assertTrue(
                coordinator.process(MemoryProcessRequest(MEMORY_TEST_SCOPE, "worker")) is
                    MemoryProcessResult.Completed,
            )
            assertEquals(1_000L, processStore.commits.single().nowMs)
            assertEquals(2_000L, processStore.commits.single().leaseNowMs)
        }

    @Test
    fun `failed extraction fences the lease with a fresh clock`() = runBlocking {
        var clockReads = 0
        val processStore = RecordingMemoryProcessingStore(
            claimed = listOf(memoryCaptureRecord(index = 1)),
        )
        val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = InMemoryMemoryCaptureStore(),
            workScheduler = RecordingMemoryWorkScheduler(),
            processingStore = processStore,
            extractor = MemoryExtractor {
                MemoryExtractorResult.Failure("synthetic_failure")
            },
            idGenerator = { Uuid.random().toString() },
            nowMs = { if (clockReads++ == 0) 1_000L else 2_000L },
        )

        assertTrue(
            coordinator.process(MemoryProcessRequest(MEMORY_TEST_SCOPE, "worker")) is
                MemoryProcessResult.Completed,
        )
        assertEquals(listOf(2_000L), processStore.failureTimes)
    }

    @Test
    fun `relation review reaches the processing store with the exact scope and frozen time`() =
        runBlocking {
            val processStore = RecordingMemoryProcessingStore(
                claimed = emptyList(),
                relationReviewResult = MemoryRelationReviewResult.Applied("link-7"),
            )
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = InMemoryMemoryCaptureStore(),
                workScheduler = RecordingMemoryWorkScheduler(),
                processingStore = processStore,
                idGenerator = { "generated-id" },
                nowMs = { 12_345L },
            )
            val command = MemoryRelationReviewCommand.Accept(
                relationCandidateId = "relation-1",
                expectedScopeId = "scope-owned-by-row",
            )

            assertEquals(
                MemoryRelationReviewResult.Applied("link-7"),
                coordinator.reviewRelation(command),
            )
            assertEquals(listOf(command), processStore.relationReviews)
            assertEquals(listOf(12_345L), processStore.relationReviewTimes)
        }

    @Test
    fun `quick capture memory is disabled by default and works only after explicit origin opt in`() =
        runBlocking {
            val store = InMemoryMemoryCaptureStore()
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = store,
                workScheduler = RecordingMemoryWorkScheduler(),
                idGenerator = { Uuid.random().toString() },
            )
            val quickCapture = completedTurn(1).copy(origin = MemoryCaptureOrigin.QUICK_CAPTURE)

            assertEquals(
                MemoryCaptureResult.Skipped(MemoryCaptureSkipReason.ORIGIN_NOT_ALLOWED),
                coordinator.capture(quickCapture),
            )
            assertTrue(
                coordinator.capture(
                    quickCapture.copy(
                        allowedOrigins = quickCapture.allowedOrigins + MemoryCaptureOrigin.QUICK_CAPTURE,
                    ),
                ) is MemoryCaptureResult.Queued,
            )
        }

    @Test
    fun `manual capture keeps a separate durable record when the same turn was auto captured`() =
        runBlocking {
            val store = InMemoryMemoryCaptureStore()
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = store,
                workScheduler = RecordingMemoryWorkScheduler(),
                idGenerator = { Uuid.random().toString() },
            )
            val automatic = completedTurn(1)

            val autoResult = coordinator.capture(automatic)
            val manualResult = coordinator.capture(
                automatic.copy(
                    assistantText = "",
                    captureSource = MemoryCaptureSource.MANUAL_SELECTION,
                    immediateCaptureThreshold = 1,
                ),
            )

            assertTrue(autoResult is MemoryCaptureResult.Queued)
            assertTrue(manualResult is MemoryCaptureResult.Queued)
            assertEquals(2, store.records.size)
            assertEquals(
                setOf(MemoryCaptureSource.AUTOMATIC_TURN, MemoryCaptureSource.MANUAL_SELECTION),
                store.records.map { it.captureSource }.toSet(),
            )
        }

    @Test
    fun `manual selections are extracted separately from automatic turns in the same conversation`() =
        runBlocking {
            val captures = listOf(
                memoryCaptureRecord(index = 1).copy(
                    userText = "manual preference",
                    assistantText = "",
                    captureSource = MemoryCaptureSource.MANUAL_SELECTION,
                ),
                memoryCaptureRecord(index = 2).copy(
                    userText = "automatic request",
                    assistantText = "automatic answer",
                    captureSource = MemoryCaptureSource.AUTOMATIC_TURN,
                ),
            )
            val requests = mutableListOf<MemoryExtractionRequest>()
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = InMemoryMemoryCaptureStore(),
                workScheduler = RecordingMemoryWorkScheduler(),
                processingStore = RecordingMemoryProcessingStore(captures),
                extractor = MemoryExtractor { request ->
                    requests += request
                    MemoryExtractorResult.Success("""{"version":1,"proposals":[]}""")
                },
                idGenerator = { Uuid.random().toString() },
            )

            val result = coordinator.process(MemoryProcessRequest(MEMORY_TEST_SCOPE, "worker"))

            assertTrue(result is MemoryProcessResult.Completed)
            assertEquals(2, requests.size)
            assertEquals(
                setOf("manual preference", "automatic request"),
                requests.map { it.turns.single().userText }.toSet(),
            )
        }

    @Test
    fun `manual user-only selection queues immediately and reaches extraction`() = runBlocking {
        val captureStore = InMemoryMemoryCaptureStore()
        val scheduler = RecordingMemoryWorkScheduler()
        val captureCoordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = captureStore,
            workScheduler = scheduler,
            idGenerator = { "manual-capture" },
        )

        val captured = captureCoordinator.capture(
            completedTurn(1).copy(
                assistantText = "",
                captureSource = MemoryCaptureSource.MANUAL_SELECTION,
                immediateCaptureThreshold = 1,
            ),
        )

        assertEquals(0L, (captured as MemoryCaptureResult.Queued).delayMs)
        assertEquals("", captureStore.records.single().assistantText)

        var extractionRequest: MemoryExtractionRequest? = null
        val processStore = RecordingMemoryProcessingStore(captureStore.records)
        val processingCoordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = captureStore,
            workScheduler = scheduler,
            processingStore = processStore,
            extractor = MemoryExtractor { request ->
                extractionRequest = request
                MemoryExtractorResult.Success("""{"version":1,"proposals":[]}""")
            },
            idGenerator = { "candidate" },
        )

        val processed = processingCoordinator.process(
            MemoryProcessRequest(MEMORY_TEST_SCOPE, "worker"),
        )

        assertTrue(processed is MemoryProcessResult.Completed)
        assertEquals("user-1", extractionRequest?.turns?.single()?.userText)
        assertEquals("", extractionRequest?.turns?.single()?.assistantText)
    }

    @Test
    fun `assistant scheduling values control idle delay and early processing threshold`() = runBlocking {
        val store = InMemoryMemoryCaptureStore()
        val scheduler = RecordingMemoryWorkScheduler()
        val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = store,
            workScheduler = scheduler,
            idGenerator = { Uuid.random().toString() },
        )

        coordinator.capture(
            completedTurn(1).copy(
                idleDelayMs = 2L * 60_000L,
                immediateCaptureThreshold = 2,
            ),
        )
        val second = coordinator.capture(
            completedTurn(2).copy(
                idleDelayMs = 2L * 60_000L,
                immediateCaptureThreshold = 2,
            ),
        )

        assertEquals(listOf(2L * 60_000L, 0L), scheduler.requests.map { it.delayMs })
        assertEquals(0L, (second as MemoryCaptureResult.Queued).delayMs)
    }

    @Test
    fun `processing a safe create auto applies it through one durable commit`() = runBlocking {
        val captureStore = InMemoryMemoryCaptureStore()
        val processStore = RecordingMemoryProcessingStore(
            claimed = listOf(memoryCaptureRecord(index = 1)),
        )
        val extractor = MemoryExtractor {
            MemoryExtractorResult.Success(
                raw = """
                {
                  "version": 1,
                  "proposals": [{
                    "action": "create",
                    "title": "Coffee preference",
                    "content": "The user consistently prefers sugar-free latte.",
                    "kind": "preference",
                    "tags": ["coffee"],
                    "importance": 0.7,
                    "confidence": 0.96,
                    "evidenceMessageIds": ["user-1"],
                    "reason": "Durable preference"
                  }]
                }
                """.trimIndent(),
            )
        }
        val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = captureStore,
            workScheduler = RecordingMemoryWorkScheduler(),
            processingStore = processStore,
            extractor = extractor,
            idGenerator = { "generated-id" },
            nowMs = { 2_000L },
        )

        val result = coordinator.process(
            MemoryProcessRequest(scopeId = MEMORY_TEST_SCOPE, workerId = "worker-1"),
        )

        assertEquals(
            MemoryProcessResult.Completed(
                processedCaptures = 1,
                autoApplied = 1,
                pendingReview = 0,
                superseded = 0,
                rejectedProposals = 0,
                failedCaptures = 0,
            ),
            result,
        )
        assertEquals(MemoryCandidateDisposition.AUTO_APPLY, processStore.commits.single()
            .candidates.single().disposition)
        assertEquals(listOf("user-1"), processStore.commits.single()
            .candidates.single().proposal.evidenceMessageIds)
    }

    @Test
    fun `near duplicate create remains flagged for human review`() = runBlocking {
        val processStore = RecordingMemoryProcessingStore(
            claimed = listOf(memoryCaptureRecord(index = 1)),
            existing = listOf(
                ExistingMemoryRecord(
                    id = 7,
                    scopeId = MEMORY_TEST_SCOPE,
                    title = "饮品偏好",
                    content = "用户偏好喝无糖拿铁咖啡",
                    revision = 1,
                    kind = MemoryKind.PREFERENCE,
                ),
            ),
        )
        val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = InMemoryMemoryCaptureStore(),
            workScheduler = RecordingMemoryWorkScheduler(),
            processingStore = processStore,
            extractor = MemoryExtractor {
                MemoryExtractorResult.Success(
                    """{"version":1,"proposals":[{
                        "action":"create",
                        "title":"饮品偏好",
                        "content":"用户长期偏好无糖拿铁咖啡。",
                        "kind":"preference",
                        "tags":["咖啡"],
                        "importance":0.7,
                        "confidence":0.96,
                        "evidenceMessageIds":["user-1"],
                        "reason":"长期偏好"
                    }]}""",
                )
            },
            idGenerator = { "near-duplicate" },
        )

        val result = coordinator.process(MemoryProcessRequest(MEMORY_TEST_SCOPE, "worker"))

        assertTrue(result is MemoryProcessResult.Completed)
        val decision = processStore.commits.single().candidates.single()
        assertEquals(MemoryCandidateDisposition.REVIEW, decision.disposition)
        assertEquals(MemoryDuplicateAssessment.NEAR, decision.duplicate)
        assertEquals(setOf(MemoryRiskFlag.NEAR_DUPLICATE), decision.risks)
    }

    @Test
    fun `eligible completed turns queue durably and the fifth capture runs immediately`() =
        runBlocking {
            val store = InMemoryMemoryCaptureStore()
            val scheduler = RecordingMemoryWorkScheduler()
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = store,
                workScheduler = scheduler,
                idGenerator = { Uuid.random().toString() },
                nowMs = { 1_000L },
            )

            val first = coordinator.capture(completedTurn(index = 1))
            assertTrue(first is MemoryCaptureResult.Queued)
            assertEquals(MEMORY_IDLE_DELAY_MS, (first as MemoryCaptureResult.Queued).delayMs)
            assertEquals("user-1", store.records.single().userText)
            assertEquals("assistant-1", store.records.single().assistantText)
            assertEquals(MemoryAutoSaveMode.SAFE_NEW_ONLY, store.records.single().autoSaveMode)

            repeat(3) { offset -> coordinator.capture(completedTurn(index = offset + 2)) }
            val fifth = coordinator.capture(completedTurn(index = 5))

            assertEquals(5, store.pendingCount(MEMORY_TEST_SCOPE))
            assertEquals(0L, (fifth as MemoryCaptureResult.Queued).delayMs)
            assertEquals(
                listOf(MEMORY_IDLE_DELAY_MS, MEMORY_IDLE_DELAY_MS, MEMORY_IDLE_DELAY_MS,
                    MEMORY_IDLE_DELAY_MS, 0L),
                scheduler.requests.map { it.delayMs },
            )
        }

    private fun completedTurn(index: Int) = CompletedMemoryTurn(
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        scopeId = MEMORY_TEST_SCOPE,
        conversationId = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        userMessageId = Uuid.random(),
        assistantMessageId = Uuid.random(),
        origin = MemoryCaptureOrigin.APP_UI,
        userText = "user-$index",
        assistantText = "assistant-$index",
        memoryEnabled = true,
        autoSaveMode = MemoryAutoSaveMode.SAFE_NEW_ONLY,
        allowedOrigins = setOf(MemoryCaptureOrigin.APP_UI, MemoryCaptureOrigin.SYSTEM_ASSISTANT),
        isHeadless = false,
        needsFinalAnswer = false,
    )

    private fun memoryCaptureRecord(index: Int) = MemoryCaptureRecord(
        id = "capture-$index",
        assistantId = "00000000-0000-0000-0000-000000000001",
        scopeId = MEMORY_TEST_SCOPE,
        conversationId = "00000000-0000-0000-0000-000000000002",
        userMessageId = "user-$index",
        assistantMessageId = "assistant-$index",
        origin = MemoryCaptureOrigin.APP_UI,
        autoSaveMode = MemoryAutoSaveMode.SAFE_NEW_ONLY,
        userText = "I consistently prefer sugar-free latte.",
        assistantText = "Understood.",
        createdAtMs = 1_000L + index,
    )

    private companion object {
        const val MEMORY_TEST_SCOPE = "00000000-0000-0000-0000-000000000001"
    }

    private class InMemoryMemoryCaptureStore : MemoryCaptureStore {
        val records = mutableListOf<MemoryCaptureRecord>()

        override suspend fun insert(record: MemoryCaptureRecord): MemoryCaptureInsertResult {
            val existing = records.firstOrNull {
                it.conversationId == record.conversationId &&
                    it.assistantMessageId == record.assistantMessageId &&
                    it.captureSource == record.captureSource
            }
            if (existing != null) return MemoryCaptureInsertResult.Duplicate(existing.id)
            records += record
            return MemoryCaptureInsertResult.Inserted
        }

        override suspend fun pendingCount(scopeId: String): Int =
            records.count { it.scopeId == scopeId }
    }

    private class RecordingMemoryWorkScheduler : MemoryWorkScheduler {
        val requests = mutableListOf<MemoryWorkRequest>()

        override suspend fun schedule(request: MemoryWorkRequest) {
            requests += request
        }
    }

    private class RecordingMemoryProcessingStore(
        private val claimed: List<MemoryCaptureRecord>,
        private val existing: List<ExistingMemoryRecord> = emptyList(),
        private val relationReviewResult: MemoryRelationReviewResult =
            MemoryRelationReviewResult.NotFound,
    ) : MemoryProcessingStore {
        val commits = mutableListOf<MemoryProcessCommit>()
        val relationReviews = mutableListOf<MemoryRelationReviewCommand>()
        val relationReviewTimes = mutableListOf<Long>()
        val failureTimes = mutableListOf<Long>()

        override suspend fun claim(request: MemoryClaimRequest): List<MemoryCaptureRecord> = claimed

        override suspend fun findExisting(
            scopeId: String,
            query: String,
            limit: Int,
            frozenNowMs: Long,
        ): List<ExistingMemoryRecord> = existing

        override suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult {
            commits += commit
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
            failureTimes += nowMs
        }

        override suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long) = Unit

        override suspend fun review(
            command: MemoryReviewCommand,
            nowMs: Long,
        ): MemoryReviewResult = MemoryReviewResult.NotFound

        override suspend fun reviewRelation(
            command: MemoryRelationReviewCommand,
            nowMs: Long,
        ): MemoryRelationReviewResult {
            relationReviews += command
            relationReviewTimes += nowMs
            return relationReviewResult
        }
    }
}

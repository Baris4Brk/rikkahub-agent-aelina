package me.rerere.rikkahub.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryConfigurationFailureRecoveryTest {
    @Test
    fun `configuration failure stays visible for manual retry without requesting automatic work retry`() =
        runBlocking {
            val store = RecordingProcessingStore()
            val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
                captureStore = NoopCaptureStore,
                workScheduler = NoopWorkScheduler,
                processingStore = store,
                extractor = MemoryExtractor {
                    MemoryExtractorResult.Failure(
                        code = "memory_extraction_model_missing",
                        retryPolicy = MemoryFailureRetryPolicy.MANUAL_ONLY,
                    )
                },
                idGenerator = { "generated" },
                nowMs = { 10_000L },
            )

            val result = coordinator.process(MemoryProcessRequest(TEST_SCOPE, "worker"))

            assertEquals(
                MemoryProcessResult.Completed(
                    processedCaptures = 0,
                    autoApplied = 0,
                    pendingReview = 0,
                    superseded = 0,
                    rejectedProposals = 0,
                    failedCaptures = 1,
                    automaticRetryFailedCaptures = 0,
                ),
                result,
            )
            assertEquals(
                listOf(MemoryFailureRetryPolicy.MANUAL_ONLY),
                store.failurePolicies,
            )
        }

    @Test
    fun `transient extraction failure still requests an automatic work retry`() = runBlocking {
        val store = RecordingProcessingStore()
        val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = NoopCaptureStore,
            workScheduler = NoopWorkScheduler,
            processingStore = store,
            extractor = MemoryExtractor {
                MemoryExtractorResult.Failure(
                    code = "memory_extraction_provider_error",
                    retryPolicy = MemoryFailureRetryPolicy.AUTOMATIC,
                )
            },
            idGenerator = { "generated" },
            nowMs = { 10_000L },
        )

        val result = coordinator.process(MemoryProcessRequest(TEST_SCOPE, "worker"))

        assertEquals(
            MemoryProcessResult.Completed(
                processedCaptures = 0,
                autoApplied = 0,
                pendingReview = 0,
                superseded = 0,
                rejectedProposals = 0,
                failedCaptures = 1,
                automaticRetryFailedCaptures = 1,
            ),
            result,
        )
        assertEquals(listOf(MemoryFailureRetryPolicy.AUTOMATIC), store.failurePolicies)
    }

    private class RecordingProcessingStore : MemoryProcessingStore {
        val failurePolicies = mutableListOf<MemoryFailureRetryPolicy>()

        override suspend fun claim(request: MemoryClaimRequest): List<MemoryCaptureRecord> =
            listOf(testCapture())

        override suspend fun findExisting(
            scopeId: String,
            query: String,
            limit: Int,
            frozenNowMs: Long,
        ): List<ExistingMemoryRecord> = emptyList()

        override suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult =
            error("A failed extraction must not commit memories")

        override suspend fun markFailed(
            captureIds: List<String>,
            scopeId: String,
            workerId: String,
            code: String,
            message: String?,
            retryPolicy: MemoryFailureRetryPolicy,
            nowMs: Long,
        ) {
            failurePolicies += retryPolicy
        }

        override suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long) = Unit

        override suspend fun review(
            command: MemoryReviewCommand,
            nowMs: Long,
        ): MemoryReviewResult = MemoryReviewResult.NotFound
    }

    private companion object {
        const val TEST_SCOPE = "assistant-scope"

        val NoopCaptureStore = object : MemoryCaptureStore {
            override suspend fun insert(record: MemoryCaptureRecord): MemoryCaptureInsertResult =
                MemoryCaptureInsertResult.Inserted

            override suspend fun pendingCount(scopeId: String): Int = 0
        }

        val NoopWorkScheduler = object : MemoryWorkScheduler {
            override suspend fun schedule(request: MemoryWorkRequest) = Unit
        }

        fun testCapture() = MemoryCaptureRecord(
            id = "capture-1",
            assistantId = Uuid.random().toString(),
            scopeId = TEST_SCOPE,
            conversationId = Uuid.random().toString(),
            userMessageId = "user-1",
            assistantMessageId = "assistant-1",
            origin = MemoryCaptureOrigin.APP_UI,
            autoSaveMode = MemoryAutoSaveMode.SAFE_NEW_ONLY,
            userText = "Please remember that I prefer sugar-free latte.",
            assistantText = "I will remember that preference.",
            createdAtMs = 1_000L,
        )
    }
}

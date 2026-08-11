package me.rerere.rikkahub.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCancellationReliabilityTest {
    @Test
    fun `cancelling an active extraction immediately returns its claimed capture`() = runBlocking {
        val processingStore = CancellationRecordingStore()
        val coordinator: MemoryV2Coordinator = DefaultMemoryV2Coordinator(
            captureStore = NoOpCaptureStore(),
            workScheduler = MemoryWorkScheduler { },
            processingStore = processingStore,
            extractor = MemoryExtractor { throw CancellationException("worker replaced") },
            idGenerator = { "generated" },
        )

        var cancellationPropagated = false
        try {
            coordinator.process(MemoryProcessRequest(scopeId = "assistant-scope", workerId = "worker"))
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue("WorkManager cancellation must propagate", cancellationPropagated)
        assertTrue("Cancellation is not a provider failure", processingStore.failedCaptureIds.isEmpty())
        assertTrue("Cancellation must not commit partial mutations", processingStore.commits.isEmpty())
        assertTrue("Interrupted captures must be returned to the queue", processingStore.releasedCaptureIds == listOf("capture-1"))
    }

    private class NoOpCaptureStore : MemoryCaptureStore {
        override suspend fun insert(record: MemoryCaptureRecord): MemoryCaptureInsertResult =
            MemoryCaptureInsertResult.Inserted

        override suspend fun pendingCount(scopeId: String): Int = 0
    }

    private class CancellationRecordingStore : MemoryProcessingStore {
        val failedCaptureIds = mutableListOf<List<String>>()
        val commits = mutableListOf<MemoryProcessCommit>()
        var releasedCaptureIds = emptyList<String>()

        override suspend fun claim(request: MemoryClaimRequest): List<MemoryCaptureRecord> =
            listOf(
                MemoryCaptureRecord(
                    id = "capture-1",
                    assistantId = "assistant-id",
                    scopeId = request.scopeId,
                    conversationId = "conversation-id",
                    userMessageId = "user-message",
                    assistantMessageId = "assistant-message",
                    origin = MemoryCaptureOrigin.APP_UI,
                    autoSaveMode = MemoryAutoSaveMode.SAFE_NEW_ONLY,
                    userText = "I prefer sugar-free latte.",
                    assistantText = "Noted.",
                    createdAtMs = 1L,
                ),
            )

        override suspend fun findExisting(
            scopeId: String,
            query: String,
            limit: Int,
            frozenNowMs: Long,
        ): List<ExistingMemoryRecord> = emptyList()

        override suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult {
            commits += commit
            return MemoryCommitResult(autoApplied = 0, pendingReview = 0, superseded = 0)
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
        }

        override suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long) = Unit

        override suspend fun releaseClaimed(
            captureIds: List<String>,
            scopeId: String,
            workerId: String,
            nowMs: Long,
        ) {
            releasedCaptureIds = captureIds
        }

        override suspend fun review(
            command: MemoryReviewCommand,
            nowMs: Long,
        ): MemoryReviewResult = MemoryReviewResult.NotFound
    }
}

package me.rerere.rikkahub.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryMutationCoordinatorTest {
    @Test
    fun `explicit source invalidations preserve one caller frozen clock across scopes`() =
        runBlocking {
            val store = RecordingProcessingStore()
            val coordinator: MemoryMutationCoordinator = DefaultMemoryMutationCoordinator(
                store = store,
                nowMs = { 999L },
            )

            coordinator.invalidateSourceConversation("assistant-a", "conversation", 123L)
            coordinator.invalidateSourceConversation("__global__", "conversation", 123L)
            coordinator.invalidateSourceMessages(
                "assistant-a",
                "conversation",
                setOf("message-a"),
                123L,
            )
            coordinator.invalidateSourceMessages(
                "__global__",
                "conversation",
                setOf("message-a"),
                123L,
            )

            assertEquals(listOf(123L, 123L, 123L, 123L), store.invalidationTimes)
        }

    @Test
    fun `implicit source invalidations still use the injected clock`() = runBlocking {
        val store = RecordingProcessingStore()
        val coordinator: MemoryMutationCoordinator = DefaultMemoryMutationCoordinator(
            store = store,
            nowMs = { 456L },
        )

        coordinator.invalidateSourceConversation("assistant-a", "conversation")
        coordinator.invalidateSourceMessages(
            "assistant-a",
            "conversation",
            setOf("message-a"),
        )

        assertEquals(listOf(456L, 456L), store.invalidationTimes)
    }

    private class RecordingProcessingStore : MemoryProcessingStore {
        val invalidationTimes = mutableListOf<Long>()

        override suspend fun claim(request: MemoryClaimRequest): List<MemoryCaptureRecord> =
            emptyList()

        override suspend fun findExisting(
            scopeId: String,
            query: String,
            limit: Int,
            frozenNowMs: Long,
        ): List<ExistingMemoryRecord> = emptyList()

        override suspend fun commit(commit: MemoryProcessCommit): MemoryCommitResult =
            MemoryCommitResult(autoApplied = 0, pendingReview = 0, superseded = 0)

        override suspend fun markFailed(
            captureIds: List<String>,
            scopeId: String,
            workerId: String,
            code: String,
            message: String?,
            retryPolicy: MemoryFailureRetryPolicy,
            nowMs: Long,
        ) = Unit

        override suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long) = Unit

        override suspend fun review(
            command: MemoryReviewCommand,
            nowMs: Long,
        ): MemoryReviewResult = MemoryReviewResult.NotFound

        override suspend fun invalidateSourceConversation(
            scopeId: String,
            conversationId: String,
            nowMs: Long,
        ): Int {
            invalidationTimes += nowMs
            return 1
        }

        override suspend fun invalidateSourceMessages(
            scopeId: String,
            conversationId: String,
            messageIds: Set<String>,
            nowMs: Long,
        ): Int {
            invalidationTimes += nowMs
            return 1
        }
    }
}
